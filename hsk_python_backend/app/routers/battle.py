"""
Battle router — /api/battle endpoints for submitting battle results.
Handles anti-cheat plausibility, sub-stage monster scaling, persistent HP,
defeat penalties (Coins + Pet Affection), league promotion, and adaptive weakness logging.
"""
from datetime import datetime, timezone
import math

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.player import PlayerStats
from app.models.pet import Pet
from app.models.battle import BattleHistory, WeaknessLog, UserVocabMastery
from app.schemas.battle import BattleResultRequest, BattleResultResponse
from app.services.combat import (
    xp_to_next_level,
    get_monster_stats,
    diminishing_returns_multiplier,
    calculate_base_damage,
    calculate_critical_multiplier,
    calculate_max_hp,
)
from app.services.leaderboard_service import league_for_area
from app.services.pet_buffs import PetBuffSpec, get_active_pet_buff
from app.services.quest_service import record_quest_progress
from app.utils.security import get_current_user_id

router = APIRouter(prefix="/api/battle", tags=["Battle"])

# How many recent battles to inspect when computing the consecutive-kill streak
STREAK_LOOKBACK = 10
WRONG_ANSWER_AFFECTION_PENALTY = 1.5
DEFEAT_AFFECTION_PENALTY = 15.0


async def _consecutive_kills(db: AsyncSession, user_id: int, monster_id: int) -> int:
    """Count the current streak of wins against the same monster (most recent first)."""
    rows = await db.execute(
        select(BattleHistory.monster_id, BattleHistory.result)
        .where(BattleHistory.user_id == user_id)
        .order_by(BattleHistory.created_at.desc(), BattleHistory.id.desc())
        .limit(STREAK_LOOKBACK)
    )
    streak = 0
    for past_monster_id, past_result in rows.all():
        if past_monster_id == monster_id and past_result == "win":
            streak += 1
        else:
            break
    return streak


async def _get_active_pet(db: AsyncSession, user_id: int) -> Pet | None:
    """Fetch user's currently equipped companion pet."""
    row = await db.execute(
        select(Pet).where(Pet.user_id == user_id, Pet.is_active == True)
    )
    return row.scalar_one_or_none()


def _validate_plausibility(
    request: BattleResultRequest,
    stats: PlayerStats,
    monster_hp: int,
    pet_buff: PetBuffSpec | None = None,
) -> None:
    """Reject obviously impossible submissions (anti-cheat)."""
    if request.correct_answers > request.questions_answered:
        raise HTTPException(status_code=400, detail="correct_answers exceeds questions_answered")

    base_hit = calculate_base_damage(stats.stat_str)
    crit_mult = calculate_critical_multiplier(stats.stat_dex) + (
        (pet_buff.crit_damage_bonus_pct / 100.0) if pet_buff else 0.0
    )
    pet_mult = 1.0 + ((pet_buff.damage_bonus_pct / 100.0) if pet_buff else 0.0)
    opener_mult = 1.0 + (
        (pet_buff.first_strike_bonus_pct / 100.0)
        if pet_buff and pet_buff.first_strike_bonus_pct > 0
        else 0.0
    )

    normal_max_hit = max(1.0, round(base_hit * crit_mult * pet_mult, 1) + 1.0)
    opener_max_hit = max(1.0, round(base_hit * crit_mult * pet_mult * opener_mult, 1) + 1.0)
    max_possible_damage = (
        opener_max_hit + normal_max_hit * (request.correct_answers - 1)
        if request.correct_answers > 0
        else 0.0
    )
    if request.damage_dealt > max_possible_damage + 0.5:
        raise HTTPException(status_code=400, detail="damage_dealt is not plausible")

    if request.result == "win" and request.damage_dealt < (monster_hp - 0.1):
        raise HTTPException(status_code=400, detail="Victory requires dealing the monster's full HP")


async def _update_weakness(db: AsyncSession, user_id: int, request: BattleResultRequest) -> None:
    """Accumulate per-skill correct/wrong counters for the adaptive boss AI."""
    if request.questions_answered == 0:
        return
    row = await db.execute(
        select(WeaknessLog).where(
            WeaknessLog.user_id == user_id, WeaknessLog.skill_type == request.skill_type
        )
    )
    log = row.scalar_one_or_none()
    if log is None:
        log = WeaknessLog(user_id=user_id, skill_type=request.skill_type, total_correct=0, total_wrong=0)
        db.add(log)
    log.total_correct = (log.total_correct or 0) + request.correct_answers
    log.total_wrong = (log.total_wrong or 0) + (request.questions_answered - request.correct_answers)


async def _update_vocab_mastery(db: AsyncSession, user_id: int, request: BattleResultRequest) -> None:
    """
    Upsert compact per-word mastery deltas from the battle session in a single batch query.
    Only creates a new row when wrong_delta > 0 to keep the database lean.
    """
    clean_deltas = [
        d for d in request.vocab_deltas
        if d.hanzi.strip() and (d.wrong_delta > 0 or d.correct_delta > 0)
    ]
    if not clean_deltas:
        return

    unique_hanzi = list({d.hanzi.strip()[:32] for d in clean_deltas})
    existing_res = await db.execute(
        select(UserVocabMastery).where(
            UserVocabMastery.user_id == user_id,
            UserVocabMastery.hanzi.in_(unique_hanzi),
        )
    )
    existing_map = {
        (row.hsk_level, row.hanzi): row
        for row in existing_res.scalars().all()
    }

    now = datetime.now(timezone.utc)
    for delta in clean_deltas:
        hanzi_key = delta.hanzi.strip()[:32]
        map_key = (delta.hsk_level, hanzi_key)
        row = existing_map.get(map_key)
        if row is None and delta.wrong_delta <= 0:
            # Never struggled with this word yet; skip inserting to keep table compact
            continue

        if row is None:
            row = UserVocabMastery(
                user_id=user_id,
                hsk_level=delta.hsk_level,
                hanzi=hanzi_key,
                pinyin=delta.pinyin[:64],
                english=delta.english[:255],
                wrong_count=0,
                correct_count=0,
                correct_streak=0,
                mastery_score=50,
            )
            db.add(row)
            existing_map[map_key] = row

        row.wrong_count = (row.wrong_count or 0) + max(0, delta.wrong_delta)
        row.correct_count = (row.correct_count or 0) + max(0, delta.correct_delta)
        if delta.pinyin and not row.pinyin:
            row.pinyin = delta.pinyin[:64]
        if delta.english and not row.english:
            row.english = delta.english[:255]

        current_score = row.mastery_score if row.mastery_score is not None else 50
        if delta.wrong_delta > 0:
            row.correct_streak = 0
            row.mastery_score = max(0, current_score - 20 * delta.wrong_delta)
        elif delta.correct_delta > 0:
            row.correct_streak = (row.correct_streak or 0) + delta.correct_delta
            row.mastery_score = min(100, current_score + 15 * delta.correct_delta)

        row.updated_at = now



@router.post("/result", response_model=BattleResultResponse)
async def submit_battle_result(
    request: BattleResultRequest,
    user_id: int = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """
    Submit battle result for server-side validation and reward distribution.

    Server enforces:
    1. Anti-cheat plausibility with active pet damage boost check
    2. Sub-stage progressive scaling (index 1..4)
    3. Persistent HP reduction across victory, retreat, and defeat
    4. Meaningful defeat penalty (Coins loss + Pet Affection drop)
    5. Diminishing returns on repeated monster kills
    6. Automatic league recalculation when Gatekeeper Boss advances highest_area_cleared
    7. Logging to battle_history and weakness_log
    """
    result = await db.execute(select(PlayerStats).where(PlayerStats.user_id == user_id))
    stats = result.scalar_one_or_none()
    if not stats:
        raise HTTPException(status_code=404, detail="Player stats not found")

    # Decode sub-stage index from monster_id (e.g. area 1, normal #3 -> 103 % 10 = 3)
    stage_index = max(1, request.monster_id % 10)
    monster_stats = get_monster_stats(request.area_id, request.monster_type, stage_index)

    # Active pet buff inspection
    active_pet = await _get_active_pet(db, user_id)
    pet_buff = get_active_pet_buff(active_pet)

    _validate_plausibility(request, stats, monster_stats["hp"], pet_buff)

    max_pet_heal = 0.0
    if pet_buff is not None:
        max_pet_heal += float(pet_buff.heal_on_correct + pet_buff.heal_on_crit) * request.correct_answers
        if pet_buff.rebirth_hp_pct > 0:
            max_pet_heal += float(max(1, int(round(stats.max_hp * pet_buff.rebirth_hp_pct / 100.0))))
    applied_pet_heal = round(min(max(0.0, float(request.hp_healed_by_pet)), max_pet_heal), 1)

    consecutive_kills = await _consecutive_kills(db, user_id, request.monster_id)
    dr_multiplier = diminishing_returns_multiplier(consecutive_kills)

    wrong_answers = max(0, request.questions_answered - request.correct_answers)
    affection_penalty = round(wrong_answers * WRONG_ANSWER_AFFECTION_PENALTY, 1)
    if request.result == "lose":
        affection_penalty = round(affection_penalty + DEFEAT_AFFECTION_PENALTY, 1)

    pet_affection_delta = 0.0
    pet_affection_after: float | None = None
    if active_pet is not None:
        prev_affection = float(active_pet.affection or 0.0)
        if affection_penalty > 0.0:
            new_affection = round(max(0.0, prev_affection - affection_penalty), 1)
            active_pet.affection = new_affection
            pet_affection_delta = round(new_affection - prev_affection, 1)
        pet_affection_after = round(float(active_pet.affection or 0.0), 1)

    xp_earned = 0
    coins_earned = 0
    level_up = False
    new_level = None
    lost_item = None

    if request.result == "win":
        # 1. Rewards (scaled by diminishing returns and awake pet coin/xp buffs)
        xp_mult = 1.0 + ((pet_buff.xp_bonus_pct / 100.0) if pet_buff else 0.0)
        coin_mult = 1.0 + ((pet_buff.coin_bonus_pct / 100.0) if pet_buff else 0.0)
        xp_earned = int(round(monster_stats["xp_reward"] * dr_multiplier * xp_mult))
        coins_earned = int(round(monster_stats["coin_reward"] * dr_multiplier * coin_mult))
        stats.xp += xp_earned
        stats.coins += coins_earned

        # 2. HP Persistence: decimal combat damage rounds UP cleanly on battle finish
        raw_remaining_hp = round(stats.hp - float(request.damage_taken) + applied_pet_heal, 1)
        stats.hp = min(stats.max_hp, max(1, math.ceil(raw_remaining_hp)))
        stats.survival_hp = stats.hp

        # 3. Level up calculation (+5 Max HP, +5 HP heal, +2 stat points per level)
        xp_needed = xp_to_next_level(stats.level)
        while stats.xp >= xp_needed:
            stats.xp -= xp_needed
            stats.level += 1
            stats.unspent_stat_points += 2
            updated_max_hp = calculate_max_hp(stats.stat_vit, stats.level)
            hp_gain = max(5, updated_max_hp - stats.max_hp)
            stats.max_hp = updated_max_hp
            stats.hp = min(stats.max_hp, stats.hp + hp_gain)
            stats.survival_hp = stats.hp
            level_up = True
            new_level = stats.level
            xp_needed = xp_to_next_level(stats.level)

        # 4. Gatekeeper area unlocking & league tier update
        if request.monster_type == "boss" and request.area_id > stats.highest_area_cleared:
            stats.highest_area_cleared = request.area_id
        stats.league = league_for_area(stats.highest_area_cleared)

    elif request.result == "lose":
        # Defeat penalty: HP drops to 10% MaxHP, lose 10% coins + wrong/defeat affection loss
        stats.hp = max(10, int(stats.max_hp * 0.10))
        stats.survival_hp = stats.hp

        coins_lost = min(500, int(stats.coins * 0.10))
        stats.coins = max(0, stats.coins - coins_lost)

        lost_parts = [f"{coins_lost} Coins"]
        if active_pet is not None and pet_affection_delta < 0:
            lost_parts.append(f"{abs(pet_affection_delta):g} Pet Affection")
        lost_item = " & ".join(lost_parts)

    else:
        # Fled battle safely: forfeit rewards, retain rounded-up net HP
        raw_remaining_hp = round(stats.hp - float(request.damage_taken) + applied_pet_heal, 1)
        stats.hp = min(stats.max_hp, max(1, math.ceil(raw_remaining_hp)))
        stats.survival_hp = stats.hp

    db.add(BattleHistory(
        user_id=user_id,
        monster_id=request.monster_id,
        area_id=request.area_id,
        monster_type=request.monster_type,
        result=request.result,
        damage_dealt=int(round(request.damage_dealt)),
        damage_taken=int(round(request.damage_taken)),
        questions_answered=request.questions_answered,
        correct_answers=request.correct_answers,
        skill_type=request.skill_type,
        xp_earned=xp_earned,
        coins_earned=coins_earned,
    ))
    await _update_weakness(db, user_id, request)
    await _update_vocab_mastery(db, user_id, request)

    quest_increments: dict[str, int] = {}
    if request.correct_answers > 0:
        quest_increments["correct_answers"] = request.correct_answers
    if request.questions_answered > 0:
        quest_increments["questions_answered"] = request.questions_answered
    if request.damage_dealt > 0:
        quest_increments["damage_dealt"] = int(round(request.damage_dealt))
    if request.result in ("win", "lose"):
        quest_increments["battles_completed"] = 1
    if request.result == "win":
        quest_increments["battle_wins"] = 1
        if request.monster_type in ("elite", "boss"):
            quest_increments["boss_wins"] = 1
        if request.questions_answered > 0 and request.correct_answers >= request.questions_answered:
            quest_increments["flawless_wins"] = 1
        if coins_earned > 0:
            quest_increments["coins_earned"] = coins_earned
    if quest_increments:
        await record_quest_progress(db, user_id, quest_increments)

    if request.result == "win":
        msg = f"Victory! +{xp_earned} XP, +{coins_earned} coins."
        if dr_multiplier < 1.0:
            msg += f" (Farming Diminishing returns: {int(dr_multiplier * 100)}%)"
        if level_up:
            msg += f" Level Up! You are now Level {new_level}!"
        if pet_affection_delta < 0:
            msg += f" Companion Affection {pet_affection_delta:g} ({wrong_answers} wrong)."
    elif request.result == "lose":
        msg = "Defeated... The beast overwhelmed your vitality!"
        if lost_item:
            msg += f" Penalty: Lost {lost_item}."
    else:
        msg = "Tactical retreat. You preserved your coins and escaped safely."
        if pet_affection_delta < 0:
            msg += f" Companion Affection {pet_affection_delta:g} ({wrong_answers} wrong)."

    return BattleResultResponse(
        result=request.result,
        xp_earned=xp_earned,
        coins_earned=coins_earned,
        damage_dealt=round(float(request.damage_dealt), 1),
        level_up=level_up,
        new_level=new_level,
        stat_points_available=stats.unspent_stat_points,
        survival_hp=stats.hp,
        lost_item=lost_item,
        pet_affection_delta=pet_affection_delta,
        pet_affection=pet_affection_after,
        message=msg,
    )

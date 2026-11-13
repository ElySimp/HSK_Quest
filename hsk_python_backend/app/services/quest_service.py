"""
Daily quest service — catalog, server-side progress recording, backfill, and claims.
"""
from dataclasses import dataclass
from datetime import date, datetime, timezone

from fastapi import HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.battle import BattleHistory
from app.models.daily import DailyClaim
from app.models.player import PlayerStats
from app.models.quest import DailyQuestRecord
from app.schemas.quest import (
    ClaimQuestResponse,
    DailyQuestItemResponse,
    DailyQuestListResponse,
)


@dataclass(frozen=True)
class DailyQuestDef:
    quest_key: str
    title: str
    description: str
    icon: str
    target: int
    reward_diamonds: int
    reward_coins: int = 0
    is_milestone: bool = False


DAILY_QUEST_DEFS: tuple[DailyQuestDef, ...] = (
    DailyQuestDef(
        quest_key="daily_bounty",
        title="Daily Check-In",
        description="Claim your Daily Bounty reward on Home Base.",
        icon="🎁",
        target=1,
        reward_diamonds=15,
        reward_coins=0,
    ),
    DailyQuestDef(
        quest_key="battle_wins",
        title="Monster Hunter",
        description="Defeat 2 Realm monsters or bosses in battle.",
        icon="⚔️",
        target=2,
        reward_diamonds=25,
        reward_coins=20,
    ),
    DailyQuestDef(
        quest_key="correct_answers",
        title="Hanzi Scholar",
        description="Answer 10 vocabulary or combat questions correctly.",
        icon="📜",
        target=10,
        reward_diamonds=20,
        reward_coins=0,
    ),
    DailyQuestDef(
        quest_key="pet_care",
        title="Spirit Companion Care",
        description="Feed or play a mini-game (RPS / Blackjack) with your pet.",
        icon="🐾",
        target=1,
        reward_diamonds=20,
        reward_coins=0,
    ),
    DailyQuestDef(
        quest_key="all_clear",
        title="Daily Mastery Chest",
        description="Complete all 4 daily quests above to unlock the grand vault.",
        icon="👑",
        target=4,
        reward_diamonds=20,
        reward_coins=50,
        is_milestone=True,
    ),
)

QUEST_DEF_BY_KEY: dict[str, DailyQuestDef] = {q.quest_key: q for q in DAILY_QUEST_DEFS}
BASE_QUEST_KEYS: tuple[str, ...] = tuple(q.quest_key for q in DAILY_QUEST_DEFS if not q.is_milestone)


async def _load_today_records(
    db: AsyncSession,
    user_id: int,
    today: date,
) -> dict[str, DailyQuestRecord]:
    res = await db.execute(
        select(DailyQuestRecord).where(
            DailyQuestRecord.user_id == user_id,
            DailyQuestRecord.quest_date == today,
        )
    )
    return {row.quest_key: row for row in res.scalars().all()}


def _sync_milestone_record(
    db: AsyncSession,
    user_id: int,
    today: date,
    records_map: dict[str, DailyQuestRecord],
) -> None:
    completed_base = 0
    for key in BASE_QUEST_KEYS:
        q_def = QUEST_DEF_BY_KEY[key]
        rec = records_map.get(key)
        if rec is not None and rec.progress >= q_def.target:
            completed_base += 1

    milestone_rec = records_map.get("all_clear")
    if milestone_rec is None and completed_base > 0:
        milestone_rec = DailyQuestRecord(
            user_id=user_id,
            quest_date=today,
            quest_key="all_clear",
            progress=completed_base,
            is_claimed=False,
        )
        db.add(milestone_rec)
        records_map["all_clear"] = milestone_rec
    elif milestone_rec is not None and milestone_rec.progress != completed_base:
        milestone_rec.progress = min(QUEST_DEF_BY_KEY["all_clear"].target, completed_base)


async def record_quest_progress(
    db: AsyncSession,
    user_id: int,
    increments: dict[str, int],
) -> None:
    """Safely increment one or more daily quest counters for the current server calendar date."""
    clean = {k: v for k, v in increments.items() if v > 0 and k in QUEST_DEF_BY_KEY and k != "all_clear"}
    if not clean:
        return

    today = date.today()
    records_map = await _load_today_records(db, user_id, today)

    for key, delta in clean.items():
        q_def = QUEST_DEF_BY_KEY[key]
        rec = records_map.get(key)
        if rec is None:
            rec = DailyQuestRecord(
                user_id=user_id,
                quest_date=today,
                quest_key=key,
                progress=min(q_def.target, delta),
                is_claimed=False,
            )
            db.add(rec)
            records_map[key] = rec
        else:
            rec.progress = min(q_def.target, (rec.progress or 0) + delta)

    _sync_milestone_record(db, user_id, today, records_map)
    await db.flush()


async def _backfill_today_activity(
    db: AsyncSession,
    user_id: int,
    today: date,
    records_map: dict[str, DailyQuestRecord],
) -> None:
    """Reflect daily bounty claims or battles already completed today before quest tracking was added."""
    changed = False

    # 1. Check DailyClaim for today
    if (records_map.get("daily_bounty") is None) or (records_map["daily_bounty"].progress < 1):
        claim_row = (
            await db.execute(
                select(DailyClaim.id).where(
                    DailyClaim.user_id == user_id,
                    DailyClaim.claim_date == today,
                )
            )
        ).first()
        if claim_row is not None:
            rec = records_map.get("daily_bounty")
            if rec is None:
                rec = DailyQuestRecord(
                    user_id=user_id,
                    quest_date=today,
                    quest_key="daily_bounty",
                    progress=1,
                    is_claimed=False,
                )
                db.add(rec)
                records_map["daily_bounty"] = rec
            else:
                rec.progress = 1
            changed = True

    # 2. Check BattleHistory rows from today's UTC midnight when quest counters are missing
    need_battle_backfill = ("battle_wins" not in records_map) or ("correct_answers" not in records_map)
    if need_battle_backfill:
        utc_midnight = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
        stats_row = (
            await db.execute(
                select(
                    func.coalesce(func.sum(BattleHistory.correct_answers), 0),
                    func.count(BattleHistory.id).filter(BattleHistory.result == "win"),
                ).where(
                    BattleHistory.user_id == user_id,
                    BattleHistory.created_at >= utc_midnight,
                )
            )
        ).first()
        if stats_row is not None:
            today_correct = int(stats_row[0] or 0)
            today_wins = int(stats_row[1] or 0)

            if "battle_wins" not in records_map and today_wins > 0:
                q_wins = QUEST_DEF_BY_KEY["battle_wins"]
                rec = DailyQuestRecord(
                    user_id=user_id,
                    quest_date=today,
                    quest_key="battle_wins",
                    progress=min(q_wins.target, today_wins),
                    is_claimed=False,
                )
                db.add(rec)
                records_map["battle_wins"] = rec
                changed = True

            if "correct_answers" not in records_map and today_correct > 0:
                q_ans = QUEST_DEF_BY_KEY["correct_answers"]
                rec = DailyQuestRecord(
                    user_id=user_id,
                    quest_date=today,
                    quest_key="correct_answers",
                    progress=min(q_ans.target, today_correct),
                    is_claimed=False,
                )
                db.add(rec)
                records_map["correct_answers"] = rec
                changed = True

    if changed:
        _sync_milestone_record(db, user_id, today, records_map)
        await db.flush()


async def build_daily_quest_list(
    db: AsyncSession,
    user_id: int,
    stats: PlayerStats | None = None,
) -> DailyQuestListResponse:
    """Fetch and assemble today's Daily Quest dashboard payload."""
    today = date.today()
    records_map = await _load_today_records(db, user_id, today)
    await _backfill_today_activity(db, user_id, today, records_map)
    _sync_milestone_record(db, user_id, today, records_map)

    if stats is None:
        stats = (
            await db.execute(select(PlayerStats).where(PlayerStats.user_id == user_id))
        ).scalar_one_or_none()
        if stats is None:
            raise HTTPException(status_code=404, detail="Player stats not found")

    items: list[DailyQuestItemResponse] = []
    completed_base = 0
    claimable_count = 0
    claimable_diamonds = 0

    for q_def in DAILY_QUEST_DEFS:
        rec = records_map.get(q_def.quest_key)
        progress = min(q_def.target, int(rec.progress or 0)) if rec is not None else 0
        is_claimed = bool(rec.is_claimed) if rec is not None else False
        is_completed = progress >= q_def.target
        can_claim = is_completed and not is_claimed

        if not q_def.is_milestone and is_completed:
            completed_base += 1
        if can_claim:
            claimable_count += 1
            claimable_diamonds += q_def.reward_diamonds

        items.append(
            DailyQuestItemResponse(
                quest_key=q_def.quest_key,
                title=q_def.title,
                description=q_def.description,
                icon=q_def.icon,
                progress=progress,
                target=q_def.target,
                reward_diamonds=q_def.reward_diamonds,
                reward_coins=q_def.reward_coins,
                is_completed=is_completed,
                is_claimed=is_claimed,
                can_claim=can_claim,
            )
        )

    return DailyQuestListResponse(
        quest_date=today.isoformat(),
        completed_count=completed_base,
        total_base_quests=len(BASE_QUEST_KEYS),
        claimable_count=claimable_count,
        claimable_diamonds=claimable_diamonds,
        total_diamonds=stats.diamonds,
        total_coins=stats.coins,
        quests=items,
    )


async def claim_single_or_all_quests(
    db: AsyncSession,
    user_id: int,
    quest_key: str | None = None,
) -> ClaimQuestResponse:
    """Claim one completed daily quest or all completed daily quests at once."""
    if quest_key is not None and quest_key not in QUEST_DEF_BY_KEY:
        raise HTTPException(status_code=404, detail="Unknown daily quest key")

    stats = (
        await db.execute(select(PlayerStats).where(PlayerStats.user_id == user_id))
    ).scalar_one_or_none()
    if stats is None:
        raise HTTPException(status_code=404, detail="Player stats not found")

    today = date.today()
    records_map = await _load_today_records(db, user_id, today)
    await _backfill_today_activity(db, user_id, today, records_map)
    _sync_milestone_record(db, user_id, today, records_map)

    target_defs = (
        [QUEST_DEF_BY_KEY[quest_key]]
        if quest_key is not None
        else list(DAILY_QUEST_DEFS)
    )

    now = datetime.now(timezone.utc)
    claimed_keys: list[str] = []
    diamonds_awarded = 0
    coins_awarded = 0

    for q_def in target_defs:
        rec = records_map.get(q_def.quest_key)
        progress = min(q_def.target, int(rec.progress or 0)) if rec is not None else 0
        is_claimed = bool(rec.is_claimed) if rec is not None else False

        if quest_key is not None:
            if progress < q_def.target:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="Quest is not completed yet",
                )
            if is_claimed:
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail="Quest reward already claimed today",
                )

        if rec is not None and progress >= q_def.target and not is_claimed:
            rec.is_claimed = True
            rec.claimed_at = now
            diamonds_awarded += q_def.reward_diamonds
            coins_awarded += q_def.reward_coins
            claimed_keys.append(q_def.quest_key)

    if not claimed_keys:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="No completed quests ready to claim right now",
        )

    stats.diamonds += diamonds_awarded
    stats.coins += coins_awarded
    await db.flush()

    daily = await build_daily_quest_list(db, user_id, stats=stats)
    reward_parts = [f"+{diamonds_awarded} Diamonds"]
    if coins_awarded > 0:
        reward_parts.append(f"+{coins_awarded} Coins")

    return ClaimQuestResponse(
        success=True,
        claimed_keys=claimed_keys,
        diamonds_awarded=diamonds_awarded,
        coins_awarded=coins_awarded,
        total_diamonds=stats.diamonds,
        total_coins=stats.coins,
        message=f"Claimed {' & '.join(reward_parts)}!",
        daily=daily,
    )

"""
Daily quest service — 69-quest pool, deterministic 5-quest daily rotation (100-200 Diamonds/day),
server-side progress recording, backfill from activity logs, and claims.
"""
from dataclasses import dataclass, replace
from datetime import date, datetime, timezone
import random

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

DAILY_QUEST_SLOTS = 5
MIN_DAILY_DIAMONDS = 125
MAX_DAILY_DIAMONDS = 185


@dataclass(frozen=True)
class DailyQuestDef:
    quest_key: str
    metric: str
    bucket: str
    title: str
    description: str
    icon: str
    target: int
    reward_diamonds: int
    reward_coins: int = 0


def _build_quest_catalog() -> tuple[DailyQuestDef, ...]:
    quests: list[DailyQuestDef] = []

    def add_tier_quests(
        prefix: str,
        metric: str,
        bucket: str,
        icon: str,
        specs: list[tuple[int, str, str, int, int]],
    ) -> None:
        for target, title, desc, diamonds, coins in specs:
            quests.append(
                DailyQuestDef(
                    quest_key=f"{prefix}_{target}",
                    metric=metric,
                    bucket=bucket,
                    title=title,
                    description=desc,
                    icon=icon,
                    target=target,
                    reward_diamonds=diamonds,
                    reward_coins=coins,
                )
            )

    # 1. Battle Wins (6 quests)
    add_tier_quests(
        "kill_monsters",
        "battle_wins",
        "combat",
        "⚔️",
        [
            (1, "First Blood", "Defeat 1 monster or boss in battle.", 20, 10),
            (2, "Monster Hunter", "Defeat 2 monsters or bosses in battle.", 25, 15),
            (3, "Realm Slayer", "Defeat 3 monsters or bosses in battle.", 30, 20),
            (4, "Vanguard Sweep", "Defeat 4 monsters or bosses in battle.", 35, 25),
            (5, "Champion Bane", "Defeat 5 monsters or bosses in battle.", 40, 30),
            (6, "Unstoppable Blade", "Defeat 6 monsters or bosses in battle.", 45, 35),
        ],
    )

    # 2. Elite & Boss Hunts (4 quests)
    add_tier_quests(
        "boss_hunt",
        "boss_wins",
        "combat",
        "👹",
        [
            (1, "Champion Challenger", "Defeat 1 Elite or Boss monster.", 25, 15),
            (2, "Guardian Executioner", "Defeat 2 Elite or Boss monsters.", 35, 25),
            (3, "Warlord Conqueror", "Defeat 3 Elite or Boss monsters.", 40, 30),
            (4, "Dynasty Overlord", "Defeat 4 Elite or Boss monsters.", 45, 40),
        ],
    )

    # 3. Battles Completed (5 quests)
    add_tier_quests(
        "arena_runs",
        "battles_completed",
        "combat",
        "🛡️",
        [
            (2, "Warm-Up Skirmish", "Complete 2 arena battles today.", 20, 10),
            (3, "Frontline Duty", "Complete 3 arena battles today.", 25, 15),
            (4, "Battle Hardened", "Complete 4 arena battles today.", 30, 20),
            (5, "Relentless Fighter", "Complete 5 arena battles today.", 35, 25),
            (6, "Iron Marathon", "Complete 6 arena battles today.", 40, 30),
        ],
    )

    # 4. Flawless Victories (3 quests)
    add_tier_quests(
        "flawless_win",
        "flawless_wins",
        "combat",
        "🎯",
        [
            (1, "Clean Precision", "Win 1 battle with 0 wrong answers.", 25, 15),
            (2, "Master Tactician", "Win 2 battles with 0 wrong answers.", 35, 25),
            (3, "Untouchable Scholar", "Win 3 battles with 0 wrong answers.", 45, 35),
        ],
    )

    # 5. Combat Damage Dealt (6 quests)
    add_tier_quests(
        "deal_damage",
        "damage_dealt",
        "power",
        "💥",
        [
            (150, "Opening Strike", "Deal 150 total damage in battles.", 20, 10),
            (300, "Heavy Assault", "Deal 300 total damage in battles.", 25, 15),
            (500, "Shattering Blow", "Deal 500 total damage in battles.", 30, 20),
            (800, "Tempest Fury", "Deal 800 total damage in battles.", 35, 25),
            (1200, "Dragon Wrath", "Deal 1,200 total damage in battles.", 40, 30),
            (1600, "Celestial Cataclysm", "Deal 1,600 total damage in battles.", 45, 40),
        ],
    )

    # 6. Correct Answers (9 quests)
    add_tier_quests(
        "correct_ans",
        "correct_answers",
        "quiz",
        "📜",
        [
            (5, "Quick Study", "Answer 5 battle questions correctly.", 20, 10),
            (8, "Diligent Pupil", "Answer 8 battle questions correctly.", 20, 10),
            (10, "Hanzi Scholar", "Answer 10 battle questions correctly.", 25, 15),
            (12, "Fluent Mind", "Answer 12 battle questions correctly.", 25, 15),
            (15, "Tone Virtuoso", "Answer 15 battle questions correctly.", 30, 20),
            (18, "Imperial Scribe", "Answer 18 battle questions correctly.", 35, 20),
            (20, "Academy Prodigy", "Answer 20 battle questions correctly.", 35, 25),
            (25, "Grand Sage", "Answer 25 battle questions correctly.", 40, 30),
            (30, "Supreme Linguist", "Answer 30 battle questions correctly.", 45, 35),
        ],
    )

    # 7. Questions Practiced (7 quests)
    add_tier_quests(
        "practice_q",
        "questions_answered",
        "quiz",
        "🧠",
        [
            (8, "Vocabulary Drill", "Answer 8 total questions in battle.", 20, 10),
            (12, "Focused Revision", "Answer 12 total questions in battle.", 20, 10),
            (16, "Memory Workout", "Answer 16 total questions in battle.", 25, 15),
            (20, "Endurance Quizzer", "Answer 20 total questions in battle.", 30, 20),
            (25, "Scholarly Grind", "Answer 25 total questions in battle.", 35, 25),
            (30, "Lexicon Deep Dive", "Answer 30 total questions in battle.", 40, 25),
            (35, "Endless Curiosity", "Answer 35 total questions in battle.", 45, 30),
        ],
    )

    # 8. Pet Care Any (5 quests)
    add_tier_quests(
        "pet_care",
        "pet_care",
        "pet",
        "🐾",
        [
            (1, "Companion Greeting", "Feed or play with your pet 1 time.", 20, 10),
            (2, "Warm Bonding", "Feed or play with your pet 2 times.", 25, 15),
            (3, "Devoted Keeper", "Feed or play with your pet 3 times.", 30, 20),
            (4, "Sanctuary Guardian", "Feed or play with your pet 4 times.", 35, 20),
            (5, "Inseparable Duo", "Feed or play with your pet 5 times.", 40, 25),
        ],
    )

    # 9. Feed Companion (4 quests)
    add_tier_quests(
        "pet_feed",
        "pet_feed",
        "pet",
        "🦴",
        [
            (1, "Snack Time", "Feed your companion pet 1 treat.", 20, 10),
            (2, "Double Helping", "Feed your companion pet 2 treats.", 25, 15),
            (3, "Gourmet Spirit", "Feed your companion pet 3 treats.", 30, 20),
            (4, "Imperial Feast", "Feed your companion pet 4 treats.", 35, 25),
        ],
    )

    # 10. Play Rock-Paper-Scissors (4 quests)
    add_tier_quests(
        "pet_rps",
        "pet_rps",
        "pet",
        "✊",
        [
            (1, "Friendly Spar", "Play 1 Rock-Paper-Scissors round with your pet.", 20, 10),
            (2, "Best of Three", "Play 2 Rock-Paper-Scissors rounds with your pet.", 25, 15),
            (3, "Playful Rivalry", "Play 3 Rock-Paper-Scissors rounds with your pet.", 30, 15),
            (4, "RPS Champion", "Play 4 Rock-Paper-Scissors rounds with your pet.", 35, 20),
        ],
    )

    # 11. Play Blackjack vs Pet (4 quests)
    add_tier_quests(
        "pet_bj",
        "pet_blackjack",
        "pet",
        "🃏",
        [
            (1, "Opening Deal", "Play 1 Blackjack hand against your pet.", 20, 10),
            (2, "Double Down Spirit", "Play 2 Blackjack hands against your pet.", 25, 15),
            (3, "Card Shark Trainer", "Play 3 Blackjack hands against your pet.", 30, 20),
            (4, "High Roller Bond", "Play 4 Blackjack hands against your pet.", 35, 25),
        ],
    )

    # 12. Daily Bounty Check-in (1 quest)
    add_tier_quests(
        "daily_bounty",
        "daily_bounty",
        "power",
        "🎁",
        [
            (1, "Daily Check-In", "Claim your Daily Bounty reward on Home Base.", 20, 15),
        ],
    )

    # 13. Merchant Shop Purchases (3 quests)
    add_tier_quests(
        "shop_buy",
        "shop_purchases",
        "power",
        "🏪",
        [
            (1, "Supply Run", "Buy 1 item from the Merchant Shop.", 20, 10),
            (2, "Quartermaster", "Buy 2 items from the Merchant Shop.", 25, 15),
            (3, "Prepared Adventurer", "Buy 3 items from the Merchant Shop.", 30, 20),
        ],
    )

    # 14. Use Food / Potions (3 quests)
    add_tier_quests(
        "use_item",
        "items_used",
        "power",
        "🧪",
        [
            (1, "Quick Recovery", "Use 1 healing meal or potion from your bag.", 20, 10),
            (2, "Healthy Habit", "Use 2 healing meals or potions from your bag.", 25, 15),
            (3, "Alchemist's Discipline", "Use 3 healing meals or potions from your bag.", 30, 20),
        ],
    )

    # 15. Coins Earned Today (5 quests)
    add_tier_quests(
        "earn_coins",
        "coins_earned",
        "power",
        "🪙",
        [
            (25, "Pocket Change", "Earn 25 Coins from battles or mini-games today.", 20, 10),
            (50, "Bounty Collector", "Earn 50 Coins from battles or mini-games today.", 25, 15),
            (80, "Mercenary Purse", "Earn 80 Coins from battles or mini-games today.", 30, 20),
            (120, "Golden Ledger", "Earn 120 Coins from battles or mini-games today.", 35, 25),
            (180, "Treasury Builder", "Earn 180 Coins from battles or mini-games today.", 45, 35),
        ],
    )

    return tuple(quests)


QUEST_POOL: tuple[DailyQuestDef, ...] = _build_quest_catalog()  # 69 quests total
QUEST_DEF_BY_KEY: dict[str, DailyQuestDef] = {q.quest_key: q for q in QUEST_POOL}


def _normalize_daily_diamonds(chosen: list[DailyQuestDef]) -> list[DailyQuestDef]:
    """Scale the 5 selected quests in 5-Diamond steps so daily total always falls in [125, 185] (within 100-200)."""
    diamonds = [q.reward_diamonds for q in chosen]
    order_desc = sorted(range(len(chosen)), key=lambda i: diamonds[i], reverse=True)
    order_asc = list(reversed(order_desc))

    total = sum(diamonds)
    guard = 0
    while total < MIN_DAILY_DIAMONDS and guard < 40:
        progressed = False
        for idx in order_desc:
            if diamonds[idx] < 50:
                diamonds[idx] += 5
                total += 5
                progressed = True
                if total >= MIN_DAILY_DIAMONDS:
                    break
        if not progressed:
            break
        guard += 1

    guard = 0
    while total > MAX_DAILY_DIAMONDS and guard < 40:
        progressed = False
        for idx in order_asc:
            if diamonds[idx] > 15:
                diamonds[idx] -= 5
                total -= 5
                progressed = True
                if total <= MAX_DAILY_DIAMONDS:
                    break
        if not progressed:
            break
        guard += 1

    return [replace(q, reward_diamonds=diamonds[i]) for i, q in enumerate(chosen)]


def select_daily_quests(user_id: int, quest_date: date) -> tuple[DailyQuestDef, ...]:
    """
    Deterministically pick 5 distinct-metric daily quests per user & calendar date
    from the 69-quest pool, capping daily Diamond rewards between 125 and 185 Diamonds.
    """
    rng = random.Random(f"v2:{user_id}:{quest_date.isoformat()}")
    chosen: list[DailyQuestDef] = []
    used_metrics: set[str] = set()

    for bucket in ("combat", "quiz", "pet", "power"):
        candidates = [
            q for q in QUEST_POOL
            if q.bucket == bucket and q.metric not in used_metrics
        ]
        pick = rng.choice(candidates)
        chosen.append(pick)
        used_metrics.add(pick.metric)

    wild_candidates = [q for q in QUEST_POOL if q.metric not in used_metrics]
    wild_pick = rng.choice(wild_candidates)
    chosen.append(wild_pick)

    normalized = _normalize_daily_diamonds(chosen)
    return tuple(normalized)


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


async def record_quest_progress(
    db: AsyncSession,
    user_id: int,
    increments: dict[str, int],
) -> None:
    """Safely increment matching daily quests among today's 5 assigned quests."""
    clean = {k: int(v) for k, v in increments.items() if int(v) > 0}
    if not clean:
        return

    today = date.today()
    today_defs = select_daily_quests(user_id, today)
    matching = [q for q in today_defs if q.metric in clean]
    if not matching:
        return

    records_map = await _load_today_records(db, user_id, today)

    for q_def in matching:
        delta = clean[q_def.metric]
        rec = records_map.get(q_def.quest_key)
        if rec is None:
            rec = DailyQuestRecord(
                user_id=user_id,
                quest_date=today,
                quest_key=q_def.quest_key,
                progress=min(q_def.target, delta),
                is_claimed=False,
            )
            db.add(rec)
            records_map[q_def.quest_key] = rec
        else:
            rec.progress = min(q_def.target, (rec.progress or 0) + delta)

    await db.flush()


async def _backfill_today_activity(
    db: AsyncSession,
    user_id: int,
    today: date,
    today_defs: tuple[DailyQuestDef, ...],
    records_map: dict[str, DailyQuestRecord],
) -> None:
    """Sync quest counters for today's Daily Bounty & BattleHistory if not yet recorded."""
    changed = False

    def apply_value(q_def: DailyQuestDef, observed_value: int) -> None:
        nonlocal changed
        if observed_value <= 0:
            return
        capped = min(q_def.target, observed_value)
        rec = records_map.get(q_def.quest_key)
        if rec is None:
            rec = DailyQuestRecord(
                user_id=user_id,
                quest_date=today,
                quest_key=q_def.quest_key,
                progress=capped,
                is_claimed=False,
            )
            db.add(rec)
            records_map[q_def.quest_key] = rec
            changed = True
        elif (rec.progress or 0) < capped:
            rec.progress = capped
            changed = True

    bounty_quest = next((q for q in today_defs if q.metric == "daily_bounty"), None)
    if bounty_quest is not None and (records_map.get(bounty_quest.quest_key) is None or records_map[bounty_quest.quest_key].progress < 1):
        claim_row = (
            await db.execute(
                select(DailyClaim.id).where(
                    DailyClaim.user_id == user_id,
                    DailyClaim.claim_date == today,
                )
            )
        ).first()
        if claim_row is not None:
            apply_value(bounty_quest, 1)

    battle_metrics = {
        "battle_wins",
        "boss_wins",
        "battles_completed",
        "flawless_wins",
        "damage_dealt",
        "correct_answers",
        "questions_answered",
        "coins_earned",
    }
    needed_battle_quests = [
        q for q in today_defs
        if q.metric in battle_metrics and (
            q.quest_key not in records_map
            or (records_map[q.quest_key].progress or 0) < q.target
        )
    ]
    if needed_battle_quests:
        day_start_utc = datetime.combine(today, datetime.min.time(), tzinfo=timezone.utc)
        rows = (
            await db.execute(
                select(
                    BattleHistory.monster_type,
                    BattleHistory.result,
                    BattleHistory.damage_dealt,
                    BattleHistory.questions_answered,
                    BattleHistory.correct_answers,
                    BattleHistory.coins_earned,
                ).where(
                    BattleHistory.user_id == user_id,
                    BattleHistory.created_at >= day_start_utc,
                )
            )
        ).all()
        if rows:
            totals = {
                "battle_wins": sum(1 for r in rows if r.result == "win"),
                "boss_wins": sum(1 for r in rows if r.result == "win" and r.monster_type in {"boss", "elite"}),
                "battles_completed": sum(1 for r in rows if r.result in {"win", "lose"}),
                "flawless_wins": sum(1 for r in rows if r.result == "win" and (r.questions_answered or 0) > 0 and r.correct_answers >= r.questions_answered),
                "damage_dealt": sum(int(r.damage_dealt or 0) for r in rows),
                "correct_answers": sum(int(r.correct_answers or 0) for r in rows),
                "questions_answered": sum(int(r.questions_answered or 0) for r in rows),
                "coins_earned": sum(int(r.coins_earned or 0) for r in rows if r.result == "win"),
            }
            for q_def in needed_battle_quests:
                observed = totals.get(q_def.metric, 0)
                if q_def.metric == "coins_earned":
                    # Preserve non-battle coin progress if already higher (e.g. RPS/Blackjack)
                    existing = records_map.get(q_def.quest_key)
                    if existing and (existing.progress or 0) > observed:
                        continue
                apply_value(q_def, observed)

    if changed:
        await db.flush()


async def build_daily_quest_list(
    db: AsyncSession,
    user_id: int,
    stats: PlayerStats | None = None,
) -> DailyQuestListResponse:
    """Fetch and assemble today's 5 randomized Daily Quests."""
    today = date.today()
    today_defs = select_daily_quests(user_id, today)
    records_map = await _load_today_records(db, user_id, today)
    await _backfill_today_activity(db, user_id, today, today_defs, records_map)

    if stats is None:
        stats = (
            await db.execute(select(PlayerStats).where(PlayerStats.user_id == user_id))
        ).scalar_one_or_none()
        if stats is None:
            raise HTTPException(status_code=404, detail="Player stats not found")

    items: list[DailyQuestItemResponse] = []
    completed_count = 0
    claimable_count = 0
    claimable_diamonds = 0

    for q_def in today_defs:
        rec = records_map.get(q_def.quest_key)
        progress = min(q_def.target, int(rec.progress or 0)) if rec is not None else 0
        is_claimed = bool(rec.is_claimed) if rec is not None else False
        is_completed = progress >= q_def.target
        can_claim = is_completed and not is_claimed

        if is_completed:
            completed_count += 1
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
        completed_count=completed_count,
        total_base_quests=len(today_defs),
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
    """Claim one completed daily quest or all completed daily quests among today's 5 missions."""
    today = date.today()
    today_defs = select_daily_quests(user_id, today)
    today_by_key = {q.quest_key: q for q in today_defs}

    if quest_key is not None and quest_key not in today_by_key:
        raise HTTPException(status_code=404, detail="Quest is not part of today's missions")

    stats = (
        await db.execute(select(PlayerStats).where(PlayerStats.user_id == user_id))
    ).scalar_one_or_none()
    if stats is None:
        raise HTTPException(status_code=404, detail="Player stats not found")

    records_map = await _load_today_records(db, user_id, today)
    await _backfill_today_activity(db, user_id, today, today_defs, records_map)

    target_defs = [today_by_key[quest_key]] if quest_key is not None else list(today_defs)

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

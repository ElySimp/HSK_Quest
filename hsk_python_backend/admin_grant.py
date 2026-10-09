"""
Admin CLI tool to grant diamonds, coins, or update player stats.

Usage:
  python admin_grant.py <username> [--diamonds <amount>] [--coins <amount>] [--set]

Examples:
  python admin_grant.py Flax --diamonds 50000 --coins 10000
  python admin_grant.py Flax --diamonds 99999 --set
"""
import argparse
import asyncio
from sqlalchemy import select

from app.database import AsyncSessionLocal
from app.models.user import User
from app.models.player import PlayerStats


async def grant_resources(
    username: str,
    diamonds: int,
    coins: int,
    hp: int | None,
    level: int | None,
    is_set_mode: bool,
):
    async with AsyncSessionLocal() as db:
        # Find user
        result = await db.execute(
            select(User).where(User.username.ilike(username.strip()))
        )
        user = result.scalar_one_or_none()
        if not user:
            print(f"❌ User '{username}' not found in database!")
            return

        # Find player stats
        stats_result = await db.execute(
            select(PlayerStats).where(PlayerStats.user_id == user.id)
        )
        stats = stats_result.scalar_one_or_none()
        if not stats:
            print(f"❌ Player stats for user '{username}' (id={user.id}) not found!")
            return

        # Apply updates
        if is_set_mode:
            if diamonds > 0:
                stats.diamonds = diamonds
            if coins > 0:
                stats.coins = coins
        else:
            stats.diamonds += diamonds
            stats.coins += coins

        if hp is not None:
            stats.hp = hp
            stats.survival_hp = hp
            if hp > stats.max_hp:
                stats.max_hp = hp

        if level is not None:
            stats.level = level

        await db.commit()
        await db.refresh(stats)

        print("\n" + "=" * 50)
        print(f"✨ [ADMIN SUCCESS] Updated stats for '{user.username}' (ID: {user.id})")
        print("=" * 50)
        print(f"💎 Diamonds : {stats.diamonds} ({'+' if not is_set_mode else 'set '}{diamonds})")
        print(f"🪙 Coins    : {stats.coins} ({'+' if not is_set_mode else 'set '}{coins})")
        print(f"❤️ HP       : {stats.hp}/{stats.max_hp}")
        print(f"⭐ Level    : {stats.level}")
        print("=" * 50 + "\n")


def main():
    parser = argparse.ArgumentParser(description="Admin CLI to grant diamonds/coins to a player.")
    parser.add_argument("username", type=str, help="Username to grant resources to")
    parser.add_argument("--diamonds", type=int, default=0, help="Amount of diamonds to add")
    parser.add_argument("--coins", type=int, default=0, help="Amount of coins to add")
    parser.add_argument("--hp", type=int, default=None, help="Set HP")
    parser.add_argument("--level", type=int, default=None, help="Set Level")
    parser.add_argument("--set", action="store_true", help="Set exact amount instead of adding")

    args = parser.parse_args()

    # Default to 10000 diamonds if no amounts specified
    diamonds = args.diamonds
    coins = args.coins
    if diamonds == 0 and coins == 0 and args.hp is None and args.level is None:
        diamonds = 10000

    asyncio.run(
        grant_resources(
            username=args.username,
            diamonds=diamonds,
            coins=coins,
            hp=args.hp,
            level=args.level,
            is_set_mode=args.set,
        )
    )


if __name__ == "__main__":
    main()

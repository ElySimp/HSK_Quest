# Spirit Beast Buff List

Edit the **Value** columns freely. After editing, tell me and I will sync them to
`PetBuffCatalog.kt` (Android) and `app/services/pet_buffs.py` (backend).
Both files must always stay identical.

Legend: DMG = attack damage, Crit Window = time (from question start) in which an answer counts as critical,
Timer = extra answer time, Shield = number of mistakes fully blocked, 0 = no effect.

## Common

| Pet (`pet_type`)              | Buff title       | ATK DMG % | Crit DMG % | Crit Window (s) | Timer (s) | Shield | Enemy DMG Reduction % | Heal / Correct | Heal / Crit | Q1 Opener % | Revive HP % | Coins % | XP % |
| ------------------------------- | ---------------- | --------- | ---------- | --------------- | --------- | ------ | --------------------- | -------------- | ----------- | ----------- | ----------- | ------- | ---- |
| Wood Rat (`wood_rat`)         | Scavenger's Nose | 0         | 0          | 0               | 0         | 0      | 2                     | 0              | 0           | 0           | 0           | 0       | 0    |
| Stone Rabbit (`stone_rabbit`) | Earthen Guard    | 0         | 0          | 0               | 0         | 0      | 0                     | 0              | 1           | 0           | 0           | 0       | 0    |
| Bamboo Panda (`bamboo_panda`) | Bamboo Vitality  | 0         | 4          | 0               | 0         | 0      | 0                     | 0              | 0           | 0           | 0           | 0       | 0    |
| Golden Koi (`golden_koi`)     | Lucky Scales     | 0         | 0          | 0               | 0         | 0      | 0                     | 0              | 0           | 0           | 0           | 3       | 0    |
| Cloud Fox (`cloud_fox`)       | Swift Breeze     | 0         | 0          | 0               | 1         | 0      | 0                     | 0              | 0           | 0           | 0           | 0       | 0    |
| Jade Frog (`jade_frog`)       | Scholar's Croak  | 0         | 0          | 0               | 0         | 0      | 0                     | 0              | 0           | 0           | 0           | 0       | 2    |

## Rare

| Pet (`pet_type`)                | Buff title    | ATK DMG % | Crit DMG % | Crit Window (s) | Timer (s) | Shield | Enemy DMG Reduction % | Heal / Correct | Heal / Crit | Q1 Opener % | Revive HP % | Coins % | XP % |
| --------------------------------- | ------------- | --------- | ---------- | --------------- | --------- | ------ | --------------------- | -------------- | ----------- | ----------- | ----------- | ------- | ---- |
| Fu Dog (`fu_dog`)               | Temple Ward   | 0         | 0          | 0               | 0         | 1      | 5                     | 0              | 0           | 0           | 0           | 0       | 0    |
| Plum Deer (`plum_deer`)         | Blossom Grace | 0         | 0          | 2               | 0         | 0      | 0                     | 0              | 5           | 0           | 0           | 0       | 0    |
| Thunder Tiger (`thunder_tiger`) | Thunder Claw  | 8         | 5          | 0               | 0         | 0      | 0                     | 0              | 0           | 0           | 0           | 0       | 0    |
| Jade Tortoise (`jade_tortoise`) | Abyssal Shell | 0         | 0          | 0               | 0         | 0      | 0                     | 0              | 0           | 0           | 0           | 5       | 5    |

## Epic

| Pet (`pet_type`)                  | Buff title      | ATK DMG % | Crit DMG % | Crit Window (s) | Timer (s) | Shield | Enemy DMG Reduction % | Heal / Correct | Heal / Crit | Q1 Opener % | Revive HP % | Coins % | XP % |
| ----------------------------------- | --------------- | --------- | ---------- | --------------- | --------- | ------ | --------------------- | -------------- | ----------- | ----------- | ----------- | ------- | ---- |
| Fire Crane (`fire_crane`)         | Crimson Inferno | 12        | 10         | 1               | 0         | 0      | 0                     | 0              | 10          | 0           | 0           | 0       | 0    |
| Moon Kitsune (`moon_kitsune`)     | Lunar Illusion  | 0         | 0          | 0               | 3         | 0      | 10                    | 0              | 0           | 0           | 0           | 15      | 15   |
| Shadow Panther (`shadow_panther`) | Phantom Ambush  | 18        | 0          | 0               | 1         | 1      | 0                     | 5              | 0           | 0           | 0           | 0       | 0    |

## Legendary

| Pet (`pet_type`)                        | Buff title             | ATK DMG % | Crit DMG % | Crit Window (s) | Timer (s) | Shield | Enemy DMG Reduction % | Heal / Correct | Heal / Crit | Q1 Opener % | Revive HP % | Coins % | XP % |
| ----------------------------------------- | ---------------------- | --------- | ---------- | --------------- | --------- | ------ | --------------------- | -------------- | ----------- | ----------- | ----------- | ------- | ---- |
| Azure Dragon (`azure_dragon`)           | Storm Dragon Sovereign | 25        | 0          | 6               | 5         | 0      | 10                    | 0              | 0           | 0           | 0           | 0       | 25   |
| Qilin (`qilin`)                         | Auspicious Mandate     | 15        | 0          | 0               | 0         | 0      | 0                     | 5              | 10          | 0           | 0           | 40      | 40   |
| Vermilion Phoenix (`vermilion_phoenix`) | Nirvana Rebirth        | 20        | 0          | 0               | 3         | 2      | 5                     | 0              | 0           | 0           | 30          | 0       | 0    |

## Notes

- Crit Window / Timer are stored in milliseconds in code (value x 1000).
- "Revive HP %" is the HP restored by a one-time revive; 0 means the pet has no revive.
- Shield charges block the full damage of a wrong answer.
- Buffs only apply while the pet is awake (affection >= 25).

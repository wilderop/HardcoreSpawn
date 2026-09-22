# HardcoreSpawn

A Paper plugin that turns your world's spawn into a hardcore quest gauntlet.

## The idea

Type `/hardcore`, confirm, and everything you own — inventory, armor,
offhand, cursor item, XP, **ender chest**, and your current location — is snapshotted and
locked away. You wake up at the world spawn with empty pockets and a quest
with a five-minute timer. Finish quests to climb endless levels. Die, quit,
or vanish for too long, and it's over: your original self is restored and
everything you gained is gone.

## Gameplay

- **Start**: `/hardcore` shows a confirmation prompt; `/hardcore confirm`
  begins the run within 60 seconds. **Anti-combat-escape:** confirming starts
  a 10-second stand-still countdown (configurable via `start.freeze-seconds`;
  big title countdown on your screen). Move, teleport, or take any damage
  during it and the start is cancelled with nothing lost — starting teleports
  you to the server spawn, so it can't be a getaway. Survive the countdown and
  you are teleported to the server spawn with nothing. No free healing — you
  start exactly as hurt/hungry as you were.
- **Start precondition**: you may only start if your XZ distance from
  `(0, 0)` **in the overworld** is at least `2000` blocks (configurable),
  **unless** your inventory (storage, armor, offhand, cursor item) **and**
  ender chest are both completely empty. Outside the overworld the distance
  rule can never be satisfied, so only fully-empty players may start there.
  This keeps players from farming a run next to their base.
- **Quests**: you are dealt a hand of **3 quests at a time** — complete
  **any one** of them to reset the 5-minute timer. Each completion deals a
  fresh quest into the hand (never duplicating the other two), so you always
  have options. Quests are endless and generated per level (~1.3× scaling
  per level, never the same template twice in a row):
  - Levels 1–3: gathering (logs, cobble, dirt…)
  - Levels 4–6: iron, crafting, zombies
  - Levels 7–9: skeletons, diamonds, obsidian
  - Levels 10–12: the Nether, blazes
  - Level 13+: multi-objective endgame quests
  - Objective types: `BREAK`, `CRAFT`, `SMELT`, `KILL`, `OBTAIN`
- **Timer**: flat 5 minutes per quest. A BossBar shows your level and the
  countdown; warnings at 1:00 and 0:30. When time runs out you take
  half-heart damage every 10 seconds for 30 seconds, then you die.
  There are no quest rewards and no healing — only progress.
- **Milestone prize**: every 30 completed quests (configurable via
  `milestone-egg-every`, 0 disables) grants a random mob spawn egg —
  pig, wolf, axolotl, sniffer, and friends. Like any other run gain, it is
  lost on death unless you bank it in a world chest first.
- **Banking**: only physical world chests can bank your gains. Your ender
  chest is wiped at run start, **cannot be opened during a run**, and is
  restored when the run ends.
- **Commands during a run**: everything except `/hardcore` and
  `/hardcoreadmin` is blocked (deny by default — no teleports, homes,
  warps, or impostor commands).
- **Death**: your run gains drop where you died. Your original inventory
  and location are restored on respawn.
- **Quit**: your run gains are deleted (not dropped — no loot pinatas),
  your snapshot is restored, and you get **no healing or any other
  advantage** from quitting.
- **Disconnect**: rejoin within 60 seconds to resume. Gone longer and the
  run ends; your snapshot is restored when you log back in.
- **Restarts**: a restart pauses every run — the quest clock and the
  disconnect clock both freeze, so downtime counts against neither.
  Sessions, snapshots, pending restores, and the leaderboard all persist.

## Commands & permissions

| Command | Permission | Description |
|---|---|---|
| `/hardcore` | `hardcore.use` | Show the confirmation prompt |
| `/hardcore confirm` | `hardcore.use` | Start the 10 s stand-still countdown (within 60 s) |
| `/hardcore quit` | `hardcore.use` | Forfeit the run, restore snapshot |
| `/hardcore status` | `hardcore.use` | Level, quest progress, time left, best |
| `/hardcoreadmin reset <player>` | `hardcore.admin` | End a player's run, restore them |
| `/hardcoreadmin reload` | `hardcore.admin` | Reload config |

## Configuration (`config.yml`)

- `quest.time-seconds` (300), `warn-at-seconds` (60, 30)
- `timeout.damage` (1.0 = half heart), `interval-seconds` (10), `kill-after-seconds` (30)
- `disconnect.grace-seconds` (60)
- `start.min-distance` (2000), `start.center-x`/`center-z` (0)
- `scaling.per-level` (1.3), quest `bands` with per-level objective templates
- `messages.*` — every player-facing string, with `{placeholders}` and `§` colors
- `hud.*` — BossBar color/style and title format

Snapshots, sessions, restores, and the leaderboard persist as YAML under
`plugins/HardcoreSpawn/`.

## Build

Requires Java 21 and Maven.

```bash
mvn package   # produces target/HardcoreSpawn-1.0.0.jar
```

Drop the jar into your server's `plugins/` folder and restart. Paper
`1.21.11` is the target; the plugin enables cleanly with no dependencies.

## Design notes

- Snapshots encode each item as its `ItemStack.serialize()` map
  (the 1.21 data-component form, so names/lore/enchantments survive),
  Base64'd inside a plain Java-serialized list. The deprecated Bukkit
  object streams are intentionally avoided: since 1.21 they depend on the
  legacy `==` type key that `ItemStack.serialize()` no longer writes, so
  they cannot round-trip items at all.
- "Level" everywhere means the quest level reached (quest 1 = level 1),
  while `questsCompleted` is tracked separately for the leaderboard.
- Crafting progress counts shift-clicks by the number of full recipe sets
  in the crafting matrix.
- Spawn interference is accepted: starting a run teleports you to the
  server spawn, and other players there are part of the game.
- Run starts are fail-safe: if the pre-run snapshot cannot be written to
  disk, or the spawn teleport fails, the run is cancelled and the player's
  inventory is restored rather than risking item loss.

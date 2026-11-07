# PvPCore

A Paper plugin for FFA / practice servers with these modules, each configurable in `config.yml`:

| Module | What it does |
|---|---|
| Wind charge reset | A falling player who throws a wind charge downward has their fall cancelled about 1 tick later: vertical speed is replaced (default 0), horizontal speed is kept, fall damage is cleared. |
| Spear rules | Rewrites each spear's `kinetic_weapon` component so the charge attack hits right away (no wind-up delay) and the damage/knockback/dismount windows do not expire. Damage still scales with speed, so an elytra dive hits hardest. |
| Shield stun | When a shield is disabled (axe hit), the victim's invulnerability frames are cleared so the follow-up hit (mace, sword, anything) lands at full damage. |
| K/D stats + action bar | Counts kills and deaths and shows only `K/D: 4.1  #4th` on every player's action bar. Rank is by K/D, best first. |
| `/leaderboard` | Opens a dialog with the best K/D first, plus your own rank at the bottom. Falls back to chat on the console or if dialogs are unavailable. |

## Build

Needs JDK 21 or newer and Maven.

```
mvn package
```

The jar is `target/PvPCore.jar`. Drop it in your server's `plugins/` folder.

You do not need Java installed to get the jar: GitHub Actions builds it on every push. Open the repository's **Actions** tab, click the latest run, and download the `PvPCore` artifact at the bottom (it is a zip with the jar inside).

It is compiled against Paper API 1.21.11 (the first version with spears) using Java 21, and should load on 1.21.11 and on the 26.x versions. To build against your exact version, change `paper.api.version` in `pom.xml`. The leaderboard dialog needs Paper 1.21.7 or newer.

If you already have a `config.yml` from an older version, delete it once so the new `stats`, `actionbar` and `leaderboard` sections are generated.

## K/D rules

- K/D = kills / deaths. A player with no deaths yet has a K/D equal to their kills.
- Ranking: highest K/D first, then more kills, then fewer deaths, then name.
- Players with no kills and no deaths are unranked and show `unranked` instead of a rank.
- Counted from real player deaths. A death with no player killer (fall, void, lava) counts as a death for that player and no kill for anyone. If your FFA plugin fakes deaths (cancels the damage and teleports the player), those will not be counted; tell me and I can hook into it.
- Totals are saved to `plugins/PvPCore/stats.yml` every 5 minutes and on shutdown.

## Commands

`/leaderboard` (permission `pvpcore.leaderboard`, default everyone)

`/pvpcore` (permission `pvpcore.admin`, default op):

- `/pvpcore reload` reloads `config.yml`
- `/pvpcore info` shows module status and the values on the spear in your hand
- `/pvpcore spear` applies the spear rules to the spear in your hand
- `/pvpcore kit spearelytra [player]` adds a test kit (spear, elytra, rockets, wind charges, mace, axe, shield). It does not clear the inventory.
- `/pvpcore stats reset <player>` clears one player's K/D. `/pvpcore stats reset all confirm` clears everyone's.

Turn on `debug: true` in the config to get chat messages explaining why a wind charge reset or shield stun did or did not trigger.

## Notes for other plugins

Spears handed out by a kit plugin are picked up by the periodic scan (default every second). For an immediate result call `PvPCore#applySpearRules(ItemStack)`.

Spears keep the rewritten component after the module is switched off. Create a fresh item to get vanilla values back.

## Test checklist

1. Kill another player. Your action bar should go from `K/D: 0.0  unranked` to `K/D: 1.0  #1st`, and theirs to `K/D: 0.0  #2nd`.
2. `/leaderboard` opens a dialog with both players, you highlighted, and your rank at the bottom.
3. Restart the server. The numbers should still be there.
4. `/pvpcore kit spearelytra`, then `/pvpcore info` with the spear held. Delay should read 0t and the windows 1200t.
5. Jump from height, look down, throw a wind charge. You should stop falling and keep sideways speed.
6. Have a second player block with a shield and hit it with an axe, then hit with a mace or sword right away. The second hit should land at full damage.

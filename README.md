# MMO Mob Scaling

**[Download on CurseForge](https://www.curseforge.com/hytale/mods/mmo-mob-scaling)**

A standalone open-world mob difficulty-scaling companion to the
[MMO Skill Tree](https://www.curseforge.com/hytale/mods/mmo-skill-tree) mod. It scales
the difficulty of open-world mobs to the players around them, so a high-power group meets
tougher, rarer enemies while a lone newcomer is not overwhelmed.

## Status

**v1.0.0 (first release, in-game-validation pending).** The full scaling system is in:
layered native zone/biome difficulty floors + a distance-from-spawn escalation + a group-power
delta, a rarity ladder (Rare/Epic/Legendary + a forced Boss tier) with a 5-affix catalog on native
`EntityEffect` assets, deterministic per-UUID rolls, bonus kill-XP + native drop-list loot, the
`/mobscaling` admin tools, and two player HUD overlays (zone-difficulty card + crosshair mob
inspector). Numbers are still being tuned in-game, so they may shift between builds; everything is
data-driven, so any of it is retunable. User guide: [CURSEFORGE.md](CURSEFORGE.md).

## Install

Drop the built jar in your server's `Mods/` folder alongside its dependencies. Both are
loaded before this mod:

- **ZiggfreedCommon >= 1.2.0** - the shared primitive lib (its `scaling/` engine is the fold this mod builds on).
- **MMOSkillTree >= 1.5.0** - the MMO Skill Tree mod (supplies the player-power / combat-level API).

## Version story

This mod is compiled against the LOCAL `MMOSkillTree-1.5.0.jar` dev jar, which carries the
frozen 1.5.0 API this mod reads (`getPowerLevel` / `statRewardSum` / `getCombatLevel` +
the `registerMobKillXpMultiplier` reward hook). Its manifest pins the RUNTIME requirement at
MMOSkillTree `>=1.5.0` and ZiggfreedCommon `>=1.2.0`; both are loaded before this mod and
referenced `compileOnly`, never bundled. See the comment block in `build.gradle`.

## Build

Gradle runs via PowerShell (Java 25). Self-contained `build.ps1` builds + installs:

```powershell
cd 'D:\dev\business\hyMMO\additional-mods\mmo-mob-scaling'; .\build.ps1
.\build.ps1 -Install:$false     # build only
.\build.ps1 -ModsDir <path>     # explicit install target (else $env:HYTALE_MODS_DIR)
```

`.\gradlew.bat build` works too. The Hytale server jar, the ZiggfreedCommon jar, and the
MMOSkillTree jar are all referenced `compileOnly` and NEVER bundled (bundling would
double-load engine-touching classes under two classloaders).

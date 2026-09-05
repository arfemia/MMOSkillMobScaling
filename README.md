# MMO Mob Scaling

**[Download on CurseForge](https://www.curseforge.com/hytale/mods/mmo-mob-scaling)**

A standalone open-world mob difficulty-scaling companion to the
[MMO Skill Tree](https://www.curseforge.com/hytale/mods/mmo-skill-tree) mod. It scales
the difficulty of open-world mobs to the players around them, so a high-power group meets
tougher, rarer enemies while a lone newcomer is not overwhelmed.

## Status

**v1.2.0 is built and held for release; 1.1.0 is the last public build.** The full scaling system is in:
layered native zone/biome difficulty floors + a distance-from-spawn escalation + a group-power
delta, a rarity ladder (Rare/Epic/Legendary + a forced Boss tier) with a 5-affix catalog on native
`EntityEffect` assets, deterministic per-UUID rolls, bonus kill-XP + native drop-list loot, the
`/mobscaling` admin tools and the `/mobscaling ui` panel with persisted live tuning, per-world
settings and spawn pools, and two player HUD overlays (zone-difficulty card + crosshair mob
inspector). 1.2.0 leaves a boss an encounter script raises, and its adds, to Ziggfreed's CommonLib
2.1.0's boss framework, and lends a bound fight the power of the region it stands in. Numbers are
still being tuned in-game, so they may shift between builds; everything is data-driven, so any of it
is retunable. User guide: [CURSEFORGE.md](CURSEFORGE.md).

## Install

Drop the built jar in your server's `Mods/` folder alongside its dependencies. Both are
loaded before this mod:

- **ZiggfreedCommon >= 2.1.0** - the shared primitive lib (its `scaling/` engine is the fold this mod builds on, and its encounter framework is what this mod reads a bound boss off and hands region power to).
- **MMOSkillTree >= 1.6.1** - the MMO Skill Tree mod (supplies the player-power / combat-level API and the ability-casting API).

## Configuration

Config files are generated on first start under `mods/MmoMobScaling/`, **relative to the folder the
server runs from** (its working directory), not next to the jar. The absolute paths are logged on every
start (search the log for `mob-scaling config:`).

- `mob-scaling.json` - your overrides, empty by default; anything you do not set inherits the default.
- `_reference/defaults-mob-scaling.json` - the full default settings, rewritten every start. Read-only
  reference to copy keys from; editing it has no effect.
- `worlds/` - one file per world rule, with a `README.txt` describing the format.

## Extension packs

A content pack that authors `Server/MmoMobScaling/**` (or one of the `Server/NPC/Groups/Mmoscaling_*.json`
tagsets) **must** declare `"Ziggfreed:MmoMobScaling"` in its `manifest.json` `Dependencies`, or the mod's
own defaults load after it and silently overwrite every override. The id is case-sensitive, and a
misspelled one aborts the server's entire asset load. The mod audits the loaded packs at start and logs
exactly which line to add. Full authoring notes and troubleshooting: [CURSEFORGE.md](CURSEFORGE.md).

## Version story

The family moves in lockstep. This mod is compiled against the LOCAL `MMOSkillTree-1.6.1.jar`
and `ZiggfreedCommon-2.1.0.jar` dev jars (the pins in `gradle.properties`), and its manifest's
RUNTIME floors are those same versions: MMOSkillTree `>=1.6.1` and ZiggfreedCommon `>=2.1.0`. When
either dependency ships, the pin and the floor move together. Both jars are loaded before this mod
and referenced `compileOnly`, never bundled. See the comment blocks in `gradle.properties` and
`build.gradle`.

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

# MMO Mob Scaling

**[Download on CurseForge](https://www.curseforge.com/hytale/mods/mmo-mob-scaling)**

A standalone open-world mob difficulty-scaling companion to the
[MMO Skill Tree](https://www.curseforge.com/hytale/mods/mmo-skill-tree) mod. It scales
the difficulty of open-world mobs to the players around them, so a high-power group meets
tougher, rarer enemies while a lone newcomer is not overwhelmed.

## Status

**v1.2.2 is the current public build, the Update 7 release; a server still on Update 6 stays on
v1.2.1.** The full scaling system is in:
layered native zone/biome difficulty floors + a distance-from-spawn escalation + a group-power
delta, a rarity ladder (Rare/Epic/Legendary + a forced Boss tier) with a 5-affix catalog on native
`EntityEffect` assets, deterministic per-UUID rolls, bonus kill-XP + native drop-list loot, the
`/mobscaling` admin tools and the `/mobscaling ui` panel with persisted live tuning, per-world
settings and spawn pools, and two player HUD overlays (zone-difficulty card + crosshair mob
inspector). 1.2.0 leaves a boss an encounter script raises, and its adds, to Ziggfreed's CommonLib
2.1.0's boss framework, and lends a bound fight the power of the region it stands in; 1.2.4 scales
such a boss to its adds' level only when its fight opts in through CommonLib 2.5.0's companion
scale, as the Headless Horseman's does in Seasons of Orbis 1.2.2. Numbers are
still being tuned in-game, so they may shift between builds; everything is data-driven, so any of it
is retunable. User guide: [CURSEFORGE.md](CURSEFORGE.md).

## Install

Drop the built jar in your server's `Mods/` folder alongside its dependencies, on a Hytale
Update 7 (0.7.x) server. Both are loaded before this mod:

- **Ziggfreed's CommonLib >= 2.5.0** - the shared primitive lib (its `scaling/` engine is the fold this mod builds on, and its encounter framework is what this mod reads a bound boss off and hands region power to).
- **MMOSkillTree >= 1.7.1** - the MMO Skill Tree mod (supplies the player-power / combat-level API and the ability-casting API).

## Configuration

Config files are generated on first start under `mods/MmoMobScaling/`, **relative to the folder the
server runs from** (its working directory), not next to the jar. The absolute paths are logged on every
start (search the log for `[MobScalingConfig] config:`).

- `mob-scaling.json` - your overrides, empty by default; anything you do not set inherits the default.
- `_reference/defaults-mob-scaling.json` - the full default settings, rewritten every start. Read-only
  reference to copy keys from; editing it has no effect.
- `worlds/` - one file per world rule, with a `README.txt` describing the format.
- `difficulty/` - one file per zone or biome floor you want to retune (a file named after a shipped
  mapping with just a `Floor` in it is enough), with a `README.txt` describing the format.
- `casters/` - one file per caster roster you switch on, switch off or add (a file named after a
  shipped roster with just `"Enabled": true` in it is enough), with a `README.txt` describing the format.

## Extension packs

A content pack that authors `Server/MmoMobScaling/**` (or one of the `Server/NPC/Groups/Mmoscaling_*.json`
tagsets) **must** declare `"Ziggfreed:MmoMobScaling"` in its `manifest.json` `Dependencies`, or the mod's
own defaults load after it and silently overwrite every override. The id is case-sensitive, and a
misspelled one aborts the server's entire asset load. The mod audits the loaded packs at start and logs
exactly which line to add. Full authoring notes and troubleshooting: [MMO Mob Scaling: Files and Packs](https://mmo-skill-tree-docs.ziggfreed.com/docs/mob-scaling/) on the docs site.

## Version story

This mod is compiled against the LOCAL MMOSkillTree and ZiggfreedCommon dev jars the family builds:
the pins in `gradle.properties` follow each sibling's own version in lockstep. Its manifest's RUNTIME
floors are the oldest versions it really needs (MMOSkillTree `>=1.7.1`, ZiggfreedCommon `>=2.5.0`)
and move up only when it starts using newer API, so a sibling's release alone never forces a release
of this mod. Both jars are loaded before this mod and referenced `compileOnly`, never bundled. See the comment blocks in `gradle.properties` and
`build.gradle`.

## Build

Gradle runs via PowerShell (Java 25). Self-contained `build.ps1` builds + installs:

```powershell
cd 'D:\dev\business\hytale-dev\mmo-family\mmo-mob-scaling'; .\build.ps1
.\build.ps1 -Install:$false     # build only
.\build.ps1 -ModsDir <path>     # explicit install target (else $env:HYTALE_MODS_DIR)
```

`.\gradlew.bat build` works too. The Hytale server jar, the ZiggfreedCommon jar, and the
MMOSkillTree jar are all referenced `compileOnly` and NEVER bundled (bundling would
double-load engine-touching classes under two classloaders).

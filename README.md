# MMO Mob Scaling

A standalone open-world mob difficulty-scaling companion to the
[MMO Skill Tree](https://www.curseforge.com/hytale/mods/mmo-skill-tree) mod. It scales
the difficulty of open-world mobs to the players around them, so a high-power group meets
tougher, rarer enemies while a lone newcomer is not overwhelmed.

## Status

**v1.0.0 (skeleton).** This build lands the foundation only:

- A zero-cost registration toggle: when the config is disabled the mod registers NO
  systems, so it carries no per-tick cost at all.
- `MobScalingConfig` (override-based, `mods/MmoMobScaling/mob-scaling.json`), carrying the
  SIMPLE-preset starter values.

The scaling systems (spawn hook, damage filter, death listener) land in later phases.

## Install

Drop the built jar in your server's `Mods/` folder alongside its dependencies. Both are
loaded before this mod:

- **ZiggfreedCommon >= 1.2.0** - the shared primitive lib (its `scaling/` engine is the fold this mod builds on).
- **MMOSkillTree >= 1.5.0** - the MMO Skill Tree mod (supplies the player-power / combat-level API).

## Version story

This mod is compiled against the LOCAL `MMOSkillTree-1.4.4.jar` dev jar, which already
carries the frozen 1.5.0 API (`getPowerLevel` / `aggregatePower` / `statRewardSum` /
`getCombatLevel`). Its manifest, however, pins the RUNTIME requirement at MMOSkillTree
`>=1.5.0` - the eventual public hyMMO release that ships those API methods. So it compiles
now against the in-progress API and requires the 1.5.0 release at runtime. This is
deliberate; see the comment block in `build.gradle`.

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

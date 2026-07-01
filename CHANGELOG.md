# Changelog

All notable changes to MMO Mob Scaling. Newest first.

## 1.0.0

Phase-1 skeleton for the open-world mob difficulty-scaling companion to MMO Skill Tree.

- **New: zero-cost registration gate.** The plugin loads its config in `setup()` and
  applies a registration gate (`MobScalingPlugin.shouldRegisterSystems`): when the config
  is disabled it registers NO systems and returns, so the mod carries no per-tick cost at
  all. The scaling systems land in a later phase.
- **New: `MobScalingConfig`** (override-based, `mods/MmoMobScaling/mob-scaling.json`),
  extending the MMO Skill Tree `AbstractOverrideConfig` base and carrying the SIMPLE-preset
  starter values (rarity spawn chance + weights, zone difficulty overrides, party-join /
  late-arrival knobs, open-world aggregation mode, region size).

### Technical

- Standalone Hytale sibling mod; package root `com.ziggfreed.mmomobscaling`, entry point
  `MobScalingPlugin`.
- Compiles `compileOnly` against the local `MMOSkillTree-1.4.4.jar` dev jar (which carries
  the frozen 1.5.0 API) while the manifest pins the runtime requirement at MMOSkillTree
  `>=1.5.0` and ZiggfreedCommon `>=1.2.0`. Neither is bundled.

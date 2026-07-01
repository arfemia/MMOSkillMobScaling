# Changelog

All notable changes to MMO Mob Scaling. Newest first.

## 1.0.0

Phase-1 skeleton for the open-world mob difficulty-scaling companion to MMO Skill Tree.

- **New: zero-cost registration gate.** The plugin loads its config in `setup()` and
  applies a registration gate (`MobScalingPlugin.shouldRegisterSystems`): when the config
  is disabled it registers NO systems and returns, so the mod carries no per-tick cost at
  all. The scaling systems land in a later phase.
- **New: codec-driven `MobScalingConfig`.** The config schema + defaults are a Hytale asset
  codec (`MobScalingSettingsAsset`, Pattern A, PascalCase); the authoritative defaults ship as
  the codec asset `Server/MmoMobScaling/Settings/Default.json` (the SIMPLE preset), and owners
  override any key in `mods/MmoMobScaling/mob-scaling.json` (same PascalCase codec shape, partial
  allowed). The settings store is registered as a real claimed Hytale asset
  (`Server/MmoMobScaling/Settings`, via ziggfreed-common's `AssetStoreRegistrar`) so a content pack
  can override it, with two codec-driven read paths: SYNCHRONOUS `CODEC.decodeJson` at `setup()` (so
  the gate reads `Enabled` before the async store populates) + an async `LoadedAssetsEvent` re-fold
  for the pack layer. No config values are baked into Java.

### Technical

- Standalone Hytale sibling mod; package root `com.ziggfreed.mmomobscaling`, entry point
  `MobScalingPlugin`.
- Compiles `compileOnly` against the local `MMOSkillTree-1.4.4.jar` dev jar (which carries
  the frozen 1.5.0 API) while the manifest pins the runtime requirement at MMOSkillTree
  `>=1.5.0` and ZiggfreedCommon `>=1.2.0`. Neither is bundled.

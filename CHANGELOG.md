# Changelog

All notable changes to MMO Mob Scaling. Newest first. No em-dashes.

## Unreleased (1.5.0-cycle, in-game-validation pending)

The open-world scaling system on top of the 1.0.0 gate + codec config. Rarity ladder (Rare/Epic/Legendary)
+ a 5-affix catalog on native `EntityEffect` assets, a deterministic per-UUID roll, and the risk/reward loop.

- New: the spawn-lock `MobScalingSpawnHook` (rolls rarity + affixes deterministically off the entity UUID,
  folds the frozen `ScaledMobComponent`, scales HP via the native `EntityStatMap`), the effect-reconcile
  `MobScalingEffectApplySystem` (applies AND sweeps the native aura / STAT-affix effects), the damage-multiply
  `MobScalingDamageFilter`, and the inspect-group `MobScalingOnHitSystem` (lifesteal + the Freezing on-hit slow,
  reading the FINAL applied damage).
- New: kill-XP reward. A rarity kill pays more XP through the MMO's own kill path via a
  `MMOSkillTreeAPI.registerMobKillXpMultiplier` provider (kill XP only, never per-hit; an underdog bonus for
  fighting above your weight; anti-runaway hard cap). Native item-drop loot is a follow-up.
- New: RECONCILE on load. HP + auras converge to the current roll (`HealthUtil.reconcileMaxHealth` + an effect
  sweep), so a floor / rarity / affix retune never strands a stale inflated max or a doubled aura on a saved
  mob; an excluded / world-disabled mob is stripped. (Disable/uninstall caveat: a fully-off mod cannot
  self-heal saved residue; see CLAUDE.md.)
- Classification: dropped the over-broad `isCanLeadFlock` exclusion, so Hostile combat families that lead a
  flock (undead, etc.) now scale; livestock stays excluded by its Neutral attitude.
- Affixes: the RARITY AURA owns the body-tint channel; affix effects no longer carry competing body tints.
  Stalwart now grants knockback immunity (`KnockbackMultiplier: 0.0`); Freezing gains the native debuff
  affordances (`Debuff` + `StatusEffectIcon`) and is asset-authoritative (duration + overlap from the asset,
  no Java constant). Roster picks are tie-broken by id (a pure function of the asset set).
- Config: the settings fold is `owner > pack-store > jar` so a PARTIAL pack override can never silently
  disable the mod; `RaritySpawnChance` is clamped; a malformed owner file warns instead of failing silent.
- Lifted to ziggfreed-common (1.2.0): `SplitMix64` (mod-local copy deleted), `HealthUtil.reconcileMaxHealth`,
  `EntityEffectService.apply` (asset-authoritative).
- Removed the dead Boss tier assets (unreachable until the NPCGroup boss classifier lands).

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

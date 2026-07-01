# CLAUDE.md - MMO Mob Scaling

A **standalone open-world mob difficulty-scaling** companion to the MMO Skill Tree mod. It
scales open-world mobs to the players around them (a high-power group meets tougher, rarer
enemies; a lone newcomer is not overwhelmed). It is a supplemental mod under the **hyMMO
monorepo**'s `additional-mods/` (a git submodule; developed from the hyMMO root).
**Status: v1.0.0 (skeleton)** - the zero-cost registration toggle + `MobScalingConfig`
landed; the scaling systems (spawn hook, damage filter, death listener) land in later
phases.

Package root: **`com.ziggfreed.mmomobscaling`**.

## Build

Gradle runs via PowerShell (Java 25). Self-contained `build.ps1` builds + installs:

```powershell
cd 'D:\dev\business\hyMMO\additional-mods\mmo-mob-scaling'; .\build.ps1
.\build.ps1 -Install:$false     # build only
```

`.\gradlew.bat build` works too. Produces `build/libs/MmoMobScaling-<version>.jar`.

## Dependencies + version story

Both dependencies are provided at runtime (loaded first) and referenced `compileOnly` -
NEVER bundled (bundling double-loads engine-touching classes under two classloaders and
breaks class identity):

- **ZiggfreedCommon >= 1.2.0** (`compileOnly files(ziggfreedCommonJar)`, pin
  `ziggfreedCommonVersion`) - the shared primitive lib; its `scaling/` engine is the fold
  this mod builds on.
- **MMOSkillTree >= 1.5.0** at runtime (manifest `Dependencies`), but compiled against the
  LOCAL `MMOSkillTree-<mmoSkillTreeVersion>.jar` dev jar (pin `mmoSkillTreeVersion=1.4.4`,
  which already carries the frozen 1.5.0 API: `getPowerLevel` / `aggregatePower` /
  `statRewardSum` / `getCombatLevel`). **Version story (intentional):** compile against the
  dev jar, require 1.5.0 at runtime. See the comment block in `build.gradle`.

jsr305 is `implementation` (the `@Nonnull`/`@Nullable` annotations must resolve). No gson: the
config is decoded by the Hytale asset codec (`RawJsonReader` from the server jar), not gson.

## Paradigm - CONFIG IS AN ASSET CODEC (never Java-baked, never a loose JSON blob)

**HARD RULE (do NOT ever regress):** every config in this mod is defined by a Hytale asset codec
(Pattern A, `AssetBuilderCodec`, **PascalCase** keys), authored as a proper `Server/*` codec asset.
NEVER put config default VALUES in Java (`loadDefaults()` with hardcoded values is forbidden), and
NEVER drop a loose / camelCase Gson blob into `Server/` (that namespace is for codec assets only).
This mirrors the MMO's `WorldRulesAsset`/`WorldRulesConfig`. If you are tempted to hardcode a default
or hand-roll a JSON parser, STOP and add a codec field instead.

- **[`asset/MobScalingSettingsAsset`](src/main/java/com/ziggfreed/mmomobscaling/asset/MobScalingSettingsAsset.java)**
  is the ONE schema authority: an `AssetBuilderCodec` with PascalCase keys (`Enabled`,
  `CompositionEnabled`, `PresetMode`, `Intensity`, `RaritySpawnChance`,
  `AllowDifficultyIncreaseOnPartyJoin`, `LateArrivalBumpFactor`, `OpenWorldAggregationMode`,
  `RegionSizeChunks`). Fields are NULLABLE wrappers so an absent key stays `null` (the codec's
  `decodeJson` only calls a setter for a key present in the JSON), which is what makes the partial
  owner overlay work.
- The **authoritative defaults** ship as the codec asset
  `src/main/resources/Server/MmoMobScaling/Settings/Default.json` (PascalCase). Owners override any
  key in `mods/MmoMobScaling/mob-scaling.json` (the SAME PascalCase codec shape, partial allowed).
- **[`config/MobScalingConfig`](src/main/java/com/ziggfreed/mmomobscaling/config/MobScalingConfig.java)**
  reads the settings through TWO codec-driven paths (the `WorldRulesConfig` dual mechanism), folding
  owner-over-default, then exposes typed getters:
  - **Synchronous** at `setup()` (`load()`): decode the jar `Default.json` + the owner file via
    `CODEC.decodeJson(...)`. REQUIRED because the zero-cost registration gate reads `isEnabled()` at
    `setup()`, before an async store would populate. A broken jar (missing bundled default) fails safe
    (disabled). There are NO Java default VALUES here (only a neutral fail-safe for the broken jar).
  - **Async** on `LoadedAssetsEvent` (`applyStoreLayer(...)`): the registered store's folded
    (jar + pack) settings asset is re-applied over the owner file, so a content pack can override the
    runtime-read settings. (The gate already fired; a change to `Enabled` needs a restart.)
- **[`asset/MobScalingAssetRegistrar`](src/main/java/com/ziggfreed/mmomobscaling/asset/MobScalingAssetRegistrar.java)**
  registers the settings store (`Server/MmoMobScaling/Settings`) via ziggfreed-common's
  `AssetStoreRegistrar` + wires the `LoadedAssetsEvent` fold, so the settings are a REAL claimed
  Hytale asset (pack-overridable), not just a bundled resource. Registered only in the plugin's
  ENABLED branch (a disabled mod registers literally nothing).
- Map-shaped SIMPLE-preset knobs (rarity weights, zone difficulty overrides) are deliberately NOT in
  this flat settings asset: their canonical home is the per-type keyed assets (`Rarities/*.json`,
  `Difficulty/*.json`) landing in a later phase.

## Paradigm - NATIVE-ASSET-FIRST (prefer native systems + author our own assets into them)

**HARD PREFERENCE (user, 2026-07-01):** prefer NATIVE Hytale systems, and prefer AUTHORING OUR OWN
ASSETS INTO native systems, over hand-rolled Java - wherever a native system actually CONSUMES the asset.
This governs the scaling MECHANISMS (affixes, auras, movement, drops, classification, effect apply), not
just the config codec above. Decision rule for every new mechanism: ask **"can this be a pure-data asset on
a native system the engine reads?"** FIRST; fall back to mod-side Java only when the native path is absent OR
the engine does not consume it. Registering a thing nothing reads is NOT native leverage.

Confirmed by the native-leverage audit (hyMMO monorepo: `.claude/research/1-5-0-mob-scaling-native-audit.md`
+ verbatim `.claude/research/raw/1-5-0-mob-scaling-native-audit.json`); adopted patterns land in later
phases:
- **Affixes / auras / movement = pure-data `EntityEffect` fields on the ONE `addInfiniteEffect` batch, zero
  Java:** Armored (`DamageResistance`), Stalwart (`RawStatModifiers` +maxHP), **Swift / Crippling
  (`ApplicationEffects.HorizontalSpeedMultiplier` >1 / <1)**, aura tints/ModelVFX/badge/removal-sounds + boss
  `KnockbackMultiplier`. Swift is NOT deferred: the old "no native Speed" premise was a verified capability
  error (there IS a native movement-speed EFFECT field, folded into real NPC walk speed every tick).
- **Classification via authored `NPCGroup` tagset assets** (`Mmoscaling_Bosses` / `Mmoscaling_Excluded`,
  queried by `hasTagInGroup(roleIndex)`), owner-editable, NOT a Java-side boss registry.
- **Item drops via native `ItemDropList`** (per-rarity bonus `Drops` assets + `getRandomItemDrops` on death);
  currency / XP / notification stay on the MMO `content/reward` path.
- **Effect apply via a native `RefSystem.onEntityAdded`** (synchronous, add-pipeline CommandBuffer), not a
  deferred `world.execute` hop.

**Verified exceptions - keep mod-side Java (the native path is WORSE; do NOT "improve" these):** difficulty /
HP / mults stay on the transient `ScaledMobComponent` (a custom `EntityStatType` registers but NO native
system reads a non-default stat index, so it is pure per-tick cost); the general `inDmgMult` stays a frozen
pipeline multiply (native `DamageResistance` is per-cause, no wildcard, changes stacking); the rarity HP
MULTIPLIER stays on `HealthUtil.scaleMaxHealth` (the effect path lacks `maximizeStatValue`); Vampiric per-hit
lifesteal stays in `OnHitEffects` (no native on-hit-DEALT sensor). Full ranked evidence lives in the hyMMO
plan's "NATIVE-LEVERAGE AUDIT RESOLUTIONS" block (`.claude/plans/1-5-0-mob-scaling-system.md`).

## Paradigm - the zero-cost registration gate

The plugin's `setup()` loads `MobScalingConfig` (codec decode, above) then applies the gate:
`MobScalingPlugin.shouldRegisterSystems(cfg)`, which delegates to the pure predicate in
`MobScalingGate` (kept OFF the `JavaPlugin`-extending plugin class so it is loadable in a unit-test
JVM - loading `MobScalingPlugin` there fails via the `PluginBase` -> `MetricsRegistry` static-init
chain). When the config is disabled the plugin registers NOTHING and returns, so the mod carries no
per-tick cost at all. The scaling systems register only inside the enabled branch (the `// Phase 5:`
TODO).

## Conventions

`@Nonnull`/`@Nullable` on params; log via `MobScalingPlugin.LOGGER` (guard the raw
flogger LOGGER behind a try/catch on any path a unit test could reach - it throws in a
log-manager-less unit JVM). **No em-dashes anywhere** (use " - ", commas, parens). Localize
all player-facing text via `Message`/lang keys from day 1 (no raw display strings) when
that surface lands. Package root `com.ziggfreed.mmomobscaling`.

## Submodule order (when a remote exists)

Commit + push HERE first, verify the SHA is on the remote, THEN bump the gitlink in the
parent hyMMO repo (a root commit pointing at an unpushed mod SHA breaks fresh clones). The
mod builds + installs independently via its own `build.ps1`; it is NOT driven by the root
`rebuild.ps1`.

## Release notes

`CHANGELOG.md` is the dev changelog (newest first); `patch-notes/<version>.md` is the
per-version release note (frontmatter + summary + bullets). **Describe shipped reality, not
aspiration** - at skeleton stage say "skeleton", not "adds a mob-scaling system".

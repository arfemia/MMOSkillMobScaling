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
  decodes BOTH layers SYNCHRONOUSLY via `MobScalingSettingsAsset.CODEC.decodeJson(...)` at plugin
  `setup()` (the `WorldRulesConfig.decodeOwnerRule` pattern), folding owner-over-default, then
  exposes typed getters. Synchronous decode (not an async `LoadedAssetsEvent` keyed-asset store) is
  REQUIRED because the zero-cost registration gate reads `isEnabled()` at `setup()`, before an async
  store would populate. A broken jar (missing bundled default) fails safe (disabled). There are NO
  Java default values in `MobScalingConfig` (only a neutral fail-safe for the broken-jar case).
- Map-shaped SIMPLE-preset knobs (rarity weights, zone difficulty overrides) are deliberately NOT in
  this flat settings asset: their canonical home is the per-type keyed assets (`Rarities/*.json`,
  `Difficulty/*.json`) landing in a later phase.

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

# CLAUDE.md - MMO Mob Scaling

A **standalone open-world mob difficulty-scaling** companion to the MMO Skill Tree mod. It
scales open-world mobs to the players around them (a high-power group meets tougher, rarer
enemies; a lone newcomer is not overwhelmed). It is a supplemental mod under the **hyMMO
monorepo**'s `additional-mods/` (a git submodule; developed from the hyMMO root).
**Status: the scaling system is LANDED** (in-game-validation pending). The zero-cost registration
toggle + codec `MobScalingConfig`, plus the spawn-lock (`MobScalingSpawnHook`: rolls rarity/affixes,
reconciles HP, stamps the rarity-decorated `DisplayNameComponent`, resolves floor + region-power group
delta), the effect reconcile (`MobScalingEffectApplySystem`: applies + sweeps native aura / affix
effects), the damage-multiply filter, the inspect-group on-hit reactions (`MobScalingOnHitSystem`:
lifesteal + Freezing slow), the kill-XP reward (a `MMOSkillTreeAPI.registerMobKillXpMultiplier`
provider), the native `ItemDropList` death loot (`MobScalingLootDropSystem` + per-rarity
`Server/Drops/*` tables; the native roll + in-world spawn PLUMBING itself is consolidated onto
ziggfreed-common's `instance.reward.NativeLootService` (loot-native-consolidation Phase P4), so this
mod keeps only the pull-count policy, the per-mob seed, and the rarity/variant bonus-table selection - a
rarity/variant may ALSO author an optional additive `BonusRewards` layer, a `String[]` of ziggfreed-common
`LootEntry` compact specs (e.g. `"xp MINING 500"`, resolved through the MMO's own `xp`
`RewardSpecRegistry` token when the MMO jar is present) for currency/command/token rewards a native
`ItemDropList` cannot carry; resolved as guaranteed/any (no win/score axis on a mob kill) and granted to
the KILLER via `InstanceRewardGranter` + a `reward.MobScalingRewardSink` (mirrors Kweebec's sink: no
standalone currency system here, so a `currency` spec no-ops; a `COMMAND` spec, e.g. the MMO's `xp`
token, runs as console). The killer is resolved off the corpse's still-resident `DeathComponent.getDeathInfo()`
(mirrors the MMO jar's `MobKillEventSystem.resolveAttackerRef`); a non-player killer just skips the reward
layer. The continuous kill-XP multiplier (`MobScalingXpReward`) is a separate path, untouched by this),
the region-power tracker (`RegionPowerTracker` + `MobScalingPresenceSystem`),
NPCGroup boss/excluded classification (`Mmoscaling_Bosses`/`Mmoscaling_Excluded` tagsets + the forced
`boss` tier), `/mobscaling purge|inspect|hud|preset|intensity|ui` (1.0.2 adds `ui`, the in-game admin
config page (full-surface, spec-driven), + full write-back persistence for every runtime edit), content validation, 9-locale `scaling.lang`, and TWO
player-facing HUD overlays (`hud/` package + `MobScalingHudSystem`: the zone-difficulty card and the
crosshair mob inspector, both codec-configured + live-tunable via `/mobscaling hud`). The 2026-07-03
concerns pass ADDED: the NATIVE-ZONE floor resolver (`world/ZoneDifficultyResolver`: authored
`Difficulty/*.json` mappings over the engine's own `Zone.name()`/`Biome.getName()`, precedence zone
exact > zone `*` > biome exact > biome `*` > `WorldRules` baseline, one memoized zone read per chunk)
PLUS a configurable DISTANCE ESCALATION (additive difficulty + rarity-chance bonus with distance from
world spawn, so the deep frontier is deadly in every zone); the ZONE + PROXIMITY HYBRID region buckets
(`RegionPowerTracker.RegionKey` = native zone name + chunk sub-grid cell; zoneless worlds fall back to
the pure grid); and the NESTED-schema rework of every codec (see the paradigm below). Meanwhile the
MMO jar's `getPowerLevel` became the real multi-pillar formula (combat + stat rewards + abilities +
mastery + achievements, weights in `Server/MMOSkillTree/PowerLevel/Default.json`), so region power now
reflects builds, not just the max combat level. Remaining FOLLOW-UPS: the TriggerVolume floor layer +
`BossCurve` (see the hyMMO handoff plan). Everything is IN-GAME-VALIDATION PENDING.

**1.1.0 adds the CasterRoster system**: a Pattern-A asset
(`Server/MmoMobScaling/CasterRosters/*.json`) binding a `Role` selector (exact `Id` XOR `Glob`,
precedence exact > longest glob > first) to `Abilities[]` entries (`AbilityId` cast via the MMO's
`castNpcAbility` API XOR `NativeChain` armed once at spawn via native `CombatSupport.addAttackOverride`),
each entry gated by `MinDifficulty`/`Rarities`/`Scope`, on its own cadence + jitter, with an optional
per-entry `Windup` animation played through the engine's own `AnimationUtils` immediately before the
cast so a scaled mob visibly telegraphs the hit. Content is validated by
`ScalingContentValidator.validateCasterRosters`. Demo content ships as `Demo_Boss_Caster.json` (arms
the shipped Fire Dragon boss) plus a fully native CAE pair, spawnable via `/npc spawn
Mmoscaling_Caster_Demo`, that shows the same periodic-special-move idea authored with zero mod config
at all. IN-GAME-VALIDATION PENDING like the rest of the mod.

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

- **ZiggfreedCommon >= 1.3.0** (`compileOnly files(ziggfreedCommonJar)`, pin
  `ziggfreedCommonVersion`; 1.3.0's `world/WorldNameMatcher` carries the suffix/contains match forms
  a `*KweebecNightmare_*` per-world Match needs to catch the `instance-`-prefixed worlds) - the shared
  primitive lib; its `scaling/` engine is the fold this mod
  builds on, and (1.0.2) its settings-UI toolkit (`ui/SettingsUiUtil`, `ui/ZigRichButton`,
  `ui/hud/HudPosition`, `util/JsonOverrideWriter`, `Pages/ZigListRow.ui`, and `ui/form/` -
  `FieldSpec`/`SettingsForm` + the five `Pages/ZigForm*Row.ui` templates) backs the admin page, which
  is now spec-driven over `ui/form/` for full coverage of every CONSUMED knob (a few leaves - the
  per-world HUD group beyond `Enabled`, `RegionSizeChunks` - decode but deliberately apply globally, so
  the per-world form does not expose them; see `pages/CLAUDE.md`). The mod's own `hud/HudPosition` copy
  was retired for the lifted common one.
- **MMOSkillTree >= 1.5.0** at runtime (manifest `Dependencies`) AND compiled against the LOCAL
  `MMOSkillTree-1.5.2.jar` dev jar (pin `mmoSkillTreeVersion=1.5.2`), which carries the frozen 1.5.0 API
  the mod uses: `getPowerLevel` / `getPowerLevelMin` / `getPowerLevelMax` / `statRewardSum` /
  `getCombatLevel` (power reads) plus `registerMobKillXpMultiplier` (the kill-XP reward hook). The
  settings fold cross-checks `Difficulty.MinCap`/`MaxCap` against the clamp reads and warns on drift
  (guarded: an older jar without the getters validates clean). The 1.1.0 caster-roster feature's
  `ABILITY` entries ALSO call the MMO's `castNpcAbility(Store, Ref, String)` API (present in
  1.6.0-cycle jars, which is why the dev-jar pin is ahead of the ">=1.5.0" runtime-manifest floor
  above); `MobScalingCasterTickSystem` latches ability casting off for the whole session with one
  warning when that method is missing on an older jar. See the comment block in `build.gradle`.

jsr305 is `implementation` (the `@Nonnull`/`@Nullable` annotations must resolve). No gson: the
config is decoded by the Hytale asset codec (`RawJsonReader` from the server jar), not gson.

## Paradigm - CONFIG IS AN ASSET CODEC (never Java-baked, never a loose JSON blob)

**HARD RULE (do NOT ever regress):** every config in this mod is defined by a Hytale asset codec
(Pattern A, `AssetBuilderCodec`, **PascalCase** keys), authored as a proper `Server/*` codec asset.
NEVER put config default VALUES in Java (`loadDefaults()` with hardcoded values is forbidden), and
NEVER drop a loose / camelCase Gson blob into `Server/` (that namespace is for codec assets only).
This mirrors the MMO's `WorldRulesAsset`/`WorldRulesConfig`. If you are tempted to hardcode a default
or hand-roll a JSON parser, STOP and add a codec field instead.

- **HARD RULE #2 (2026-07-03, user mandate; do NOT regress): cohesive knob groups are NESTED
  sub-objects, NEVER flat prefixed keys.** A group of related fields gets its own static nested class
  with its own `BuilderCodec`, referenced via `new KeyedCodec<>("Group", Group.CODEC, false)` (the
  in-repo exemplars: `MobScalingSettingsAsset.OpenWorld`/`Difficulty`/`DistanceEscalation`/`ZoneHud`,
  `RarityAsset.Roll`/`Multipliers`/`Affixes`/`Families`, `AffixAsset.Roll`/`FoldDeltas`, the MMO jar's
  `WorldSettings.Pool` + the MMO jar's `PowerLevelAsset.Clamp`/`Pillars`/`Modes`). A flat suffix/prefix soup
  (`ZoneHudOffsetX`, `HpMult`/`OutDamageMult`/...) is a schema smell: it is not future-proof (a new
  knob lands INSIDE its group) and it does not read as a schema. Nesting composes with the partial
  overlay: every nesting level uses NULLABLE wrapper fields and the fold walks per LEAF.
- **[`asset/MobScalingSettingsAsset`](src/main/java/com/ziggfreed/mmomobscaling/asset/MobScalingSettingsAsset.java)**
  is the ONE schema authority: an `AssetBuilderCodec` with PascalCase keys, top-level `Enabled` /
  `PresetMode` (verified UNCONSUMED - nothing reads `getPresetMode()` outside the schema/config fold;
  deliberately NOT exposed on the admin-page UI, round-2 hardening) / `Intensity` / `RaritySpawnChance`
  plus the NESTED groups `OpenWorld`
  (`AggregationMode`/`RegionSizeChunks`/`GroupDeltaBandWidth`/`AllowDifficultyIncreaseOnPartyJoin`/
  `LateArrivalBumpFactor`/`CompositionEnabled`), `Difficulty` (`MinCap`/`MaxCap` + nested
  `DistanceEscalation` `Enabled`/`StartDistanceBlocks`/`BlocksPerPoint`/`MaxBonus`/
  `RarityChancePerPoint`), `ZoneHud` and `InspectorHud` (`Enabled`/`Position`/`OffsetX`/`OffsetY`
  (+`RangeBlocks` on the inspector); positions are named corner presets parsed by
  `hud/HudPosition.parse`). Fields are NULLABLE wrappers at EVERY nesting level so an absent key (or a
  partially-filled group) stays `null`, which is what makes the per-leaf partial owner overlay work.
  **1.0.1**: `Intensity` is a NUMERIC multiplier (default 1.0, was a dead string) applied to the
  `StatCurve` slopes in `config/MobScalingConfig.statCurveModel()` (runtime-tunable via `/mobscaling
  intensity`, `setIntensityRuntime`); `OpenWorld` gained `PlayerScalingEnabled` (default true; false
  skips the group delta). **1.0.2**: `Difficulty` gained `Floor` (the world-baseline difficulty floor
  under the zone/biome `Difficulty/*.json` mappings; global default 30.0 in `Settings/Default.json` -
  absorbed from the MMO jar's removed `WorldRules.MobScaling` group), and the 1.0.1 inline
  `WorldOverrides` array was REMOVED in favour of the per-world files below.
- **PER-WORLD settings are their OWN files (1.0.2)**: keyed raw-`Payload` assets
  [`asset/WorldSettingsAsset`](src/main/java/com/ziggfreed/mmomobscaling/asset/WorldSettingsAsset.java)
  under `Server/MmoMobScaling/Worlds/*.json` (jar/packs) PLUS a scanned owner dir
  `mods/MmoMobScaling/worlds/*.json` (one file per world rule, filename = id; bare body canonical, a
  pack-style `Payload` wrapper is peeled). The body's ONE schema authority is
  [`asset/WorldSettings`](src/main/java/com/ziggfreed/mmomobscaling/asset/WorldSettings.java)
  (`BuilderCodec`, nullable leaves): `Match` (blank = a pool-only BASE, never matched), per-world
  `Enabled` kill-switch, `Intensity`, `RaritySpawnChance`, the FULL `Difficulty` + `OpenWorld` groups
  (reused codecs; `RegionSizeChunks` decodes but stays GLOBAL for grid consistency), `ZoneHud`/
  `InspectorHud` (per-world `Enabled` consumed; hide-only vs a globally-on HUD), and the `Pool` group
  (`Rarities`/`Variants`/`Affixes` `Allow`/`Deny` lists, deny wins; `Variants.ChanceMultiplier`;
  `Affixes.ExtraSlots`). A body may carry a top-level `"Parent": "<file-id>"` resolved CROSS-LAYER by
  common's `codec/JsonParentResolver` (raw pre-merge, memoized, cycle-guarded; child overrides per leaf,
  arrays replace wholesale) - unset leaves fall through the chain THEN to the global effective settings.
  [`config/WorldSettingsConfig`](src/main/java/com/ziggfreed/mmomobscaling/config/WorldSettingsConfig.java)
  owns the pool + fold (pack layer cached from `LoadedAssetsEvent`, owner dir re-scanned per refold,
  replace-by-id across layers - layering is id-replace, inheritance is Parent's job) and the ONE-TIME
  migration off the shipped-1.0.1 inline owner array (`migrateLegacyOwnerOverrides`: each entry ->
  `worlds/<match>.json`, `PlayerScalingEnabled` moved under `OpenWorld`, array stripped). Matching is
  common's `world/WorldNameMatcher` (exact > longest `*`-prefix > `*`; the old `WorldOverrideMatcher`
  is deleted). **The spawn hook + HUD + inspect read the per-world view via
  `config/SpawnScalingSettings` (interface; `MobScalingConfig implements` it) +
  `MobScalingConfig.spawnSettingsFor(worldName)` (cached `ResolvedWorldSettings` overlay with
  precompiled pool sets; returns `this` on no-match), NEVER the global getters directly.** Jar defaults:
  `Worlds/DungeonOfFear_Base.json` (pool-only base: escalation off) inherited by `DungeonOfFear_I/II/III`
  (I + II pin player scaling off) + `Worlds/KweebecNightmare.json` (`Enabled:false`). The MMO jar's
  WorldRules carries NO mob-scaling knobs anymore - this mod's files are the ONE per-world surface.
- The **authoritative defaults** ship as the codec asset
  `src/main/resources/Server/MmoMobScaling/Settings/Default.json` (PascalCase). Owners override any
  key in `mods/MmoMobScaling/mob-scaling.json` (the SAME PascalCase codec shape, partial allowed).
- **WRITE-BACK (1.0.2): `config/MobScalingOwnerWriter` is the ONE path that persists a runtime edit** to
  that owner file (partial-override write via the common `util/JsonOverrideWriter`, then
  `MobScalingConfig.refreshFromDisk` refolds live). BOTH the admin UI ([`pages/MobScalingAdminPage`](src/main/java/com/ziggfreed/mmomobscaling/pages/CLAUDE.md), `/mobscaling ui`)
  AND the `/mobscaling intensity|hud|preset` commands go through it, so a live change now STICKS across a
  restart (1.0.1's runtime-only setters remain but are superseded). Never write the owner file or mutate
  `MobScalingConfig` fields from a page/command directly - route through `MobScalingOwnerWriter`.
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
  the settings asset: their canonical home is the per-type keyed assets, ALL LANDED as Pattern-A
  codecs with nested groups: `Rarities/*.json` (`Roll`/`Multipliers`/`Affixes`/`Families` groups, fold
  `RarityConfig`), `Variants/*.json` (the second overlay axis - `Roll` with an absolute `Chance` +
  `AllowedRarities` requires-rarity gate, `Multipliers`/`Affixes`/`Families` + top-level `AuraEffectId`
  (fallback tint, applied only when the base rarity has none) / `BonusDropList` (stacks on the rarity's death
  loot), fold `VariantConfig`), `Affixes/*.json` (`Roll` incl. `AllowedRarities`
  + `AllowedVariants`/`FoldDeltas`, fold `AffixConfig`), and
  `Difficulty/*.json` (`TargetType` Zone|Biome + `TargetId` native name or `*` + `Floor`, fold
  `DifficultyConfig` with a derived O(1) name index, consumed by `world/ZoneDifficultyResolver`; the
  jar ships the Zone0..Zone4 starter gradient + the zone wildcard + an Ocean1 biome example).

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
- **Affixes / auras / movement = pure-data `EntityEffect` fields self-applied via the asset-authoritative
  `EntityEffectService.apply`, zero Java:** Armored (`DamageResistance`), **Stalwart (`KnockbackMultiplier: 0.0`
  = knockback immunity; its +15% HP is `HpDelta` folded into `hpMult`, applied via `HealthUtil`, NOT an
  effect)**, **Swift (`ApplicationEffects.HorizontalSpeedMultiplier` 1.3)**, aura tints/ModelVFX. Swift is NOT
  deferred: there IS a native movement-speed EFFECT field, folded into real NPC walk speed every tick.
  **The RARITY AURA owns the body-tint channel (blue=rare, purple=epic, gold=legendary); affix effects carry
  NO body tint** (they would fight the aura with no arbitration) - affix identity is the mechanic + (follow-up)
  the name stamp / a particle telegraph. The Freezing slow is VICTIM-applied and keeps its frost tint.
- **Classification via authored `NPCGroup` tagset assets** (`Mmoscaling_Bosses` / `Mmoscaling_Excluded`,
  queried by `hasTagInGroup(roleIndex)`), owner-editable, NOT a Java-side boss registry. The **per-family
  rarity gate** (1.0.0) reuses the SAME native mechanism: a rarity's nested `Families` block
  (`AllowGroups`/`DenyGroups` native `NPCGroup` ids + `AllowRoles`/`DenyRoles` role-name globs, deny wins,
  absent = allow-all) narrows which tiers may roll on a given mob. The matcher lives in the axis-neutral
  `family/` package (pure `FamilyFilter`/`FamilyGlob` - the glob lifts native `StringUtil.isGlobMatching`,
  case-folded - plus the engine `MobFamilyMatcher`, which mirrors `MobClassifier`'s lazy group-index cache
  and warns once on an unknown group id). It is a pure `Predicate<Rarity>` threaded into `RarityRoster.pick`
  (consumes no RNG, determinism preserved); the FORCED boss tier bypasses the roll and is unaffected. The
  package is deliberately axis-neutral so the **variant** axis (below) reuses it unchanged.
- **Variant OVERLAY axis** (1.0.0): a `variant/` package (`Variant`/`VariantRoster`) rolls a SECOND,
  independent family-gated overlay AFTER the base rarity (at most one), stacking MULTIPLICATIVELY on the
  rarity in `MobScaleFold` (the fold takes a nullable `Variant`; `MobScaleResult` gained a `variantId`). A
  variant carries its OWN affix slots + allow-list; affixes gained an `AllowedVariants` gate so an affix can
  be variant-exclusive (the shipped `venomous` on `horrific`), and `AffixRoster.pick(rarity, variant, rng)`
  rolls both hosts into one distinct list sharing the used-set + single-resistance cap. A variant has NO
  aura/tint (rarity owns that channel) - identity is the `{variant} {rarity} {base}` name frame + its
  affix(es). The variant roll is ONE deterministic draw partitioned by the eligible variants' absolute
  `Chance`, gated by `MobFamilyMatcher` (`Families`) AND the variant's `AllowedRarities` (which base rarities
  it may overlay; `["*"]` = any incl. plain, passed the rolled base rarity id). A variant's `BonusDropList`
  stacks on the rarity's in `MobScalingLootDropSystem` (both lists pulled), as does its `BonusRewards`
  additive command/token layer (P4; both hosts' entries are granted to the killer), and its `AuraEffectId` is a
  FALLBACK tint applied by `MobScalingEffectApplySystem` only when the base rarity contributed no aura (rarity
  always wins the single tint channel). The crosshair inspector HUD renders the variant as its own coloured
  tag (`#MmoscalingInspectVariant`, `Variant.displayColor()`).
- **Item drops via native `ItemDropList`** (per-rarity bonus `Drops` assets + `getRandomItemDrops` on death);
  currency / XP / notification stay on the MMO `content/reward` path.
- **Effect apply via a native `RefSystem.onEntityAdded`** (synchronous, add-pipeline CommandBuffer), not a
  deferred `world.execute` hop.

**Verified exceptions - keep mod-side Java (the native path is WORSE; do NOT "improve" these):** difficulty /
HP / mults stay on the transient `ScaledMobComponent` (a custom `EntityStatType` registers but NO native
system reads a non-default stat index, so it is pure per-tick cost); the general `inDmgMult` stays a frozen
pipeline multiply (native `DamageResistance` is per-cause, no wildcard, changes stacking); the rarity HP
MULTIPLIER **and the Stalwart affix HpDelta** stay on `HealthUtil` (the effect path lacks `maximizeStatValue`,
and an effect-based +maxHP would spawn the mob damaged + double-apply with the HpDelta fold) - but the LOAD path
now uses the RECONCILE variant `HealthUtil.reconcileMaxHealth` (converges the keyed modifier to the fresh roll,
so a retune / floor / rarity change never strands a stale inflated max); Vampiric per-hit lifesteal stays
mod-side in `MobScalingOnHitSystem` (no native on-hit-DEALT sensor). Full ranked evidence lives in the hyMMO
plan's "NATIVE-LEVERAGE AUDIT RESOLUTIONS" block (`.claude/plans/1-5-0-mob-scaling-system.md`).

**Disable / uninstall caveat (persisted residue):** the `mmoscaling_hp` MAX modifier + the `Mmoscaling_*`
infinite auras persist WITH a saved mob. While the mod is ENABLED, the spawn hook reconciles them on every
load (retunes self-heal, and an excluded / world-disabled mob is stripped). But a FULLY disabled / uninstalled
mod registers nothing and cannot self-heal, so its residue lingers on saved scaled mobs until each dies.
Recommendation: run once with the mod enabled after a big retune so the reconcile sweeps saved mobs; for a
FULL uninstall, run `/mobscaling purge` per world first (the command registers even when scaling is
disabled, precisely for this flow) - it strips the HP modifier + all `Mmoscaling_*` infinite effects off
loaded mobs.

## Paradigm - the zero-cost registration gate

The plugin's `setup()` loads `MobScalingConfig` (codec decode, above) then applies the gate:
`MobScalingPlugin.shouldRegisterSystems(cfg)`, which delegates to the pure predicate in
`MobScalingGate` (kept OFF the `JavaPlugin`-extending plugin class so it is loadable in a unit-test
JVM - loading `MobScalingPlugin` there fails via the `PluginBase` -> `MetricsRegistry` static-init
chain). When the config is disabled the plugin registers NOTHING and returns, so the mod carries no
per-tick cost at all. The scaling systems + the kill-XP reward provider register only inside the
enabled branch.

## Conventions

`@Nonnull`/`@Nullable` on params; log via `MobScalingPlugin.LOGGER` (guard the raw
flogger LOGGER behind a try/catch on any path a unit test could reach - it throws in a
log-manager-less unit JVM). **No em-dashes anywhere** (use " - ", commas, parens). Localize
all player-facing text via `Message`/lang keys from day 1 (no raw display strings) when
that surface lands. Package root `com.ziggfreed.mmomobscaling`.

## Submodule order (when a remote exists)

Commit + push HERE first, verify the SHA is on the remote, THEN bump the gitlink in the
parent hyMMO repo (a root commit pointing at an unpushed mod SHA breaks fresh clones). The
mod builds + installs independently via its own `build.ps1`, and the root `rebuild.ps1 -Mods`
ALSO drives it (dependency-ordered after `ziggfreed-common`) via that same `build.ps1`.

## Release notes

`CHANGELOG.md` is the dev changelog (newest first); `patch-notes/<version>.md` is the
per-version release note (frontmatter + summary + bullets). **Describe shipped reality, not
aspiration** - at skeleton stage say "skeleton", not "adds a mob-scaling system".

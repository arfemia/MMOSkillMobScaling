# CLAUDE.md - MMO Mob Scaling

A **standalone open-world mob difficulty-scaling** companion to the MMO Skill Tree mod. It
scales open-world mobs to the players around them (a high-power group meets tougher, rarer
enemies; a lone newcomer is not overwhelmed). It is a supplemental mod under the **hyMMO
monorepo**'s `additional-mods/` (a git submodule; developed from the hyMMO root).
**Status: v1.2.0 (in development beside ziggfreed-common 2.1.0 and MMO Skill Tree 1.6.1, the family it ships with; 1.1.0 released 2026-08-31).** The zero-cost registration
toggle + codec `MobScalingConfig`, plus the spawn-lock in two halves: `MobScalingSpawnHook` (the
pre-add `HolderSystem`: the mod and per-world switches, the classification, the residue cleanup, and
a one-tick `PendingRollComponent` stamp; a holder already carrying `ScaledMobComponent` is left as
it is, so an in-place role change re-adding a boss through every holder system never re-rolls it)
and `MobScalingRollSystem` (the tick after the add, on a valid `Ref`: SKIPS a `ManualTrigger`
spawn-marker spawn (a scripted boss or its adds) and an encounter's bound subject
(`event/EncounterBinding`, a `LinkageError`-guarded `EncounterRuntime.isBoundSubject`), else rolls
rarity/affixes/variant from the stable seed, stamps the result, decorates the display name,
reconciles HP and calls the effect + caster-arm bodies directly, since a stamp onto a live entity
fires no `RefSystem`; one INFO per boot per path), the effect reconcile
(`MobScalingEffectApplySystem`: applies + sweeps native aura / affix effects), the damage-multiply filter, the inspect-group on-hit reactions (`MobScalingOnHitSystem`:
lifesteal + Freezing slow), the kill-XP reward (a `MMOSkillTreeAPI.registerMobKillXpMultiplier`
provider) + the kill-rarity attribution (`event/MobScalingRarityAttribution`, a
`registerKillRarityProvider` provider handing a scaled kill's rarity id to the MMO as the kill
qualifier, which its engines match tier-authored KILL_ENTITY criteria on (First_Legend_Kill and
friends) and its mob-drop command `{tier}` placeholder resolves; registration is
LinkageError-guarded for older MMO jars), the death loot (`MobScalingLootDropSystem`: a rarity and a variant each author ONE `Loot`
block in ziggfreed-common's shared loot vocabulary - `Lootables` by id and/or inline `Rolls` whose
`Grants` carry `DropLists` (the per-rarity native `Server/Drops/*` tables), `Items`, `Commands` and
registered `Rewards` kinds, each roll gateable on `Conditions` and scaled by a factor-driven `Chance`.
The engine that rolls it is `loot.LootEngine`; this mod keeps only the per-trigger POLICY - the pass
count (`Multipliers.Loot` = how many times a block is rolled, `floor` guaranteed plus a fractional
extra off the per-mob seed), the corpse drop position, and the killer resolution. Items and drop lists
spill on the ground through `instance.reward.NativeLootService` whatever killed the mob; commands and
reward kinds need a player and are wired only when the killer resolves to one, off the corpse's
still-resident `DeathComponent.getDeathInfo()` (mirrors the MMO jar's
`event/MmoMomentReactions.resolveAttackerRef`). ONE `FactorSnapshot` covers the whole death, so two rolls
asking the same question always agree. The continuous kill-XP multiplier (`MobScalingXpReward`) is a
separate path, untouched by this),
the region-power tracker (`RegionPowerTracker` + `MobScalingPresenceSystem`),
NPCGroup boss/excluded classification (`Mmoscaling_Bosses`/`Mmoscaling_Excluded` tagsets + the forced
`boss` tier; `Mmoscaling_Bosses` is the AMBIENT world-boss scope, a boss nobody scripted, since a
scripted or encounter-bound boss is skipped before the classifier's answer matters), the fill of
ziggfreed-common's `EncounterPowerSource` seam (`factor/EncounterPowerFill`: a bound fight's power is
the tracked region power at its SUBJECT's own world and chunk, null on a cold miss, never zero;
`RegionPowerTracker.scalarIfTracked` beside the zero-delta `scalarFor`; `world/RegionKeys` composes
the key for the presence tick, the factor and the fill alike), `/mobscaling purge|inspect|hud|preset|intensity|worlds|ui` (1.0.2 adds `worlds`, the read-only
listing of the folded per-world rules, and `ui`, the in-game admin
config page (full-surface, spec-driven), + full write-back persistence for every runtime edit), content validation, 9-locale `mmomobscaling.lang`, and TWO
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

**1.1.0 also PUBLISHES what this mod knows about a mob, as factors** ([`factor/MobScalingFactors`](src/main/java/com/ziggfreed/mmomobscaling/factor/MobScalingFactors.java)):
five namespaced ids claimed at `setup()` through ziggfreed-common's process-wide
`FactorContributions` door - `mmomobscaling:mob_rarity_tier` (ladder position, 0 = plain, derived from
the tiers' own strength ordering via `RarityRoster.tierOf`), `:mob_rarity` (Param = a rarity id),
`:mob_affix` (Param = an affix id), `:mob_difficulty`, `:region_power`. Every one reads
`FactorContext.target()`, the entity the moment happened TO, so a mob-kill roll can weigh the mob's
rarity and the killer's own luck in one formula; `region_power` falls back to the subject because it is
about a PLACE. **The claim is made INSIDE the zero-cost enabled branch on purpose**: a disabled mod
must publish nothing, so content gated on a scaled mob fails closed exactly as it does where this mod
is absent. The same class owns this mod's OWN `FactorRegistry` (carrying `HytaleFactors`, so a `Loot`
roll can read the killer's native stat channels), which is what the death-loot rolls resolve against.
**Never make another mod depend on this one to read a mob's rarity - contribute the reading, do not
export an API.**

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

**THE LOCKSTEP RULE (maintainer, 2026-09-04):** this mod ships together with the ziggfreed-common
and MMO Skill Tree versions it is built against, and its manifest floors ARE those versions. A pin
in `gradle.properties` and the matching `>=` floor in `manifest.json` move together, in the same
change, whenever either dependency ships. The `LinkageError` guards around the newer seams
(`registerKillRarityProvider`, `EncounterRuntime`, the encounter power seam) keep a mis-installed
server from failing to load the mod; they never make an older jar a supported one.

- **ZiggfreedCommon >= 2.1.0** (`compileOnly files(ziggfreedCommonJar)`, pin
  `ziggfreedCommonVersion=2.1.0`, the manifest floor `>=2.1.0`: the boss framework, whose
  `EncounterRuntime.isBoundSubject` the deferred roll reads and whose `EncounterPowerSource` seam
  this mod fills, neither of which exists in 2.0.x) - the shared
  primitive lib; its `scaling/` engine is the fold this mod
  builds on, and (1.0.2) its settings-UI toolkit (`ui/SettingsUiUtil`, `ui/ZigRichButton`,
  `ui/hud/HudPosition`, `util/JsonOverrideWriter`, `Pages/ZigListRow.ui`, and `ui/form/` -
  `FieldSpec`/`SettingsForm` + the five `Pages/ZigForm*Row.ui` templates) backs the admin page, which
  is now spec-driven over `ui/form/` for full coverage of every CONSUMED knob (a few leaves - the
  per-world HUD group beyond `Enabled`, `RegionSizeChunks` - decode but deliberately apply globally, so
  the per-world form does not expose them; see `pages/CLAUDE.md`). The mod's own `hud/HudPosition` copy
  was retired for the lifted common one.
- **MMOSkillTree >= 1.6.1** at runtime (manifest `Dependencies`) AND compiled against the LOCAL
  `MMOSkillTree-1.6.1.jar` dev jar (pin `mmoSkillTreeVersion=1.6.1`), the release this mod ships
  beside, which carries the frozen API the mod uses: `getPowerLevel` / `getPowerLevelMin` /
  `getPowerLevelMax` / `statRewardSum` / `getCombatLevel` (power reads), `registerMobKillXpMultiplier`
  (the kill-XP reward hook), `registerKillRarityProvider` (the kill-rarity attribution hook; its
  registration is LinkageError-guarded) and `castNpcAbility(Store, Ref, String)` (the caster
  roster's `ABILITY` entries; `MobScalingCasterTickSystem` latches ability casting off for the
  whole session with one warning when the method is missing). The settings fold cross-checks
  `Difficulty.MinCap`/`MaxCap` against the clamp reads and warns on drift (guarded: a jar without
  the getters validates clean). See the comment blocks in `gradle.properties` and `build.gradle`.

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
  is the ONE schema authority: an `AssetBuilderCodec` with PascalCase keys, top-level `Name` (an
  optional human-readable echo of the asset key; its setter is a no-op, the filename is authoritative)
  / `ActivePreset` (which `Settings/<name>.json` folds between the owner file and the jar `Default`,
  resolved owner-over-jar in `config/MobScalingConfig`; the persistent authority behind `/mobscaling
  preset` via `MobScalingOwnerWriter.saveActivePreset`, with `Casual`/`Hardcore`/`Playtest` shipped
  beside `Default`) / `Enabled` / `PresetMode` (verified UNCONSUMED - nothing reads `getPresetMode()`
  outside the schema/config fold; deliberately NOT exposed on the admin-page UI, round-2 hardening) /
  `Intensity` / `RaritySpawnChance` plus the NESTED groups `OpenWorld`
  (`AggregationMode`/`RegionSizeChunks`/`GroupDeltaBandWidth`/`AllowDifficultyIncreaseOnPartyJoin`/
  `LateArrivalBumpFactor`/`CompositionEnabled`/`OnlyRaiseDifficulty`/`PlayerScalingEnabled`/
  `PlayerScalingStartRingBlocks`), `Difficulty` (`Floor`/`MinCap`/`MaxCap` + nested
  `DistanceEscalation` `Enabled`/`StartDistanceBlocks`/`BlocksPerPoint`/`MaxBonus`/
  `RarityChancePerPoint` and nested `StatCurve` `HpPerPoint`/`OutDamagePerPoint`/
  `InDamageReductionPerPoint`/`MaxHpMult`/`MaxOutDamageMult`/`MinInDamageMult`), `ZoneHud`
  (`Enabled`/`Position`/`OffsetX`/`OffsetY`/`ShowLocationName`/`ZoneNameKeyPrefix`/
  `BiomeNameKeyPrefix`) and `InspectorHud` (the four anchor leaves `Enabled`/`Position`/`OffsetX`/`OffsetY`
  plus `RangeBlocks`/`PortraitEnabled`, the three location-name leaves being `ZoneHud`-only;
  positions are named corner presets parsed by `ziggfreed-common`'s `ui/hud/HudPosition.parse`).
  Fields are NULLABLE wrappers at EVERY nesting level so an absent key (or a
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
  (`BuilderCodec`, nullable leaves): **`Where`** - the SHARED `world/WorldSelector` group
  (`Match`/`GameplayConfig`/`ExcludeMatch`), the same spelling an NPC placement and an MMO
  world rule use, so this mod holds no matcher and no pattern parser of its own; absent or empty
  (tested with `WorldSelector.isBlank()`, so `"Where": {}` reads the same as omitting it) = a
  pool-only BASE, never matched - per-world
  `Enabled` kill-switch, `Intensity`, `RaritySpawnChance`, the FULL `Difficulty` + `OpenWorld` groups
  (reused codecs; `RegionSizeChunks` decodes but stays GLOBAL for grid consistency), `ZoneHud`/
  `InspectorHud` (per-world `Enabled` consumed; hide-only vs a globally-on HUD), and the `Pool` group
  (`Rarities`/`Variants`/`Affixes` `Allow`/`Deny` lists, deny wins; `Variants.ChanceMultiplier`;
  `Affixes.ExtraSlots`). A body may carry a top-level `"Parent": "<file-id>"` resolved CROSS-LAYER by
  common's `codec/JsonParentResolver` (raw pre-merge, memoized, cycle-guarded; child overrides per leaf,
  arrays replace wholesale) - unset leaves fall through the chain THEN to the global effective settings.
  **`Where` under `Parent` REPLACES wholesale here too** (the fold passes `Set.of("Where")` as
  `JsonParentResolver`'s replace-keys): a child authoring `"Where": {"GameplayConfig": [...]}` gets
  exactly that selector, never the parent's `Match`/`ExcludeMatch` leaves merged underneath it -
  the same rule the placement engine's native `Parent` decode applies to `WorldSelector`, so a
  `Where` means one thing everywhere in the family (a per-leaf merge would silently broaden a
  retargeted child to worlds nobody authored it for). A child that OMITS `Where` still inherits
  the parent's selector whole; one that authors it and wants the parent's `ExcludeMatch` restates it.
  [`config/WorldSettingsConfig`](src/main/java/com/ziggfreed/mmomobscaling/config/WorldSettingsConfig.java)
  owns the pool + fold (pack layer cached from `LoadedAssetsEvent`, owner dir re-scanned per refold,
  replace-by-id across layers - layering is id-replace, inheritance is Parent's job) and the ONE-TIME
  migration off the shipped-1.0.1 inline owner array (`migrateLegacyOwnerOverrides`: each entry ->
  `worlds/<match>.json`, the flat `Match` string rewritten as `"Where": {"Match": [...]}`,
  `PlayerScalingEnabled` moved under `OpenWorld`, array stripped). **Selection is the SHARED ladder**:
  each rule's `Where` is scored by `WorldSelector` into a `MatchRank` and the most specific wins
  (exact `GameplayConfig` > exact name > longest literal pattern core > bare `*`), with the FIRST of
  two equally specific rules keeping the world. **The spawn hook + HUD + inspect read the per-world
  view via `config/SpawnScalingSettings` (interface; `MobScalingConfig implements` it) +
  `MobScalingConfig.spawnSettingsFor(world)` (cached `ResolvedWorldSettings` overlay with
  precompiled pool sets; returns `this` on no-match), NEVER the global getters directly.** Prefer the
  `World` overload wherever the world is in hand - it can score all three axes, including the
  `GameplayConfig` key that is the only stable handle on an instance world; the `String` overload is
  the pure, testable form and keeps its OWN cache, so a world can never serve a view resolved from
  fewer axes than the real caller would have got. Jar defaults: `Worlds/DungeonOfFear_I/II/III.json`
  (I + II turn scaling off entirely, III keeps player/group scaling and drops only distance
  escalation; all three pin `PlayerScalingStartRingBlocks` to 0) + `Worlds/KweebecNightmare.json`
  (`Enabled:false`). The MMO jar's WorldRules carries NO mob-scaling knobs - this mod's files are the
  ONE per-world surface.
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
  (fallback tint, applied only when the base rarity has none) / `Loot` (the shared loot block, rolled in
  ADDITION to the rarity's), fold `VariantConfig`), `Affixes/*.json` (`Roll` incl. `AllowedRarities`
  + `AllowedVariants`/`FoldDeltas`, fold `AffixConfig`), and
  `Difficulty/*.json` (`TargetType` Zone|Biome + `TargetId` native name or `*` + `Floor`, fold
  `DifficultyConfig` with a derived O(1) name index, consumed by `world/ZoneDifficultyResolver`; the
  jar ships the Zone1..Zone4 starter gradient with its per-tier entries (`Zone1_Spawn`,
  `Zone1_Tier1..3`, `Zone2_Tier1..3`, `Zone3_Tier1..3`, `Zone4_Tier4/5`), the `*` zone wildcard
  (`ZoneAny.json`) and an `Ocean1` biome example (`OceanBiome.json`)).

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
  effect)**, **Swift (`ApplicationEffects.HorizontalSpeedMultiplier` 1.3 + the same value on
  `MovementEffects.SpeedMultiplier`)**, aura tints/ModelVFX. Swift is NOT deferred: there IS a native
  movement-speed EFFECT field, folded into real NPC walk speed every tick. Speed effects author BOTH
  leaves in lockstep: `HorizontalSpeedMultiplier` moves NPCs, `MovementEffects.SpeedMultiplier` (Update 6)
  is what a PLAYER target applies - so the victim-applied Freezing slow carries both at 0.7.
  **The RARITY AURA owns the body-tint channel (blue=rare, purple=epic, gold=legendary); affix effects carry
  NO body tint** (they would fight the aura with no arbitration) - affix identity is the mechanic + (follow-up)
  the name stamp / a particle telegraph. The Freezing slow is VICTIM-applied and keeps its frost tint.
  The six ELEMENT WARD affixes (`Ward_Arcane`/`Fire`/`Ice`/`Lightning`/`Void`/`Water`) are the purest case:
  each is `Kind: STAT` + `ResistanceBearing: true` and does nothing but name its own `Mmoscaling_Ward_*`
  effect, a bare per-cause `DamageResistance` block (Percent 0.4, `Infinite`) with zero mod-side Java.
  Being resistance-bearing is what puts them, and `Armored`, under the single-resistance cap in
  `AffixRoster.pick`, so a mob never wears two resistance-bearing affixes at once.
- **Classification via authored `NPCGroup` tagset assets** (`Mmoscaling_Bosses` / `Mmoscaling_Excluded`,
  queried by `hasTagInGroup(roleIndex)`), owner-editable, NOT a Java-side boss registry.
  **`Mmoscaling_Bosses` names AMBIENT world bosses only**, the ones the world spawns where they roam:
  a boss an encounter script raises (a `ManualTrigger` spawn-marker spawn) or a live encounter has
  bound is skipped by the deferred roll before any group is consulted, because the encounter already
  owns its stats (ziggfreed-common's `EncounterScaling` keys its own `HealthUtil` modifier), so listing
  such a role in the tagset changes nothing. The **per-family
  rarity gate** (1.0.0) reuses the SAME native mechanism: a rarity's nested `Families` block
  (`AllowGroups`/`DenyGroups` native `NPCGroup` ids + `AllowRoles`/`DenyRoles` role-name globs, deny wins,
  absent = allow-all) narrows which tiers may roll on a given mob, and the same block's third pair,
  `ForceGroups`/`ForceRoles` (evaluated force > deny > allow), hands a tier to a family outright, bypassing
  the weight, the difficulty band, the spawn chance AND the allow/deny gate - it is a FLOOR, so a normal
  roll landing on a stronger tier still wins. The shipped `Rarities/Boss.json` (`Roll.Weight` 0) points
  `ForceGroups` at the `Mmoscaling_Bosses` tagset, and that is what grants the boss tier. The matcher lives in the axis-neutral
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
  it may overlay; `["*"]` = any incl. plain, passed the rolled base rarity id). A variant's own `Loot` block
  is rolled by `MobScalingLootDropSystem` IN ADDITION to the rarity's (both hosts, same pass count), and its
  `AuraEffectId` is a
  FALLBACK tint applied by `MobScalingEffectApplySystem` only when the base rarity contributed no aura (rarity
  always wins the single tint channel). The crosshair inspector HUD renders the variant as its own coloured
  tag (`#MmoscalingInspectVariant`, `Variant.displayColor()`).
- **Item drops stay native `ItemDropList` assets** (the per-rarity `Server/Drops/*` tables), referenced from
  a `Loot` block's `Grants.DropLists` and rolled through `getRandomItemDrops` on death - so WHAT falls out
  is pure data an owner or pack overrides by id, and the loot block only decides when and how often.
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
chain). When the config is disabled the plugin registers no SYSTEMS and returns, so the mod carries no
per-tick cost at all. Two things register BEFORE the gate on purpose, because neither costs a tick:
the `/mobscaling` admin command (so `purge` still works on the uninstall path) and a `BootEvent`
listener running `MobScalingAssetRegistrar.runBootAudit()` - the log-only boot content audit,
`asset/PackDependencyAudit` for pack load-order shadowing plus a dangling-asset-reference sweep over
every folded store, so a disabled mod can still explain itself. The scaling systems, the kill-XP
reward and kill-rarity attribution providers, the factor contributions and the encounter power seam
fill register only inside the enabled branch (a switched-off mod tracks no power, so the seam keeps
its own unfilled posture rather than a fill that answers nothing).

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

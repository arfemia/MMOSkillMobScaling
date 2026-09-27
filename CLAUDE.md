# CLAUDE.md - MMO Mob Scaling

A **standalone open-world mob difficulty-scaling** companion to the MMO Skill Tree mod. It
scales open-world mobs to the players around them (a high-power group meets tougher, rarer
enemies; a lone newcomer is not overwhelmed). It is a supplemental mod under the **hyMMO
monorepo**'s `additional-mods/` (a git submodule; developed from the hyMMO root).
**Status: v1.2.1 (a hotfix in development beside ziggfreed-common 2.1.1 and MMO Skill Tree 1.6.3, the family it ships with; 1.2.0 released 2026-09-12).** 1.2.1 stops the mod's damage
reduction from scaling a landing hit to nothing (the engine rounds damage to a whole number, so a
sufficiently mitigated hit reached the health stat as zero and a mob read as immune to a whole weapon
rather than merely tough), gives the `Armored` affix the `Bludgeoning`/`Slashing` cause keys it
was missing (inbound resistance is matched on the exact leaf cause, so the affix did nothing against
most melee), shows the resolved health / damage-taken / damage-dealt percentages on the crosshair
inspector, makes `/mobscaling purge` runnable from the console, and stops a missing ordering dependency
from failing the whole plugin and taking `purge` down with it. Its config-surface pass drops the four
settings leaves nothing read (`PresetMode`, `OpenWorld.AllowDifficultyIncreaseOnPartyJoin` /
`LateArrivalBumpFactor` / `CompositionEnabled`), makes a per-world `OpenWorld.AggregationMode` fold for
real (the presence tick declares each world's mode + grid size to `RegionPowerTracker.adoptWorldFold`),
makes `OpenWorld.RegionSizeChunks` and every `ZoneHud` / `InspectorHud` leaf per-world and consumed, and
wires the zone/biome floor OWNER layer (`config/DifficultyOwnerLayer`, `mods/MmoMobScaling/difficulty/`).
**Its fold rework (the parity re-derivation, [[power-difficulty-parity]] in the hyMMO vault): a rarity or
variant is a `DifficultyMultiplier` on the difficulty the curve is read at (`dEff = difficulty * rarity *
variant`, deliberately NOT re-clamped to `Difficulty.MaxCap`; `Multipliers` keeps only `Loot`/`Xp`), the
tank axis is ONE effective-HP curve split geometrically (`StatCurve.EffectiveHpPerPoint` + `VisibleHpShare`,
`hp = ehp^share`, `in = ehp^(share-1)`, one composite rail `MaxEffectiveHpMult` enforced LAST by raising
`in`, never `hp`, with each affix's declared `FoldDeltas.ResistancePercent` mirror counted), the damage axis
is a POWER CURVE (`StatCurve.OutDamageScale` + `OutDamageShape`, `out = 1 + scale * (dEff - 1)^shape`, shape
1.0 being the straight line whose scale is a plain per-point slope; the tank axis stays linear), `Intensity` is
DELETED and only REPORTED (`config/LegacyIntensityReport`, from the boot audit: one warning per file still
carrying it or a retired curve leaf, across the owner file, the owner and pack world bodies and every pack's
settings files, nothing rewritten), and the Java balance constants left `MobScaleFold` for the
`Difficulty.Clamps` asset group.
`MobScaleResult.difficulty` stays the SPOT difficulty everywhere it is read (HUD, inspector, `/mobscaling
inspect`, the XP underdog gap, the `mob_difficulty` factor, every `MinDifficulty` gate).** The zero-cost registration
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
the region-power tracker (`RegionPowerTracker` + `MobScalingPresenceSystem`; the tick reads the
PER-WORLD view and declares each world's `AggregationMode` + `RegionSizeChunks` to the tracker with
`adoptWorldFold`, so every bucket in a world folds under its own mode on update and removal alike and
a grid-size change purges that world's presence),
NPCGroup boss/excluded classification (`Mmoscaling_Bosses`/`Mmoscaling_Excluded` tagsets + the forced
`boss` tier; `Mmoscaling_Bosses` is the AMBIENT world-boss scope, a boss nobody scripted, since a
scripted or encounter-bound boss is skipped before the classifier's answer matters), the fill of
ziggfreed-common's `EncounterPowerSource` seam (`factor/EncounterPowerFill`: a bound fight's power is
the tracked region power at its SUBJECT's own world and chunk, null on a cold miss, never zero;
`RegionPowerTracker.scalarIfTracked` beside the zero-delta `scalarFor`; `world/RegionKeys` composes
the key for the presence tick, the factor and the fill alike; a world declaring `DISABLED`, or one no presence tick has declared yet, answers ABSENT to both (`RegionPowerTracker.holdsOpinion`), never a confident zero - `scalarIfTracked` for the seam, `readingFor` for the factor - while the spawn path's `scalarFor` keeps its zero delta), `/mobscaling purge|inspect|hud|preset|worlds|ui` (1.0.2 adds `worlds`, the read-only
listing of the folded per-world rules, and `ui`, the in-game admin
config page (full-surface, spec-driven), + full write-back persistence for every runtime edit), content validation, 9-locale `mmomobscaling.lang`, and TWO
player-facing HUD overlays (`hud/` package + `MobScalingHudSystem`: the zone-difficulty card and the
crosshair mob inspector, both codec-configured + live-tunable via `/mobscaling hud`). The 2026-07-03
concerns pass ADDED: the NATIVE-ZONE floor resolver (`world/ZoneDifficultyResolver`: authored
`Difficulty/*.json` mappings over the engine's own `Zone.name()`/`Biome.getName()`, precedence zone
exact > zone segment-prefix > biome exact > biome segment-prefix > zone `*` > biome `*` > the
world-baseline `Difficulty.Floor` (per-world, else global), so a named biome floor beats the
`ZoneAny` wildcard, one memoized zone read per chunk)
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

- **ZiggfreedCommon >= 2.1.1** (`compileOnly files(ziggfreedCommonJar)`, pin
  `ziggfreedCommonVersion=2.1.1`, the manifest floor `>=2.1.1`: the released 2.1.x build of the boss framework, whose
  `EncounterRuntime.isBoundSubject` the deferred roll reads and whose `EncounterPowerSource` seam
  this mod fills, neither of which exists in 2.0.x) - the shared
  primitive lib; its `scaling/` engine is the fold this mod
  builds on, and (1.0.2) its settings-UI toolkit (`ui/SettingsUiUtil`, `ui/ZigRichButton`,
  `ui/hud/HudPosition`, `util/JsonOverrideWriter`, `Pages/ZigListRow.ui`, and `ui/form/` -
  `FieldSpec`/`SettingsForm` + the five `Pages/ZigForm*Row.ui` templates) backs the admin page, which
  is now spec-driven over `ui/form/` for full coverage of every CONSUMED knob, the per-world form
  included (every per-world leaf the schema decodes is consumed and exposed, the two HUD groups and
  `RegionSizeChunks` among them; see `pages/CLAUDE.md`). The mod's own `hud/HudPosition` copy
  was retired for the lifted common one.
- **MMOSkillTree >= 1.6.3** at runtime (manifest `Dependencies`) AND compiled against the LOCAL
  `MMOSkillTree-1.6.3.jar` dev jar (pin `mmoSkillTreeVersion=1.6.3`), the release this mod ships
  beside (the API it uses has been frozen since 1.6.1; the 1.6.3 floor is the player mitigation bound the
  damage axis is derived against), which carries the frozen API the mod uses: `getPowerLevel` / `getPowerLevelMin` /
  `getPowerLevelMax` / `statRewardSum` / `getCombatLevel` (power reads), `registerMobKillXpMultiplier`
  (the kill-XP reward hook), `registerKillRarityProvider` (the kill-rarity attribution hook; its
  registration is LinkageError-guarded) and `castNpcAbility(Store, Ref, String)` (the caster
  roster's `ABILITY` entries; `MobScalingCasterTickSystem` latches ability casting off for the
  whole session with one warning when the method is missing). The settings fold cross-checks
  `Difficulty.MinCap`/`MaxCap` against the clamp reads and warns on drift (guarded: a jar without
  the getters validates clean). See the comment blocks in `gradle.properties` and `build.gradle`.

jsr305 is `implementation` (the `@Nonnull`/`@Nullable` annotations must resolve). No gson dependency of
its own: the settings decode through the Hytale asset codec (`RawJsonReader` from the server jar), and the
raw-body layers (the owner folders, the `Parent` pool, the boot report over retired keys) use the gson the
server jar already provides.


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
  beside `Default`) / `Enabled` / `RaritySpawnChance` plus the NESTED groups `OpenWorld`
  (`AggregationMode`/`RegionSizeChunks`/`GroupDeltaBandWidth`/`OnlyRaiseDifficulty`/
  `PlayerScalingEnabled`/`PlayerScalingStartRingBlocks`; every leaf here is read - a leaf nothing
  consumes is deleted, never carried), `Difficulty` (`Floor`/`MinCap`/`MaxCap` + nested
  `DistanceEscalation` `Enabled`/`StartDistanceBlocks`/`BlocksPerPoint`/`MaxBonus`/
  `RarityChancePerPoint`, nested `StatCurve` `EffectiveHpPerPoint`/`VisibleHpShare`/
  `OutDamageScale`/`OutDamageShape`/`MaxEffectiveHpMult`/`MaxOutDamageMult` (the linear tank slope and its
  split, the power-curve damage axis `1 + scale * (dEff - 1)^shape` whose shape 1.0 is the straight line,
  and the curve's own two ceilings; `MobScalingConfig.buildCurve` is the ONE constructor every layer and
  the admin preview build it through) and nested `Clamps` `MinHpMult`/`MaxInDamageMult`/`MinOutDamageMult`/
  `MinLootMult`/`MaxLootMult` (the safety rails that are not the curve's shape; `buildClamps`
  likewise) - both fold to `MobScaleFold.DifficultyStatCurve` / `MobScaleFold.Clamps`, whose `NONE`
  values are the genuine identity (every factor 1.0, no rail), never a tuning), `ZoneHud`
  (`Enabled`/`Position`/`OffsetX`/`OffsetY`/`ShowLocationName`/`ZoneNameKeyPrefix`/
  `BiomeNameKeyPrefix`) and `InspectorHud` (the four anchor leaves `Enabled`/`Position`/`OffsetX`/`OffsetY`
  plus `RangeBlocks`/`PortraitEnabled`, the three location-name leaves being `ZoneHud`-only;
  positions are named corner presets parsed by `ziggfreed-common`'s `ui/hud/HudPosition.parse`).
  Fields are NULLABLE wrappers at EVERY nesting level so an absent key (or a
  partially-filled group) stays `null`, which is what makes the per-leaf partial owner overlay work.
  There is NO slope multiplier: an owner tunes the curve themselves. `config/LegacyIntensityReport`
  (run once per boot from `MobScalingAssetRegistrar.runBootAudit`, enabled or not, after every store has
  folded) REWRITES NOTHING: it names, in one warning per file, every layer still authoring `Intensity` or
  one of the four retired `StatCurve` leaves (`HpPerPoint`, `InDamageReductionPerPoint`, `MaxHpMult`,
  `MinInDamageMult`, each with its value) - the owner file, every owner world file (by the path its id
  resolves to), every jar or pack world body no owner file shadows (`WorldSettingsConfig.packOnlyIds` /
  `authoredRawJsonById` / `mergedRawJsonById` are its reads) and every `Server/MmoMobScaling/Settings/*.json`
  in every loaded asset pack (read raw off `AssetPack.getRoot()`, since the settings codec keeps no key it
  does not declare) - saying what the file folds to today for the leaves that replaced the multiplier and
  offering one starting point for the damage axis only (`OutDamageScale` times the old `Intensity`, marked
  a suggestion); the tank axis gets no number, because the slopes `Intensity` scaled there no longer exist
  and any carried value would be a third curve. A pack body is named with the owner-copy route. **1.0.1**: `OpenWorld` gained `PlayerScalingEnabled` (default true; false
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
  `Enabled` kill-switch, `RaritySpawnChance`, the FULL `Difficulty` + `OpenWorld` groups
  (reused codecs, every leaf per-world - `RegionSizeChunks` too: a region bucket is keyed by world, so
  the proximity grid only has to agree within one, and `RegionPowerTracker.adoptWorldFold` purges a
  world's tracked presence when its declared grid size changes because every key composed under the
  old size is stale), the FULL `ZoneHud`/`InspectorHud` groups (`Enabled` is hide-only against a
  globally-on HUD; the corner, offsets, location line, name-key prefixes, inspector reach and portrait
  are read for the world the viewing player stands in - `ScalingHud.worldSettings()` for the corner,
  the HUD system's per-world view for the rest), and the `Pool` group
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
  AND the `/mobscaling hud|preset` commands go through it, so a live change now STICKS across a
  restart (1.0.1's runtime-only HUD setters remain but are superseded). The same class carries the per-world
  file writes (`saveWorldFile`/`deleteWorldFile`) and the zone/biome floor writes
  (`saveDifficultyMapping`/`saveDifficultyFloor`/`deleteDifficultyMapping`, one owner file per mapping under
  `mods/MmoMobScaling/difficulty/`, refolded live through `DifficultyOwnerLayer`). Never write an owner
  file or mutate `MobScalingConfig` fields from a page/command directly - route through `MobScalingOwnerWriter`.
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
- Map-shaped content (the rarity ladder, the zone and biome floors) is deliberately NOT in
  the settings asset: its canonical home is the per-type keyed assets, ALL LANDED as Pattern-A
  codecs with nested groups: `Rarities/*.json` (`Roll` + the top-level `DifficultyMultiplier`, which IS
  the tier's strength - the fold reads the curve at `difficulty * DifficultyMultiplier`, and
  `Rarity.compareStrength` / `RarityRoster.tierOf` order the ladder by it - + the reward-only
  `Multipliers` `Loot`/`Xp` (a per-stat `Hp`/`OutDamage`/`InDamage` key is NOT a leaf: the engine's own
  `Unused key(s)` load warning names a file still carrying one) /`Affixes`/`Families` groups, fold
  `RarityConfig`), `Variants/*.json` (the second overlay axis -
  `Roll` with an absolute `Chance` + `AllowedRarities` requires-rarity gate, its own
  `DifficultyMultiplier` multiplied with the rarity's, `Multipliers`/`Affixes`/`Families` + top-level
  `AuraEffectId` (fallback tint, applied only when the base rarity has none) / `Loot` (the shared loot
  block, rolled in ADDITION to the rarity's), fold `VariantConfig`), `Affixes/*.json` (`Roll` incl.
  `AllowedRarities` + `AllowedVariants`/`FoldDeltas` - the four multiplicative deltas plus
  `ResistancePercent`, the DECLARED mirror of the effect's percent `DamageResistance` the effective-HP
  rail counts, `ScalingContentValidator.validateAffixResistanceMirrors` reporting drift against the live
  effect at boot - fold `AffixConfig`), and
  `Difficulty/*.json` (`TargetType` Zone|Biome + `TargetId` native name or `*` + `Floor`, every leaf
  nullable, fold `DifficultyConfig` with a derived O(1) name index, consumed by
  `world/ZoneDifficultyResolver`; the jar ships the Zone1..Zone4 starter gradient with its per-tier
  entries (`Zone1_Spawn`, `Zone1_Tier1..3`, `Zone2_Tier1..3`, `Zone3_Tier1..3`, `Zone4_Tier4/5`), the
  `*` zone wildcard (`ZoneAny.json`) and an `Ocean1` biome example (`OceanBiome.json`)). **Its OWNER
  layer is `mods/MmoMobScaling/difficulty/<id>.json`** (`config/DifficultyOwnerLayer`): one file per
  mapping on the `worlds/` convention (filename = id, matched by the ONE id key `OwnerFiles.idKey`, the
  sanitizer, so case, spaces and separators all fold to one key; bare body, `Payload` peeled, README seeded -
  the shared mechanics live in `config/OwnerFiles`, whose scan and `resolveFile` key a file the same way, so a
  hand-named `Arena.json` or `Arena Big.json` is the file read AND the file written for `arena` / `arena_big`,
  and several files keying to one id are settled deterministically, canonical spelling first, with one
  warning naming them all; `WorldSettingsConfig`, `DifficultyConfig` and a `Parent` lookup key by it too), overlaying the shipped mapping of the same id PER
  LEAF (`Zone2.json` = `{"Floor": 60.0}` retunes the shipped Zone2 and inherits its target; a new id
  must carry all three leaves or is skipped with a warning naming the missing one). It is scaffolded at
  `setup()` and SCANNED on the difficulty store's `LoadedAssetsEvent` (a partial file can only inherit
  from a shipped mapping that has loaded) and after every `MobScalingOwnerWriter` difficulty write;
  `DifficultyConfig.packMapping(id)` is the shipped layer it overlays.

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
  = knockback immunity; its +15% HP is the affix's `FoldDeltas.Hp` leaf folded into `hpMult`, applied via `HealthUtil`, NOT an
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
MULTIPLIER **and the Stalwart affix `FoldDeltas.Hp`** stay on `HealthUtil` (the effect path lacks `maximizeStatValue`,
and an effect-based +maxHP would spawn the mob damaged + double-apply with the `FoldDeltas.Hp` fold) - but the LOAD path
now uses the RECONCILE variant `HealthUtil.reconcileMaxHealth` (converges the keyed modifier to the fresh roll,
so a retune / floor / rarity change never strands a stale inflated max); Vampiric per-hit lifesteal stays
mod-side in `MobScalingOnHitSystem` (no native on-hit-DEALT sensor). Full ranked evidence lives in the hyMMO
plan's "NATIVE-LEVERAGE AUDIT RESOLUTIONS" block (`.claude/plans/1-5-0-mob-scaling-system.md`).

**Disable / uninstall caveat (persisted residue):** the `mmoscaling_hp` MAX modifier persists WITH a saved
mob - it is a keyed `StaticModifier` on the Health stat, and `EntityStatValue.CODEC` serializes the whole
modifier map with the entity. While the mod is ENABLED, the spawn hook reconciles it on every load (retunes
converge, and an excluded / world-disabled mob is stripped). A fully disabled or uninstalled mod registers
nothing and cannot self-heal, so that modifier is what lingers.

The `Mmoscaling_*` infinite AURAS are a different story and the old advice here was wrong about them:
`ActiveEntityEffect` persists the effect's **id as a string**, and on load `EffectControllerComponent`
re-resolves the index and DROPS any effect whose id no longer resolves. The effects ship inside this jar's
own asset pack, so REMOVING the jar removes the assets and every saved aura disappears by itself on its
next chunk load. What still needs the purge is the config-DISABLED-but-installed case, where the ids do
still resolve and the auras do linger.

So: after a big retune, one run with the mod enabled lets the reconcile sweep saved mobs. Before a full
uninstall, run `/mobscaling purge` (it registers even when scaling is disabled, precisely for this flow) -
a player sweeps the world they are in, the CONSOLE sweeps every loaded world and reports per world in the
log.

**Disabling costs a scaled mob its absolute health, and re-enabling gives it back full.** Stripping the
modifier lets the engine's recalculate clamp current HP to the un-scaled max, so a 20x mob sitting at
1900/2000 becomes 100/100; re-enabling takes the first-apply branch, which maximizes, so it returns at full.
A fraction-preserving reconcile is the fix (capture `EntityStatValue.asPercentage()`, write the modifier,
restore the fraction through `EntityStatMap.processStatChanges(..., Percent, Set)`), and it belongs in
ziggfreed-common's `HealthUtil` beside the existing reconcile rather than here.

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

# Changelog

All notable changes to MMO Mob Scaling. Newest first. No em-dashes.

## 0.6.0 (in-game-validation pending)

- New: PER-FAMILY gating for rarities AND variants. A rarity tier (or a variant, below) can be whitelisted /
  blacklisted to mob FAMILIES via a nested `Families` block (`AllowGroups`/`DenyGroups` = native `NPCGroup`
  tagset ids, `AllowRoles`/`DenyRoles` = role-name globs like `Spider*`, native `IncludeRoles` semantics,
  case-insensitive; deny wins, an absent block = every mob eligible). The gate only NARROWS the roll,
  consumes no RNG so per-mob determinism is unchanged, and reuses the same native `hasTagInGroup`
  classification the boss/excluded tagsets already use. New `family/` package (`FamilyFilter`/`FamilyGlob`
  pure + `MobFamilyMatcher` engine); a validator flags a self-contradictory filter (deny `*`, or an id in
  both allow + deny), and the matcher warns once on a referenced NPCGroup id that does not exist.
- New: mob VARIANT overlays (`Server/MmoMobScaling/Variants/*.json`). A variant is a SECOND, independent roll
  axis that STACKS on top of the base rarity: a mob rolls its rarity as before, then independently rolls at
  most one family-gated variant, so you get "Horrific Epic Spider" (epic base * horrific overlay). A variant
  carries its own absolute-`Chance` roll gate, `MinDifficulty` band, a `Families` filter, stat `Multipliers`
  that stack MULTIPLICATIVELY on the base rarity, and its own affix slots + allow-list. Affixes gain an
  `AllowedVariants` gate (mirroring `AllowedRarities`), so an affix can be variant-exclusive. A variant carries
  NO aura/tint (the rarity owns that single channel); its identity is the name decoration (`{variant} {rarity}
  {base}`) + its granted affix(es). New `variant/` package (`Variant`/`VariantRoster`) + `VariantConfig` fold
  + a `Variants` asset store; the variant roll is a single deterministic draw whose eligible chances partition
  it. Ships an example: a spider-only `horrific` variant (`Variants/Horrific.json`) granting a unique
  `venomous` affix (`Affixes/Venomous.json`, gated to the `horrific` variant, so it is transitively spider-only
  with no affix-side family filter).
  - A variant also carries an optional `BonusDropList` (its death loot STACKS on top of the base rarity's
    drops; the example ships venom/silk `Mmoscaling_Drops_Horrific`), an optional `AuraEffectId` applied ONLY
    when the base rarity has no aura of its own (a fallback body-tint for a plain-base variant mob - the rarity
    still owns the single tint channel; the example ships a green `Mmoscaling_Aura_Horrific`), and a
    `Roll.AllowedRarities` requires-rarity gate (absent = `["*"]` = any base incl. plain; e.g.
    `["epic","legendary"]` = only on epic+). The crosshair mob-inspector HUD now renders the variant as its
    own coloured tag (`Variant.NameColor`) beside the rarity tag.
- Improvement: the settings fold now cross-checks `Difficulty.MinCap`/`MaxCap` against the MMO jar's
  PowerLevel clamp (the new `MMOSkillTreeAPI.getPowerLevelMin()`/`getPowerLevelMax()` reads) and warns
  when the two scales drift. The group delta subtracts aggregated power from base difficulty directly,
  so a one-sided retune of either config silently miscalibrated every spawn before this check; an
  unreadable clamp (older MMO jar) validates as clean, it is advisory only.
- Improvement: both HUD overlays (the zone-difficulty card + the crosshair mob inspector) restyled to
  MATCH the native Hytale objective HUD (the client's own `ObjectivePanel`/`ObjectiveCommon`): the
  `ObjectivePanelContainer` frame plus the native palette (gray `#b7b8b9` headings, gold `#ca9f37`
  emphasis) and the bold + letter-spacing font family, in place of the plain translucent plate. The
  frame texture is copied alongside each `.ui`. Panel heights adjusted for the frame padding; the
  inspector HP bar re-fit to the reframed info column (172px inner). Styling only, no behavior change.

## 0.5.0 (2026-07-04, first public beta, in-game-validation pending)

The first public release of MMO Mob Scaling, folding the earlier never-shipped internal skeleton (the
zero-cost gate + codec config) into the full open-world scaling system: a rarity ladder (Rare/Epic/Legendary
+ a forced Boss tier) + a 5-affix catalog on native `EntityEffect` assets, a deterministic per-UUID roll,
and the risk/reward loop. Numbers are still being tuned in-game, so they may shift between builds.

- New: NATIVE-ZONE difficulty floors. `world/ZoneDifficultyResolver` resolves each chunk's floor off the
  engine's OWN worldgen (memoized `Zone.name()`/`Biome.getName()`, one query per chunk ever) through
  authored `Server/MmoMobScaling/Difficulty/*.json` mappings (Pattern-A codec: `TargetType` Zone|Biome,
  `TargetId` native name or `*`, `Floor`; precedence zone exact > zone `*` > biome exact > biome `*` >
  the `WorldRules` world baseline). The jar ships the Zone0..Zone4 starter gradient (3/8/22/38/55) + a
  zone wildcard (10) + an Ocean1 biome example; owners retune one file per zone.
- New: DISTANCE ESCALATION. Past a configurable radius from world spawn, every extra
  `BlocksPerPoint` blocks adds +1 difficulty on the zone floor (capped at `MaxBonus`) AND raises the
  rarity spawn chance (`RarityChancePerPoint`), so the deep frontier is denser AND higher-band in every
  zone; fully configurable under `Difficulty.DistanceEscalation`, breakdown shown by `/mobscaling inspect`.
- New: ZONE + PROXIMITY hybrid region buckets. The group-power aggregate is keyed by the NATIVE zone
  name plus a chunk sub-grid cell within it (`RegionPowerTracker.RegionKey`), so a zone border always
  splits buckets (1:1 with Hytale zones) while the delta stays local inside a huge zone; a world with
  no native worldgen falls back to the pure chunk grid.
- Rework (breaking, pre-release): every config codec now uses NESTED sub-object groups instead of flat
  prefixed keys - the settings asset (`OpenWorld`/`Difficulty`+`DistanceEscalation`/`ZoneHud`/
  `InspectorHud` groups), rarities (`Roll`/`Multipliers`/`Affixes`), affixes (`Roll`/`FoldDeltas`) -
  with per-LEAF partial owner overlays (a half-filled group inherits the rest). The MMO jar's
  `WorldRules` keys moved into its nested `MobScaling` group in the same pass.
- Improvement: player power (read per region-cross from `MMOSkillTreeAPI.getPowerLevel`) is now the
  MMO jar's real multi-pillar formula (combat + tree stat rewards + abilities + mastery + achievements
  per `PowerLevel.json` weights), so region difficulty tracks BUILDS, not just the max combat level.
- Improvement: HUD polish. The zone-difficulty card shows the FRIENDLY zone name (the base game's own
  `server.map.region.<id>` region names, e.g. "Cinder Wastes", client-resolved for free) instead of the
  raw zone id, with the biome prettified; both name-key prefixes are codec-configurable
  (`ZoneHud.ZoneNameKeyPrefix` default `server.map.region.`, `BiomeNameKeyPrefix` default blank ->
  prettify, since vanilla ships no biome name key). The mob-inspector card gains a generated mob PORTRAIT
  (`Icons/ModelsGenerated/<role>.png`, the native Memories still; toggle `InspectorHud.PortraitEnabled`)
  and renders each affix as an ICON chip driven by a new shared `IconSpec` codec (an affix's `Icon` is an
  item id OR a Common-rooted texture path) instead of a plain name label; the five built-in affixes ship
  icons (armor/shield/meat/ice item ids + a Stamina texture for Swift).
- New: the spawn-lock `MobScalingSpawnHook` (rolls rarity + affixes deterministically off the entity UUID,
  folds the frozen `ScaledMobComponent`, scales HP via the native `EntityStatMap`), the effect-reconcile
  `MobScalingEffectApplySystem` (applies AND sweeps the native aura / STAT-affix effects), the damage-multiply
  `MobScalingDamageFilter`, and the inspect-group `MobScalingOnHitSystem` (lifesteal + the Freezing on-hit slow,
  reading the FINAL applied damage).
- New: kill-XP reward. A rarity kill pays more XP through the MMO's own kill path via a
  `MMOSkillTreeAPI.registerMobKillXpMultiplier` provider (kill XP only, never per-hit; an underdog bonus for
  fighting above your weight; anti-runaway hard cap).
- New: native item-drop LOOT. A rarity mob's death pulls bonus items from its tier's native `ItemDropList`
  (`Rarity.BonusDropList` -> the authored `Server/Drops/MmoMobScaling/Mmoscaling_Drops_*` tables, owner/pack
  overridable by id) inside the corpse window, mirroring the vanilla `DropDeathItems` timing; the folded
  `LootMult` buys the pull count (floor guaranteed + a deterministic per-mob fractional extra).
- New: open-world region-power GROUP DELTA. A cached per-region player-power scalar (`RegionPowerTracker`,
  maintained on player region-cross by `MobScalingPresenceSystem`, O(1) spawn-path read, never a per-spawn
  scan) resolves through ziggfreed-common's `ScalingEngine` over the world floor, band-clamped by the new
  `GroupDeltaBandWidth`/`DifficultyMinCap`/`DifficultyMaxCap` settings keys - `MinDifficulty` above the floor
  is now a live lever (a strong group makes Legendary/Freezing bands reachable).
- New: NPCGroup boss classification. Authored native tagsets `Server/NPC/Groups/Mmoscaling_Bosses.json`
  (dragons, Void guardian, broodmothers; forces the re-authored weight-0 `boss` rarity tier + its aura)
  and `Mmoscaling_Excluded.json` (ships empty; the owner opt-out list, wins over everything).
- New: rarity-decorated display names. A rarity mob's `DisplayNameComponent` is re-stamped with the
  localized `name.decorated` frame (nested rarity + base-name messages, never joined English order), so
  death messages / kill feed / logs read "Epic Zombie"; a `PersistentDisplayName` (player-named) is never touched.
- New: `/mobscaling` admin command (`hytale:Admin`): `purge` strips ALL scaling residue (HP modifier +
  `Mmoscaling_*` infinite effects) off loaded mobs - the full-uninstall hatch, registered OUTSIDE the
  zero-cost gate on purpose; `inspect` reports power / floor / tracked region power / the exact effective
  difficulty a spawn at the caller's position would resolve.
- New: two player-facing HUD overlays, driven by one per-player ticking system (`MobScalingHudSystem`,
  lazy install self-heals world-transfer HUD teardown; skip-if-unchanged pushes on top of per-HUD throttles):
  - the ZONE DIFFICULTY card (`ZoneDifficultyHud`, `Hud/MmoscalingZoneHud.ui`): the effective local spawn
    difficulty (the exact `/mobscaling inspect` number), a coloured qualitative threat tier relative to the
    viewer (Trivial/Easy/Fair/Hard/Deadly off the difficulty-minus-power delta, `ZoneTier`), the viewer's
    own power level, and the tracked group (region) power when present; hides when scaling is off for the world;
  - the MOB INSPECTOR (`MobInspectorHud`, `Hud/MmoscalingMobInspector.ui`): the entity under the crosshair
    (the engine's own `TargetUtil.getTargetEntity` raycast, range from the new `InspectorRangeBlocks` key),
    showing its display name, a coloured rarity tag (the new pack-authorable `Rarity.NameColor`), the frozen
    scaled difficulty, a live HP bar with `current / max`, and the rolled affix names; unscaled mobs still
    get name + HP, other players are never inspectable.
- New: HUD admin settings. Nine codec keys (`ZoneHudEnabled`/`ZoneHudPosition`/`ZoneHudOffsetX`/`ZoneHudOffsetY`,
  the `InspectorHud*` four, `InspectorRangeBlocks`) with named corner presets (TOP_LEFT ... BOTTOM_RIGHT), plus
  `/mobscaling hud <zone|inspector> <on|off|POSITION> [offsetX] [offsetY]` for LIVE tuning across all online
  players (runtime only; the owner file stays the persistent authority and the command says so).
- New: content validation (value-sanity findings over the folded rarities/affixes, warned at load, never
  blocking) and the full 8-locale `scaling.lang` fan-out (de/es/fr/hu/it/pt-BR/ru/tr alongside en-US),
  including the HUD strings (tier words, card frames, the hud subcommand).
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

### Foundation (the held internal 1.0.0 skeleton, folded into this first release)

The zero-cost registration gate + the codec config that the scaling system above builds on. Never
shipped on its own; it is part of 0.5.0.

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
- Compiles `compileOnly` against the local `MMOSkillTree-1.5.0.jar` dev jar (the frozen 1.5.0
  API) while the manifest pins the runtime requirement at MMOSkillTree `>=1.5.0` and
  ZiggfreedCommon `>=1.2.0`. Neither is bundled.

# Changelog

All notable changes to MMO Mob Scaling. Newest first. No em-dashes.

## 1.0.2 (unreleased, in-game-validation pending)

Per-world config reworked onto its own files with inheritance, more per-world knobs, an in-game admin
config UI, and full persistence for every runtime tuning path. Requires MMO Skill Tree 1.5.0+ (the build
that removes the old WorldRules mob-scaling baseline - update BOTH together) and Ziggfreed's CommonLib
1.3.0+.

- Change (schema rework): per-world settings move OUT of the inline `WorldOverrides` array into their own
  keyed asset files, `Server/MmoMobScaling/Worlds/*.json` (packs/jar) + a scanned owner dir
  `mods/MmoMobScaling/worlds/*.json` (one file per world rule; filename = id; a bare body is canonical,
  the pack-style `Payload` wrapper is accepted). A file carries the world `Match` selector (same exact >
  longest-`*`-prefix > `*` precedence) and may carry a top-level `"Parent": "<other-file-id>"`: unset
  leaves walk up the Parent chain (cross-layer, cycle-guarded, resolved by CommonLib's new
  `JsonParentResolver`), and whatever is still unset falls through to the GLOBAL effective settings - so
  a file is a partial overlay by default and a full custom definition when fully authored. A file with no
  `Match` is a pool-only BASE others inherit from. Layering across jar/pack/owner is replace-by-id
  (inheritance is Parent's job); everything decodes through ONE schema authority (`WorldSettings.CODEC`).
  A legacy owner `WorldOverrides` array (shipped in 1.0.1) MIGRATES automatically on first boot: each
  entry becomes its own `worlds/<match>.json` (the old top-level `PlayerScalingEnabled` moves under
  `OpenWorld`) and the array is stripped from the owner file. The old inline array on presets no longer
  decodes.
- New: per-world kill-switch + baseline floor, absorbed from the MMO jar. A world file's `Enabled: false`
  turns scaling off in matching worlds (residue is stripped on load); `Difficulty.Floor` is the
  world-baseline difficulty floor under the zone/biome `Difficulty/*.json` mappings (global default 30.0
  in `Settings/Default.json`). These replace the never-released `WorldRules.MobScaling` group on the MMO
  jar - mob difficulty now has exactly ONE per-world authoring surface (this mod's files). BREAKING pair:
  MMO Skill Tree 1.5.0 removes that group, so update both mods in the same deploy.
- New: the WHOLE `OpenWorld` group is per-world (AggregationMode, GroupDeltaBandWidth,
  OnlyRaiseDifficulty, AllowDifficultyIncreaseOnPartyJoin, LateArrivalBumpFactor, CompositionEnabled,
  PlayerScalingEnabled). `RegionSizeChunks` alone stays global (the region-power grid must stay
  consistent).
- New: a per-world `Pool` group gating what rolls in a world: `Rarities`/`Variants`/`Affixes` each take
  `Allow`/`Deny` id lists (deny wins; absent = allow-all), `Variants.ChanceMultiplier` scales every
  eligible variant's absolute chance (0 = no variants in that world), and `Affixes.ExtraSlots` rolls
  bonus affixes on top of the rarity/variant slots (a plain, variant-less mob has no host, so extras are
  a no-op there). So an endgame dungeon can spawn only Elite+, roll double variants, and stack an extra
  affix - per world, no Java.
- New: per-world HUD visibility. A world file's `ZoneHud.Enabled: false` / `InspectorHud.Enabled: false`
  hides that overlay in matching worlds as players cross world borders (a per-world `true` cannot
  re-enable a globally-off HUD; the global toggle stays the cheap fast path).
- New: `/mobscaling worlds` lists the folded per-world files (id, Match or base-only, Parent,
  owner-vs-shipped origin, kill-switch state).
- Change: the shipped dungeon defaults moved from `Settings/Default.json`'s inline array to jar world
  files that exercise the new inheritance: `Worlds/DungeonOfFear_Base.json` (a pool-only base:
  escalation off) inherited by `DungeonOfFear_I/II/III.json` (I + II also pin player scaling off), plus
  `Worlds/KweebecNightmare.json` (`Enabled: false` - absorbed from the MMO jar's old default).
- New: an in-game admin config page, `/mobscaling ui` (admin only). Four tabs - Global (Enabled, active
  preset, Intensity, RaritySpawnChance, player/group scaling, difficulty caps, distance escalation), Zone
  HUD and Mob Inspector HUD (enable, position preset + pixel offsets, sub-toggles, inspector range), and
  Worlds (a per-world FILE editor: add / edit / delete `worlds/*.json`, each row badged shipped vs
  owner override, with file name / Match / Parent / kill-switch / baseline floor / intensity / rarity
  chance / caps / player scaling / escalation; blank fields inherit; deleting an owner file re-exposes
  the shipped file underneath; the Pool / OpenWorld-rest / HUD / StatCurve per-world groups stay
  file-authored). Global + HUD edits write the owner file `mods/MmoMobScaling/mob-scaling.json` and
  refold live; HUD + preset edits apply to all online players with no reconnect. `Enabled` shows a
  "takes effect on restart" note (the zero-cost registration gate registers systems at startup).
- New: full persistence for the live commands. `/mobscaling intensity`, `/mobscaling hud`, and
  `/mobscaling preset` now SAVE to the owner file (they were runtime-only in 1.0.1, lost on restart). The
  UI and the commands share ONE write-back path (`config/MobScalingOwnerWriter` -> the owner file ->
  `MobScalingConfig.refreshFromDisk`), so a change made either way sticks and applies live.
- Change: requires Ziggfreed's CommonLib 1.3.0+ - the shared settings-UI toolkit the admin page consumes
  (`util/JsonOverrideWriter` owner-file write-back, `ui/hud/HudPosition` layout value, `ui/SettingsUiUtil`
  form binding, `Pages/ZigListRow.ui` row). The mod's private `hud/HudPosition` copy is retired in favour
  of the lifted common one (identical behavior).

## 1.0.1

Per-world / per-instance tuning plus a live intensity dial. Requires MMO Skill Tree 1.5.0+ and
Ziggfreed's CommonLib 1.2.0+.

- New: PER-WORLD settings overlays. `Server/MmoMobScaling/Settings/*.json` gains a `WorldOverrides`
  array; each entry is a world-name `Match` (the SAME fuzzy matching as the MMO's WorldRules: exact >
  longest trailing-`*` prefix > bare `*`, case-insensitive) bound to a PARTIAL settings body that
  overlays the global fold for matching worlds at spawn time. A matched world may set its own
  `Intensity`, `RaritySpawnChance`, `PlayerScalingEnabled`, and the full `Difficulty` group (caps +
  `DistanceEscalation` + `StatCurve`); every unset leaf inherits the global settings. Layers
  CONCATENATE (deduped by `Match`, owner > preset > jar), so an owner file ADDS to / overrides shipped
  defaults without re-authoring the whole list. Resolved through a new `world/WorldOverrideMatcher` + a
  `config/SpawnScalingSettings` view the spawn hook, the HUD, and `/mobscaling inspect` all read, so a
  dungeon reports its ACTUAL numbers.
- New: `PlayerScalingEnabled` toggle (a global `OpenWorld` leaf + a per-world override). `false` skips
  the player/group power delta entirely, pinning a world to its escalated floor, the switch a
  fixed-difficulty authored dungeon uses.
- New: numeric `Intensity` dial (replaces the old inert string label). A multiplier (default `1.0`,
  clamped `>= 0`) on the difficulty-to-stat curve slopes (how tanky mobs are + how hard they hit),
  bounded by the existing per-factor caps; it does not touch rarity/affix magnitudes. Runtime-tunable
  with `/mobscaling intensity [multiplier]` (runtime only; the owner file's `Intensity` is the
  persistent authority). A world with an authored per-world `Intensity` override is unaffected.
- New: shipped defaults for three authored dungeons. `Default.json` ships `WorldOverrides` for
  `instance-dungeon_of_fear_i/ii/iii`: player/group scaling OFF for I and II (fixed difficulty), and
  distance-from-spawn escalation OFF for all three. The `_i*`/`_ii*`/`_iii*` prefixes self-disambiguate
  via longest-prefix and also catch suffixed instance worlds.
- Change: the four settings presets (Default/Casual/Hardcore/Playtest) no longer carry a string
  `Intensity` (their difficulty lives in their `StatCurve`); `Intensity` folds to a neutral `1.0`
  unless authored.

## 1.0.0

The first release of MMO Mob Scaling, a standalone open-world mob difficulty-scaling companion to the
MMO Skill Tree mod: open-world mobs scale to the players around them (a high-power group meets tougher,
rarer, affixed enemies; a lone newcomer is never overwhelmed). Everything is data-driven Hytale assets,
so any of it can be retuned per file or extended from a content pack. Requires MMO Skill Tree 1.5.0+ and
Ziggfreed's CommonLib 1.2.0+.

- New: LAYERED open-world difficulty. Every hostile mob is scaled to a difficulty resolved from three
  layers: Hytale's own worldgen ZONE and BIOME floors (`world/ZoneDifficultyResolver`, memoized
  `Zone.name()`/`Biome.getName()`, one query per chunk, over authored `Server/MmoMobScaling/Difficulty/*.json`
  Pattern-A mappings, precedence zone exact > zone `*` > biome exact > biome `*` > the `WorldRules` world
  baseline; the jar ships the Zone0..Zone4 gradient 3/8/22/38/55 + a zone wildcard + an Ocean1 biome example),
  a distance-from-spawn ESCALATION (past a configurable radius every `BlocksPerPoint` blocks adds +1 difficulty
  capped at `MaxBonus` AND raises rarity chance via `RarityChancePerPoint`, under `Difficulty.DistanceEscalation`),
  and the real POWER of the players standing in the region.
- New: ZONE + PROXIMITY hybrid region buckets. The group-power aggregate is keyed by the native zone name
  plus a chunk sub-grid cell (`RegionPowerTracker.RegionKey`), so a zone border always splits buckets while
  the delta stays local inside a huge zone; a world with no native worldgen falls back to the pure chunk grid.
  The cached per-region player-power scalar (maintained on player region-cross by `MobScalingPresenceSystem`,
  an O(1) spawn-path read, never a per-spawn scan) resolves through ziggfreed-common's `ScalingEngine` over the
  world floor, band-clamped by `OpenWorld.GroupDeltaBandWidth` + `Difficulty.MinCap`/`MaxCap`.
- New: player power is the MMO jar's real multi-pillar formula (combat + tree stat rewards + abilities +
  mastery + achievements per `PowerLevel.json` weights, read per region-cross from
  `MMOSkillTreeAPI.getPowerLevel`), so region difficulty tracks a player's BUILD, not just the max combat level.
- New: RARITY ladder + affixes. Rare / Epic / Legendary + a forced Boss tier, each a coloured nameplate, an
  aura tint, stat multipliers, affix slots, bonus XP, and a bonus loot table; five affixes ride native Hytale
  `EntityEffect` assets (Armored, Stalwart = knockback immunity + HP, Swift = native move-speed, Vampiric,
  Freezing = victim slow). Rolls are DETERMINISTIC per mob UUID, so a chunk reload reproduces the same mob. The
  rarity aura owns the single body-tint channel (blue/purple/gold); affix effects carry no competing tint.
- New: PER-FAMILY gating for rarities AND variants. A rarity tier (or a variant, below) can be whitelisted /
  blacklisted to mob FAMILIES via a nested `Families` block (`AllowGroups`/`DenyGroups` = native `NPCGroup`
  tagset ids, `AllowRoles`/`DenyRoles` = role-name globs like `Spider*`, case-insensitive; deny wins, an absent
  block = every mob eligible). The gate only NARROWS the roll and consumes no RNG (per-mob determinism
  unchanged), reusing the same native `hasTagInGroup` classification the boss/excluded tagsets use. New
  `family/` package (`FamilyFilter`/`FamilyGlob` pure + `MobFamilyMatcher` engine); a validator flags a
  self-contradictory filter (deny `*`, or an id in both allow + deny), and the matcher warns once on an unknown
  NPCGroup id.
- New: mob VARIANT overlays (`Server/MmoMobScaling/Variants/*.json`). A variant is a SECOND, independent roll
  axis that STACKS on top of the base rarity, so you get "Horrific Epic Spider" (epic base * horrific overlay).
  A variant carries its own absolute-`Chance` roll gate, `MinDifficulty` band, a `Families` filter, stat
  `Multipliers` that stack multiplicatively on the rarity, its own affix slots + allow-list, an optional
  `BonusDropList` (death loot stacks on the rarity's), an optional `AuraEffectId` fallback tint (applied only
  when the base rarity has no aura), and a `Roll.AllowedRarities` requires-rarity gate. Affixes gain an
  `AllowedVariants` gate (mirroring `AllowedRarities`) so an affix can be variant-exclusive. At most one variant
  lands per mob; a variant has no aura/tint (identity is the `{variant} {rarity} {base}` name frame + its
  affixes). New `variant/` package (`Variant`/`VariantRoster`) + `VariantConfig` fold + a `Variants` asset
  store. Ships a worked example: a spider-only `horrific` variant granting a unique `venomous` affix (gated to
  `horrific`, so it is transitively spider-only), with a `Mmoscaling_Drops_Horrific` bonus-loot table and a
  green `Mmoscaling_Aura_Horrific` fallback tint.
- New: risk pays. A scaled kill grants bonus MMO XP through the MMO's own kill path (a
  `MMOSkillTreeAPI.registerMobKillXpMultiplier` provider: kill XP only, an underdog bonus for fighting above
  your weight, an anti-runaway hard cap) and pulls extra loot from its tier's native `ItemDropList`
  (`Rarity.BonusDropList` -> `Server/Drops/MmoMobScaling/Mmoscaling_Drops_*`, owner/pack overridable), spawned
  as real ground items at the corpse mirroring vanilla `DropDeathItems` timing.
- New: NPCGroup BOSS classification. Authored native tagsets `Server/NPC/Groups/Mmoscaling_Bosses.json` (forces
  the weight-0 `boss` rarity tier + its aura) and `Mmoscaling_Excluded.json` (the owner opt-out list, wins over
  everything). The forced boss tier bypasses the rarity roll and the family gate.
- New: rarity-decorated display names. A scaled mob's `DisplayNameComponent` is re-stamped with the localized
  `name.decorated` frame (nested rarity + base-name messages, never joined English order), so death messages /
  kill feed read "Epic Zombie"; a player-named `PersistentDisplayName` is never touched.
- New: two player-facing HUD overlays, driven by one per-player ticking system (`MobScalingHudSystem`,
  lazy-install self-heal, skip-if-unchanged pushes): a ZONE DIFFICULTY card (`ZoneDifficultyHud`,
  `Hud/MmoscalingZoneHud.ui`: local effective difficulty, a coloured threat tier relative to the viewer, the
  viewer's own power + the tracked group power, the friendly in-game zone name) and a MOB INSPECTOR
  (`MobInspectorHud`, `Hud/MmoscalingMobInspector.ui`: the mob under the crosshair, its portrait, name, coloured
  rarity + variant tags, scaled difficulty, a live `current / max` HP bar, and its affixes as icon chips). Both
  restyled to MATCH the native Hytale objective HUD (the `ObjectivePanelContainer` frame + native palette +
  font); both toggle and reposition live via `/mobscaling hud`, and honor the MMO's per-player `/mmohud` toggles.
- New: `/mobscaling` admin command (`hytale:Admin`): `inspect` (report the difficulty inputs + breakdown at
  your position), `preset` (switch live between Default / Casual / Hardcore / Playtest), `hud` (live-tune the
  overlays across all online players), `purge` (strip ALL scaling residue - the HP modifier + `Mmoscaling_*`
  infinite effects - off loaded mobs, the full-uninstall hatch, registered OUTSIDE the zero-cost gate).
- New: RECONCILE on load. HP + auras converge to the current roll (`HealthUtil.reconcileMaxHealth` + an effect
  sweep) so a floor / rarity / affix retune never strands a stale inflated max or a doubled aura on a saved mob;
  an excluded / world-disabled mob is stripped. (A fully-disabled/uninstalled mod cannot self-heal saved
  residue; run `/mobscaling purge` per world first, see CLAUDE.md.)
- New: the settings fold cross-checks `Difficulty.MinCap`/`MaxCap` against the MMO jar's PowerLevel clamp
  (`MMOSkillTreeAPI.getPowerLevelMin()`/`getPowerLevelMax()`) and warns when the two scales drift; an unreadable
  clamp (older MMO jar) validates clean, advisory only. Content validation runs value-sanity findings over the
  folded rarities / affixes / variants at load (warn, never block).
- New: the zero-cost registration gate. The plugin loads its config in `setup()` and applies a registration
  gate (`MobScalingPlugin.shouldRegisterSystems`): when the config is disabled it registers NO systems and
  returns, so a disabled mod carries no per-tick cost at all.
- New: codec-driven config. The schema + defaults are Hytale asset codecs (Pattern A, PascalCase, NESTED
  sub-object groups, never flat prefixed keys, never Java-baked values): the settings asset
  (`MobScalingSettingsAsset` -> `Server/MmoMobScaling/Settings/Default.json`, groups
  `OpenWorld`/`Difficulty`+`DistanceEscalation`/`ZoneHud`/`InspectorHud`) plus the per-type keyed assets
  `Rarities/`/`Variants/`/`Affixes/`/`Difficulty/`. Owners override any key in
  `mods/MmoMobScaling/mob-scaling.json` (partial allowed, per-leaf overlay); a content pack can override the same
  paths. The settings fold is `owner > pack-store > jar`, so a partial pack override can never silently disable
  the mod, and `RaritySpawnChance` is clamped.
- New: full 9-locale `scaling.lang` (de/es/fr/hu/it/pt-BR/ru/tr alongside en-US), including the rarity / affix /
  variant name keys and the HUD strings.

### Technical

- Standalone Hytale sibling mod; package root `com.ziggfreed.mmomobscaling`, entry point `MobScalingPlugin`.
- Compiles `compileOnly` against the local `MMOSkillTree-1.5.0.jar` dev jar (the frozen 1.5.0 API) while the
  manifest pins the runtime requirement at MMOSkillTree `>=1.5.0` and ZiggfreedCommon `>=1.2.0`. Neither is
  bundled.
- Effect apply via a native `RefSystem.onEntityAdded` (synchronous add-pipeline CommandBuffer); the general
  damage multiply is a frozen `DamageModule` filter; the rarity HP multiplier + the Stalwart affix HpDelta stay
  on `HealthUtil` (the effect path lacks `maximizeStatValue`, and an effect-based +maxHP would spawn the mob
  damaged + double-apply); Vampiric per-hit lifesteal stays mod-side in `MobScalingOnHitSystem` (no native
  on-hit-dealt sensor).
- Consumes ziggfreed-common 1.2.0: the domain-free `scaling/` engine (`ScalingContext`/`ScalingEngine`),
  `HealthUtil.reconcileMaxHealth` + the ref-less `scaleMaxHealth(Holder,...)`, `EntityIdentifierUtil`
  `roleName`/`roleIndex`, and `EntityEffectService.apply` (asset-authoritative).

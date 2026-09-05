# Changelog

All notable changes to MMO Mob Scaling. Newest first. No em-dashes.

## 1.2.0 - unreleased

Requires Ziggfreed Common 2.1.0 and MMO Skill Tree 1.6.1 or newer; the three ship together.

- **The mob inspector's affix chips draw their item icons.** Each chip declared an `ItemIcon` widget, a type the client does not have, so an affix pictured by an item id laid out a blank gap before its name; an affix pictured by a texture path was unaffected. The chips declare a one-slot `ItemGrid` now, sized to the chip's own 18-pixel height and styled from Ziggfreed Common's shared `@ZigIconGrid18` (`Common/ZigButtons.ui`, reached from `Hud/` as `../Common/`; a grid with no `Style` draws nothing), fed by the same `IconRenderer` call as before. Needs Ziggfreed Common 2.1.0, where the seam itself was corrected.
- **The rarity roll happens one tick after the spawn, on the mob's own entity reference.** The spawn hook still runs on the pre-add holder and still decides the gates (the mod and per-world switches, the exclusion, the ambient-boss scope); what it no longer does is roll. It stamps a one-tick `PendingRollComponent` and `MobScalingRollSystem` rolls on the first tick after the add, where a valid `Ref` exists. The roll itself is unchanged (the same seed off the entity's uuid and the world seed, the same rosters, the same family and pool gates, the same fold), so a given mob still gets the rarity, affixes and variant it always got. The one thing a player can see: a freshly spawned scaled mob enters the world at its base maximum health and is reconciled to its scaled maximum a tick later (the first apply heals to the new max).
- **A scripted spawn and an encounter's own boss are left alone.** An NPC raised by a `ManualTrigger` spawn marker (what an encounter script's `TriggerSpawners` fires for a boss and its adds) is skipped, and so is a mob a live encounter has bound as its subject: neither gets a rarity, an affix, a name decoration, an aura or this mod's health modifier, and a stale modifier or aura from an earlier save is stripped off it. A server owner wants this because a boss fight's health is the fight's own, scaled by the encounter framework per party member and per power point; a second owner writing the same maximum would fight it every phase. Both reads are only possible on the deferred tick: the engine attaches a mob's spawn-marker reference after the store's add, and the framework indexes a bound boss on its own tick. A ziggfreed-common jar older than the framework has no `EncounterRuntime`; the skip degrades to "roll as before" with one warning.
- **A multi-phase boss is rolled at most once per life.** An in-place role change is a remove and a re-add through every registered holder system with a fresh reference, so without a guard every phase swap would re-roll and re-scale the boss mid-fight. A holder that already carries a `ScaledMobComponent` was decided in an earlier life of that same holder and is left as it is. (A chunk reload is a different case: the component is transient, so a reloaded mob is rolled again from its stable seed to the identical result, which is what reconciles a retune onto a saved mob.)
- **`Mmoscaling_Bosses` means ambient world bosses.** The tagset's five roles are unchanged; what the file says, in its `$Comment` and in the classifier, is that it is the elite scope for a boss nobody scripted. A boss an encounter runs declares itself by being bound and is never classified by this mod, so listing its role here changes nothing.
- **A bound fight reads the region power at its boss.** This mod fills ziggfreed-common's `EncounterPowerSource` seam (`factor/EncounterPowerFill`), so a binding row's `Scale.HealthPerPowerPoint` has a number to multiply: the tracked player power of the region the SUBJECT stands in, read at the boss's own world and chunk through the same `RegionKeys` the presence tick writes with. It is not an aggregate over the members (the row's `Scale.HealthPerMember` already pays for them, and the tracker is region-keyed), and a region nobody is tracked in answers null, "nothing is known", never a confident zero (`RegionPowerTracker.scalarIfTracked`, beside the zero-delta `scalarFor` the spawn read keeps). Filled inside the enabled branch, so a switched-off mod leaves the seam on its own unfilled posture.
- **The three Dungeon of Fear world rules cannot shadow each other.** `*dungeon_of_fear_i*`, `*dungeon_of_fear_ii*` and `*dungeon_of_fear_iii*` nested by construction (`i` inside `ii` inside `iii`), so the content validator warned that removing a more specific rule would hand its worlds to the shorter one, and that the Kweebec rule tied with the `_i` rule on specificity. The patterns are `*dungeon_of_fear_i-*`, `*dungeon_of_fear_ii-*` and `*dungeon_of_fear_iii-*`: the `-` is the delimiter the engine puts between an instance's name and its id (`instance-<name>-<uuid>`), so each core ends where its own name ends and matches its own instance alone. Both warnings are gone.
- **Version 1.2.0; the family moves in lockstep.** The manifest floors rise to Ziggfreed Common `>=2.1.0` (the boss framework: `EncounterRuntime` and the power seam, neither of which exists in 2.0.x) and MMO Skill Tree `>=1.6.1`, and the compile pins name the same jars. The floors are the versions this mod is built against; the `LinkageError` guards around the newer seams are a safety net for a mis-installed server, not advertised compatibility with an older jar.
- **Role reads go through the shared library.** `MobScalingCasterArmSystem` and `MobScalingHudSystem` each fetched the `NPCEntity` component themselves purely to call `getRoleName()`, which is exactly the read `ziggfreed-common`'s `EntityIdentifierUtil.roleName` exists to own; both now ask it, and the arm system asks through the `CommandBuffer` it was handed. Behaviour is identical - this mod already keyed its rosters, affixes and classification on the role name, which is now the identity the whole family uses. The spawn hook keeps its own reads, since it holds the `NPCEntity` for the classifier anyway.

## 1.1.0 - 2026-08-31

- **The Asset Editor shows the real multiplier baselines and offers dropdowns on the closed vocabularies.** A rarity's and a variant's `Multipliers` leaves declare their neutral 1.0 default in the exported schema (an unauthored leaf renders 1.0, not 0), and the settings `PresetMode` (SIMPLE/TUNED/ADVANCED) and open-world `AggregationMode` (SOLO/AVERAGE/PEAK/WEIGHTED/DISABLED) export their closed value sets as dropdowns with a one-line meaning per value. Decode is unchanged.

NPC caster rosters: a Pattern-A asset (Server/MmoMobScaling/CasterRosters/*.json) binding a Role
selector (exact Id XOR glob) to a list of abilities a matching, gate-eligible mob arms at spawn and
fires on its own cadence+jitter. Each entry is AbilityId (cast via the MMO's new
MMOSkillTreeAPI.castNpcAbility(Store,Ref,String), requires MMO Skill Tree 1.6.0) XOR NativeChain (a
RootInteraction id armed via native CombatSupport.addAttackOverride), gated by MinDifficulty/Rarities/
Scope (HOSTILE|BOSS|ANY) against the frozen MobScaleResult - the gate model matches MobScaleResult
exactly (a difficulty float, a rarity id string, a scope byte), no integer tier concept introduced.
The manifest runtime requirement stays ">=1.5.0" (unchanged, deliberate); only the ABILITY caster
entries need MMO 1.6.0, and they degrade gracefully with one warning on an older jar instead of
refusing to load the whole mod - see CasterFeatureState.

- New: this mod PUBLISHES what it knows about a mob as ordinary factor readings, so any other mod's
  authored content can gate and scale on them with no dependency in either direction. Five ids,
  claimed through ziggfreed-common's process-wide contribution door at setup:
  `mmomobscaling:mob_rarity_tier` (the mob's place on the rarity ladder, 0 for plain and one step per
  authored tier, derived from the tiers themselves so inserting one moves everything above it up),
  `mmomobscaling:mob_rarity` (Param = a rarity id, for content that means one specific tier),
  `mmomobscaling:mob_affix` (Param = an affix id), `mmomobscaling:mob_difficulty`, and
  `mmomobscaling:region_power` (the tracked player power in the region the moment happened in). The
  four mob readings are about the entity the moment happened TO, never the one acting, so a mob-kill
  formula can weigh the mob's rarity and the killer's own luck in one expression without either
  question reading the other's entity; `region_power` is about a PLACE, so it reads the target's
  position and falls back to the subject's when there is no target. On a server without this mod - or with it switched off,
  since the claim is made inside the enabled branch - nothing answers the ids, so a gate on one stays
  shut and a formula term on one adds zero, and one authored file is correct everywhere. A boot line
  lists what was published.
- New: attributes each kill's rolled rarity to the MMO's kill-rarity seam
  (MMOSkillTreeAPI.registerKillRarityProvider, additive on the MMO's 1.6.0-cycle jar): a scaled
  kill carries its rarity id (Rare / Epic / Legendary / Boss) as the kill moment's qualifier, so
  quest and achievement criteria authoring a rarity word match scaled kills - the MMO's tier
  achievements (First_Legend_Kill, Legendary_Slayer, Rare_Hunter, Elite_Slayer) progress on them -
  and a mob-drop command's {tier} placeholder resolves to the same answer; a plain floor mob
  attributes nothing, and an ordinary kill criterion still counts every kill exactly once. On
  an older MMO jar the registration degrades with one warning (the same graceful-degradation
  story as the caster ABILITY entries) and everything else is unaffected.
- Fixed: the Freezing affix's slow actually slows the player it hits. The slow effect authored only
  the NPC movement leaf (`ApplicationEffects.HorizontalSpeedMultiplier`), which a player's client
  never applies, so a frozen player saw the frost tint and snow overlay at full running speed. The
  effect now also authors the player leaf (`MovementEffects.SpeedMultiplier`, Update 6) at the same
  0.7, and the Swift affix carries the matching 1.3 pair so its haste holds on any carrier.
- Changed (HARD BREAK, schema): a rarity or variant authors its death loot in ONE `Loot` block, the
  shared loot vocabulary the rest of the ecosystem already speaks, replacing the `BonusDropList`
  string and the `BonusRewards` compact-spec array. Rewrite `"BonusDropList": "Mmoscaling_Drops_Epic"`
  as `"Loot": { "Rolls": [ { "Grants": { "DropLists": ["Mmoscaling_Drops_Epic"] } } ] }`; a
  `"BonusRewards": ["xp MINING 500"]` entry becomes an ordinary `Grants.Commands` line or a
  `Grants.Rewards` entry naming a registered reward kind. The native `Server/Drops/*` tables are
  untouched and are simply referenced from `Grants.DropLists`, so no item content moved. What the
  block buys beyond the old pair: shared tables by id, exact item grants, per-roll `Conditions` and a
  factor-scaled `Chance`, ladder tiers, and any reward kind another mod registered - including gating
  on the readings above. The tier's `Multipliers.Loot` keeps its job as the number of times the whole
  block is rolled, which for a drop list is exactly what it always did; note that a tier rolling more
  than once now repeats its command and reward grants too, so write per-pass amounts. The five
  shipped rarity/variant files are re-authored onto it, and the content
  audit now names a dangling `Loot.Lootables` table id alongside a dangling drop list.
- Changed (HARD BREAK, schema): a world rule targets its worlds with the SHARED `Where`
  group, not a flat `Match` string. `WorldSettings.Where` decodes through `WorldSelector.CODEC`, so
  a rule authors `Match` (world-name patterns, the same wildcard
  grammar as the old flat field), `GameplayConfig` (exact config keys, the sturdy axis for an
  instance world whose NAME carries a fresh uuid) and `ExcludeMatch` - one vocabulary shared with
  NPC placements, dialogue world conditions and MMO world rules, scored on one specificity ladder.
  Rewrite `"Match": "*dungeon_*"` as `"Where": { "Match": ["*dungeon_*"] }`. Base-versus-rule
  semantics are preserved and made explicit: `"Where": {}` reads the same as omitting the group
  (both mean a pool-only base a `Parent` inherits from, never matched), via `WorldSelector.isBlank`.
  The 1.0.1 owner-array migration and the owner-directory README emit and teach the new shape, and
  the four shipped `Worlds/*.json` are re-authored onto it. The three Dungeon of Fear rules also
  move from a trailing-`*` prefix pattern to the CONTAINS form (`*dungeon_of_fear_i*`), which is
  what actually reaches a live instance world (its name is `instance-<Name>-<uuid>`, so the token
  sits mid-name); their relative precedence is unchanged, since the I/II/III literal cores still
  order longest-first.
- Changed: the copied matcher and the second evaluation engine are gone. `WorldSettingsConfig`
  selects by `MatchRank` through the one shared selector, `resolve(World)` scores all three axes
  while `resolve(String)` stays the pure name-only core (each with its own cache, so a world can
  never be served a view resolved from fewer axes than it has), and
  `ScalingContentValidator`'s private pattern parser is deleted in favour of the shared
  `WorldNameMatcher.Pattern` - so a validator can no longer reassure an author about an ordering
  the runtime does not use. `WorldRankParityTest` retires with the second ladder it guarded.
- Tuning: the shipped zone difficulty-floor gradient is flattened to a gentler early game -
  Zone1 8 -> 1 (Spawn 3 -> 1, Tier1/2/3 6/9/12 -> 1/2/3), Zone2 22 -> 5 (Tier1/2/3 18/22/26 ->
  5/8/10), Zone3 38 -> 12 (Tier1/2/3 34/38/42 -> 12/15/18), Zone4 55 -> 20 (Tier4/5 52/58 ->
  25/28), zone wildcard 10 -> 2. The world-baseline `Difficulty.Floor` (30.0) and the
  distance-escalation curve are unchanged.
- New: CasterRosterAsset + CasterRosterConfig (defaults<pack<owner fold) + Rosters.casterRosters()
  (id-sorted for a deterministic CasterRosterMatcher tie-break).
- New: CasterRosterMatcher, a pure precedence matcher (exact roleId > longest matching glob > first),
  the first implementation of the planned BossCurve keying pattern.
- New: per-entry Windup animations - an optional Windup{Animation, ItemAnimations, Slot} group played
  through the engine's own entity-generic AnimationUtils immediately before an ability cast, so a
  scaled mob visibly telegraphs the hit (zero MMO coupling; a NATIVE_CHAIN entry needs no Windup, its
  chain carries its own animation nodes).
- New: MobScalingCasterArmSystem (RefSystem, mirrors MobScalingEffectApplySystem) +
  MobScalingCasterTickSystem (EntityTickingSystem whose Archetype query itself excludes every
  non-caster mob, so steady-state cost is proportional to armed mobs only). NATIVE_CHAIN entries arm
  ONCE at spawn, never on cadence (a re-arm resets the engine's attack round-robin cursor and starves
  the mob's other chains).
- New: CasterFeatureState - a session-wide latch that disables ability-cast rosters with ONE warning
  on a LinkageError (running against a pre-1.6.0 MMO jar), so a mismatched jar pair degrades
  gracefully; NativeChain entries are unaffected.
- New: ScalingContentValidator.validateCasterRosters (Role.Id XOR Glob, AbilityId XOR NativeChain,
  unknown Scope, CadenceSeconds >= 2s floor, negative MinDifficulty/JitterSeconds, duplicate Role.Glob).

Community bug-fix wave (2026-08-04 Discord scan):

- Fix (CRITICAL, player scaling inert): the player/group power delta was gated behind a start ring that
  reused the distance-escalation start radius (shipped default 15000 blocks), so spawn difficulty sat
  at the floor everywhere regardless of player power, and toggling DistanceEscalation changed nothing.
  The ring is now its own orthogonal knob, `OpenWorld.PlayerScalingStartRingBlocks` (global default
  5000.0 = a newbie-protected ring near spawn; per-world overridable, and the three shipped Dungeon of
  Fear rules pin it to 0.0 so instance scaling applies from the first spawn). `/mobscaling inspect`
  gains a player-scaling line (applied / enabled / ring radius / in-ring / distance from spawn) and the
  admin UI exposes the knob on both the global and per-world forms.
- Rework: per-rarity role/group TARGETING lists replace the unfinished Java-side boss special case. A
  rarity's `Families` group gains `ForceGroups`/`ForceRoles` beside the existing Allow/Deny lists
  (force > deny > allow; the strongest matching forced tier wins, and force acts as a floor a stronger
  natural roll can still beat). The shipped `Rarities/Boss.json` now authors
  `Families.ForceGroups: ["Mmoscaling_Bosses"]`, so the boss NPCGroup finally does what its comment
  always promised; an owner overriding Boss.json without ForceGroups deliberately opts out.
- Fix (log spam + wasted tick time): re-adding an already-scaled mob (chunk reload, world transfer)
  threw `IllegalArgumentException: Entity contains component type: ScaledMobComponent` on every
  attempt (reported at 13k+ occurrences in one session, 46% of that log's warnings) and aborted the
  HP reconcile that re-add exists to run. The stamp is now an idempotent replace, so a re-add
  reconciles quietly (deterministic per-mob seed: same mob, same roll) and the reconcile-on-load
  design actually works for already-stamped mobs. The caster-kit arm stamp gets the same treatment.
- New: boot-time pack audit (PackDependencyAudit): a pack that authors `Server/MmoMobScaling/*` or
  `Server/NPC/Groups/Mmoscaling_*.json` without declaring the `Ziggfreed:MmoMobScaling` manifest
  dependency is named in the log with the exact missing dependency line, covering both failure shapes
  (a whole-asset-load abort with a misleading engine stack trace, or a silently shadowed override that
  loses the last-pack-wins race). Log-only, never a boot failure, and runs even when scaling is
  disabled.
- New: reference-existence validation in ScalingContentValidator: a dangling rarity/variant
  AuraEffectId, affix EffectId, BonusDropList, Families Allow/Deny/Force group id, or roster role id
  now WARNs by name at load (previously an unresolvable effect id was one runtime warn-once and a
  silent no-op, and a misleading engine boot crash got blamed on it). Degrades to permissive when an
  engine store cannot answer.
- Fix (config discoverability): the per-world `worlds/` owner folder is scaffolded up front with a
  README describing the one-file-per-world convention, and every boot logs the absolute owner-config
  paths (`mob-scaling config: ...`), closing the "the mod never creates its config" reports (the
  paths are relative to the server working directory, which is what made them hard to find).
- New: validator findings for shadowable per-world Match patterns: a rule whose match core is a strict
  prefix of another's (fragile if the longer rule is ever removed) and two rules with equal-length
  cores (a silent insertion-order tie-break) each WARN.
- Docs: CURSEFORGE/README gain "Where the files live", "Extension packs" (copy-pasteable manifest
  dependency, case sensitivity, zip-root rule), and a third-party nameplate compatibility note (a mod
  that flattens the localized display-name Message without substituting params prints the raw
  `{rarity} {base}` template; this mod deliberately never writes the overhead nameplate, and the
  crosshair inspector HUD renders rarity/variant correctly regardless).
- New: demo content - Demo_Boss_Caster.json arms the shipped Fire Dragon boss with the MMO's fireball
  (~14s cadence, a Hurt-flinch Windup on the Status slot since the dragon rig ships no cast animation),
  the NPC-only dragon_arcana ice bolt (~20s, no Windup - its native chain carries its own animation
  nodes), and a dodge NativeChain pointing at this mod's OWN Attack-tagged
  Mmoscaling_Demo_Dodge root (arms out of the box, and never risks the engine classifying a dodging
  PLAYER as attacking, which is why the MMO's player-facing MMO_Dodge stays untagged). Plus a fully
  native CAE_Mmoscaling_Caster_Demo.json/Mmoscaling_Caster_Demo.json pair (spawn via
  /npc spawn Mmoscaling_Caster_Demo) demonstrating the same periodic-cast idea authored entirely as
  native asset content, zero Java.
- Fix (compat): MobScalingDamageFilter's Order.BEFORE dependency retargeted from CombatXpEventSystem
  (moved to the MMO's Inspect damage group this cycle) to CombatDamageEventSystem (confirmed still
  Filter-group) - SystemDependency resolves by class across the whole graph regardless of group, so
  the old dependency was not actually broken by the move, just redundant and pointed at a system no
  longer in this phase; retargeting removes that latent fragility.
- Changed (HARD BREAK, upgraders): the lang file is renamed `scaling.lang` -> `mmomobscaling.lang` in
  all nine locales, so the namespace the engine derives from the filename becomes `mmomobscaling.`
  instead of the bare `scaling.` this mod shipped in 1.0.0 and 1.0.1. Every authored key stays the
  same (`rarity.rare.name`, `command.usage`, `ui.title`, and so on) - only the file's own basename
  and the fully-resolved prefix change, so `scaling.rarity.rare.name` reads as
  `mmomobscaling.rarity.rare.name` from this version on. This brings the mod in line with every
  other mod in the family, each of which namespaces its lang file by its own mod id rather than a
  bare English word a second mod could equally claim. A server owner with a translation overlay or
  a third-party pack authoring against the old `scaling.*` ids needs to re-point them at
  `mmomobscaling.*`.

## 1.0.2 (superseded by 1.1.0)

An in-game admin config UI (`/mobscaling ui`) with full persistence for every runtime tuning path,
plus per-world config reworked onto its own files with inheritance and more per-world knobs. Requires
MMO Skill Tree 1.5.0+ (the build that removes the old WorldRules mob-scaling baseline - update BOTH
together) and Ziggfreed's CommonLib 1.3.0+.

- Change (default tuning): softened the DEFAULT difficulty->stat curve and pushed distance escalation
  farther out, so a scaled mob is tankier than it is bursty and the deep-frontier ramp starts later.
  `Settings/Default.json` `StatCurve.OutDamagePerPoint` 0.04 -> 0.01 (the OUTGOING-damage bonus per
  difficulty point; the HP + incoming-reduction slopes and every cap are unchanged) and
  `DistanceEscalation.StartDistanceBlocks` 5000 -> 15000. The `Casual` (0.02 -> 0.0025) and `Hardcore`
  (0.08 -> 0.05) presets get the same out-damage softening; `Playtest` is deliberately left steep.
- Change (dungeon defaults rework): the shipped Dungeon of Fear world files are flat + self-contained now -
  the shared `DungeonOfFear_Base` Parent file is removed. `DungeonOfFear_I`/`II` turn scaling OFF outright
  (`Enabled:false`) in their instances; `DungeonOfFear_III` keeps player/group scaling ON with distance
  escalation OFF (inlined, no longer inherited from the base). III's effective behaviour is unchanged; I/II
  now disable scaling entirely instead of only pinning player-scaling off.
- Fix (Kweebec match): `KweebecNightmare.json`'s per-world Match is `*KweebecNightmare_*` (contains) so it
  catches the real instance worlds, whose live names carry BOTH a leading `instance-` prefix AND a random
  suffix (`instance-KweebecNightmare_Chase_Dread-<uuid>`). The old trailing-`*` prefix (`KweebecNightmare_*`)
  never matched those, so the scaling-off kill-switch silently did nothing there. Needs Ziggfreed's CommonLib
  1.3.0+, whose `WorldNameMatcher` carries the suffix/contains match forms (manifest requirement `>=1.3.0`).
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
  chance / caps / player scaling / escalation). Global + HUD edits write the owner file
  `mods/MmoMobScaling/mob-scaling.json` and refold live; HUD + preset edits apply to all online players
  with no reconnect. `Enabled` shows a "takes effect on restart" note (the zero-cost registration gate
  registers systems at startup).
- Change (rework): the admin config page is now SPEC-DRIVEN over the new ziggfreed-common `ui/form`
  engine (`FieldSpec` + `SettingsForm`, five `Pages/ZigForm*Row.ui` templates) instead of ~24
  hand-written per-knob `.ui` rows + a matching per-field `EventData` codec key - adding a knob later is
  one `FieldSpec` line plus one lang key. `EventData` collapses to five keys (`Action`/`Tab`/`WorldId`/
  `Field`/`@Value`). FULL knob coverage across all four tabs: Global gains the whole
  `OpenWorld` group (aggregation, region size, band width, only-raise, party-join, late-arrival,
  composition), and the six `StatCurve` leaves; the per-world editor gains the same `OpenWorld` group,
  the six `StatCurve` leaves, the `Pool` group (rarity/variant/affix allow-deny + variant chance + extra
  affix slots), and per-world Zone/Inspector HUD visibility - every CONSUMED per-world knob is editable
  now (the rest of each HUD group - position, offsets, range, the zone/biome name-key prefixes - and
  `OpenWorld.RegionSizeChunks` decode on `WorldSettings` but apply GLOBALLY on purpose, so they stay off
  the per-world form: a HUD position is a per-viewport concern, not a per-world one, and the
  region-power grid size must stay identical across worlds). The Worlds
  tab is a TWO-PANEL layout (world list left, add/edit editor right, on a wider 960x680 frame) instead
  of one long single-column scroller. The page NEVER reopens itself now: every event answers with a
  partial `sendUpdate` (world-list changes clear + re-append + rebind in the same update, the official
  shared-source `ChangeModelPage.buildModelList` pattern), so editing no longer resets scroll position.
  Every hint/note WRAPS (the `ZigFormNoteRow` template) instead of truncating. Each tab (Global/Zone
  HUD/Inspector HUD) collects + saves as ONE unit via `SettingsForm.collectLeaves`; a toggle (`Enabled`,
  `PlayerScalingEnabled`, HUD enable/portrait/show-location, etc.) is instant-persist on click via a
  small `id -> ToggleDef` lookup table, not a chain of switch arms. A `collectLeaves` validation failure
  now NAMES the failing field (`scaling.ui.status.invalid_field`, a nested client-resolved `Message`
  param, validated against the official `PortalDeviceActivePage` precedent).
- Change: the per-world editor seeds from the file's AUTHORED body, not the `Parent`-merged effective
  view (`WorldSettingsConfig.authoredById`, a new accessor over a newly-tracked pre-merge raw-body pool;
  `foldedView()`/`effectiveById()` are both backed by the SAME post-merge map, so neither was safe to
  seed an editor from once the exposed knob count grew to ~40 - saving back would have materialized
  every inherited leaf into the child file and silently broken inheritance). A blank field / the Inherit
  tri-state now round-trips faithfully across a save.
- New: full persistence for the live commands. `/mobscaling intensity`, `/mobscaling hud`, and
  `/mobscaling preset` now SAVE to the owner file (they were runtime-only in 1.0.1, lost on restart). The
  UI and the commands share ONE write-back path (`config/MobScalingOwnerWriter` -> the owner file ->
  `MobScalingConfig.refreshFromDisk`), so a change made either way sticks and applies live.
- Fix: `/mobscaling` now takes the subcommand as a REQUIRED positional arg. It was optional, which (the
  Hytale parser binds optional args by NAME, not position) meant a bare token like `/mobscaling hud`
  never bound to the subcommand and silently fell through to `inspect`. The follow-on tuning values stay
  optional, so they are passed by name: `--hudTarget=<zone|inspector>`, `--hudValue=<on|off|POSITION>`,
  `--hudOffsetX`/`--hudOffsetY`, `--presetName`, `--intensity`.
- Change: requires Ziggfreed's CommonLib 1.3.0+ - the shared settings-UI toolkit the admin page consumes
  (`util/JsonOverrideWriter` owner-file write-back, `ui/hud/HudPosition` layout value, `ui/SettingsUiUtil`
  form binding, `Pages/ZigListRow.ui` row). The mod's private `hud/HudPosition` copy is retired in favour
  of the lifted common one (identical behavior).
- Change (round-2 admin-UX hardening, in-game feedback): removed the `PresetMode` dropdown from the
  Global tab. Verified nothing consumes `MobScalingConfig.getPresetMode()` outside the schema
  (`MobScalingSettingsAsset`) and the config fold; the codec field + fold stay for an owner who still
  sets it by hand, only the dead UI row + its lang key (`ui.global.preset_mode`) are gone.
- Change: the Global tab is reordered difficulty-first: `Enabled` note, Difficulty (floor + caps), the
  stat Curve (`Intensity` now leads it, as the curve's global slope multiplier), rarity + distance
  escalation (`RaritySpawnChance` leads it), Open World group last. `ui.global.esc_header` reworded to
  "Rarity & distance escalation" to match.
- New: a live skeleton-preview panel beside the Global settings. The Global tab is now two-panel
  (`LayoutMode: Left`): the existing form on the left, a new "Preview: Skeleton" column on the right
  showing a PLAIN mob (no rarity/variant) run through the CURRENT (unsaved) Global-form difficulty stat
  curve at five evenly-spaced sample difficulties between the live `MinCap`/`MaxCap`
  (`MobScalingAdminPage.refreshPreview`, mirroring `MobScalingConfig.buildCurve` - package-private there,
  so the `MobScaleFold.DifficultyStatCurve` is constructed directly in the page). Shows HP / outgoing-
  damage multipliers and incoming-damage-reduction percent per sample, formatted compactly
  (`x1.8`/`-22%`); refreshes on every Global-form `field`/`press`/`saveGlobal`/`selectPreset` event via a
  small preview-only partial update (never re-pushing the form's own values). Damage stays factor-only
  (base attack damage lives in weapon/attack assets, out of scope).
- New: the HP cell shows the skeleton's REAL health, not just the multiplier (`x2.6 (239)`), via a new
  `pages/RoleBaseHealthResolver`. The role registry read is public and entity-free
  (`NPCPlugin.getIndex` -> `getRoleBuilderInfo` -> `BuilderInfo.getBuilder()` returns the already-parsed
  `Builder<Role>` off the loaded-asset registry); the engine's OWN `BuilderManager.validateAllSpawnableNPCs`
  proves the EVALUATION is equally entity-free (`new ExecutionContext(builder.getBuilderParameters().createScope())`).
  The one non-public hop - `BuilderRole`'s `protected final IntHolder maxHealth` field, whose public
  accessor demands a `BuilderSupport` (and so a live entity) purely as an API-surface artifact - is
  bridged with a cached, `setAccessible`-once reflective field read, evaluated via the SAME entity-free
  pattern (`holder.rawGet(null)` for a static value, mirroring the engine's own `IntHolder.readJSON`;
  a real `ExecutionContext` for a `"Compute"`-driven one). Fully `try/catch(Throwable)`-guarded;
  memoizes both a success and a failure per role name for the process lifetime, so a broken read
  degrades silently to multipliers-only once, never retried per keystroke.
- New: inline help text on every setting. Every leaf-bearing field/toggle spec across all FOUR forms
  (Global, Zone HUD, Inspector HUD, Worlds; ~80 fields) now carries a `.withHint(...)` - one qualitative
  sentence, no digits - rendered under the row via the ziggfreed-common `ui/form` engine's `#Hint`
  sub-label (`FieldSpec.withHint`/`SettingsForm.applyHint`). A world-form field that mirrors an IDENTICAL
  global concept reuses the matching global hint key; a tri-state, a pool gate, or a world-identity field
  gets its own key (54 new `scaling.ui.hint.*` keys total).
- New: the per-world editor shows what a blank/Inherit field currently inherits. On edit (and after a
  save re-seeds), every blank/Inherit field's hint gains a computed "Inherits: {value}" line (a NEW
  `scaling.ui.world.inherits` key) on top of its static help text, resolved from
  `WorldSettingsConfig.effectiveById` (the `Parent`-merged view) falling back to the GLOBAL live
  `MobScalingConfig` value per leaf (a Pool gate's global reads as allow-all / an empty deny list /
  neutral scale / zero extra slots; a per-world HUD tri-state's global is the zone/inspector enabled
  flag). Composed via `Message.join(staticHint, Message.raw("\n"), inheritsMsg)` (no new wrapper lang key
  needed). An authored field shows the static hint alone; clearing the editor resets every hint to
  static-only.
- Change: world-list rows wrap instead of truncating. A new MOD-LOCAL `Pages/MmoscalingWorldRow.ui`
  (modeled on ziggfreed-common's `Pages/ZigListRow.ui`, same child ids) replaces the shared row for this
  page's 300px list panel: `#Title` wraps to two lines (no fixed title height) instead of cutting off a
  long world id / match pattern, `#Badge`/`#EditBtn`/`#RemoveBtn` narrow to leave room. `buildWorldList`
  is unchanged beyond the template-path constant.
- Fix (round-3 admin-UX hardening, in-game validation): the absolute-HP resolver silently fell back to
  multipliers-only for the LIVE skeleton, because the vanilla `Skeleton` role is a `Variant`
  (`"Type": "Variant", "Reference": "Template_Intelligent", "Modify": {"MaxHealth": 92, ...}`), not a
  plain `BuilderRole` - `RoleBaseHealthResolver`'s old `instanceof BuilderRole` gate rejected it outright.
  The resolver now mirrors the engine's own `BuilderManager.validateAllSpawnableNPCs` for a
  `BuilderRoleVariant`: seed an `ExecutionContext` from the variant's OWN builder parameters, fold the
  WHOLE `Modify` chain via the variant's public `createModifierScope(ExecutionContext)`, walk the SAME
  reference chain (`getReferenceIndex()`/`getBuilderManager()`/`tryGetCachedValidRole()` - all public, no
  second reflective field needed) to the TERMINAL template `BuilderRole`, then evaluate ITS `MaxHealth`
  holder against the folded scope (`Template_Intelligent.json`'s `"MaxHealth": {"Compute": "MaxHealth"}`
  is a `Compute` expression, so the old `isStatic()`/`rawGet(null)` shortcut would NPE on it - both role
  shapes now always evaluate via `rawGet(ctx)`). The preview now shows `x4.2 (386)` for the skeleton
  instead of `x4.2` alone.
- New: observed-spawn ground truth backs the resolver too. `RoleBaseHealthResolver.recordObserved` lets
  `event.MobScalingSpawnHook` feed it a role's ACTUAL pre-scale base max health, read off the balanced
  `EntityStatMap` right before this mod's own `mmoscaling_hp` modifier applies - live truth that already
  includes native balancing plus whatever any earlier-ordered mod stacked on top. `baseMaxHealth` checks
  this cache before the reflective template read, so once any mob of a role has spawned this session, the
  preview's absolute HP for that role reflects the table's actual live numbers, not just the authored
  template value.
- Change: the per-world editor's "Inherits: X" hint line now renders WHITE + BOLD end to end (the label
  and the substituted value alike), instead of the same muted grey as the static hint text above it.
- New: a manual difficulty probe in the Global-tab preview. A "Try a difficulty" field below the five
  fixed samples previews ONE more row at whatever difficulty (`>= 1`) you type, UNCLAMPED to the live
  Min/Max cap band - a way to sanity-check one specific number without retuning the caps first. Wired
  outside `globalForm` entirely (its own `"previewD"` event; nothing to persist), refreshed alongside the
  five fixed rows on every Global-tab change and on its own keystroke; hidden while blank or unparseable.

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

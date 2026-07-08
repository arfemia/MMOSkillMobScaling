# MMO Mob Scaling

**Open-world mobs that scale to the players around them.** A high-power group meets tougher, rarer,
affixed enemies; a lone newcomer is never overwhelmed. A standalone companion to the
[MMO Skill Tree](https://www.curseforge.com/hytale/mods/mmo-skill-tree) mod that turns Hytale's open
world into a living difficulty curve, zone by zone, without you hand-placing a single spawn.

> **v1.0.0 - first release.** The full scaling system is in. It is still in active in-game
> tuning, so numbers may shift between builds. Everything is data-driven, so you can retune any of it.

## Requirements

Both load before this mod (drop all three jars in your server `Mods/` folder):

- **MMO Skill Tree >= 1.5.0** - supplies the player-power / combat-level API the scaling reads.
- **Ziggfreed's CommonLib >= 1.2.0** - the shared primitive library this mod builds on.

Targets Hytale server `0.5.x` (Update 5). Zero client install: everything is server-side.

## What it does

Every hostile open-world mob, as it spawns, is scaled to a difficulty resolved from three layers:

1. **Native zone + biome floors.** Each of Hytale's own worldgen zones carries a baseline difficulty
   (a gentle starter gradient ships out of the box, Zone 0 through Zone 4). The deeper the zone, the
   harder its mobs. You retune one small file per zone or biome; no zone registry to invent.
2. **Distance-from-spawn escalation.** Past a configurable radius, every stretch of blocks adds
   difficulty AND raises the chance of rare, affixed mobs, so the deep frontier is deadly in every
   zone. A lone traveler far from home should feel it.
3. **The players actually there.** A region's difficulty tracks the real power of the group standing
   in it (their full MMO Skill Tree build: combat, stat rewards, abilities, mastery, achievements),
   not just the highest level present. Roll up with a strong party and the zone answers.

The tougher a mob, the better it pays: a scaled kill grants bonus MMO XP (with an underdog bonus for
punching above your weight) and pulls extra loot from a per-rarity drop table.

## Features

- **Rarity ladder.** Rare, Epic, Legendary, plus a forced Boss tier for tagged mobs. Each tier is a
  colored nameplate, a body-tint aura, stat multipliers, affix slots, bonus XP, and a bonus loot table.
- **Affixes** on native Hytale effects (no client mod): **Armored** (damage resistance), **Stalwart**
  (knockback immunity + extra health), **Swift** (faster movement), **Vampiric** (life-steal on hit),
  and **Freezing** (a chilling on-hit slow). Author your own in a content pack.
- **Deterministic rolls.** A mob's rarity and affixes are seeded from its identity, so the same mob
  reproduces identically across chunk reloads (no reroll churn).
- **Native-first everywhere.** Difficulty rides Hytale's own worldgen zones, effects, NPC-group
  tagsets, and item-drop lists, so your existing world and other mods keep working.
- **Two player HUD overlays** (see below).
- **Presets + full data control.** Ship-ready presets and per-file tuning for everything.

## HUD overlays

Two lightweight, per-player, always-current overlays (both toggle on or off and reposition live):

- **Zone Difficulty card.** The local spawn difficulty, a threat tier read RELATIVE to you (Trivial
  through Deadly, colored), your own power, the tracked group power, and the **friendly location name**
  (the zone shows its real in-game name, e.g. "Cinder Wastes", not an internal id; the biome is shown
  alongside).
- **Mob Inspector card.** Look at any mob and see a **portrait** of it, its name, rarity tag, scaled
  difficulty, a live health bar, and its affixes as **icon chips**. Affix icons are data-driven (an
  item id or a texture), so a content pack can theme them.

## Commands

`/mobscaling <subcommand>` (requires the `hytale:Admin` role, or OP when permissions are off):

| Subcommand | What it does |
| --- | --- |
| `inspect` | Report the difficulty inputs at your position (zone floor, distance bonus, effective difficulty, region power, rarity chance). The default subcommand. |
| `preset <name>` | Switch the active settings preset live: `Default`, `Casual`, `Hardcore`, `Playtest`. |
| `intensity [multiplier]` | Show or live-set the global difficulty intensity multiplier (`1.0` = normal, higher = tougher mobs). |
| `hud <zone\|inspector> <on\|off\|POSITION> [offsetX] [offsetY]` | Toggle or reposition either overlay live for all players (positions: `TOP_LEFT` ... `BOTTOM_RIGHT`). |
| `worlds` | List every loaded per-world settings file: its match pattern, parent, shipped-vs-owner origin, and on/off state. |
| `ui` | Open the in-game admin config page: every knob across four tabs (global settings, Zone HUD, Mob Inspector HUD, and a two-panel editor over the per-world files - world list on the left, add/edit on the right). Every setting that applies per world is editable here now, including a world's spawn pool, difficulty stat curve, open-world scaling group, and whether it shows the zone/inspector HUD (a few knobs, like the HUD's on-screen position, only make sense globally and stay in the config file). The Global tab is difficulty-first and shows a live "Preview: Skeleton" panel beside your settings (a plain mob run through your current difficulty stat curve at five sample levels, updating as you type). Every field has a short help line, and a blank/Inherit field in the world editor tells you exactly what it is inheriting. World rows wrap instead of cutting off long names. Each edit is saved and applied live. |
| `purge` | Strip all scaling residue (the health modifier + `Mmoscaling_*` effects) off loaded mobs in your world. Run this per world before uninstalling. |

Every change made in `/mobscaling ui` or via the `intensity` / `hud` / `preset` subcommands is now SAVED to `mods/MmoMobScaling/mob-scaling.json` and applied live to all players (no restart needed, except toggling the master enable).

## Configuration

Everything is a proper Hytale asset (owner-overridable, pack-extendable), under `mods/MmoMobScaling/`
and the mod's asset stores. You never edit Java.

- **Presets.** Set `ActivePreset` (or use `/mobscaling preset`) to `Default` (balanced), `Casual`
  (gentler curve, rarer specials), `Hardcore` (harsher, denser), or `Playtest` (steep ramp from
  spawn, for testing). Presets are partial overlays; anything you do not set inherits the default.
- **`mods/MmoMobScaling/mob-scaling.json`** overrides any settings key (master enable, rarity chance,
  difficulty caps, the distance-escalation curve, the difficulty-to-stats curve, group-power
  aggregation, and both HUD overlays incl. the zone/biome name-key prefixes and the inspector
  portrait toggle). Only your changes are stored; a partial nested group inherits the rest.
- **Zone / biome floors** are one small file each under the mod's `Difficulty/` assets
  (`TargetType` Zone or Biome, the native name or a `*` wildcard, and a `Floor`).
- **Rarities and affixes** are one file each (roll weight, difficulty band, multipliers, affix slots,
  the native effect, bonus drop table, display color, and the inspector icon).
- **Per-world control: one file per world.** Drop a file in `mods/MmoMobScaling/worlds/` (or ship
  `Server/MmoMobScaling/Worlds/*.json` in a pack): a `Match` pattern (exact, a `Prefix_*`, or `*`, so
  suffixed instance worlds are caught) plus just the settings that world changes. A world can switch
  scaling off entirely (`Enabled: false`), set its own baseline difficulty floor, intensity, rarity
  chance, caps, escalation, stat curve, the whole group-scaling behavior, hide the HUD overlays, and
  gate its spawn `Pool` (allow/deny rarities, variants, and affixes, scale variant chance, extra affix
  slots). A file may name a `"Parent"` file and inherit everything it does not set; anything still
  unset falls back to the global settings. A file in `worlds/` with the same name as a shipped one
  replaces it; deleting yours brings the shipped one back. Three Dungeon of Fear instances plus the
  Kweebec Nightmare worlds ship pre-tuned via a shared parent base. (A pre-1.0.2 inline
  `WorldOverrides` list migrates to files automatically on first start.)

Content packs can add or replace rarities, affixes, zone floors, per-world files, and drop tables; the
fold order is `mod defaults < content pack < server owner`.

## Uninstall note

While the mod is enabled it reconciles scaled mobs every time they load, so a retune or a removal is
picked up cleanly. A fully removed mod cannot self-heal saved mobs, so for a clean uninstall run
`/mobscaling purge` in each world first (that command works even when scaling is disabled), then
remove the jar.

## Installation

1. Install **MMO Skill Tree >= 1.5.0** and **Ziggfreed's CommonLib >= 1.2.0**.
2. Drop `MmoMobScaling-<version>.jar` in your server's `Mods/` folder.
3. Start the server. Scaling is on by default with the balanced preset; tune from `mods/MmoMobScaling/`.

## Compatibility

Native-asset-first by design: scaling rides Hytale's own worldgen, effects, NPC groups, and drop
lists, so it coexists with other content mods. A world using a non-standard worldgen (flat/void/custom)
simply falls back to a distance-and-proximity grid with the world's baseline floor.

## Links & Support

- Companion mod: [MMO Skill Tree](https://www.curseforge.com/hytale/mods/mmo-skill-tree)
- Full developer changelog: [CHANGELOG.md](CHANGELOG.md)
- Website: [wintergreen-solutions.com](https://wintergreen-solutions.com)

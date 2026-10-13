# MMO Mob Scaling

**Adds rare, tougher monsters to Hytale's open world, scaled to the players nearby.**

The deeper the zone and the farther you roam from spawn, the harder the monsters get. A strong group meets stronger mobs, and a new player is never swamped. Some mobs spawn Rare, Epic or Legendary: they glow in their rarity's colour, hit harder, carry extra powers, and pay more XP and loot.

A companion to [MMO Skill Tree](https://www.curseforge.com/hytale/mods/mmo-skill-tree). It needs MMO Skill Tree 1.7.1 and [Ziggfreed's CommonLib](https://www.curseforge.com/hytale/mods/ziggfreeds-commonlib) 2.3.0 on a Hytale Update 7 (0.7.x) server, so update all three together.

## What you'll see

- Rare, Epic and Legendary mobs, each tougher than the last, plus Boss for the world's roaming bosses. A rarer mob gives more MMO Skill Tree XP, more still if it outranks you, and rolls extra loot.
- Affixes. A rare mob can be Armored, Swift, Stalwart (hard to knock back, with extra health), Vampiric (it heals when it hits you) or Freezing (its hits slow you). Or it can be warded against one damage type, like Fireward or Spellward. Horrific spiders can be Venomous.
- The Zone Difficulty card shows how dangerous the area is for you, from Trivial to Deadly, with the zone's name and your power.
- Look at a mob and the Mob Inspector card shows its portrait, rarity, health and affixes, with a line on what each affix does.

Bosses from Hytale's own boss fights, such as the Feastmaster, are left alone. The fight sets their health.

## For Server Owners

- Scaling is on as soon as you install it, with the balanced preset. Switch with `/mobscaling preset --presetName=Hardcore` (or `Default`, `Casual`, `Playtest`).
- `/mobscaling ui` opens a settings page with every option, a live preview of how tough a skeleton gets, and editors for each world and each zone. Changes save and apply at once.
- `/mobscaling inspect` reports the difficulty where you stand.
- These commands need the `hytale:Admin` role, or OP when permissions are off.
- Your files are in `mods/MmoMobScaling/`, inside the folder your server runs from. The log prints the full path on every start: search it for `mob-scaling config:`.
- A file in `mods/MmoMobScaling/worlds/` turns scaling off in a world or gives it its own difficulty. The folder's `README.txt` shows how. Kweebec Nightmare and the Dungeon of Fear worlds come tuned.
- Mobs can cast MMO Skill Tree spells. One example ships switched off: save `demo_boss_caster.json` holding `{"Enabled": true}` in `mods/MmoMobScaling/casters/` and the Ember Dragon casts Fireball and throws ice.
- Before you uninstall, run `/mobscaling purge` from the server console to strip scaling off every loaded world, then remove the jar.

## For Mod and Pack Authors

A content pack can add or replace rarities, affixes, caster rosters, zone floors, world files and drop tables. It must list this mod in its `manifest.json`, or the mod's defaults load after your pack and replace everything in it:

```json
{
  "Group": "YourName",
  "Name": "YourPack",
  "Version": "1.0.0",
  "ServerVersion": ">=0.7.0-pre.0 <0.8.0",
  "IncludesAssetPack": true,
  "Dependencies": { "Ziggfreed:MmoMobScaling": ">=1.1.0" }
}
```

- The id is exactly `Ziggfreed:MmoMobScaling`, capitals included. A misspelt id stops the server loading any assets, and the log shows `Missing default DamageCause assets`. Scroll up from it to `Missing required dependencies` to find the pack.
- `manifest.json` sits at the root of the zip, with no folder around it, or the pack is skipped.
- On every start the mod logs any pack it is overriding, with the line to add, and any rarity or affix that names an effect or drop list that doesn't exist.

## Installation

1. Install MMO Skill Tree 1.7.1 and Ziggfreed's CommonLib 2.3.0 (or newer).
2. Drop `MmoMobScaling-<version>.jar` into your server's `Mods/` folder.
3. Start the server.

## Links & Support

- Companion mod: [MMO Skill Tree](https://www.curseforge.com/hytale/mods/mmo-skill-tree)
- Patch notes for every version: [patch-notes/](https://github.com/arfemia/MMOSkillMobScaling/tree/main/patch-notes)
- Website: [wintergreen-solutions.com](https://wintergreen-solutions.com)

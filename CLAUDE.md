# MMO Mob Scaling

Open-world mob difficulty scaling (rarity ladder, affixes, region power, two HUD overlays). The family-wide rules apply here; this file adds only what is specific to this mod. Power-difficulty derivation: the MMO repo's `git -C mmo-family/mmo-skills show archive/claude-infra-2026-10:.claude/plans/power-difficulty-parity.md`.

## Build and lockstep

- Build with `.\build.ps1` (`-Install:$false` builds only). `ziggfreed-common` and the MMO jar are `compileOnly`, never bundled.
- `gradle/deprecation-gate.gradle` (run by `check`) is the MMO's (`mmo-family/mmo-skills`), copied byte for byte: it changes only by copying the MMO's.
- The `gradle.properties` pins (`ziggfreedCommonVersion`, `mmoSkillTreeVersion`) follow their siblings' versions in lockstep; a manifest `>=` floor is the oldest version the mod really needs and moves only for newer API, never above its pin (R364). The `LinkageError` guards are a mis-install net, not older-jar support.
- Read player power only through the frozen `MMOSkillTreeAPI`; never widen it or write an MMO file.

## Gate and wiring

- Zero-cost gate: a disabled mod registers no systems. The `/mobscaling` command and the `BootEvent` audit register before the gate on purpose (purge must work on the uninstall path). `Enabled` needs a restart. Keep `MobScalingGate` off the `JavaPlugin` class so unit tests can load it.
- Factor contributions are claimed inside the enabled branch; another mod reads a mob's rarity through those factors, never through an exported API.
- Logging: `MobScalingPlugin.LOGGER` inside a `catch (Throwable)` (the `safeInfo` / `safeWarn` pattern) on any unit-reachable path, since the logger can throw an `Error` that escapes `catch (Exception)`; this mod has no `SafeLog`.

## Spawn roll

- The spawn lock has two halves: `MobScalingSpawnHook` stamps `PendingRollComponent` pre-add and `MobScalingRollSystem` rolls the tick after. The roll skips `ManualTrigger` spawn-marker spawns and encounter-bound subjects. A stamp onto a live entity fires no `RefSystem`, so the roll calls the effect and caster-arm bodies directly. A holder already carrying `ScaledMobComponent` is never re-rolled.
- `MobScaleResult.difficulty` is the spot difficulty everywhere it is read. Rarity and variant multiply the curve-read difficulty (`dEff`), which is not re-clamped to `MaxCap`.
- `Mmoscaling_Bosses` lists ambient world bosses only; encounter-raised or bound bosses are skipped before classification.

## Config

- Per-world consumers read `MobScalingConfig.spawnSettingsFor(world)`, never the global getters; prefer the `World` overload. Under `Parent`, a world file's `Where` replaces wholesale, never per leaf.
- Every runtime edit persists through `config/MobScalingOwnerWriter`; pages and commands never write owner files or mutate `MobScalingConfig`.
- A settings leaf nothing reads is deleted from codec, fold, page and lang together.

## Effects and damage

- Kept mod-side on purpose: difficulty and multipliers on the transient `ScaledMobComponent`, the incoming multiply in the pipeline (`MobScalingDamageFilter`: the engine's inbound lookup needs an exact type match), the folded `hpMult` (Stalwart's `FoldDeltas.Hp` included) on `HealthUtil`'s reconcile, Vampiric lifesteal in `MobScalingOnHitSystem`.
- The outgoing multiply (`outDmgMult`, affix `FoldDeltas.OutDamage` folded in) rides a native `OutgoingDamage` effect from zc's runtime effect grid, put on at roll by the effect reconcile (`event/OutgoingDamageEffect`: `Mmoscaling_OutDamage_All_<percent>`, keyed on the `Physical`, `Projectile` and `Elemental` roots); the engine applies it after armour.
- The rarity aura owns the body-tint channel; affix effects carry no tint, and a variant's aura is only a fallback.
- Speed effects author `HorizontalSpeedMultiplier` (NPCs) and `MovementEffects.SpeedMultiplier` (players) together.
- The `mmoscaling_hp` modifier persists with saved mobs: run `/mobscaling purge` before an uninstall (the console sweeps every loaded world).

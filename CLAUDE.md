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

gson is `compileOnly` only (the MMOSkillTree jar's inherited `AbstractOverrideConfig.GSON`
provides it at runtime); jsr305 is `implementation` (the `@Nonnull`/`@Nullable` annotations
must resolve).

## Paradigm - the zero-cost registration gate

The plugin's `setup()` loads `MobScalingConfig` then applies the gate:
`MobScalingPlugin.shouldRegisterSystems(cfg)`, which delegates to the pure predicate in
`MobScalingGate` (kept OFF the `JavaPlugin`-extending plugin class so it is loadable in a
unit-test JVM - loading `MobScalingPlugin` there fails via the `PluginBase` ->
`MetricsRegistry` static-init chain). When the config is disabled the plugin registers
NOTHING and returns, so the mod carries no per-tick cost at all. The scaling systems
register only inside the enabled branch (the `// Phase 5:` TODO).

`MobScalingConfig` extends the MMO's `AbstractOverrideConfig` (override-based:
`mods/MmoMobScaling/mob-scaling.json` stores only customizations; `SCHEMA_VERSION` bumps on
structural change), mirroring `EliteMobsConfig` (nullable-wrapper `OverrideData` +
`{schemaVersion, overrides}` ConfigData shape).

**Defaults live in a `/Server` JSON asset, NOT baked into Java** (the repo paradigm: content
/ config defaults ship as `Server/*` JSON, never Java `*Defaults`). The authoritative default
values are `src/main/resources/Server/MmoMobScaling/mob-scaling.defaults.json`; `loadDefaults()`
only orchestrates reading them. It is read SYNCHRONOUSLY as a classpath resource (NOT a Hytale
keyed asset via `LoadedAssetsEvent`) on purpose: the zero-cost registration gate reads `enabled`
at plugin `setup()`, which runs BEFORE a keyed-asset store would populate, so a keyed asset could
not gate registration. A broken jar (missing bundled JSON) fails safe (disabled). Later content
collections (rarities / affixes / difficulty mappings), read at spawn time not setup, DO ship as
proper keyed assets under `Server/MmoMobScaling/<Type>/`.

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

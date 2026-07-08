package com.ziggfreed.mmomobscaling;

import java.nio.file.Paths;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.mmomobscaling.asset.MobScalingAssetRegistrar;
import com.ziggfreed.mmomobscaling.command.MobScalingCommand;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;
import com.ziggfreed.mmomobscaling.config.WorldSettingsConfig;
import com.ziggfreed.mmoskilltree.api.MMOSkillTreeAPI;
import com.ziggfreed.mmomobscaling.event.MobScalingDamageFilter;
import com.ziggfreed.mmomobscaling.event.MobScalingEffectApplySystem;
import com.ziggfreed.mmomobscaling.event.MobScalingHudSystem;
import com.ziggfreed.mmomobscaling.event.MobScalingLootDropSystem;
import com.ziggfreed.mmomobscaling.event.MobScalingOnHitSystem;
import com.ziggfreed.mmomobscaling.event.MobScalingPresenceSystem;
import com.ziggfreed.mmomobscaling.event.MobScalingSpawnHook;
import com.ziggfreed.mmomobscaling.event.MobScalingXpReward;

/**
 * Entry point for MMO Mob Scaling, a standalone open-world mob difficulty-scaling
 * companion to the MMO Skill Tree mod.
 *
 * <p>It loads {@link MobScalingConfig} and applies the ZERO-COST registration gate: when the
 * config is disabled, NO systems are registered, so the mod carries no per-tick cost at all.
 * When enabled it registers the scaling systems - the spawn-lock {@code MobScalingSpawnHook}
 * (rolls rarity/affixes + reconciles HP), the effect-reconcile {@code MobScalingEffectApplySystem}
 * (applies + sweeps the native aura / affix effects), the {@code MobScalingDamageFilter}
 * (the damage-multiply, pinned before armor + the MMO combat-XP read), and the
 * {@code MobScalingOnHitSystem} (lifesteal + the Freezing on-hit slow, in the inspect group) -
 * plus a kill-XP reward multiplier registered on the frozen {@code MMOSkillTreeAPI}.
 *
 * <p>Version story: this mod compiles against the local MMOSkillTree dev jar (which
 * already carries the frozen 1.5.0 API) while its manifest pins the runtime
 * requirement at MMOSkillTree {@code >=1.5.0}. See build.gradle for the rationale.
 */
public class MobScalingPlugin extends JavaPlugin {

    public static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static MobScalingPlugin instance;

    /** The registered transient {@code ScaledMobComponent} type (set in {@link #setup()} when enabled). */
    private ComponentType<EntityStore, ScaledMobComponent> scaledMobComponentType;

    @Nonnull
    public static MobScalingPlugin getInstance() {
        return instance;
    }

    /** The frozen scaled-mob component type; {@code null} until {@code setup()} registers it (mod enabled). */
    public ComponentType<EntityStore, ScaledMobComponent> getScaledMobComponentType() {
        return scaledMobComponentType;
    }

    public MobScalingPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
        safeInfo("MMO Mob Scaling initializing...");
    }

    @Override
    protected void setup() {
        // Load the override-based config from mods/MmoMobScaling/mob-scaling.json.
        try {
            MobScalingConfig cfg = MobScalingConfig.getInstance();
            cfg.setConfigPath(Paths.get("mods", "MmoMobScaling", "mob-scaling.json"));
            cfg.load();
            // Per-world settings (1.0.2): the scanned owner dir + the one-time migration off the
            // shipped 1.0.1 inline WorldOverrides array, then an owner-only fold (the jar/pack Worlds
            // store folds in later on LoadedAssetsEvent).
            WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
            worlds.setOwnerDir(Paths.get("mods", "MmoMobScaling", "worlds"));
            worlds.migrateLegacyOwnerOverrides(cfg.getConfigPath());
            worlds.refold();
        } catch (Throwable t) {
            safeWarn("Failed to load mob-scaling config, using defaults: " + t.getMessage());
        }

        // The admin command registers OUTSIDE the zero-cost gate on purpose (a command carries no
        // per-tick cost): /mobscaling purge exists precisely for the DISABLED/uninstalling case,
        // where no system runs to self-heal saved scaling residue.
        getCommandRegistry().registerCommand(new MobScalingCommand());

        // THE ZERO-COST REGISTRATION GATE: when disabled we register NO SYSTEMS, so the
        // mod has no per-tick cost at all. shouldRegisterSystems keeps the decision
        // unit-testable without a running server.
        if (!shouldRegisterSystems(MobScalingConfig.getInstance())) {
            safeWarn("Mob scaling disabled; no systems registered (zero per-tick cost).");
            return;
        }

        // Register the frozen scaled-mob component FIRST (transient: re-derived per spawn from the stable
        // seed, no codec) so the systems can resolve its ComponentType at construction.
        scaledMobComponentType = getEntityStoreRegistry().registerComponent(
                ScaledMobComponent.class, ScaledMobComponent::new);

        // Register the settings + rarity/affix asset stores + their LoadedAssetsEvent folds (real claimed
        // assets, pack-overridable). Only when enabled, so a disabled mod registers literally nothing.
        MobScalingAssetRegistrar.registerAll(this);

        // Scaling systems: the spawn-lock (HolderSystem) + effect reconcile (RefSystem) + the damage-multiply
        // filter + the on-hit behavioral reactions (inspect group, after ApplyDamage) + the death bonus loot
        // (native ItemDropList pulls inside the corpse window).
        getEntityStoreRegistry().registerSystem(new MobScalingSpawnHook());
        getEntityStoreRegistry().registerSystem(new MobScalingEffectApplySystem());
        getEntityStoreRegistry().registerSystem(new MobScalingDamageFilter());
        getEntityStoreRegistry().registerSystem(new MobScalingOnHitSystem());
        getEntityStoreRegistry().registerSystem(new MobScalingLootDropSystem());

        // Open-world group delta: track player presence per region grid (updates only on region cross)
        // so the spawn hook reads a CACHED per-region power scalar - never a per-spawn player scan.
        getEntityStoreRegistry().registerSystem(new MobScalingPresenceSystem());
        getEntityStoreRegistry().registerSystem(new MobScalingPresenceSystem.Removal());

        // Player-facing overlays: the zone-difficulty card + the crosshair mob inspector, driven by one
        // per-player ticking system (lazy HUD install self-heals world-transfer teardown). Registered
        // even when both HUDs start config-disabled so /mobscaling hud ... on works without a restart;
        // a disabled tick is two boolean reads.
        getEntityStoreRegistry().registerSystem(new MobScalingHudSystem());

        // Reward: register the kill-XP multiplier so a rarity kill pays more through the MMO's own kill path.
        MMOSkillTreeAPI.registerMobKillXpMultiplier(new MobScalingXpReward());

        safeInfo("Mob scaling enabled; systems registered.");
    }

    @Override
    protected void shutdown() {
        safeInfo("MMO Mob Scaling shutdown complete.");
    }

    /**
     * The zero-cost registration gate. Delegates to {@link MobScalingGate} - the pure
     * predicate is kept off this {@code JavaPlugin}-extending class so it stays loadable in
     * a plain unit-test JVM (loading this class in a unit JVM fails via the
     * {@code JavaPlugin} -> {@code PluginBase} -> {@code MetricsRegistry} static-init chain).
     * Systems register only when a non-null config is enabled.
     */
    public static boolean shouldRegisterSystems(@Nullable MobScalingConfig cfg) {
        return MobScalingGate.shouldRegisterSystems(cfg);
    }

    // ==================== Logging (guarded) ====================
    // The flogger-backed LOGGER can throw in a log-manager-less JVM, so guard each
    // call. On a live server these degrade to a no-op only if logging itself fails.

    private static void safeInfo(@Nonnull String message) {
        try {
            LOGGER.atInfo().log(message);
        } catch (Throwable ignored) {
            // logging must never take down the plugin
        }
    }

    private static void safeWarn(@Nonnull String message) {
        try {
            LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // logging must never take down the plugin
        }
    }
}

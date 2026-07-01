package com.ziggfreed.mmomobscaling;

import java.nio.file.Paths;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;

/**
 * Entry point for MMO Mob Scaling, a standalone open-world mob difficulty-scaling
 * companion to the MMO Skill Tree mod.
 *
 * <p>This v1.0.0 build is a Phase-1 skeleton: it loads {@link MobScalingConfig} and
 * applies the ZERO-COST registration gate. When the config is disabled, NO systems
 * are registered, so the mod carries no per-tick cost at all. The actual scaling
 * systems (spawn hook, damage filter, death listener) land in a later phase; nothing
 * is registered yet because those systems do not exist.
 *
 * <p>Version story: this mod compiles against the local MMOSkillTree dev jar (which
 * already carries the frozen 1.5.0 API) while its manifest pins the runtime
 * requirement at MMOSkillTree {@code >=1.5.0}. See build.gradle for the rationale.
 */
public class MobScalingPlugin extends JavaPlugin {

    public static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static MobScalingPlugin instance;

    @Nonnull
    public static MobScalingPlugin getInstance() {
        return instance;
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
        } catch (Throwable t) {
            safeWarn("Failed to load mob-scaling config, using defaults: " + t.getMessage());
        }

        // THE ZERO-COST REGISTRATION GATE: when disabled we register NOTHING, so the
        // mod has no per-tick cost at all. shouldRegisterSystems keeps the decision
        // unit-testable without a running server.
        if (!shouldRegisterSystems(MobScalingConfig.getInstance())) {
            safeWarn("Mob scaling disabled; no systems registered (zero per-tick cost).");
            return;
        }

        safeInfo("Mob scaling enabled; systems registered.");
        // Phase 5: getEntityStoreRegistry().registerSystem(new MobScalingSpawnHook());
        //          ... the spawn hook / damage filter / death listener land in Phase 5.
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

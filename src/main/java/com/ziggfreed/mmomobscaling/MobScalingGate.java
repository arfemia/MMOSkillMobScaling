package com.ziggfreed.mmomobscaling;

import javax.annotation.Nullable;

import com.ziggfreed.mmomobscaling.config.MobScalingConfig;

/**
 * The pure zero-cost registration predicate, kept OFF the {@code JavaPlugin}-extending
 * {@link MobScalingPlugin} so it is loadable in a plain unit-test JVM.
 *
 * <p>Loading {@link MobScalingPlugin} in a unit JVM fails (its {@code JavaPlugin} ->
 * {@code PluginBase} -> {@code MetricsRegistry} static-init chain throws without a running
 * server), so a test cannot reference that class. This helper holds the same decision the
 * plugin's {@code setup()} gate makes, and {@link MobScalingPlugin#shouldRegisterSystems}
 * delegates here, so the gate stays unit-testable.
 */
public final class MobScalingGate {

    private MobScalingGate() {}

    /**
     * Whether the scaling systems should be registered: only when a non-null config is
     * enabled. When false, the plugin registers NOTHING (zero per-tick cost).
     */
    public static boolean shouldRegisterSystems(@Nullable MobScalingConfig cfg) {
        return cfg != null && cfg.isEnabled();
    }
}

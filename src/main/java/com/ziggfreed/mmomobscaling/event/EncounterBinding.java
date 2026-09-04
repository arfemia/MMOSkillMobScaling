package com.ziggfreed.mmomobscaling.event;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.run.EncounterRuntime;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;

/**
 * The guarded read of "is this mob a live encounter's bound boss?", the second of the roll's two
 * skips. The answer is {@code EncounterRuntime.isBoundSubject}, a lock-free index read; the guard is
 * for a ziggfreed-common jar older than the boss framework, on which that class does not exist. The
 * first call on such a jar raises a {@link LinkageError}, which is caught ONCE, reported once, and
 * latched, so every later spawn rolls as if no framework were installed rather than paying the
 * throw again or refusing to load the mod.
 */
final class EncounterBinding {

    private static final AtomicBoolean FRAMEWORK_MISSING = new AtomicBoolean();

    private EncounterBinding() {
    }

    /** True when {@code ref} is the bound subject of a live run; false with no framework to ask. */
    static boolean isBoundSubject(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        if (FRAMEWORK_MISSING.get()) {
            return false;
        }
        try {
            return EncounterRuntime.isBoundSubject(store, ref);
        } catch (LinkageError e) {
            if (FRAMEWORK_MISSING.compareAndSet(false, true)) {
                safeWarn("EncounterRuntime is unavailable on this ziggfreed-common jar (needs 2.1.0 or newer); "
                        + "an encounter's bound boss is not recognised, so it is rolled like any other mob. Cause: " + e);
            }
            return false;
        }
    }

    private static void safeWarn(@Nonnull String message) {
        try {
            MobScalingPlugin.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less JVMs
        }
    }
}

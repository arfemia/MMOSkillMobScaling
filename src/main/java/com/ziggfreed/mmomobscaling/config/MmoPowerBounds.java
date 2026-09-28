package com.ziggfreed.mmomobscaling.config;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.mmoskilltree.api.MMOSkillTreeAPI;

/**
 * The ONE guarded read of what this mod can see of the MMO's power scale through the frozen
 * {@link MMOSkillTreeAPI}: the clamp floor and ceiling every consumer's power is bounded to, and one
 * player's own power. Every read answers {@code null} instead of throwing when the API is not there
 * (a unit JVM, or an older MMO jar without the getters), so a caller degrades to "not available" and
 * never fails: the boot-time caps cross-check treats {@code null} as clean, the admin page's read-only
 * MMO panel says so on screen. This mod never widens that API and never writes an MMO file; where the
 * numbers are edited is the MMO's own Power Level page and {@code mods/mmoskilltree/power-level.json}.
 */
public final class MmoPowerBounds {

    private MmoPowerBounds() {
    }

    /** The MMO's {@code Clamp.MinPower}, or {@code null} when unreadable. */
    @Nullable
    public static Double min() {
        try {
            return MMOSkillTreeAPI.getPowerLevelMin();
        } catch (Throwable t) {
            return null;
        }
    }

    /** The MMO's {@code Clamp.MaxPower}, or {@code null} when unreadable. */
    @Nullable
    public static Double max() {
        try {
            return MMOSkillTreeAPI.getPowerLevelMax();
        } catch (Throwable t) {
            return null;
        }
    }

    /** One player's clamped power right now, or {@code null} when unreadable. World-thread only. */
    @Nullable
    public static Double of(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            return MMOSkillTreeAPI.getPowerLevel(store, ref);
        } catch (Throwable t) {
            return null;
        }
    }
}

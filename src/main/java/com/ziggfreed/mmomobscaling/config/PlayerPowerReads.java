package com.ziggfreed.mmomobscaling.config;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.seam.EncounterSeams;

/**
 * The ONE guarded read of player power this mod makes: ziggfreed-common's player power seam, which a mod that
 * measures power its own way fills (MMO Skill Tree fills it with its Power Level) and which otherwise answers with
 * the library's own reading of worn gear, edited on the owner menu's Player Power screen. This mod reads one number
 * and its scale from it and never knows who answered beyond the name {@link #owner()} gives.
 *
 * <p>Every read answers {@code null} instead of throwing when the seam is not there (a ziggfreed-common older than
 * the one that has it, a mis-install), so a caller degrades to "not available" and never fails: the boot-time caps
 * cross-check treats {@code null} as clean, and the admin page's read-only panel says so on screen.
 */
public final class PlayerPowerReads {

    private PlayerPowerReads() {
    }

    /** The floor of the answering reading's scale, or {@code null} when unreadable. */
    @Nullable
    public static Double min() {
        try {
            return EncounterSeams.playerPowerMin();
        } catch (Throwable t) {
            return null;
        }
    }

    /** The ceiling of the answering reading's scale, or {@code null} when unreadable. */
    @Nullable
    public static Double max() {
        try {
            return EncounterSeams.playerPowerMax();
        } catch (Throwable t) {
            return null;
        }
    }

    /** One player's power right now, on that scale, or {@code null} when unreadable. World-thread only. */
    @Nullable
    public static Double of(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            return EncounterSeams.playerPower(store, ref);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Who answers player power, in plain words: the filling mod's name ({@code MMOSkillTree}), or the library's own
     * ({@code ziggfreedcommon}); {@code null} when unreadable.
     */
    @Nullable
    public static String owner() {
        try {
            return EncounterSeams.playerPowerOwner();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Does a mod fill the seam (so power is that mod's reading, not the library's gear reading)? False when unreadable. */
    public static boolean filled() {
        try {
            return EncounterSeams.isPlayerPowerFilled();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * One boot line saying whose reading power is and on what scale, the line that proves where this mod's scaling
     * reads power from.
     */
    @Nonnull
    public static String sourceLine() {
        Double min = min();
        Double max = max();
        String scale = min == null || max == null ? "scale unreadable"
                : "scale " + min + " to " + max;
        if (filled()) {
            return "Mob scaling reads player power from " + owner() + ", which fills ziggfreed-common's player power "
                    + "seam (" + scale + ").";
        }
        String owner = owner();
        if (owner == null) {
            return "Mob scaling cannot read player power: ziggfreed-common has no player power seam on this jar, so "
                    + "every region reads the place difficulty alone.";
        }
        return "Mob scaling reads player power from ziggfreed-common's own reading of worn gear, since no mod fills "
                + "its player power seam (" + scale + "; edited on /zigadmin, Player Power).";
    }
}

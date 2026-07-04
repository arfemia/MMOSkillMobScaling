package com.ziggfreed.mmomobscaling.hud;

import javax.annotation.Nonnull;

/**
 * The qualitative zone-threat tier shown on the zone-difficulty HUD, classified from the DELTA
 * between the effective local spawn difficulty and the viewing player's own power level (positive
 * delta = the zone outguns the player). Pure logic (no engine coupling) so the banding is
 * unit-testable; the thresholds are deliberately coarse - the HUD is a feel readout, not a
 * balance authority.
 *
 * <p>Each tier carries its display lang key ({@code scaling.hud.zone.tier.<id>}) and the
 * {@code TextColor} hex the HUD pushes with it.
 */
public enum ZoneTier {

    TRIVIAL("trivial", "#9e9e9e"),
    EASY("easy", "#81c784"),
    FAIR("fair", "#e0e0e0"),
    HARD("hard", "#ffb74d"),
    DEADLY("deadly", "#e57373");

    /** Delta at or below which the zone is TRIVIAL for the viewer. */
    private static final double TRIVIAL_MAX_DELTA = -15.0;
    /** Delta at or below which the zone is EASY (above trivial). */
    private static final double EASY_MAX_DELTA = -5.0;
    /** Delta below which the zone is FAIR (above easy); HARD from here up to {@link #DEADLY_MIN_DELTA}. */
    private static final double FAIR_MAX_DELTA = 5.0;
    /** Delta at or above which the zone is DEADLY. */
    private static final double DEADLY_MIN_DELTA = 15.0;

    @Nonnull
    private final String id;
    @Nonnull
    private final String colorHex;

    ZoneTier(@Nonnull String id, @Nonnull String colorHex) {
        this.id = id;
        this.colorHex = colorHex;
    }

    /** Classify the (difficulty - playerPower) delta into a tier. */
    @Nonnull
    public static ZoneTier fromDelta(double delta) {
        if (delta <= TRIVIAL_MAX_DELTA) {
            return TRIVIAL;
        }
        if (delta <= EASY_MAX_DELTA) {
            return EASY;
        }
        if (delta < FAIR_MAX_DELTA) {
            return FAIR;
        }
        if (delta < DEADLY_MIN_DELTA) {
            return HARD;
        }
        return DEADLY;
    }

    /** The lang key for this tier's display word ({@code scaling.hud.zone.tier.<id>}). */
    @Nonnull
    public String langKey() {
        return "scaling.hud.zone.tier." + id;
    }

    /** The {@code TextColor} hex pushed alongside the tier word. */
    @Nonnull
    public String colorHex() {
        return colorHex;
    }
}

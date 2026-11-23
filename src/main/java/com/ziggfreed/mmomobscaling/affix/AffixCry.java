package com.ziggfreed.mmomobscaling.affix;

import javax.annotation.Nullable;

/**
 * A war cry an affix gives its mob (an affix asset's {@code Cry} group), copied in shape from the game's pack enrage
 * ({@code Template_Dungeon_Goblin_Scrapper.json}): once the mob has turned on a target, it stands and cries for
 * {@link #windupSeconds} wearing {@link #castEffectId} (the telegraph players see coming, with
 * {@link #soundEventId} played where it stands), then puts {@link #buffEffectId} for {@link #buffSeconds} on itself
 * and on every creature within {@link #radius} blocks that is hostile to its target, and cries again
 * {@link #repeatMinSeconds} to {@link #repeatMaxSeconds} later while it still fights. A mob already wearing the buff
 * (another crier's) holds its own cry until the buff runs out, as the game's scrapper does.
 *
 * <p>Pure data: every number is authored on the affix asset (its owner layer, {@code mods/MmoMobScaling/affixes.json},
 * and the CommonLib owner form {@code Mmoscaling_Affix_Cries} edit it). Sanitised here so a bad value never stalls a
 * mob: no negative time or reach, a repeat window that is never backwards.
 *
 * @param windupSeconds    how long the mob cries before the buff lands (0 lands it at once)
 * @param castEffectId     the effect the mob wears while it cries, for the wind-up; null for none
 * @param soundEventId     the sound played where the mob stands as the cry begins; null for a silent cry
 * @param buffEffectId     the effect the cry puts on the mob and the creatures it rallies
 * @param buffSeconds      how long the buff lasts: the cry's length
 * @param radius           how far from the mob, in blocks, a creature is rallied
 * @param repeatMinSeconds the shortest wait after a cry before the next
 * @param repeatMaxSeconds the longest wait after a cry before the next
 */
public record AffixCry(double windupSeconds, @Nullable String castEffectId, @Nullable String soundEventId,
        @Nullable String buffEffectId, double buffSeconds, double radius, double repeatMinSeconds,
        double repeatMaxSeconds) {

    public AffixCry {
        windupSeconds = nonNegative(windupSeconds);
        castEffectId = blankToNull(castEffectId);
        soundEventId = blankToNull(soundEventId);
        buffEffectId = blankToNull(buffEffectId);
        buffSeconds = nonNegative(buffSeconds);
        radius = nonNegative(radius);
        repeatMinSeconds = nonNegative(repeatMinSeconds);
        repeatMaxSeconds = Math.max(repeatMinSeconds, nonNegative(repeatMaxSeconds));
    }

    /** The wind-up in milliseconds. */
    public long windupMillis() {
        return Math.round(windupSeconds * 1000.0);
    }

    /**
     * The wait before the next cry, in milliseconds, for a uniform draw {@code u} in {@code [0, 1)}: the point
     * {@code u} of the way from {@code repeatMinSeconds} to {@code repeatMaxSeconds} (a fixed wait when the two are
     * equal; a {@code u} outside the range is held to it).
     */
    public long nextRepeatMillis(double u) {
        double t = Double.isFinite(u) ? Math.max(0.0, Math.min(1.0, u)) : 0.0;
        double seconds = repeatMinSeconds + (repeatMaxSeconds - repeatMinSeconds) * t;
        return Math.round(seconds * 1000.0);
    }

    /** Does this cry do anything at all: a buff to put on, for some time, on someone in reach (the crier counts)? */
    public boolean rallies() {
        return buffEffectId != null && buffSeconds > 0.0;
    }

    private static double nonNegative(double v) {
        return Double.isFinite(v) ? Math.max(0.0, v) : 0.0;
    }

    @Nullable
    private static String blankToNull(@Nullable String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}

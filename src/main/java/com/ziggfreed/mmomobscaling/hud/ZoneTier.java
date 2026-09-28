package com.ziggfreed.mmomobscaling.hud;

import javax.annotation.Nonnull;

import com.ziggfreed.mmomobscaling.scaling.MobScaleFold.DifficultyStatCurve;

/**
 * The qualitative zone-threat tier shown on the zone-difficulty HUD, classified from the THREAT RATIO
 * between the local spot difficulty and the viewing player's own power,
 * {@code outFactor(difficulty) / outFactor(power)}, both read off the SAME {@link DifficultyStatCurve}
 * the spawn fold evaluates a plain mob's hit on, so the word on the card and the mob's damage can never
 * disagree. Pure logic (no engine coupling); the banding is unit-tested.
 *
 * <p><b>What the ratio is.</b> Parity holds a matched fight (a plain mob at the player's own power) to a
 * constant time-to-die, and time-to-die is inversely proportional to the mob's outgoing multiplier, so
 * the ratio is exactly how many times FASTER the player dies here than in a matched fight: a ratio of 2
 * halves the time-to-die, a ratio of 0.5 doubles it. It is a ratio and not a difference because the damage
 * axis is a curve that starts from 1: the same fifteen difficulty points multiply the hit by far more over
 * a low power than over a high one, so no fixed delta means the same fight at every power, and the ratio
 * does by construction (a matched fight is 1.0 at every power, whatever the curve).
 *
 * <p><b>Where the bands sit, derived from time-to-die.</b>
 * <ul>
 *   <li>FAIR: the player dies at most a QUARTER faster, or at most a quarter slower, than in a matched
 *       fight, the ratio band {@code (1 / 1.25, 1.25)} ({@link #FAIR_TOLERANCE}). The quarter is a quarter
 *       of the RATE, which is the quantity the curve moves; read as duration it is a fifth less time at the
 *       hard edge and a quarter more at the easy one. The tolerance is geometric so a zone a quarter
 *       softer and a zone a quarter harder read the same distance from matched. A quarter is the first
 *       clean fraction above the model's own noise: two builds at one power already differ by more than a
 *       tenth in what they can take, so a narrower band would call the spread between two players a
 *       different fight.</li>
 *   <li>HARD and EASY: past the fair edge and up to a DOUBLING of it, {@code ratio < 1.25 * 2}
 *       ({@link #BAND_MULTIPLE}), so dying up to 2.5 times faster, or lasting up to 2.5 times longer. A
 *       doubling of the fair EDGE (1.25 to 2.5, not of the 0.25 departure from matched) is the smallest
 *       step a player feels in a fight a handful of hits long:
 *       under parity a matched plain mob takes a fixed fraction of the player's health per hit, so each
 *       band edge takes about a hit off the count.</li>
 *   <li>DEADLY and TRIVIAL: anything past that doubling.</li>
 * </ul>
 * The boundaries are a classification of a ratio, not a tuning of any fight: they move no mob's health,
 * damage, loot or XP, and retuning the curve moves zones between bands without moving the boundaries,
 * which is why they are derived here and not authored in an asset.
 *
 * <p><b>Edge cases, each deliberate.</b> The curve is read at the RAW power, never clamped to
 * {@code Difficulty.MinCap} / {@code MaxCap}: the caps bound what a spot can be, not what a player is, so a
 * power past the cap reads a capped zone as easy, which is true, since the zone cannot reach them. A power
 * BELOW the floor is not a separate case: the floor and the curve's origin are the same difficulty, so such
 * a player reads the floor as a matched fight, which is what the weakest zone is for the weakest character.
 * A power at or below the curve's origin (0, negative,
 * or the MMO's own minimum, which is what {@code getPowerLevel} answers for a character it holds no data on)
 * is read at difficulty 1, the weakest fight the curve expresses, so an unknown character gets the fresh
 * character's word and never an EASY by accident. A curve whose outgoing factor has railed
 * ({@code MaxOutDamageMult}) is read railed, exactly as the fold reads it: two zones both past the rail read
 * alike because the mob really does hit alike there, and a zone past the rail against a power below it
 * reads the rail's own lead and no more. The rail therefore caps what the word can say, which is the rail
 * flattening the fight rather than the card misreading it; a rail set past the curve's own top, where the
 * shipped one sits, is never reached by a plain spot. The identity curve (a flat damage axis) reads FAIR
 * everywhere for the same reason: every zone hits alike. A ratio that is not a positive finite number (a
 * corrupt input; no live path produces one, since both factors are at least 1) carries no information and
 * reads as matched rather than falling through to DEADLY.
 *
 * <p>Each tier carries its display lang key ({@code mmomobscaling.hud.zone.tier.<id>}) and the
 * {@code TextColor} hex the HUD pushes with it.
 */
public enum ZoneTier {

    TRIVIAL("trivial", "#9e9e9e"),
    EASY("easy", "#81c784"),
    FAIR("fair", "#e0e0e0"),
    HARD("hard", "#ffb74d"),
    DEADLY("deadly", "#e57373");

    /**
     * FAIR spans time-to-die within this fraction of a matched fight either way: the ratio band
     * {@code (1 / FAIR_TOLERANCE, FAIR_TOLERANCE)}, a quarter.
     */
    static final double FAIR_TOLERANCE = 1.25;
    /**
     * Each band past FAIR spans a doubling of the fair EDGE (1.25 to 2.5), not of the departure from
     * matched: HARD and EASY run out to
     * {@code FAIR_TOLERANCE * BAND_MULTIPLE} (2.5 times faster, or 2.5 times longer), DEADLY and TRIVIAL lie
     * beyond.
     */
    static final double BAND_MULTIPLE = 2.0;

    /** Ratio at or above which the zone is HARD (dying more than a quarter faster than matched). */
    private static final double HARD_MIN_RATIO = FAIR_TOLERANCE;
    /** Ratio at or above which the zone is DEADLY (dying 2.5 times faster, or more). */
    private static final double DEADLY_MIN_RATIO = FAIR_TOLERANCE * BAND_MULTIPLE;
    /** Ratio at or below which the zone is EASY (lasting more than a quarter longer than matched). */
    private static final double EASY_MAX_RATIO = 1.0 / HARD_MIN_RATIO;
    /** Ratio at or below which the zone is TRIVIAL (lasting 2.5 times longer, or more). */
    private static final double TRIVIAL_MAX_RATIO = 1.0 / DEADLY_MIN_RATIO;

    @Nonnull
    private final String id;
    @Nonnull
    private final String colorHex;

    ZoneTier(@Nonnull String id, @Nonnull String colorHex) {
        this.id = id;
        this.colorHex = colorHex;
    }

    /**
     * The threat ratio of a spot at {@code difficulty} for a viewer at {@code playerPower}:
     * {@code curve.outFactor(difficulty) / curve.outFactor(playerPower)}, how many times faster the viewer
     * dies here than in a matched fight. {@code curve} is the world's own {@code Difficulty.StatCurve}, the
     * one its spawns fold on. Neither input is clamped (see the class comment for why); the curve itself
     * floors both at difficulty 1.
     */
    public static double threatRatio(@Nonnull DifficultyStatCurve curve, double difficulty, double playerPower) {
        return curve.outFactor(difficulty) / curve.outFactor(playerPower);
    }

    /** Classify a threat ratio ({@link #threatRatio}) into a tier. A matched fight (1.0) is FAIR. */
    @Nonnull
    public static ZoneTier fromThreatRatio(double ratio) {
        if (!(ratio > 0.0 && ratio < Double.POSITIVE_INFINITY)) {
            return FAIR; // NaN or infinite: no information, never an accidental DEADLY
        }
        if (ratio <= TRIVIAL_MAX_RATIO) {
            return TRIVIAL;
        }
        if (ratio <= EASY_MAX_RATIO) {
            return EASY;
        }
        if (ratio < HARD_MIN_RATIO) {
            return FAIR;
        }
        if (ratio < DEADLY_MIN_RATIO) {
            return HARD;
        }
        return DEADLY;
    }

    /** {@link #fromThreatRatio} of {@link #threatRatio}: the tier the zone card shows. */
    @Nonnull
    public static ZoneTier classify(@Nonnull DifficultyStatCurve curve, double difficulty, double playerPower) {
        return fromThreatRatio(threatRatio(curve, difficulty, playerPower));
    }

    /** The lang key for this tier's display word ({@code mmomobscaling.hud.zone.tier.<id>}). */
    @Nonnull
    public String langKey() {
        return "mmomobscaling.hud.zone.tier." + id;
    }

    /** The {@code TextColor} hex pushed alongside the tier word. */
    @Nonnull
    public String colorHex() {
        return colorHex;
    }
}

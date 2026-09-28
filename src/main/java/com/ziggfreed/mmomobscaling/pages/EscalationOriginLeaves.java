package com.ziggfreed.mmomobscaling.pages;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The one place the two escalation-origin fields collected off a form are turned into the leaves the codec
 * wants. Pure and engine-free, so it is unit-tested apart from the page.
 *
 * <p>An origin coordinate is the one optional NUMBER on the Global tab, and it may be negative. The shared
 * form kinds cannot say both at once: a NUMBER field rejects a minus sign, an INT field refuses to be blank
 * on a form that collects with {@code blankIsInherit = false} (the Global tab's rule, where nothing else is
 * optional), and neither kind has an "unset" spelling there. So both origin fields are TEXT specs (blank is
 * always legal for TEXT, on every form), and this step does the parsing the form would have done for a
 * number: a blank collects as a {@code null} leaf (remove the override, so the axis reads the world's spawn
 * point again), a finite decimal of either sign collects as the {@link Double} the codec's
 * {@code Codec.DOUBLE} leaf re-decodes unchanged, and anything else names the offending leaf so the page
 * can refuse the save with the field's own label.
 */
final class EscalationOriginLeaves {

    /** The dotted PascalCase leaf paths the two origin fields write, on the global and the world form alike. */
    static final String X_LEAF = "Difficulty.DistanceEscalation.Origin.X";
    static final String Z_LEAF = "Difficulty.DistanceEscalation.Origin.Z";
    static final List<String> LEAVES = List.of(X_LEAF, Z_LEAF);

    private EscalationOriginLeaves() {
    }

    /**
     * Normalize the origin leaves in place: for each of {@link #LEAVES} the map carries, a {@code null} or
     * blank value becomes {@code null} (the override removed), a numeric string becomes its {@link Double},
     * a {@link Number} stays a {@link Double}. Leaves the map does not carry are left absent (a form that
     * never collected them removes nothing). Every other leaf is untouched.
     *
     * @return the leaf path of the first origin value that is not a finite coordinate, or {@code null} when
     *         every origin leaf normalized; on a non-null answer the map is left as it was
     */
    @Nullable
    static String normalize(@Nonnull Map<String, Object> leaves) {
        for (String leaf : LEAVES) {
            if (leaves.containsKey(leaf) && isInvalid(parse(leaves.get(leaf)))) {
                return leaf;
            }
        }
        for (String leaf : LEAVES) {
            if (leaves.containsKey(leaf)) {
                leaves.put(leaf, parse(leaves.get(leaf)));
            }
        }
        return null;
    }

    /**
     * The value {@link #parse} answers for anything that is not a coordinate. It is compared BY VALUE
     * ({@link #isInvalid}), never by reference: a {@code cond ? double : Double} expression unboxes and
     * re-boxes, so an identity check against a shared marker silently misses. NaN can never be a legal
     * answer because every non-finite input is refused, so the value is unambiguous.
     */
    private static final double INVALID = Double.NaN;

    private static boolean isInvalid(@Nullable Double parsed) {
        return parsed != null && parsed.isNaN();
    }

    /** A coordinate ({@link Double}), {@code null} for blank/absent, or NaN ({@link #INVALID}) for anything else. */
    @Nullable
    private static Double parse(@Nullable Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : INVALID;
        }
        if (raw instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                double d = Double.parseDouble(trimmed);
                return Double.isFinite(d) ? d : INVALID;
            } catch (NumberFormatException ex) {
                return INVALID;
            }
        }
        return INVALID;
    }
}

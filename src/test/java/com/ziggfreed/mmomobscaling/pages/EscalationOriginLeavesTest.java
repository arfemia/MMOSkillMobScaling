package com.ziggfreed.mmomobscaling.pages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The origin fields' round trip off both forms: the two TEXT fields collect as strings, and this step turns
 * them into what the codec's DOUBLE leaves want. A blank (or a {@code null} from the world form) removes the
 * override so the axis reads the world spawn point again; a coordinate of either sign becomes its Double; a
 * non-number names the leaf and leaves the map untouched, so the page can refuse the save by field.
 */
class EscalationOriginLeavesTest {

    private static Map<String, Object> collected(Object x, Object z) {
        Map<String, Object> leaves = new LinkedHashMap<>();
        leaves.put("Difficulty.Floor", 45.0); // an unrelated leaf, must survive untouched
        leaves.put(EscalationOriginLeaves.X_LEAF, x);
        leaves.put(EscalationOriginLeaves.Z_LEAF, z);
        return leaves;
    }

    @Test
    void blankOnTheGlobalFormRemovesTheOverride() {
        // The Global tab collects a blank TEXT as "" (its blankIsInherit is false); that must mean "unset".
        Map<String, Object> leaves = collected("", "  ");
        assertNull(EscalationOriginLeaves.normalize(leaves), "blanks are legal");
        assertTrue(leaves.containsKey(EscalationOriginLeaves.X_LEAF), "the leaf is still written (as a removal)");
        assertNull(leaves.get(EscalationOriginLeaves.X_LEAF), "blank X -> null leaf: the spawn point again");
        assertNull(leaves.get(EscalationOriginLeaves.Z_LEAF), "blank Z -> null leaf");
        assertEquals(45.0, leaves.get("Difficulty.Floor"), "an unrelated leaf is untouched");
    }

    @Test
    void nullFromTheWorldFormStaysNull() {
        // The world form collects a blank TEXT as null already (blankIsInherit true).
        Map<String, Object> leaves = collected(null, "12");
        assertNull(EscalationOriginLeaves.normalize(leaves));
        assertNull(leaves.get(EscalationOriginLeaves.X_LEAF), "inherit stays inherit");
        assertEquals(12.0, leaves.get(EscalationOriginLeaves.Z_LEAF), "the other axis parses independently");
    }

    @Test
    void coordinatesOfEitherSignParseToDoubles() {
        Map<String, Object> leaves = collected(" -4000 ", "1200.5");
        assertNull(EscalationOriginLeaves.normalize(leaves));
        assertEquals(Double.class, leaves.get(EscalationOriginLeaves.X_LEAF).getClass(),
                "a coordinate boxes as the Double a Codec.DOUBLE leaf re-decodes unchanged");
        assertEquals(-4000.0, leaves.get(EscalationOriginLeaves.X_LEAF), "a minus sign is a coordinate, not an error");
        assertEquals(1200.5, leaves.get(EscalationOriginLeaves.Z_LEAF));
    }

    @Test
    void aNonNumberNamesTheLeafAndChangesNothing() {
        Map<String, Object> leaves = collected("-100", "north");
        assertEquals(EscalationOriginLeaves.Z_LEAF, EscalationOriginLeaves.normalize(leaves),
                "the offending leaf is named so the page can point at the field");
        assertEquals("-100", leaves.get(EscalationOriginLeaves.X_LEAF), "on a refusal the map is left as collected");
        assertEquals("north", leaves.get(EscalationOriginLeaves.Z_LEAF));

        Map<String, Object> infinite = collected("Infinity", "0");
        assertEquals(EscalationOriginLeaves.X_LEAF, EscalationOriginLeaves.normalize(infinite),
                "a non-finite number is not a coordinate");
    }

    @Test
    void aFormThatNeverCollectedTheLeavesRemovesNothing() {
        Map<String, Object> leaves = new LinkedHashMap<>();
        leaves.put("Difficulty.Floor", 45.0);
        assertNull(EscalationOriginLeaves.normalize(leaves));
        assertFalse(leaves.containsKey(EscalationOriginLeaves.X_LEAF), "no origin leaf is invented");
        assertEquals(1, leaves.size());
    }
}

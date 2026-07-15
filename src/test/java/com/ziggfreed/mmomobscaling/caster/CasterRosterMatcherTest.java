package com.ziggfreed.mmomobscaling.caster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Pure precedence tests for {@link CasterRosterMatcher}: exact roleId > longest glob > first. */
class CasterRosterMatcherTest {

    private static CasterRoster exact(String id, String roleId) {
        return new CasterRoster(id, roleId, null, List.of());
    }

    private static CasterRoster glob(String id, String roleGlob) {
        return new CasterRoster(id, null, roleGlob, List.of());
    }

    @Test
    void exactRoleIdBeatsGlob() {
        CasterRoster wide = glob("wide", "Dragon_*");
        CasterRoster narrow = exact("narrow", "Dragon_Fire");
        CasterRoster match = CasterRosterMatcher.match("Dragon_Fire", List.of(wide, narrow));
        assertSame(narrow, match, "an exact Role.Id match wins over any matching Glob");
    }

    @Test
    void exactRoleIdIsCaseInsensitive() {
        CasterRoster r = exact("dragon", "Dragon_Fire");
        assertSame(r, CasterRosterMatcher.match("dragon_fire", List.of(r)), "exact match is case-insensitive");
    }

    @Test
    void longestMatchingGlobWins() {
        CasterRoster broad = glob("broad", "Dragon_*");
        CasterRoster specific = glob("specific", "Dragon_Fire_*");
        // Longer authored pattern wins when both match.
        CasterRoster match = CasterRosterMatcher.match("Dragon_Fire_Elite", List.of(broad, specific));
        assertSame(specific, match, "the longer (more specific) matching glob wins");

        // The broad pattern still catches a role the specific one does not.
        assertSame(broad, CasterRosterMatcher.match("Dragon_Frost", List.of(broad, specific)),
                "a role only the broader glob matches resolves to it");
    }

    @Test
    void tiedGlobLengthKeepsFirstInInputOrder() {
        CasterRoster first = glob("first", "Drag*");
        CasterRoster second = glob("second", "Drag*"); // identical pattern length (and text) - a dead-content tie
        assertSame(first, CasterRosterMatcher.match("Dragon_Fire", List.of(first, second)),
                "an exact tie in glob length/text keeps whichever roster appears first in the caller's list");
        assertSame(second, CasterRosterMatcher.match("Dragon_Fire", List.of(second, first)),
                "reversing the input order flips which one 'first' resolves to");
    }

    @Test
    void noMatchReturnsNull() {
        CasterRoster r = exact("skeleton", "Skeleton_Fighter");
        assertNull(CasterRosterMatcher.match("Dragon_Fire", List.of(r)), "no matching roster resolves to null");
        assertNull(CasterRosterMatcher.match("anything", List.of()), "an empty roster list resolves to null");
    }

    @Test
    void invalidSelectorRosterNeverMatches() {
        // Neither Id nor Glob authored: hasValidRoleSelector() is false, and the matcher must never
        // pick it up via either path (it has no roleId, and hasRoleGlob() is false too).
        CasterRoster broken = new CasterRoster("broken", null, null, List.of());
        assertNull(CasterRosterMatcher.match("Dragon_Fire", List.of(broken)), "a selector-less roster never matches");

        // Both authored: still resolvable via the Id path (roleId wins the internal check first).
        CasterRoster both = new CasterRoster("both", "Dragon_Fire", "Dragon_*", List.of());
        assertEquals(both, CasterRosterMatcher.match("Dragon_Fire", List.of(both)),
                "a roster authoring both selectors still matches via its Id (content-invalid, but not inert)");
    }
}

package com.ziggfreed.mmomobscaling.family;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pure tests for the ROLE half of {@link FamilyFilter} (glob allow/deny) plus its allow-all / unrestricted
 * predicates. The GROUP half needs a live asset map, so it is exercised in-game (see the plan's verification
 * recipe), not here.
 */
class FamilyFilterTest {

    private static FamilyFilter filter(List<String> allowGroups, List<String> denyGroups,
            List<String> allowRoles, List<String> denyRoles) {
        return new FamilyFilter(allowGroups, denyGroups, allowRoles, denyRoles);
    }

    @Test
    void allowAllIsUnrestricted() {
        assertTrue(FamilyFilter.ALLOW_ALL.isUnrestricted(), "the empty filter is unrestricted");
        assertTrue(FamilyFilter.ALLOW_ALL.allowsAll(), "the empty filter allows every mob");
    }

    @Test
    void emptyAllowSideAllowsAll_evaluatedOverBothLists() {
        // A deny-only filter still has an empty ALLOW side (both allow lists empty) -> allowsAll true.
        FamilyFilter denyOnly = filter(List.of(), List.of("Bosses"), List.of(), List.of("*Boss*"));
        assertTrue(denyOnly.allowsAll(), "empty allow side (both lists) = allow all");
        assertFalse(denyOnly.isUnrestricted(), "a deny entry means it is NOT fully unrestricted");
    }

    @Test
    void allowRolesNonEmptyIsNotAllowAll() {
        // AllowGroups set but AllowRoles empty must NOT read as allow-all (the empty list does not widen).
        FamilyFilter spidersOnly = filter(List.of("Spiders"), List.of(), List.of(), List.of());
        assertFalse(spidersOnly.allowsAll(), "a non-empty allow SIDE is a real restriction");
    }

    @Test
    void allowsRoleGlob() {
        FamilyFilter f = filter(List.of(), List.of(), List.of("Spider*"), List.of());
        assertTrue(f.allowsRole("Spider_Cave"), "role glob admits the family");
        assertFalse(f.allowsRole("Wolf"), "role glob excludes others");
    }

    @Test
    void deniesRoleGlob() {
        FamilyFilter f = filter(List.of(), List.of(), List.of(), List.of("Spider*"));
        assertTrue(f.deniesRole("Spider_Cave"), "deny glob catches the family");
        assertFalse(f.deniesRole("Wolf"), "deny glob leaves others alone");
    }

    @Test
    void blankPatternsAreIgnored() {
        FamilyFilter f = filter(List.of(), List.of(), List.of("", "  "), List.of(""));
        assertFalse(f.allowsRole("Spider_Cave"), "blank allow patterns never match");
        assertFalse(f.deniesRole("Spider_Cave"), "blank deny patterns never match");
    }
}

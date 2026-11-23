package com.ziggfreed.mmomobscaling.scaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * The party rule's mechanics on test-authored numbers: who counts (a player exactly at the radius does, one just past
 * it does not), the three rungs for exactly two, exactly three and four or more, the boss rule's flat step per player
 * up to its cap, and the switched-off rule. No shipped number is restated here.
 */
class PartyRuleTest {

    private static final double EPS = 1e-9;

    /** Radius 40, rungs 1.5 / 2.0 / 2.5, a boss +0.25 a player up to four. */
    private static final PartyRule RULE = new PartyRule(true, 40.0, 1.5, 2.0, 2.5, 0.25, 4, List.of("Pack"));

    private static final Vector3d MOB = new Vector3d(100.0, 64.0, -20.0);

    private static Vector3d offset(double dx, double dy, double dz) {
        return new Vector3d(MOB.x + dx, MOB.y + dy, MOB.z + dz);
    }

    @Test
    void aPlayerExactlyAtTheRadiusCountsAndOneJustPastItDoesNot() {
        assertEquals(1, RULE.partySize(MOB, List.of(offset(40.0, 0.0, 0.0))), "exactly at the edge counts");
        assertEquals(0, RULE.partySize(MOB, List.of(offset(40.001, 0.0, 0.0))), "just past the edge does not");
        assertEquals(1, RULE.partySize(MOB, List.of(offset(0.0, 0.0, -40.0))), "the edge holds on every axis");
        // A diagonal 3-4-5 at scale 8 is exactly 40 away; the reach is a sphere, not a box.
        assertEquals(1, RULE.partySize(MOB, List.of(offset(24.0, 0.0, 32.0))), "a diagonal at the radius counts");
        assertEquals(0, RULE.partySize(MOB, List.of(offset(30.0, 0.0, 30.0))), "the box corner inside 40 per axis does not");
    }

    @Test
    void thePartyIsEveryPlayerInReachAndNoOneElse() {
        List<Vector3d> players = Arrays.asList(offset(1, 0, 0), offset(0, 30, 0), null, offset(50, 0, 0),
                offset(-39, 0, 0));
        assertEquals(3, RULE.partySize(MOB, players), "three in reach; one past it and a missing position count nothing");
        assertEquals(0, RULE.partySize(MOB, List.of()), "nobody near is a party of none");
    }

    @Test
    void aPartyOfTwoThreeOrFourAndMoreTakesItsRung() {
        assertEquals(1.0, RULE.healthMultiplier(0, false), EPS, "nobody near changes nothing");
        assertEquals(1.0, RULE.healthMultiplier(1, false), EPS, "one player changes nothing");
        assertEquals(1.5, RULE.healthMultiplier(2, false), EPS, "exactly two");
        assertEquals(2.0, RULE.healthMultiplier(3, false), EPS, "exactly three");
        assertEquals(2.5, RULE.healthMultiplier(4, false), EPS, "four");
        assertEquals(2.5, RULE.healthMultiplier(9, false), EPS, "four and more share the last rung");
    }

    @Test
    void aBossAddsTheSameStepForEachPlayerPastTheFirstUpToItsCap() {
        assertEquals(1.0, RULE.healthMultiplier(1, true), EPS, "a lone player leaves a boss as it is");
        assertEquals(1.25, RULE.healthMultiplier(2, true), EPS, "one player past the first");
        assertEquals(1.50, RULE.healthMultiplier(3, true), EPS, "two past the first");
        assertEquals(1.75, RULE.healthMultiplier(4, true), EPS, "three past the first");
        assertEquals(1.75, RULE.healthMultiplier(8, true), EPS, "players past the cap add nothing");
        double step2 = RULE.healthMultiplier(3, true) - RULE.healthMultiplier(2, true);
        double step1 = RULE.healthMultiplier(2, true) - RULE.healthMultiplier(1, true);
        assertEquals(step1, step2, EPS, "additive: every player adds the same amount");

        PartyRule wide = new PartyRule(true, 40.0, 1.5, 2.0, 2.5, 0.25, 10, List.of());
        assertEquals(1.0 + 0.25 * 7, wide.healthMultiplier(8, true), EPS, "an owner's higher cap counts more players");
    }

    @Test
    void aSwitchedOffRuleChangesNothing() {
        PartyRule off = new PartyRule(false, 40.0, 1.5, 2.0, 2.5, 0.25, 4, List.of());
        assertEquals(1.0, off.healthMultiplier(4, false), EPS);
        assertEquals(1.0, off.healthMultiplier(4, true), EPS);
        assertEquals(1.0, PartyRule.NONE.healthMultiplier(4, false), EPS, "the fail-safe counts no one");
        assertFalse(PartyRule.NONE.enabled());
    }

    @Test
    void aRuleNeverShrinksAMobOrReachesBackwards() {
        PartyRule odd = new PartyRule(true, -5.0, 0.5, Double.NaN, 3.0, -1.0, 0, Arrays.asList(" Pack ", "", null));
        assertEquals(0.0, odd.radius(), EPS, "a negative reach reads as none");
        assertEquals(1.0, odd.healthMultiplier(2, false), EPS, "a rung under 1.0 never makes a mob softer");
        assertEquals(1.0, odd.healthMultiplier(3, false), EPS, "a broken rung reads as 1.0");
        assertEquals(1.0, odd.healthMultiplier(4, true), EPS, "a negative boss step adds nothing");
        assertEquals(List.of("Pack"), odd.skipGroups(), "the skip list is trimmed, its blanks dropped");
        assertTrue(odd.within(0.0), "a player standing on the mob is within even a zero reach");
    }
}

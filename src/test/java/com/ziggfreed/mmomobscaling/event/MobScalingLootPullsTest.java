package com.ziggfreed.mmomobscaling.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Verifies the pure pull-count math in {@link MobScalingLootDropSystem#lootPulls(double, double)}: floor of
 * the folded loot multiplier guaranteed, one extra when the per-mob roll lands under the fractional part.
 */
class MobScalingLootPullsTest {

    @Test
    void wholeMultipliersAreExactPullCounts() {
        assertEquals(1, MobScalingLootDropSystem.lootPulls(1.0, 0.0), "1.0 -> always exactly 1");
        assertEquals(1, MobScalingLootDropSystem.lootPulls(1.0, 0.999), "no fractional part -> no extra pull");
        assertEquals(2, MobScalingLootDropSystem.lootPulls(2.0, 0.999), "2.0 -> always exactly 2");
        assertEquals(3, MobScalingLootDropSystem.lootPulls(3.0, 0.0), "the fold cap 3.0 -> 3 pulls");
    }

    @Test
    void fractionalPartBuysAnExtraPullUnderTheRoll() {
        assertEquals(2, MobScalingLootDropSystem.lootPulls(1.25, 0.10), "roll under frac -> extra pull");
        assertEquals(1, MobScalingLootDropSystem.lootPulls(1.25, 0.25), "roll at frac -> no extra (strict <)");
        assertEquals(1, MobScalingLootDropSystem.lootPulls(1.25, 0.90), "roll over frac -> no extra");
        assertEquals(2, MobScalingLootDropSystem.lootPulls(1.5, 0.49), "epic 1.5 pays 2 about half the time");
    }

    @Test
    void degenerateMultipliersAreSafe() {
        assertEquals(0, MobScalingLootDropSystem.lootPulls(0.0, 0.0), "zero mult -> no pulls");
        assertEquals(0, MobScalingLootDropSystem.lootPulls(-1.0, 0.0), "negative mult -> no pulls");
        assertEquals(1, MobScalingLootDropSystem.lootPulls(0.5, 0.25), "sub-1 mult -> a chance at one pull");
        assertEquals(0, MobScalingLootDropSystem.lootPulls(0.5, 0.75), "sub-1 mult can pay nothing");
    }
}

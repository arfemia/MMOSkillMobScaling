package com.ziggfreed.mmomobscaling.caster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * Pure gate-logic tests for {@link CasterEntry} - the {@code MobScalingGate} split precedent: the
 * per-mob eligibility decision lives in this pure record (no engine coupling), so it is directly
 * unit-testable without the engine-coupled {@code MobScalingCasterArmSystem} that calls it.
 */
class CasterEntryTest {

    private static CasterEntry entry(double minDifficulty, List<String> rarities, CasterEntry.Scope scope) {
        return new CasterEntry(CasterEntry.Kind.ABILITY, "fireball", null, minDifficulty, rarities,
                scope, false, 10_000L, 0L, null);
    }

    @Test
    void difficultyGate() {
        CasterEntry e = entry(20.0, List.of(), CasterEntry.Scope.ANY);
        assertFalse(e.isEligible(19.9f, "", MobScaleResult.SCOPE_HOSTILE), "just below MinDifficulty fails");
        assertTrue(e.isEligible(20.0f, "", MobScaleResult.SCOPE_HOSTILE), "exactly at MinDifficulty passes");
        assertTrue(e.isEligible(50.0f, "", MobScaleResult.SCOPE_HOSTILE), "above MinDifficulty passes");
    }

    @Test
    void emptyRaritiesAllowsAny() {
        CasterEntry e = entry(0.0, List.of(), CasterEntry.Scope.ANY);
        assertTrue(e.allowsRarity(""), "empty Rarities allows a plain mob");
        assertTrue(e.allowsRarity("epic"), "empty Rarities allows any rarity");
    }

    @Test
    void explicitRaritiesAllowListNarrows() {
        CasterEntry e = entry(0.0, List.of("epic", "legendary"), CasterEntry.Scope.ANY);
        assertTrue(e.allowsRarity("epic"), "listed rarity allowed");
        assertTrue(e.allowsRarity("Epic"), "case-insensitive");
        assertFalse(e.allowsRarity(""), "a plain mob is excluded by an explicit allow-list");
        assertFalse(e.allowsRarity("rare"), "an unlisted rarity is excluded");
    }

    @Test
    void wildcardRarityAllowsAny() {
        CasterEntry e = entry(0.0, List.of("*"), CasterEntry.Scope.ANY);
        assertTrue(e.allowsRarity(""), "wildcard allows a plain mob too");
        assertTrue(e.allowsRarity("legendary"));
    }

    @Test
    void scopeGate() {
        CasterEntry any = entry(0.0, List.of(), CasterEntry.Scope.ANY);
        assertTrue(any.isEligible(0f, "", MobScaleResult.SCOPE_HOSTILE));
        assertTrue(any.isEligible(0f, "", MobScaleResult.SCOPE_BOSS));

        CasterEntry bossOnly = entry(0.0, List.of(), CasterEntry.Scope.BOSS);
        assertFalse(bossOnly.isEligible(0f, "", MobScaleResult.SCOPE_HOSTILE), "BOSS scope excludes a plain hostile");
        assertTrue(bossOnly.isEligible(0f, "", MobScaleResult.SCOPE_BOSS), "BOSS scope allows a boss");

        CasterEntry hostileOnly = entry(0.0, List.of(), CasterEntry.Scope.HOSTILE);
        assertTrue(hostileOnly.isEligible(0f, "", MobScaleResult.SCOPE_HOSTILE));
        assertFalse(hostileOnly.isEligible(0f, "", MobScaleResult.SCOPE_BOSS));
    }

    @Test
    void invalidKindNeverEligible() {
        CasterEntry invalid = new CasterEntry(CasterEntry.Kind.INVALID, null, null, 0.0, List.of(),
                CasterEntry.Scope.ANY, false, 10_000L, 0L, null);
        assertFalse(invalid.isEligible(9999f, "legendary", MobScaleResult.SCOPE_BOSS),
                "an INVALID (neither/both AbilityId+NativeChain) entry is never eligible regardless of gates");
    }

    @Test
    void scopeParseDefaultsAndUnknown() {
        assertEquals(CasterEntry.Scope.ANY, CasterEntry.Scope.parse(null), "absent Scope defaults to ANY");
        assertEquals(CasterEntry.Scope.ANY, CasterEntry.Scope.parse(""), "blank Scope defaults to ANY");
        assertEquals(CasterEntry.Scope.HOSTILE, CasterEntry.Scope.parse("hostile"), "case-insensitive parse");
        assertEquals(CasterEntry.Scope.BOSS, CasterEntry.Scope.parse("Boss"));

        assertNull(CasterEntry.Scope.tryParse("WHATEVER"), "an unrecognised raw value fails tryParse");
        assertEquals(CasterEntry.Scope.ANY, CasterEntry.Scope.parse("WHATEVER"),
                "parse() falls back to ANY even for an unrecognised value (the validator flags it separately)");
    }
}

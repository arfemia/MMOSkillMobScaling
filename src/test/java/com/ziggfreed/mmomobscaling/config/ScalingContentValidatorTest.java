package com.ziggfreed.mmomobscaling.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.rarity.Rarity;

/** Exercises the pure value-sanity checks in {@link ScalingContentValidator}. */
class ScalingContentValidatorTest {

    @Test
    void cleanShippedShapesPass() {
        Rarity epic = new Rarity("epic", "", 25, 25, 2.2, 1.9, 0.7, 1.5, 1.3, 2, "aura", "drops", List.of("*"));
        Rarity boss = new Rarity("boss", "", 0, 0, 4.0, 2.2, 0.6, 3.0, 2.0, 2, "aura", "drops", List.of("*"));
        assertTrue(ScalingContentValidator.validateRarities(List.of(epic, boss)).isEmpty(),
                "the shipped ladder shapes (incl. the weight-0 force-only boss) are clean");

        Affix armored = new Affix("armored", "", "", "eff", 3, 5, List.of("*"), 0, 0, 0, 0, Affix.KIND_STAT, null, true);
        Affix vampiric = new Affix("vampiric", "", "", null, 2, 20, List.of("*"), 0, 0, 0, 0, Affix.KIND_BEHAVIORAL, "vampiric", false);
        assertTrue(ScalingContentValidator.validateAffixes(List.of(armored, vampiric)).isEmpty(),
                "shipped affix shapes are clean");
    }

    @Test
    void badRarityValuesAreFlagged() {
        Rarity bad = new Rarity("bad", "", -1, -5, 0.0, 0.0, 0.0, -1, -1, -1, null, null, List.of("*"));
        List<String> findings = ScalingContentValidator.validateRarities(List.of(bad));
        assertEquals(6, findings.size(), "weight, minDifficulty, hp, damage, loot/xp, slots all flagged: " + findings);
    }

    @Test
    void noOpAndUndispatchableAffixesAreFlagged() {
        Affix noOp = new Affix("noop", "", "", null, 1, 0, List.of("*"), 0, 0, 0, 0, Affix.KIND_STAT, null, false);
        Affix silent = new Affix("silent", "", "", "eff", 1, 0, List.of("*"), 0, 0, 0, 0, Affix.KIND_HYBRID, null, false);
        Affix weird = new Affix("weird", "", "", "eff", 1, 0, List.of("*"), 0, 0, 0, 0, "MAGICAL", null, false);
        List<String> findings = ScalingContentValidator.validateAffixes(List.of(noOp, silent, weird));
        assertEquals(3, findings.size(), "no-op STAT, BehaviorId-less HYBRID, unknown Kind all flagged: " + findings);
    }
}

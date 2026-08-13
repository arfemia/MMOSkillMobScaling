package com.ziggfreed.mmomobscaling.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorConditions;
import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.factor.FactorRegistry;

/**
 * What this mod's published readings do when there is nothing to read. Every id here is about a MOB, and
 * most of the moments that will ask are not about a mob at all - a block break, a dialogue line, a placement
 * sweep - so the answer has to be "cannot tell" rather than a number. That is the whole contract other mods'
 * content is written against, and it is the half that never gets exercised in game until something is
 * already wrong.
 *
 * <p>The live paths (a real corpse carrying a real scaling component, a tracked region) need a running
 * server and are covered by the in-game pass.
 */
class MobScalingFactorsTest {

    @Test
    void everyIdIsNamespacedToThisMod() {
        assertFalse(MobScalingFactors.ids().isEmpty(), "the mod publishes something");
        for (String id : MobScalingFactors.ids()) {
            assertTrue(id.startsWith("mmomobscaling:"),
                    "an author must be able to tell from the id alone which mod answers it: " + id);
        }
    }

    @Test
    void contributingIsIdempotentAndAttributed() {
        MobScalingFactors.contribute();
        MobScalingFactors.contribute();
        for (String id : MobScalingFactors.ids()) {
            assertTrue(FactorContributions.isContributed(id), id + " is claimed");
        }
        List<String> mine = FactorContributions.contributors().get(MobScalingFactors.OWNER);
        assertNotNull(mine, "the claims are attributed to this mod, so a boot log names what to install");
        assertTrue(mine.containsAll(MobScalingFactors.ids()),
                "every published id is attributed: " + mine);
    }

    @Test
    void aMomentWithNoMobAnswersNothing() {
        MobScalingFactors.contribute();
        FactorRegistry registry = new FactorRegistry("test");
        FactorContext nothing = FactorContext.builder().build();
        for (String id : MobScalingFactors.ids()) {
            assertNull(registry.resolve(id, nothing),
                    id + " must answer 'cannot tell' rather than a number when there is no mob");
        }
    }

    @Test
    void aGateOnAMobReadingStaysShutWithNoMob() {
        MobScalingFactors.contribute();
        FactorRegistry registry = new FactorRegistry("test");
        FactorContext nothing = FactorContext.builder().build();

        FactorCondition bounded = FactorCondition.of(MobScalingFactors.MOB_RARITY_TIER, null, 1.0, null);
        FactorCondition presence = FactorCondition.of(MobScalingFactors.MOB_DIFFICULTY, null, null, null);
        assertFalse(FactorConditions.pass(new FactorCondition[] {bounded}, registry, nothing),
                "a Min gate on a mob reading fails closed");
        assertFalse(FactorConditions.pass(new FactorCondition[] {presence}, registry, nothing),
                "and so does a bounds-less one - that is how 'only where a mob was scaled' is written");
    }

    @Test
    void aFormulaTermOnAMobReadingAddsNothingAndLeavesTheRestAlone() {
        MobScalingFactors.contribute();
        FactorRegistry registry = new FactorRegistry("test");
        FactorContext nothing = FactorContext.builder().build();

        FactorFormula formula = FactorFormula.of(5.0, new FactorFormula.Term[] {
                FactorFormula.Term.of(MobScalingFactors.MOB_RARITY_TIER, null, 10.0)
        }, null);
        assertEquals(5.0, formula.evaluate(registry, nothing), 1e-9,
                "an unanswerable term contributes zero; the base and every other term survive");
    }
}

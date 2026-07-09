package com.ziggfreed.mmomobscaling.affix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.cast.OnHitRegistry;

/**
 * Characterization tests for {@link AffixBehaviorRegistry}: the {@code lifestealOnHit} accessor's
 * {@link OnHitRegistry#NO_OP} guards, and the two builtin behaviors' case-insensitive lookup.
 *
 * <p>A "real heal fires" case (a positive amount with a live, non-null attacker {@code Ref}) is
 * deliberately NOT exercised here: {@code Ref<EntityStore>}'s only public constructors take a
 * {@code Store<EntityStore>}, and there is no trivially-constructible standalone {@code Store} on
 * this test classpath (it is engine-bootstrapped in a running server, not a plain POJO) - building
 * one would need either a mocking framework this module does not depend on or a partially-faked
 * engine object whose behavior under {@code HealthUtil.heal} would not be trustworthy. The three
 * NO_OP guard cases below fully cover {@code lifestealOnHit}'s public contract (amount<=0, no
 * attacker) without touching the engine; the "amount>0 and a real attacker" path is exercised
 * indirectly by {@code MobScalingOnHitSystem}'s existing on-hit wiring, unchanged by this refactor.
 */
class AffixBehaviorRegistryTest {

    @Test
    void zeroAmountIsNoOp() {
        BiConsumer<Store<EntityStore>, Ref<EntityStore>> c = AffixBehaviorRegistry.lifestealOnHit(0.0, null);
        assertSame(OnHitRegistry.NO_OP, c, "a zero heal amount never heals");
    }

    @Test
    void negativeAmountIsNoOp() {
        BiConsumer<Store<EntityStore>, Ref<EntityStore>> c = AffixBehaviorRegistry.lifestealOnHit(-1.0, null);
        assertSame(OnHitRegistry.NO_OP, c, "a negative heal amount never heals");
    }

    @Test
    void positiveAmountWithNoAttackerIsNoOp() {
        BiConsumer<Store<EntityStore>, Ref<EntityStore>> c = AffixBehaviorRegistry.lifestealOnHit(5.0, null);
        assertSame(OnHitRegistry.NO_OP, c, "no attacker Ref to heal -> NO_OP even with a positive amount");
    }

    @Test
    void vampiricResolvesCaseInsensitivelyWithExpectedShape() {
        AffixBehavior lower = AffixBehaviorRegistry.get("vampiric");
        AffixBehavior upper = AffixBehaviorRegistry.get("VAMPIRIC");
        assertNotNull(lower, "vampiric is a registered builtin");
        assertSame(lower, upper, "lookup is case-insensitive (same registry entry)");
        assertEquals(AffixBehavior.Kind.LIFESTEAL, lower.kind());
        assertEquals(0.02, lower.magnitude(), 0.0001, "vampiric heals 2% of damage dealt");
        assertNull(lower.effectId(), "LIFESTEAL carries no effect id");
    }

    @Test
    void freezingResolvesCaseInsensitivelyWithExpectedShape() {
        assertTrue(AffixBehaviorRegistry.has("freezing"), "freezing is a registered builtin");
        AffixBehavior mixed = AffixBehaviorRegistry.get("FrEeZiNg");
        assertNotNull(mixed);
        assertEquals(AffixBehavior.Kind.APPLY_EFFECT_ON_HIT, mixed.kind());
        assertEquals("Mmoscaling_Affix_Freezing_Slow", mixed.effectId());
    }

    @Test
    void unknownBehaviorIdIsUnregistered() {
        assertNull(AffixBehaviorRegistry.get("no-such-behavior"));
    }
}

package com.ziggfreed.mmomobscaling.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.mmomobscaling.family.FamilyFilter;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.variant.Variant;

/**
 * The display-name decoration's frames, pinned on the pure {@code decoratedName} so no store is needed: a
 * tier that decorates wraps the base name in the rarity frame, a tier with {@code DecorateName} off leaves the
 * base exactly as it is (the same instance, so the roll stamps nothing), and a variant's frame wraps whatever
 * the rarity step left either way.
 */
class MobScalingDecoratedNameTest {

    private static final Message BASE = Message.translation("fixture.npc.name");

    private static final Variant OVERLAY = new Variant("fixture_overlay", "", 0.15, 20, 1.25, 1, 1, 1,
            List.of("*"));

    private static Rarity tier(String id, boolean decorates) {
        return new Rarity(id, "", 0, 0, 3.0, 1, 1, 0, null, List.of("*"), "", FamilyFilter.ALLOW_ALL, null,
                decorates);
    }

    @Test
    void aTierThatDecoratesWrapsTheBaseInTheRarityFrame() {
        FormattedMessage name = MobScalingRollSystem.decoratedName(BASE, tier("fixture_tier", true), null)
                .getFormattedMessage();
        assertEquals("mmomobscaling.name.decorated", name.messageId);
        assertEquals("mmomobscaling.rarity.fixture_tier.name", name.messageParams.get("rarity").messageId);
        assertEquals("fixture.npc.name", name.messageParams.get("base").messageId);
    }

    @Test
    void aTierThatDoesNotDecorateLeavesTheNameAsItIs() {
        assertSame(BASE, MobScalingRollSystem.decoratedName(BASE, tier("fixture_tier", false), null),
                "the mob keeps its own name and nothing is stamped");
        assertSame(BASE, MobScalingRollSystem.decoratedName(BASE, null, null), "a plain mob keeps its name too");
    }

    @Test
    void aVariantStillFramesTheNameUnderATierThatDoesNotDecorate() {
        FormattedMessage name = MobScalingRollSystem.decoratedName(BASE, tier("fixture_tier", false), OVERLAY)
                .getFormattedMessage();
        assertEquals("mmomobscaling.name.variant_decorated", name.messageId);
        assertEquals("mmomobscaling.variant.fixture_overlay.name", name.messageParams.get("variant").messageId);
        assertEquals("fixture.npc.name", name.messageParams.get("inner").messageId,
                "the variant frame wraps the bare name, with no rarity frame inside it");
    }

    @Test
    void aVariantWrapsTheRarityFrameOfATierThatDecorates() {
        FormattedMessage name = MobScalingRollSystem.decoratedName(BASE, tier("fixture_tier", true), OVERLAY)
                .getFormattedMessage();
        assertEquals("mmomobscaling.name.variant_decorated", name.messageId);
        FormattedMessage inner = name.messageParams.get("inner");
        assertEquals("mmomobscaling.name.decorated", inner.messageId);
        assertEquals("fixture.npc.name", inner.messageParams.get("base").messageId);
    }
}

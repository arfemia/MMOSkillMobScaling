package com.ziggfreed.mmomobscaling.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * The rarity attribution's answer contract: a scaled victim attributes its rolled rarity id
 * (the Rarities asset id spelling the MMO's kill qualifier carries), a plain roll and an
 * unstamped entity both attribute nothing. Pinned on the pure {@code rarityOf} read so the
 * contract holds without a store or the plugin singleton.
 */
public class MobScalingRarityAttributionTest {

    private static MobScaleResult result(String rarityId) {
        return new MobScaleResult(25f, rarityId, "", new String[0],
                1f, 1f, 1f, 1f, 1f, MobScaleResult.SCOPE_HOSTILE);
    }

    @Test
    void scaledVictimAttributesItsRarityId() {
        assertEquals("Legendary",
                MobScalingRarityAttribution.rarityOf(new ScaledMobComponent(result("Legendary"))));
        assertEquals("Rare",
                MobScalingRarityAttribution.rarityOf(new ScaledMobComponent(result("Rare"))));
    }

    @Test
    void plainRollAttributesNothing() {
        // An empty rarity id is the fold's "plain floor mob" spelling; it must not become a
        // blank qualifier on the kill.
        assertNull(MobScalingRarityAttribution.rarityOf(new ScaledMobComponent(result(""))));
    }

    @Test
    void unstampedVictimAttributesNothing() {
        assertNull(MobScalingRarityAttribution.rarityOf(null));
    }
}

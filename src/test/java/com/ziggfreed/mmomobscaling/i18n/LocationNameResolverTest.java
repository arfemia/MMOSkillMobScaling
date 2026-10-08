package com.ziggfreed.mmomobscaling.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;

/**
 * The zone card's location names. The base game names no biome, so a biome shows only under an authored key
 * family and never as its raw worldgen id; the zone keeps its lookup through the game's own region names, and
 * an owner's blank zone prefix still prettifies the zone id.
 */
class LocationNameResolverTest {

    @Test
    void aBlankBiomePrefixShowsNoBiome() {
        assertNull(LocationNameResolver.biomeName("Ocean1", ""), "the shipped blank prefix shows no biome line");
        assertNull(LocationNameResolver.biomeName("Ocean1", "   "), "a whitespace prefix is blank too");
    }

    @Test
    void aBlankBiomeIdShowsNoBiome() {
        assertNull(LocationNameResolver.biomeName("", "fixture.biome."), "no biome here, nothing to name");
    }

    @Test
    void anAuthoredBiomeKeyFamilyNamesTheBiome() {
        Message biome = LocationNameResolver.biomeName("Ocean1", "fixture.biome.");
        assertNotNull(biome);
        assertEquals("fixture.biome.Ocean1", biome.getMessageId(), "the client resolves the owner's own key");
    }

    @Test
    void theZoneStillResolvesThroughItsPrefixOrPrettifiesUnderABlankOne() {
        Message fallback = Message.translation("fixture.title");
        assertEquals("server.map.region.Zone1_Tier1",
                LocationNameResolver.displayName("Zone1_Tier1", "server.map.region.", fallback).getMessageId());
        assertEquals("Zone1 Tier1",
                LocationNameResolver.displayName("Zone1_Tier1", "", fallback).getRawText());
        assertEquals("fixture.title", LocationNameResolver.displayName("", "server.map.region.", fallback)
                .getMessageId(), "a blank zone id falls back to the caller's title");
    }
}

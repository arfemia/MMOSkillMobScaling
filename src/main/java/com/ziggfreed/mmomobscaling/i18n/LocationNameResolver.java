package com.ziggfreed.mmomobscaling.i18n;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

/**
 * Turns a raw native worldgen ZONE / BIOME id into a CLIENT-resolved display {@link Message}, so the HUD
 * shows "Cinder Wastes" instead of "Zone4_Tier5". A pure, thread-safe factory (only builds server-side
 * Message objects; the client resolves them in the viewer's locale).
 *
 * <p><b>Zone:</b> vanilla carries the friendly name as a lang-key CONVENTION, not a field on the zone
 * object - the base game's {@code server.lang} maps {@code server.map.region.<Zone.name()>} to the region
 * title (e.g. {@code server.map.region.Zone4_Tier5 = "Cinder Wastes"}). So we nest
 * {@code Message.translation(prefix + zoneId)} with the configurable {@code ZoneNameKeyPrefix} (default
 * {@code "server.map.region."}); the client resolves the base-game name for FREE, zero keys to author.
 * A modded world with a different namespace sets the prefix; a BLANK prefix prettifies the raw id instead
 * (the safe fallback where no lang key exists - a missing key would otherwise render the literal key text).
 *
 * <p><b>Biome:</b> vanilla ships NO biome name key ({@code server.map.biome.*} does not exist; {@code /zone}
 * prints the raw id). So {@code BiomeNameKeyPrefix} defaults to BLANK - we prettify {@code Biome.getName()}
 * ("Forest_Birch_Trork" -> "Forest Birch Trork"). An owner who authors biome keys sets the prefix.
 *
 * <p>The prefixes live on the {@code ZoneHud} settings group ({@code MobScalingConfig.getZoneNameKeyPrefix()}
 * / {@code getBiomeNameKeyPrefix()}), so the resolution is codec-driven, not hardcoded.
 */
public final class LocationNameResolver {

    private LocationNameResolver() {
    }

    /**
     * The display name for a zone/biome id: when {@code keyPrefix} is non-blank, a nested
     * {@code Message.translation(keyPrefix + id)} (client-resolved); otherwise a prettified raw id
     * ({@code Message.raw}). A blank {@code id} yields {@code fallback} (the caller's placeholder).
     */
    @Nonnull
    public static Message displayName(@Nonnull String id, @Nonnull String keyPrefix, @Nonnull Message fallback) {
        if (id.isBlank()) {
            return fallback;
        }
        if (!keyPrefix.isBlank()) {
            return Message.translation(keyPrefix + id);
        }
        return Message.raw(prettify(id));
    }

    /**
     * Prettify a raw worldgen id for display: {@code _}/{@code -} to spaces, runs collapsed, trimmed
     * (e.g. {@code "Forest_Birch_Trork"} -> {@code "Forest Birch Trork"}). Ids are already segment-cased,
     * so no case change is applied. Never returns null; a fully-separator id degrades to the trimmed input.
     */
    @Nonnull
    public static String prettify(@Nonnull String id) {
        String spaced = id.replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").trim();
        return spaced.isEmpty() ? id : spaced;
    }
}

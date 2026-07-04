package com.ziggfreed.mmomobscaling.world;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One resolved difficulty-floor mapping: a NATIVE worldgen target (a Zone like {@code Zone2}, or a
 * Biome like {@code Ocean1}; {@code "*"} for the type-wide wildcard) bound to an authored floor.
 * Decoded from {@code Server/MmoMobScaling/Difficulty/*.json} via
 * {@code DifficultyMappingAsset}, folded {@code defaults < pack < owner} by {@code DifficultyConfig},
 * and consumed by {@link ZoneDifficultyResolver} (precedence: zone exact &gt; zone PREFIX &gt;
 * biome exact &gt; biome PREFIX &gt; zone {@code *} &gt; biome {@code *} &gt; the
 * {@code WorldRules.mobDifficultyFloor} baseline).
 *
 * <p>The mapping never invents a zone registry: {@code targetId} is the engine's own
 * {@code Zone.name()} / {@code Biome.getName()} string, matched case-insensitively. Because the shipped
 * world's zone names are COMPOUND (e.g. {@code Zone2_Tier1}, {@code Zone2_Shore}), a mapping matches
 * either EXACTLY or at a SEGMENT boundary: {@code targetId} {@code "Zone2"} matches {@code Zone2} and
 * every {@code Zone2_*}, but never {@code Zone20_*} (the trailing {@code "_"} guards the boundary).
 */
public record DifficultyMapping(@Nonnull String id, @Nonnull TargetType targetType,
        @Nonnull String targetId, double floor) {

    /** The native worldgen namespace a mapping keys into. */
    public enum TargetType {
        ZONE,
        BIOME;

        /** Parse a codec string ({@code "Zone"} / {@code "Biome"}, any case); {@code null} when unknown. */
        @Nullable
        public static TargetType parse(@Nullable String raw) {
            if (raw == null) {
                return null;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "zone" -> ZONE;
                case "biome" -> BIOME;
                default -> null;
            };
        }
    }

    /** True when this mapping is the type-wide wildcard ({@code TargetId: "*"}). */
    public boolean isWildcard() {
        return "*".equals(targetId);
    }

    /**
     * Case-insensitive match against a native zone/biome name: an EXACT match, OR a SEGMENT-BOUNDARY
     * prefix match ({@code nativeName} starts with {@code targetId + "_"}). The trailing {@code "_"}
     * keeps {@code "Zone2"} from matching {@code "Zone20_..."} while still matching {@code "Zone2_Tier1"}.
     * The {@code "*"} wildcard is handled separately (via {@link #isWildcard()}), never here.
     */
    public boolean matches(@Nonnull String nativeName) {
        if (targetId.equalsIgnoreCase(nativeName)) {
            return true;
        }
        return nativeName.regionMatches(true, 0, targetId, 0, targetId.length())
                && nativeName.length() > targetId.length()
                && nativeName.charAt(targetId.length()) == '_';
    }
}

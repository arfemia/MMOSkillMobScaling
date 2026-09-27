package com.ziggfreed.mmomobscaling.asset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.mmomobscaling.world.DifficultyMapping;

/**
 * A pack-authorable difficulty-floor mapping, loaded from {@code Server/MmoMobScaling/Difficulty/*.json}.
 * Pattern A - the {@link #CODEC} decodes directly into typed fields (the codec IS the schema
 * authority). PascalCase keys (the mod's {@code AssetCodecInitTest} guards static init).
 *
 * <p>The mapping binds a NATIVE worldgen id to a floor - {@code TargetType: "Zone"} keys the engine's
 * own {@code Zone.name()} ({@code Zone0}..{@code Zone4} on the default worldgen), {@code "Biome"} keys
 * {@code Biome.getName()} ({@code Plains1}/{@code Ocean1}/...); {@code TargetId: "*"} is the type-wide
 * wildcard. Precedence + the {@code WorldRules} baseline live in
 * {@code world/ZoneDifficultyResolver}. The jar ships the starter gradient; a pack replaces a mapping
 * by id, and an owner file under {@code mods/MmoMobScaling/difficulty/} overlays the same-id mapping
 * per leaf (a file holding only {@code Floor} retunes it), folded {@code pack < owner} through
 * {@code DifficultyConfig}. Every leaf is a NULLABLE wrapper so an absent key reads as absent, which
 * is what lets a partial owner file inherit the leaves it does not author.
 *
 * <p>Pack JSON shape:
 * <pre>{@code
 * { "TargetType": "Zone", "TargetId": "Zone2", "Floor": 22.0 }
 * }</pre>
 */
public final class DifficultyMappingAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, DifficultyMappingAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String targetType;
    @Nullable private String targetId;
    @Nullable private Double floor;

    public static final AssetBuilderCodec<String, DifficultyMappingAsset> CODEC = AssetBuilderCodec.builder(
                    DifficultyMappingAsset.class,
                    DifficultyMappingAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            // Optional human-readable echo of the asset key (the filename is authoritative).
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op - id comes from the filename */ },
                    a -> a.id)
            .add()
            // The native worldgen namespace: "Zone" | "Biome".
            .append(new KeyedCodec<>("TargetType", Codec.STRING, false),
                    (a, v) -> a.targetType = v, a -> a.targetType)
            .add()
            // The engine's own Zone.name() / Biome.getName() string, or "*" for the type-wide wildcard.
            .append(new KeyedCodec<>("TargetId", Codec.STRING, false),
                    (a, v) -> a.targetId = v, a -> a.targetId)
            .add()
            // The authored difficulty floor for spawns whose chunk resolves to this target.
            .append(new KeyedCodec<>("Floor", Codec.DOUBLE, false),
                    (a, v) -> a.floor = v, a -> a.floor)
            .add()
            .build();

    public DifficultyMappingAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The authored {@code TargetType} string ({@code Zone} / {@code Biome}); {@code null} when absent. */
    @Nullable public String getTargetType() { return targetType; }

    /** The authored native zone/biome name or {@code *}; {@code null} when absent. */
    @Nullable public String getTargetId() { return targetId; }

    /** The authored floor; {@code null} when absent (a partial owner file inheriting the shipped one). */
    @Nullable public Double getFloor() { return floor; }

    /**
     * Map the decoded fields onto the runtime {@link DifficultyMapping}; {@code null} (skip + warn at
     * the fold) when {@code TargetType} is unknown, {@code TargetId} is absent/blank or {@code Floor}
     * is absent - a shipped mapping is complete by definition.
     */
    @Nullable
    public DifficultyMapping toMapping(@Nonnull String mappingId) {
        DifficultyMapping.TargetType type = DifficultyMapping.TargetType.parse(targetType);
        if (type == null || targetId == null || targetId.isBlank() || floor == null) {
            return null;
        }
        return new DifficultyMapping(mappingId, type, targetId.trim(), floor);
    }
}

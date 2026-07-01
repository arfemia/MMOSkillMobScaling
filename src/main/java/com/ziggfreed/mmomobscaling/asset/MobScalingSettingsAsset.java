package com.ziggfreed.mmomobscaling.asset;

import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;

/**
 * The mob-scaling settings, authored as a PROPER Hytale asset codec (Pattern A,
 * {@link AssetBuilderCodec}, PascalCase keys) - the ONE schema authority for the config, exactly
 * like the MMO's {@code WorldRulesAsset}. The jar ships the authoritative defaults as a codec asset
 * ({@code Server/MmoMobScaling/Settings/Default.json}); owners override any key in
 * {@code mods/MmoMobScaling/mob-scaling.json} (the SAME PascalCase codec shape, partial allowed).
 *
 * <p><b>Fields are NULLABLE wrappers on purpose.</b> {@code decodeJson} calls a field's setter ONLY
 * for a key that is present in the JSON (verified in {@code BuilderCodec.decodeJson0}); an absent key
 * leaves the field {@code null}. So decoding the jar Default.json yields all-non-null (authoritative
 * defaults), decoding a partial owner file yields non-null only for owner-set keys, and
 * {@code MobScalingConfig} folds owner-over-default cleanly with NO values baked into Java.
 *
 * <p>Decoded SYNCHRONOUSLY at plugin {@code setup()} via {@code CODEC.decodeJson(...)} (the
 * {@code WorldRulesConfig.decodeOwnerRule} pattern), so the zero-cost registration gate can read
 * {@code Enabled} before {@code LoadedAssetsEvent} would populate an async asset store.
 *
 * <p>Map-shaped SIMPLE-preset knobs (rarity weights, zone difficulty overrides) are deliberately NOT
 * here: their canonical home is the per-type keyed assets ({@code Rarities/*.json},
 * {@code Difficulty/*.json}) landing in a later phase, not this flat settings object.
 */
public final class MobScalingSettingsAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, MobScalingSettingsAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private Boolean compositionEnabled;
    @Nullable private String presetMode;
    @Nullable private String intensity;
    @Nullable private Double raritySpawnChance;
    @Nullable private Boolean allowDifficultyIncreaseOnPartyJoin;
    @Nullable private Double lateArrivalBumpFactor;
    @Nullable private String openWorldAggregationMode;
    @Nullable private Integer regionSizeChunks;

    public static final AssetBuilderCodec<String, MobScalingSettingsAsset> CODEC = AssetBuilderCodec.builder(
                    MobScalingSettingsAsset.class,
                    MobScalingSettingsAsset::new,
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
            // Master toggle: the zero-cost registration gate reads this at setup().
            .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled)
            .add()
            // Open-world density/composition scaling toggle (gated at registration too).
            .append(new KeyedCodec<>("CompositionEnabled", Codec.BOOLEAN, false),
                    (a, v) -> a.compositionEnabled = v, a -> a.compositionEnabled)
            .add()
            // Customization tier: "SIMPLE" | "TUNED" | "ADVANCED".
            .append(new KeyedCodec<>("PresetMode", Codec.STRING, false),
                    (a, v) -> a.presetMode = v, a -> a.presetMode)
            .add()
            // Intensity dial: "off" | "soft" | "medium" | "hard" | "brutal".
            .append(new KeyedCodec<>("Intensity", Codec.STRING, false),
                    (a, v) -> a.intensity = v, a -> a.intensity)
            .add()
            // Chance a hostile mob rolls a non-plain rarity.
            .append(new KeyedCodec<>("RaritySpawnChance", Codec.DOUBLE, false),
                    (a, v) -> a.raritySpawnChance = v, a -> a.raritySpawnChance)
            .add()
            // One-shot additive difficulty bump when a stronger player/party arrives in a region.
            .append(new KeyedCodec<>("AllowDifficultyIncreaseOnPartyJoin", Codec.BOOLEAN, false),
                    (a, v) -> a.allowDifficultyIncreaseOnPartyJoin = v, a -> a.allowDifficultyIncreaseOnPartyJoin)
            .add()
            // Size (flat additive difficulty) of the late-arrival bump.
            .append(new KeyedCodec<>("LateArrivalBumpFactor", Codec.DOUBLE, false),
                    (a, v) -> a.lateArrivalBumpFactor = v, a -> a.lateArrivalBumpFactor)
            .add()
            // How a region's participant powers fold: SOLO | AVERAGE | PEAK | WEIGHTED | DISABLED.
            .append(new KeyedCodec<>("OpenWorldAggregationMode", Codec.STRING, false),
                    (a, v) -> a.openWorldAggregationMode = v, a -> a.openWorldAggregationMode)
            .add()
            // Region grid size (chunks per side) for the open-world power aggregate.
            .append(new KeyedCodec<>("RegionSizeChunks", Codec.INTEGER, false),
                    (a, v) -> a.regionSizeChunks = v, a -> a.regionSizeChunks)
            .add()
            .build();

    public MobScalingSettingsAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable public Boolean getEnabled() { return enabled; }
    @Nullable public Boolean getCompositionEnabled() { return compositionEnabled; }
    @Nullable public String getPresetMode() { return presetMode; }
    @Nullable public String getIntensity() { return intensity; }
    @Nullable public Double getRaritySpawnChance() { return raritySpawnChance; }
    @Nullable public Boolean getAllowDifficultyIncreaseOnPartyJoin() { return allowDifficultyIncreaseOnPartyJoin; }
    @Nullable public Double getLateArrivalBumpFactor() { return lateArrivalBumpFactor; }
    @Nullable public String getOpenWorldAggregationMode() { return openWorldAggregationMode; }
    @Nullable public Integer getRegionSizeChunks() { return regionSizeChunks; }
}

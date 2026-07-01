package com.ziggfreed.mmomobscaling.asset;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.mmomobscaling.affix.Affix;

/**
 * A pack-authorable mob AFFIX, loaded from {@code Server/MmoMobScaling/Affixes/*.json}. Pattern A - the
 * {@link #CODEC} decodes directly into typed fields (PascalCase keys, guarded by the mod's
 * {@code AssetCodecInitTest}), mirroring {@link RarityAsset}. An affix's stat magnitude is authored in the
 * referenced native {@code EntityEffect} ({@code EffectId}); this asset holds only mob-scaling POLICY (gating,
 * the pipeline deltas with no native path, kind + behavior dispatch).
 *
 * <p>Pack JSON shape (all fields optional; absent = the documented default):
 * <pre>{@code
 * { "Name": "armored", "DisplayNameKey": "scaling.affix.armored.name",
 *   "DescriptionKey": "scaling.affix.armored.desc",
 *   "EffectId": "Mmoscaling_Affix_Armored",
 *   "SpawnWeight": 3.0, "MinDifficulty": 5, "AllowedRarities": ["*"],
 *   "Kind": "STAT", "ResistanceBearing": true }
 * }</pre>
 */
public final class AffixAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, AffixAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String displayNameKey;
    @Nullable private String descriptionKey;
    @Nullable private String effectId;
    private double spawnWeight = 1.0;
    private double minDifficulty = 0.0;
    @Nullable private String[] allowedRarities;
    private double outDamageDelta = 0.0;
    private double inDamageDelta = 0.0;
    private double hpDelta = 0.0;
    private double lootBonus = 0.0;
    @Nullable private String kind;
    @Nullable private String behaviorId;
    private boolean resistanceBearing = false;

    public static final AssetBuilderCodec<String, AffixAsset> CODEC = AssetBuilderCodec.builder(
                    AffixAsset.class,
                    AffixAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op - id comes from the filename */ },
                    a -> a.id)
            .add()
            .append(new KeyedCodec<>("DisplayNameKey", Codec.STRING, false), (a, v) -> a.displayNameKey = v, a -> a.displayNameKey)
            .add()
            .append(new KeyedCodec<>("DescriptionKey", Codec.STRING, false), (a, v) -> a.descriptionKey = v, a -> a.descriptionKey)
            .add()
            .append(new KeyedCodec<>("EffectId", Codec.STRING, false), (a, v) -> a.effectId = v, a -> a.effectId)
            .add()
            .append(new KeyedCodec<>("SpawnWeight", Codec.DOUBLE, false), (a, v) -> a.spawnWeight = v, a -> a.spawnWeight)
            .add()
            .append(new KeyedCodec<>("MinDifficulty", Codec.DOUBLE, false), (a, v) -> a.minDifficulty = v, a -> a.minDifficulty)
            .add()
            .append(new KeyedCodec<>("AllowedRarities", Codec.STRING_ARRAY, false), (a, v) -> a.allowedRarities = v, a -> a.allowedRarities)
            .add()
            .append(new KeyedCodec<>("OutDamageDelta", Codec.DOUBLE, false), (a, v) -> a.outDamageDelta = v, a -> a.outDamageDelta)
            .add()
            .append(new KeyedCodec<>("InDamageDelta", Codec.DOUBLE, false), (a, v) -> a.inDamageDelta = v, a -> a.inDamageDelta)
            .add()
            .append(new KeyedCodec<>("HpDelta", Codec.DOUBLE, false), (a, v) -> a.hpDelta = v, a -> a.hpDelta)
            .add()
            .append(new KeyedCodec<>("LootBonus", Codec.DOUBLE, false), (a, v) -> a.lootBonus = v, a -> a.lootBonus)
            .add()
            .append(new KeyedCodec<>("Kind", Codec.STRING, false), (a, v) -> a.kind = v, a -> a.kind)
            .add()
            .append(new KeyedCodec<>("BehaviorId", Codec.STRING, false), (a, v) -> a.behaviorId = v, a -> a.behaviorId)
            .add()
            .append(new KeyedCodec<>("ResistanceBearing", Codec.BOOLEAN, false), (a, v) -> a.resistanceBearing = v, a -> a.resistanceBearing)
            .add()
            .build();

    public AffixAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Build the runtime {@link Affix} (the map key is the id). Absent {@code AllowedRarities} -> {@code ["*"]}
     * (all); absent {@code Kind} -> {@code STAT}; absent display/description keys stay {@code ""} so the text
     * util falls back to a convention key.
     */
    @Nonnull
    public Affix toAffix() {
        List<String> allowed = allowedRarities != null ? List.of(allowedRarities) : List.of("*");
        String nameKey = displayNameKey != null ? displayNameKey : "";
        String descKey = descriptionKey != null ? descriptionKey : "";
        String k = kind != null ? kind : Affix.KIND_STAT;
        return new Affix(id, nameKey, descKey, effectId, spawnWeight, minDifficulty, allowed,
                outDamageDelta, inDamageDelta, hpDelta, lootBonus, k, behaviorId, resistanceBearing);
    }
}

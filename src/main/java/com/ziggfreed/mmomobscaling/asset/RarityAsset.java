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
import com.ziggfreed.mmomobscaling.rarity.Rarity;

/**
 * A pack-authorable mob RARITY tier, loaded from {@code Server/MmoMobScaling/Rarities/*.json}. Pattern A -
 * the {@link #CODEC} decodes directly into typed fields (the codec IS the schema authority), mirroring
 * ziggfreed-common's {@code LootTableAsset} field-for-field. Every {@link KeyedCodec} key is PascalCase
 * (the constructor rejects a lower-case first letter at static init; the mod's {@code AssetCodecInitTest}
 * guards it). The jar ships the starter ladder; a pack or owner overrides any field by id, folded
 * {@code defaults < pack < owner} through {@link com.ziggfreed.mmomobscaling.config.RarityConfig}.
 *
 * <p>Pack JSON shape (all fields optional; absent = the documented default):
 * <pre>{@code
 * { "Name": "epic", "DisplayNameKey": "scaling.rarity.epic.name",
 *   "Weight": 25, "MinDifficulty": 25,
 *   "HpMult": 2.0, "OutDamageMult": 1.5, "InDamageMult": 0.8,
 *   "LootMult": 1.5, "XpMult": 1.3, "AffixSlots": 2,
 *   "AuraEffectId": "Mmoscaling_Aura_Epic", "AllowedAffixes": ["*"] }
 * }</pre>
 */
public final class RarityAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, RarityAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String displayNameKey;
    private double weight = 1.0;
    private double minDifficulty = 0.0;
    private double hpMult = 1.0;
    private double outDamageMult = 1.0;
    private double inDamageMult = 1.0;
    private double lootMult = 1.0;
    private double xpMult = 1.0;
    private int affixSlots = 0;
    @Nullable private String auraEffectId;
    @Nullable private String[] allowedAffixes;

    public static final AssetBuilderCodec<String, RarityAsset> CODEC = AssetBuilderCodec.builder(
                    RarityAsset.class,
                    RarityAsset::new,
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
            .append(new KeyedCodec<>("DisplayNameKey", Codec.STRING, false),
                    (a, v) -> a.displayNameKey = v, a -> a.displayNameKey)
            .add()
            .append(new KeyedCodec<>("Weight", Codec.DOUBLE, false), (a, v) -> a.weight = v, a -> a.weight)
            .add()
            .append(new KeyedCodec<>("MinDifficulty", Codec.DOUBLE, false), (a, v) -> a.minDifficulty = v, a -> a.minDifficulty)
            .add()
            .append(new KeyedCodec<>("HpMult", Codec.DOUBLE, false), (a, v) -> a.hpMult = v, a -> a.hpMult)
            .add()
            .append(new KeyedCodec<>("OutDamageMult", Codec.DOUBLE, false), (a, v) -> a.outDamageMult = v, a -> a.outDamageMult)
            .add()
            .append(new KeyedCodec<>("InDamageMult", Codec.DOUBLE, false), (a, v) -> a.inDamageMult = v, a -> a.inDamageMult)
            .add()
            .append(new KeyedCodec<>("LootMult", Codec.DOUBLE, false), (a, v) -> a.lootMult = v, a -> a.lootMult)
            .add()
            .append(new KeyedCodec<>("XpMult", Codec.DOUBLE, false), (a, v) -> a.xpMult = v, a -> a.xpMult)
            .add()
            .append(new KeyedCodec<>("AffixSlots", Codec.INTEGER, false), (a, v) -> a.affixSlots = v, a -> a.affixSlots)
            .add()
            .append(new KeyedCodec<>("AuraEffectId", Codec.STRING, false), (a, v) -> a.auraEffectId = v, a -> a.auraEffectId)
            .add()
            .append(new KeyedCodec<>("AllowedAffixes", Codec.STRING_ARRAY, false), (a, v) -> a.allowedAffixes = v, a -> a.allowedAffixes)
            .add()
            .build();

    public RarityAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Build the runtime {@link Rarity} (the map key is the id). An absent {@code AllowedAffixes} means
     * "allow all" ({@code ["*"]}); an explicit empty list means "allow none". An absent display key stays
     * {@code ""} so the text util falls back to the convention key at render time.
     */
    @Nonnull
    public Rarity toRarity() {
        List<String> allowed = allowedAffixes != null ? List.of(allowedAffixes) : List.of("*");
        String nameKey = displayNameKey != null ? displayNameKey : "";
        return new Rarity(id, nameKey, weight, minDifficulty, hpMult, outDamageMult, inDamageMult,
                lootMult, xpMult, affixSlots, auraEffectId, allowed);
    }
}

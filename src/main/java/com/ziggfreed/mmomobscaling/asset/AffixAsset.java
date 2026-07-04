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
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.mmomobscaling.affix.Affix;

/**
 * A pack-authorable mob AFFIX, loaded from {@code Server/MmoMobScaling/Affixes/*.json}. Pattern A - the
 * {@link #CODEC} decodes directly into typed fields (PascalCase keys, guarded by the mod's
 * {@code AssetCodecInitTest}), mirroring {@link RarityAsset}. An affix's stat magnitude is authored in the
 * referenced native {@code EntityEffect} ({@code EffectId}); this asset holds only mob-scaling POLICY (gating,
 * the pipeline deltas with no native path, kind + behavior dispatch).
 *
 * <p><b>Cohesive field groups are NESTED sub-objects</b> (the schema-design rule): the roll gate is
 * {@code Roll} (weight + band + rarity allow-list, the same group shape as {@code RarityAsset.Roll}),
 * the pipeline fold contributions are {@code FoldDeltas}.
 *
 * <p>Pack JSON shape (all fields optional; absent = the documented default):
 * <pre>{@code
 * { "Name": "armored", "DisplayNameKey": "scaling.affix.armored.name",
 *   "DescriptionKey": "scaling.affix.armored.desc",
 *   "EffectId": "Mmoscaling_Affix_Armored",
 *   "Roll": { "Weight": 3.0, "MinDifficulty": 5, "AllowedRarities": ["*"] },
 *   "FoldDeltas": { "Hp": 0.15, "OutDamage": 0.0, "InDamage": 0.0, "LootBonus": 0.0 },
 *   "Kind": "STAT", "ResistanceBearing": true,
 *   "Icon": { "ItemId": "Armor_Bronze_Chest" } }
 * }</pre>
 *
 * <p>The optional {@code Icon} is the shared {@link IconSpec} (an {@code ItemId} rendered as that item's
 * generated icon, OR a Common-rooted {@code TexturePath} such as {@code "UI/StatusEffects/Stamina.png"});
 * the inspector HUD renders it as the affix chip's leading glyph, absent = the chip shows its label only.
 */
public final class AffixAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, AffixAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String displayNameKey;
    @Nullable private String descriptionKey;
    @Nullable private String effectId;
    @Nullable private Roll roll;
    @Nullable private FoldDeltas foldDeltas;
    @Nullable private String kind;
    @Nullable private String behaviorId;
    private boolean resistanceBearing = false;
    @Nullable private IconSpec icon;

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
            .append(new KeyedCodec<>("DisplayNameKey", Codec.STRING, false),
                    (a, v) -> a.displayNameKey = v, a -> a.displayNameKey)
            .add()
            .append(new KeyedCodec<>("DescriptionKey", Codec.STRING, false),
                    (a, v) -> a.descriptionKey = v, a -> a.descriptionKey)
            .add()
            .append(new KeyedCodec<>("EffectId", Codec.STRING, false), (a, v) -> a.effectId = v, a -> a.effectId)
            .add()
            // The roll gate: pick weight, difficulty band, and which rarities may draw this affix.
            .append(new KeyedCodec<>("Roll", Roll.CODEC, false), (a, v) -> a.roll = v, a -> a.roll)
            .add()
            // Additive pipeline-fold contributions (ONLY the axes with no native effect path).
            .append(new KeyedCodec<>("FoldDeltas", FoldDeltas.CODEC, false),
                    (a, v) -> a.foldDeltas = v, a -> a.foldDeltas)
            .add()
            .append(new KeyedCodec<>("Kind", Codec.STRING, false), (a, v) -> a.kind = v, a -> a.kind)
            .add()
            .append(new KeyedCodec<>("BehaviorId", Codec.STRING, false), (a, v) -> a.behaviorId = v, a -> a.behaviorId)
            .add()
            .append(new KeyedCodec<>("ResistanceBearing", Codec.BOOLEAN, false),
                    (a, v) -> a.resistanceBearing = v, a -> a.resistanceBearing)
            .add()
            // Optional inspector-HUD chip icon (shared IconSpec: an item id or a Common-rooted texture path).
            .append(new KeyedCodec<>("Icon", IconSpec.CODEC, false), (a, v) -> a.icon = v, a -> a.icon)
            .add()
            .build();

    public AffixAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Build the runtime {@link Affix} (the map key is the id). Absent groups/leaves take the neutral
     * defaults (weight 1, no band gate, zero deltas). Absent {@code Roll.AllowedRarities} ->
     * {@code ["*"]} (all); absent {@code Kind} -> {@code STAT}; absent display/description keys stay
     * {@code ""} so the text util falls back to a convention key.
     */
    @Nonnull
    public Affix toAffix() {
        double weight = roll != null && roll.weight != null ? roll.weight : 1.0;
        double minDifficulty = roll != null && roll.minDifficulty != null ? roll.minDifficulty : 0.0;
        List<String> allowed = roll != null && roll.allowedRarities != null
                ? List.of(roll.allowedRarities) : List.of("*");
        double outDelta = delta(foldDeltas != null ? foldDeltas.outDamage : null);
        double inDelta = delta(foldDeltas != null ? foldDeltas.inDamage : null);
        double hpDelta = delta(foldDeltas != null ? foldDeltas.hp : null);
        double lootBonus = delta(foldDeltas != null ? foldDeltas.lootBonus : null);
        String nameKey = displayNameKey != null ? displayNameKey : "";
        String descKey = descriptionKey != null ? descriptionKey : "";
        String k = kind != null ? kind : Affix.KIND_STAT;
        String iconItemId = icon != null ? icon.itemId() : null;
        String iconTexturePath = icon != null ? icon.texturePath() : null;
        return new Affix(id, nameKey, descKey, effectId, weight, minDifficulty, allowed,
                outDelta, inDelta, hpDelta, lootBonus, k, behaviorId, resistanceBearing,
                iconItemId, iconTexturePath);
    }

    private static double delta(@Nullable Double v) {
        return v != null ? v : 0.0;
    }

    /** The roll gate: pick weight + difficulty band + the rarity allow-list ({@code ["*"]} = any). */
    public static final class Roll {
        public static final BuilderCodec<Roll> CODEC = BuilderCodec.builder(Roll.class, Roll::new)
                .append(new KeyedCodec<>("Weight", Codec.DOUBLE, false),
                        (r, v) -> r.weight = v, r -> r.weight)
                .add()
                .append(new KeyedCodec<>("MinDifficulty", Codec.DOUBLE, false),
                        (r, v) -> r.minDifficulty = v, r -> r.minDifficulty)
                .add()
                .append(new KeyedCodec<>("AllowedRarities", Codec.STRING_ARRAY, false),
                        (r, v) -> r.allowedRarities = v, r -> r.allowedRarities)
                .add()
                .build();

        @Nullable private Double weight;
        @Nullable private Double minDifficulty;
        @Nullable private String[] allowedRarities;
    }

    /** Additive fold deltas on the frozen spawn result (each absent leaf = 0.0, no contribution). */
    public static final class FoldDeltas {
        public static final BuilderCodec<FoldDeltas> CODEC = BuilderCodec
                .builder(FoldDeltas.class, FoldDeltas::new)
                .append(new KeyedCodec<>("Hp", Codec.DOUBLE, false), (d, v) -> d.hp = v, d -> d.hp)
                .add()
                .append(new KeyedCodec<>("OutDamage", Codec.DOUBLE, false),
                        (d, v) -> d.outDamage = v, d -> d.outDamage)
                .add()
                .append(new KeyedCodec<>("InDamage", Codec.DOUBLE, false),
                        (d, v) -> d.inDamage = v, d -> d.inDamage)
                .add()
                .append(new KeyedCodec<>("LootBonus", Codec.DOUBLE, false),
                        (d, v) -> d.lootBonus = v, d -> d.lootBonus)
                .add()
                .build();

        @Nullable private Double hp;
        @Nullable private Double outDamage;
        @Nullable private Double inDamage;
        @Nullable private Double lootBonus;
    }
}

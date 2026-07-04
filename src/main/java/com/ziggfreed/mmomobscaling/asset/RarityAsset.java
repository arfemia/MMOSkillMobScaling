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
import com.ziggfreed.mmomobscaling.rarity.Rarity;

/**
 * A pack-authorable mob RARITY tier, loaded from {@code Server/MmoMobScaling/Rarities/*.json}. Pattern A -
 * the {@link #CODEC} decodes directly into typed fields (the codec IS the schema authority). Every
 * {@link KeyedCodec} key is PascalCase (the constructor rejects a lower-case first letter at static init;
 * the mod's {@code AssetCodecInitTest} guards it). The jar ships the starter ladder; a pack or owner
 * overrides any field by id, folded {@code defaults < pack < owner} through
 * {@link com.ziggfreed.mmomobscaling.config.RarityConfig}.
 *
 * <p><b>Cohesive field groups are NESTED sub-objects</b> (the schema-design rule): the roll gate is
 * {@code Roll}, the stat/reward multipliers are {@code Multipliers}, the affix policy is
 * {@code Affixes} - each its own {@link BuilderCodec}, so a future knob lands INSIDE its group
 * instead of growing a flat suffix-soup ({@code HpMult}/{@code OutDamageMult}/...).
 *
 * <p>Pack JSON shape (all fields optional; absent = the documented default):
 * <pre>{@code
 * { "Name": "epic", "DisplayNameKey": "scaling.rarity.epic.name", "NameColor": "#b388ff",
 *   "Roll": { "Weight": 25, "MinDifficulty": 25 },
 *   "Multipliers": { "Hp": 2.2, "OutDamage": 1.9, "InDamage": 0.7, "Loot": 1.5, "Xp": 1.3 },
 *   "Affixes": { "Slots": 2, "Allowed": ["*"] },
 *   "AuraEffectId": "Mmoscaling_Aura_Epic", "BonusDropList": "Mmoscaling_Drops_Epic" }
 * }</pre>
 */
public final class RarityAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, RarityAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String displayNameKey;
    @Nullable private String nameColor;
    @Nullable private Roll roll;
    @Nullable private Multipliers multipliers;
    @Nullable private AffixPolicy affixes;
    @Nullable private String auraEffectId;
    @Nullable private String bonusDropList;

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
            // Display colour (#rrggbb) for this tier's name on the mob-inspector HUD (and any future
            // rarity-coloured render site). Absent = plain white at render time.
            .append(new KeyedCodec<>("NameColor", Codec.STRING, false), (a, v) -> a.nameColor = v, a -> a.nameColor)
            .add()
            // The roll gate: how often this tier is picked and from which difficulty band on.
            .append(new KeyedCodec<>("Roll", Roll.CODEC, false), (a, v) -> a.roll = v, a -> a.roll)
            .add()
            // The stat/reward multipliers folded into the frozen spawn result.
            .append(new KeyedCodec<>("Multipliers", Multipliers.CODEC, false),
                    (a, v) -> a.multipliers = v, a -> a.multipliers)
            .add()
            // The affix policy: how many slots this tier rolls and which affixes it may draw.
            .append(new KeyedCodec<>("Affixes", AffixPolicy.CODEC, false), (a, v) -> a.affixes = v, a -> a.affixes)
            .add()
            .append(new KeyedCodec<>("AuraEffectId", Codec.STRING, false),
                    (a, v) -> a.auraEffectId = v, a -> a.auraEffectId)
            .add()
            .append(new KeyedCodec<>("BonusDropList", Codec.STRING, false),
                    (a, v) -> a.bonusDropList = v, a -> a.bonusDropList)
            .add()
            .build();

    public RarityAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Build the runtime {@link Rarity} (the map key is the id). Absent groups/leaves take the neutral
     * defaults (weight 1, no band gate, all multipliers 1.0, zero affix slots). An absent
     * {@code Affixes.Allowed} means "allow all" ({@code ["*"]}); an explicit empty list means "allow
     * none". An absent display key stays {@code ""} so the text util falls back to the convention key.
     */
    @Nonnull
    public Rarity toRarity() {
        double weight = roll != null && roll.weight != null ? roll.weight : 1.0;
        double minDifficulty = roll != null && roll.minDifficulty != null ? roll.minDifficulty : 0.0;
        double hp = mult(multipliers != null ? multipliers.hp : null);
        double out = mult(multipliers != null ? multipliers.outDamage : null);
        double in = mult(multipliers != null ? multipliers.inDamage : null);
        double loot = mult(multipliers != null ? multipliers.loot : null);
        double xp = mult(multipliers != null ? multipliers.xp : null);
        int slots = affixes != null && affixes.slots != null ? affixes.slots : 0;
        List<String> allowed = affixes != null && affixes.allowed != null ? List.of(affixes.allowed) : List.of("*");
        String nameKey = displayNameKey != null ? displayNameKey : "";
        String color = nameColor != null ? nameColor : "";
        return new Rarity(id, nameKey, weight, minDifficulty, hp, out, in,
                loot, xp, slots, auraEffectId, bonusDropList, allowed, color);
    }

    private static double mult(@Nullable Double v) {
        return v != null ? v : 1.0;
    }

    /** The roll gate: pick weight + the difficulty band this tier unlocks at. */
    public static final class Roll {
        public static final BuilderCodec<Roll> CODEC = BuilderCodec.builder(Roll.class, Roll::new)
                .append(new KeyedCodec<>("Weight", Codec.DOUBLE, false),
                        (r, v) -> r.weight = v, r -> r.weight)
                .add()
                .append(new KeyedCodec<>("MinDifficulty", Codec.DOUBLE, false),
                        (r, v) -> r.minDifficulty = v, r -> r.minDifficulty)
                .add()
                .build();

        @Nullable private Double weight;
        @Nullable private Double minDifficulty;
    }

    /** The stat/reward multipliers (each absent leaf = 1.0, the plain baseline). */
    public static final class Multipliers {
        public static final BuilderCodec<Multipliers> CODEC = BuilderCodec
                .builder(Multipliers.class, Multipliers::new)
                .append(new KeyedCodec<>("Hp", Codec.DOUBLE, false), (m, v) -> m.hp = v, m -> m.hp)
                .add()
                .append(new KeyedCodec<>("OutDamage", Codec.DOUBLE, false),
                        (m, v) -> m.outDamage = v, m -> m.outDamage)
                .add()
                .append(new KeyedCodec<>("InDamage", Codec.DOUBLE, false),
                        (m, v) -> m.inDamage = v, m -> m.inDamage)
                .add()
                .append(new KeyedCodec<>("Loot", Codec.DOUBLE, false), (m, v) -> m.loot = v, m -> m.loot)
                .add()
                .append(new KeyedCodec<>("Xp", Codec.DOUBLE, false), (m, v) -> m.xp = v, m -> m.xp)
                .add()
                .build();

        @Nullable private Double hp;
        @Nullable private Double outDamage;
        @Nullable private Double inDamage;
        @Nullable private Double loot;
        @Nullable private Double xp;
    }

    /** The affix policy: slot count + the allow-list of affix ids ({@code ["*"]} = any). */
    public static final class AffixPolicy {
        public static final BuilderCodec<AffixPolicy> CODEC = BuilderCodec
                .builder(AffixPolicy.class, AffixPolicy::new)
                .append(new KeyedCodec<>("Slots", Codec.INTEGER, false), (p, v) -> p.slots = v, p -> p.slots)
                .add()
                .append(new KeyedCodec<>("Allowed", Codec.STRING_ARRAY, false),
                        (p, v) -> p.allowed = v, p -> p.allowed)
                .add()
                .build();

        @Nullable private Integer slots;
        @Nullable private String[] allowed;
    }
}

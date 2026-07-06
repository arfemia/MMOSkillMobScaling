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
import com.ziggfreed.mmomobscaling.family.FamilyFilter;
import com.ziggfreed.mmomobscaling.variant.Variant;

/**
 * A pack-authorable mob VARIANT: a family-flavored OVERLAY that stacks on a base rarity, loaded from
 * {@code Server/MmoMobScaling/Variants/*.json}. Pattern A (the {@link #CODEC} IS the schema, PascalCase keys,
 * guarded by {@code AssetCodecInitTest}), mirroring {@link RarityAsset} but with two deliberate differences:
 * the roll gate is an ABSOLUTE {@code Chance} (not a relative {@code Weight}, since a variant is an
 * independent yes/no overlay rolled separately from the rarity ladder - see
 * {@link com.ziggfreed.mmomobscaling.variant.VariantRoster}), and there is NO {@code AuraEffectId}/
 * {@code BonusDropList} (the rarity owns the single body-tint + bonus-drop channels; a variant's identity is
 * its name decoration + its granted affix(es) + its multiplier stack).
 *
 * <p><b>Cohesive field groups are NESTED sub-objects</b> (the schema rule): {@code Roll} (chance + band +
 * family... no, family is its own group), {@code Multipliers}, {@code Affixes}, {@code Families} - each its
 * own {@link BuilderCodec}. The {@code Families} block is the SAME shape the rarity gate uses (native
 * {@code NPCGroup} ids + role globs, deny wins, absent = allow-all), so a variant reuses the exact matcher.
 *
 * <p>Pack JSON shape (all fields optional; absent = the documented default):
 * <pre>{@code
 * { "Name": "horrific", "DisplayNameKey": "scaling.variant.horrific.name", "NameColor": "#7cb342",
 *   "Roll": { "Chance": 0.15, "MinDifficulty": 20 },
 *   "Multipliers": { "Hp": 1.5, "OutDamage": 1.4, "InDamage": 0.9, "Loot": 1.3, "Xp": 1.2 },
 *   "Affixes": { "Slots": 1, "Allowed": ["venomous"] },
 *   "Families": { "AllowGroups": ["Spiders"], "AllowRoles": ["Spider*"] } }
 * }</pre>
 */
public final class VariantAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, VariantAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String displayNameKey;
    @Nullable private String nameColor;
    @Nullable private Roll roll;
    @Nullable private Multipliers multipliers;
    @Nullable private AffixPolicy affixes;
    @Nullable private Families families;
    @Nullable private String auraEffectId;
    @Nullable private String bonusDropList;

    public static final AssetBuilderCodec<String, VariantAsset> CODEC = AssetBuilderCodec.builder(
                    VariantAsset.class,
                    VariantAsset::new,
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
            .append(new KeyedCodec<>("NameColor", Codec.STRING, false), (a, v) -> a.nameColor = v, a -> a.nameColor)
            .add()
            .append(new KeyedCodec<>("Roll", Roll.CODEC, false), (a, v) -> a.roll = v, a -> a.roll)
            .add()
            .append(new KeyedCodec<>("Multipliers", Multipliers.CODEC, false),
                    (a, v) -> a.multipliers = v, a -> a.multipliers)
            .add()
            .append(new KeyedCodec<>("Affixes", AffixPolicy.CODEC, false), (a, v) -> a.affixes = v, a -> a.affixes)
            .add()
            .append(new KeyedCodec<>("Families", Families.CODEC, false), (a, v) -> a.families = v, a -> a.families)
            .add()
            // Optional aura effect (an Mmoscaling_* infinite EntityEffect); applied ONLY when the base rarity
            // has no aura of its own (the rarity owns the single body-tint channel), so it tints a variant on
            // a plain mob without fighting a rarity aura.
            .append(new KeyedCodec<>("AuraEffectId", Codec.STRING, false),
                    (a, v) -> a.auraEffectId = v, a -> a.auraEffectId)
            .add()
            // Optional native ItemDropList pulled on death IN ADDITION to the base rarity's bonus drops.
            .append(new KeyedCodec<>("BonusDropList", Codec.STRING, false),
                    (a, v) -> a.bonusDropList = v, a -> a.bonusDropList)
            .add()
            .build();

    public VariantAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Build the runtime {@link Variant} (the map key is the id). Absent groups/leaves take the neutral
     * defaults (chance 0 = not rollable, no band gate, all multipliers 1.0, zero affix slots). An absent
     * {@code Affixes.Allowed} means "allow all" ({@code ["*"]}); an explicit empty list means "allow none".
     * An absent {@code Families} block = {@link FamilyFilter#ALLOW_ALL} (every mob eligible).
     */
    @Nonnull
    public Variant toVariant() {
        double chance = roll != null && roll.chance != null ? roll.chance : 0.0;
        double minDifficulty = roll != null && roll.minDifficulty != null ? roll.minDifficulty : 0.0;
        double hp = mult(multipliers != null ? multipliers.hp : null);
        double out = mult(multipliers != null ? multipliers.outDamage : null);
        double in = mult(multipliers != null ? multipliers.inDamage : null);
        double loot = mult(multipliers != null ? multipliers.loot : null);
        double xp = mult(multipliers != null ? multipliers.xp : null);
        int slots = affixes != null && affixes.slots != null ? affixes.slots : 0;
        List<String> allowed = affixes != null && affixes.allowed != null ? List.of(affixes.allowed) : List.of("*");
        // Absent AllowedRarities -> ["*"] = overlays ANY base rarity (including a plain mob); an explicit
        // list requires the base rarity id to match (a plain mob's "" only matches "*").
        List<String> allowedRarities = roll != null && roll.allowedRarities != null
                ? List.of(roll.allowedRarities) : List.of("*");
        String nameKey = displayNameKey != null ? displayNameKey : "";
        String color = nameColor != null ? nameColor : "";
        FamilyFilter filter = families != null ? families.toFilter() : FamilyFilter.ALLOW_ALL;
        return new Variant(id, nameKey, chance, minDifficulty, hp, out, in, loot, xp, slots, allowed,
                allowedRarities, auraEffectId, bonusDropList, color, filter);
    }

    private static double mult(@Nullable Double v) {
        return v != null ? v : 1.0;
    }

    private static List<String> list(@Nullable String[] arr) {
        return arr != null ? List.of(arr) : List.of();
    }

    /**
     * The roll gate: this variant's absolute spawn chance, the difficulty band it unlocks at, and which
     * BASE rarities it may overlay ({@code AllowedRarities}, absent = {@code ["*"]} = any incl. plain).
     */
    public static final class Roll {
        public static final BuilderCodec<Roll> CODEC = BuilderCodec.builder(Roll.class, Roll::new)
                .append(new KeyedCodec<>("Chance", Codec.DOUBLE, false),
                        (r, v) -> r.chance = v, r -> r.chance)
                .add()
                .append(new KeyedCodec<>("MinDifficulty", Codec.DOUBLE, false),
                        (r, v) -> r.minDifficulty = v, r -> r.minDifficulty)
                .add()
                .append(new KeyedCodec<>("AllowedRarities", Codec.STRING_ARRAY, false),
                        (r, v) -> r.allowedRarities = v, r -> r.allowedRarities)
                .add()
                .build();

        @Nullable private Double chance;
        @Nullable private Double minDifficulty;
        @Nullable private String[] allowedRarities;
    }

    /** The stat/reward multipliers (each absent leaf = 1.0), stacked MULTIPLICATIVELY on the base rarity. */
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

    /** The affix policy: slot count + the allow-list of affix ids ({@code ["*"]} = any) this variant grants. */
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

    /**
     * The mob-family gate (identical shape to {@code RarityAsset.Families}): native {@code NPCGroup} ids
     * ({@code AllowGroups}/{@code DenyGroups}) + role-name globs ({@code AllowRoles}/{@code DenyRoles}). Deny
     * wins; an empty allow side allows all; an absent/empty block = {@link FamilyFilter#ALLOW_ALL}.
     */
    public static final class Families {
        public static final BuilderCodec<Families> CODEC = BuilderCodec.builder(Families.class, Families::new)
                .append(new KeyedCodec<>("AllowGroups", Codec.STRING_ARRAY, false),
                        (f, v) -> f.allowGroups = v, f -> f.allowGroups)
                .add()
                .append(new KeyedCodec<>("DenyGroups", Codec.STRING_ARRAY, false),
                        (f, v) -> f.denyGroups = v, f -> f.denyGroups)
                .add()
                .append(new KeyedCodec<>("AllowRoles", Codec.STRING_ARRAY, false),
                        (f, v) -> f.allowRoles = v, f -> f.allowRoles)
                .add()
                .append(new KeyedCodec<>("DenyRoles", Codec.STRING_ARRAY, false),
                        (f, v) -> f.denyRoles = v, f -> f.denyRoles)
                .add()
                .build();

        @Nullable private String[] allowGroups;
        @Nullable private String[] denyGroups;
        @Nullable private String[] allowRoles;
        @Nullable private String[] denyRoles;

        @Nonnull
        FamilyFilter toFilter() {
            return new FamilyFilter(list(allowGroups), list(denyGroups), list(allowRoles), list(denyRoles));
        }
    }
}

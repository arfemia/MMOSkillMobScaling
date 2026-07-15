package com.ziggfreed.mmomobscaling.asset;

import java.util.ArrayList;
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
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.mmomobscaling.caster.CasterEntry;
import com.ziggfreed.mmomobscaling.caster.CasterRoster;

/**
 * A pack-authorable NPC caster roster, loaded from {@code Server/MmoMobScaling/CasterRosters/*.json}.
 * Pattern A - the {@link #CODEC} decodes directly into typed fields (the codec IS the schema
 * authority), PascalCase keys, mirroring {@link RarityAsset}. {@code id} is the asset filename.
 *
 * <p>Binds a {@code Role} selector to a list of abilities a matching mob arms at spawn and casts on
 * its own cadence (see {@code event/MobScalingCasterArmSystem} / {@code MobScalingCasterTickSystem}).
 * The gate model deliberately mirrors {@code scaling/MobScaleResult} (a difficulty FLOAT, a rarity ID
 * STRING, a scope BYTE) - there is no integer "tier" concept anywhere in this mod.
 *
 * <p><b>Cohesive field groups are NESTED sub-objects</b>: the role selector is {@code Role} (its own
 * {@link BuilderCodec}); each {@code Abilities[]} element is its own {@link AbilityEntry} group.
 *
 * <p>Pack JSON shape:
 * <pre>{@code
 * { "Name": "demo_boss_caster",
 *   "Role": { "Id": "Dragon_Fire" },
 *   "Abilities": [
 *     { "AbilityId": "fireball", "MinDifficulty": 20, "Scope": "BOSS",
 *       "CadenceSeconds": 14, "JitterSeconds": 3,
 *       "Windup": { "Animation": "Hurt" } },
 *     { "NativeChain": "MMO_Dodge", "Scope": "BOSS",
 *       "CadenceSeconds": 6, "JitterSeconds": 2 }
 *   ] }
 * }</pre>
 *
 * <p>{@code Role} takes EXACTLY ONE of {@code Id} (an exact role id) or {@code Glob} (native
 * {@code IncludeRoles}-style wildcard, case-insensitive) - validator-enforced
 * ({@code ScalingContentValidator.validateCasterRosters}), not a decode failure; an
 * {@code Abilities[]} element takes EXACTLY ONE of {@code AbilityId} (an MMO ability id, cast via
 * {@code MMOSkillTreeAPI.castNpcAbility}) or {@code NativeChain} (a {@code RootInteraction} id, armed
 * via native {@code CombatSupport.addAttackOverride}) - same validator-enforced XOR, both directions
 * degrade to a harmless no-op entry rather than poisoning the whole roster.
 *
 * <p>An {@code Abilities[]} element may also carry an OPTIONAL nested {@code Windup} group (a cast
 * wind-up animation cue played immediately before the cast fires; see {@link Windup} and
 * {@link CasterEntry.Windup}). {@code Windup} only takes effect on an {@code AbilityId} entry - a
 * {@code NativeChain} entry arms once at spawn and never ticks, so an authored {@code Windup} there is
 * validator-flagged as ineffective.
 */
public final class CasterRosterAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, CasterRosterAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private RoleSelector role;
    @Nullable private AbilityEntry[] abilities;

    public static final AssetBuilderCodec<String, CasterRosterAsset> CODEC = AssetBuilderCodec.builder(
                    CasterRosterAsset.class,
                    CasterRosterAsset::new,
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
            // Which mob role(s) this roster targets: exactly one of Id (exact) / Glob (wildcard).
            .append(new KeyedCodec<>("Role", RoleSelector.CODEC, false), (a, v) -> a.role = v, a -> a.role)
            .add()
            // The abilities a matching mob arms at spawn (each entry: AbilityId XOR NativeChain + gates).
            .append(new KeyedCodec<>("Abilities", new ArrayCodec<>(AbilityEntry.CODEC, AbilityEntry[]::new), false),
                    (a, v) -> a.abilities = v, a -> a.abilities)
            .add()
            .build();

    public CasterRosterAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Build the runtime {@link CasterRoster}. Absent {@code Role} -&gt; neither selector authored (the
     * roster never matches anything; validator-flagged). An {@code Abilities[]} element missing exactly
     * one of {@code AbilityId}/{@code NativeChain} decodes with {@link CasterEntry.Kind#INVALID} (kept,
     * not dropped, so the validator can see and flag it) rather than vanishing silently.
     */
    @Nonnull
    public CasterRoster toDomain() {
        String roleId = role != null ? blankToNull(role.id) : null;
        String roleGlob = role != null ? blankToNull(role.glob) : null;
        List<CasterEntry> entries = new ArrayList<>();
        if (abilities != null) {
            for (AbilityEntry e : abilities) {
                if (e != null) {
                    entries.add(e.toDomain());
                }
            }
        }
        return new CasterRoster(id, roleId, roleGlob, entries);
    }

    @Nullable
    private static String blankToNull(@Nullable String v) {
        return (v == null || v.isBlank()) ? null : v;
    }

    /** Which mob role(s) this roster targets: {@code Id} (exact) XOR {@code Glob} (wildcard). */
    public static final class RoleSelector {
        public static final BuilderCodec<RoleSelector> CODEC = BuilderCodec.builder(RoleSelector.class, RoleSelector::new)
                .append(new KeyedCodec<>("Id", Codec.STRING, false), (r, v) -> r.id = v, r -> r.id)
                .add()
                .append(new KeyedCodec<>("Glob", Codec.STRING, false), (r, v) -> r.glob = v, r -> r.glob)
                .add()
                .build();

        @Nullable private String id;
        @Nullable private String glob;
    }

    /**
     * One {@code Abilities[]} entry: {@code AbilityId} XOR {@code NativeChain}, plus the gates
     * ({@code MinDifficulty}, {@code Rarities}, {@code Scope}) and cadence ({@code CadenceSeconds},
     * {@code JitterSeconds}). Every leaf is a nullable wrapper; {@link #toDomain()} applies the
     * documented neutral defaults (0 difficulty, any rarity, {@code ANY} scope, 0 jitter).
     */
    public static final class AbilityEntry {
        public static final BuilderCodec<AbilityEntry> CODEC = BuilderCodec.builder(AbilityEntry.class, AbilityEntry::new)
                .append(new KeyedCodec<>("AbilityId", Codec.STRING, false), (e, v) -> e.abilityId = v, e -> e.abilityId)
                .add()
                .append(new KeyedCodec<>("NativeChain", Codec.STRING, false), (e, v) -> e.nativeChain = v, e -> e.nativeChain)
                .add()
                .append(new KeyedCodec<>("MinDifficulty", Codec.DOUBLE, false),
                        (e, v) -> e.minDifficulty = v, e -> e.minDifficulty)
                .add()
                .append(new KeyedCodec<>("Rarities", Codec.STRING_ARRAY, false), (e, v) -> e.rarities = v, e -> e.rarities)
                .add()
                .append(new KeyedCodec<>("Scope", Codec.STRING, false), (e, v) -> e.scope = v, e -> e.scope)
                .add()
                .append(new KeyedCodec<>("CadenceSeconds", Codec.DOUBLE, false),
                        (e, v) -> e.cadenceSeconds = v, e -> e.cadenceSeconds)
                .add()
                .append(new KeyedCodec<>("JitterSeconds", Codec.DOUBLE, false),
                        (e, v) -> e.jitterSeconds = v, e -> e.jitterSeconds)
                .add()
                .append(new KeyedCodec<>("Windup", Windup.CODEC, false), (e, v) -> e.windup = v, e -> e.windup)
                .add()
                .build();

        @Nullable private String abilityId;
        @Nullable private String nativeChain;
        @Nullable private Double minDifficulty;
        @Nullable private String[] rarities;
        @Nullable private String scope;
        @Nullable private Double cadenceSeconds;
        @Nullable private Double jitterSeconds;
        @Nullable private Windup windup;

        @Nonnull
        CasterEntry toDomain() {
            String ability = blankToNull(abilityId);
            String chain = blankToNull(nativeChain);
            boolean hasAbility = ability != null;
            boolean hasChain = chain != null;
            CasterEntry.Kind kind = hasAbility != hasChain
                    ? (hasAbility ? CasterEntry.Kind.ABILITY : CasterEntry.Kind.NATIVE_CHAIN)
                    : CasterEntry.Kind.INVALID; // neither or both authored
            double diff = minDifficulty != null ? minDifficulty : 0.0;
            List<String> rarityList = rarities != null ? List.of(rarities) : List.of();
            CasterEntry.Scope resolvedScope = CasterEntry.Scope.tryParse(scope);
            boolean unknown = resolvedScope == null;
            CasterEntry.Scope scopeOrAny = resolvedScope != null ? resolvedScope : CasterEntry.Scope.ANY;
            long cadenceMs = cadenceSeconds != null ? Math.round(cadenceSeconds * 1000.0) : 0L;
            long jitterMs = jitterSeconds != null ? Math.round(jitterSeconds * 1000.0) : 0L;
            CasterEntry.Windup windupDomain = windup != null ? windup.toDomain() : null;
            return new CasterEntry(kind, hasAbility ? ability : null, hasChain ? chain : null, diff, rarityList,
                    scopeOrAny, unknown, cadenceMs, jitterMs, windupDomain);
        }
    }

    /**
     * The OPTIONAL cast wind-up animation cue for an {@code Abilities[]} entry (nested group, leaves
     * nullable per this mod's HARD RULE #2). {@code Animation} is EITHER a model-level {@code AnimationSets}
     * key (played on {@code Status} by default) or, when {@code ItemAnimations} is also authored, the
     * paired {@code ItemPlayerAnimations} animation id (played on {@code Action} by default); {@code Slot}
     * overrides the default. See {@link CasterEntry.Windup} for the full field semantics.
     */
    public static final class Windup {
        public static final BuilderCodec<Windup> CODEC = BuilderCodec.builder(Windup.class, Windup::new)
                .append(new KeyedCodec<>("Animation", Codec.STRING, false), (w, v) -> w.animation = v, w -> w.animation)
                .add()
                .append(new KeyedCodec<>("ItemAnimations", Codec.STRING, false),
                        (w, v) -> w.itemAnimations = v, w -> w.itemAnimations)
                .add()
                .append(new KeyedCodec<>("Slot", Codec.STRING, false), (w, v) -> w.slot = v, w -> w.slot)
                .add()
                .build();

        @Nullable private String animation;
        @Nullable private String itemAnimations;
        @Nullable private String slot;

        /**
         * Always returns a non-null domain object when the {@code Windup} group itself is present, even
         * with a blank {@code Animation} - kept, not dropped, so {@code ScalingContentValidator} can flag
         * the blank-but-group-present authoring mistake rather than it silently vanishing.
         */
        @Nonnull
        CasterEntry.Windup toDomain() {
            return new CasterEntry.Windup(blankToNull(animation) != null ? animation : "",
                    blankToNull(itemAnimations), blankToNull(slot));
        }
    }
}

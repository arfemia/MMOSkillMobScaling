package com.ziggfreed.mmomobscaling.caster;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * One resolved caster-roster ability entry (the runtime model decoded from an {@code Abilities[]}
 * element of a {@code Server/MmoMobScaling/CasterRosters/*.json}
 * {@link com.ziggfreed.mmomobscaling.asset.CasterRosterAsset}). Immutable, pure data - no engine
 * coupling - so the eligibility GATE ({@link #isEligible}) is directly unit-testable (the
 * {@code MobScalingGate} split precedent: keep the decision pure, let the engine-coupled
 * {@code RefSystem}/{@code EntityTickingSystem} only call it).
 *
 * <p>Exactly ONE of {@link #abilityId} / {@link #nativeChain} is authored ({@link #kind} reflects
 * which; {@link Kind#INVALID} when the entry authored neither or both - the arm system skips an
 * {@code INVALID} entry, {@code ScalingContentValidator} flags it as content). An {@code ABILITY}
 * entry is cast via the frozen {@code MMOSkillTreeAPI.castNpcAbility}; a {@code NATIVE_CHAIN} entry
 * is armed via the native {@code CombatSupport.addAttackOverride} (see {@code caster/NativeChainAttacker}).
 *
 * <p>An {@code ABILITY} entry may also carry an OPTIONAL {@link #windup}, played by
 * {@code MobScalingCasterTickSystem} on the mob's ref immediately before the cast fires, same tick.
 * {@code NATIVE_CHAIN} entries never tick here (armed once at spawn) so an authored {@link #windup} on
 * one is inert - {@code ScalingContentValidator} flags that authoring mistake.
 */
public record CasterEntry(
        @Nonnull Kind kind,
        @Nullable String abilityId,
        @Nullable String nativeChain,
        double minDifficulty,
        @Nonnull List<String> rarities,
        @Nonnull Scope scope,
        boolean scopeUnknown,
        long cadenceMs,
        long jitterMs,
        @Nullable Windup windup) {

    public CasterEntry {
        rarities = List.copyOf(rarities);
    }

    /** Which half of the {@code AbilityId} XOR {@code NativeChain} authoring choice this entry made. */
    public enum Kind {
        ABILITY,
        NATIVE_CHAIN,
        /** Neither or both of {@code AbilityId}/{@code NativeChain} were authored - a no-op, content bug. */
        INVALID
    }

    /** The {@code Scope} gate: which of a mob's classification scopes this entry may fire on. */
    public enum Scope {
        HOSTILE,
        BOSS,
        ANY;

        /** Parse a codec string (any case); absent -&gt; {@link #ANY} (the documented default). */
        @Nonnull
        public static Scope parse(@Nullable String raw) {
            Scope parsed = tryParse(raw);
            return parsed != null ? parsed : ANY;
        }

        /** {@code null} when {@code raw} is present but not one of {@code HOSTILE|BOSS|ANY} (unknown content). */
        @Nullable
        public static Scope tryParse(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return ANY;
            }
            return switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "HOSTILE" -> HOSTILE;
                case "BOSS" -> BOSS;
                case "ANY" -> ANY;
                default -> null;
            };
        }

        /** True when a mob classified with {@code scopeByte} ({@link MobScaleResult#SCOPE_HOSTILE}/
         *  {@link MobScaleResult#SCOPE_BOSS}) satisfies this gate. */
        public boolean matches(byte scopeByte) {
            return switch (this) {
                case ANY -> true;
                case HOSTILE -> scopeByte == MobScaleResult.SCOPE_HOSTILE;
                case BOSS -> scopeByte == MobScaleResult.SCOPE_BOSS;
            };
        }
    }

    /**
     * True when this entry may roll for a mob of the given rarity id. Empty {@link #rarities} (the
     * absent-authoring default) allows any rarity, including a plain mob ({@code rarityId} {@code ""});
     * a wildcard {@code "*"} entry allows any too. Otherwise the (case-insensitive) rarity id must be
     * explicitly listed.
     */
    public boolean allowsRarity(@Nonnull String rarityId) {
        if (rarities.isEmpty()) {
            return true;
        }
        String want = rarityId.toLowerCase(Locale.ROOT);
        for (String r : rarities) {
            if (r == null) {
                continue;
            }
            if ("*".equals(r) || r.toLowerCase(Locale.ROOT).equals(want)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The full per-mob eligibility gate: the mob's scaled difficulty clears {@link #minDifficulty},
     * its rolled rarity id is allowed, and its classification {@code scope} byte satisfies
     * {@link #scope}. Pure; called once per candidate mob at arm time.
     */
    public boolean isEligible(float difficulty, @Nonnull String rarityId, byte scopeByte) {
        return kind != Kind.INVALID
                && difficulty >= minDifficulty
                && allowsRarity(rarityId)
                && scope.matches(scopeByte);
    }

    /**
     * An OPTIONAL cast wind-up animation cue for an {@code ABILITY} entry (see the class javadoc for
     * when it plays). Kept as a domain object even when {@link #animation} is blank - a group present
     * with a blank {@code Animation} is a no-op {@code ScalingContentValidator} flags rather than
     * silently dropping (the same "kept, not dropped, so the validator can see it" convention as
     * {@link Kind#INVALID}) - so a caller MUST blank-check before playing.
     *
     * <p>{@link #animation} is EITHER a model-level {@code AnimationSets} key (played on the
     * {@code Status} slot by default) OR, when {@link #itemAnimations} is also authored, the paired
     * {@code ItemPlayerAnimations} animation id (played on the {@code Action} slot by default). The
     * engine validates a model-level key against the model's own animation set map for every slot
     * EXCEPT {@code Action}/{@code Emote}; an item-anim pair skips that validation entirely and simply
     * no-ops client-side on a rig mismatch. {@link #slot} overrides the default slot choice.
     *
     * <p>Model-key EXISTENCE per mob role is NOT validated anywhere in this mod (model assets are
     * engine-side, read only at animation-play time) - a bad key logs a per-minute engine warning and
     * no-ops rather than failing content validation.
     */
    public record Windup(@Nonnull String animation, @Nullable String itemAnimations, @Nullable String slot) {

        public Windup {
            animation = animation == null ? "" : animation;
        }

        /** True when {@link #itemAnimations} is authored - the pair plays on {@code Action} by default. */
        public boolean isItemAnim() {
            return itemAnimations != null && !itemAnimations.isBlank();
        }
    }
}

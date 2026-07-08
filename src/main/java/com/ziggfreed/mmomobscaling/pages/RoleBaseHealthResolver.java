package com.ziggfreed.mmomobscaling.pages;

import java.lang.reflect.Field;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.asset.builder.BuilderManager;
import com.hypixel.hytale.server.npc.asset.builder.holder.IntHolder;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.builders.BuilderRole;
import com.hypixel.hytale.server.npc.role.builders.BuilderRoleVariant;
import com.hypixel.hytale.server.npc.util.expression.ExecutionContext;
import com.hypixel.hytale.server.npc.util.expression.Scope;

/**
 * Reflective, DISPLAY-ONLY resolver for a role's declared base {@code MaxHealth} (e.g. {@code 92} for
 * the vanilla {@code Skeleton} role) - backs the absolute HP number beside the skeleton-preview
 * multipliers on the mob-scaling admin page ({@code /mobscaling ui}, Global tab). Never used on a
 * gameplay path (spawn/damage/loot); a wrong or absent read only means the preview shows
 * multipliers-only for that session.
 *
 * <p><b>Two independent sources, observed preferred (round-3 hardening).</b> {@link #recordObserved}
 * lets {@code event.MobScalingSpawnHook} feed this resolver a role's ACTUAL pre-scale base max health,
 * read straight off a real spawn's balanced {@code EntityStatMap} BEFORE this mod's own HP modifier
 * touches it - ground truth that already includes native balancing PLUS whatever any earlier-ordered
 * mod stacked on top, not just the raw authored JSON. {@link #baseMaxHealth} always checks that cache
 * FIRST; only when no mob of that role has spawned yet this session does it fall back to the reflective
 * template read below.
 *
 * <p><b>Why reflection is acceptable for the template-read fallback.</b> The role registry read itself
 * is entirely PUBLIC and entity-free: {@link NPCPlugin#getIndex(String)} -&gt;
 * {@link NPCPlugin#getRoleBuilderInfo(int)} -&gt; {@link BuilderInfo#getBuilder()} returns the
 * already-PARSED {@link Builder}{@code <Role>} straight off the loaded-asset registry, no spawned
 * {@code NPCEntity} involved. The engine's OWN validation pass proves the EVALUATION is equally
 * entity-free: {@code BuilderManager.validateAllSpawnableNPCs} (shared source,
 * {@code BuilderManager.java:834}) builds its {@link ExecutionContext} from JUST
 * {@code builder.getBuilderParameters().createScope()} - no holder, no world, no player - to validate
 * every spawnable role at asset-load time, long before anything is spawned, and resolves a
 * {@link BuilderRoleVariant}'s modifier scope the exact same way {@link #resolveVariant} does here
 * ({@code spawnableBuilder.createModifierScope(context)}, a PUBLIC method). The ONLY non-public link in
 * either chain is {@link BuilderRole}'s {@code protected final IntHolder maxHealth} field: its PUBLIC
 * accessor ({@code getMaxHealth(BuilderSupport)}) demands a {@code BuilderSupport} wrapper that in turn
 * demands a live {@code NPCEntity} + {@code Holder<EntityStore>} purely as an API-surface artifact of
 * the shared multi-field builder-support plumbing, NOT because reading a simple int field needs one.
 * Reflection bridges exactly that one hop (read the field, never write it), fully
 * {@code try/catch(Throwable)}-guarded with permanent per-session memoization on ANY failure (a missing
 * role, an engine refactor renaming/removing the field, a module system denying the accessible flag,
 * ...) so a broken read degrades silently to "unknown" - the preview falls back to multipliers-only -
 * and is never retried on every keystroke, never a page crash.
 *
 * <p><b>Two role shapes, one evaluation contract.</b> A PLAIN {@link BuilderRole} (a role authored
 * directly, no {@code Variant}) evaluates its OWN {@code maxHealth} holder against an
 * {@link ExecutionContext} seeded from ITS OWN {@code getBuilderParameters().createScope()}. A VARIANT
 * role (e.g. the vanilla {@code Skeleton}: {@code "Type": "Variant", "Reference":
 * "Template_Intelligent", "Modify": {"MaxHealth": 92, ...}}) is a {@link BuilderRoleVariant}, NOT a
 * {@link BuilderRole} - its {@code Modify} block lives on a DIFFERENT class with no {@code maxHealth}
 * field at all; the role's real base health instead lives on the TERMINAL template's {@code MaxHealth}
 * holder (for {@code Skeleton}, {@code Template_Intelligent.json}'s {@code "MaxHealth": {"Compute":
 * "MaxHealth"}} - a {@code Compute} expression, so it MUST be evaluated with a real folded scope; the
 * {@code isStatic()}/{@code rawGet(null)} shortcut would NPE on it). {@link #resolveVariant} mirrors
 * {@code validateAllSpawnableNPCs} exactly: seed an {@link ExecutionContext} from the VARIANT's OWN
 * builder parameters, fold the WHOLE chain via the variant's public
 * {@code createModifierScope(ExecutionContext)} (which internally walks nested variants-of-variants,
 * the same {@code while (roleBuilder instanceof BuilderRoleVariant)} loop mirrored here to independently
 * find the TERMINAL {@link BuilderRole} OBJECT - {@code createModifierScope} only returns the folded
 * {@link Scope}, never the role it was folded against), install that folded scope onto the context
 * ({@code ctx.setScope(modScope)}, the same step {@code BuilderRoleVariant.executeOnSuperRole}'s
 * Scope-taking overload performs before delegating to the referenced role), then {@code rawGet(ctx)} the
 * terminal role's {@code maxHealth} holder. Every method used to walk the chain
 * ({@code getBuilderParameters}/{@code createModifierScope}/{@code getBuilderManager}/
 * {@code getReferenceIndex}/{@code tryGetCachedValidRole}) is PUBLIC on the shared-source classes -
 * reflection is needed only for the ONE {@code maxHealth} field hop, identical to the plain-role path
 * (no second reflective field: {@code BuilderRoleVariant.getReferenceIndex()} is already public).
 */
public final class RoleBaseHealthResolver {

    /** Per-role-name memoized TEMPLATE read: a resolved (&gt;0) health, or a permanently-cached ABSENT on any failure. */
    private static final ConcurrentHashMap<String, OptionalInt> CACHE = new ConcurrentHashMap<>();

    /**
     * Observed-spawn ground truth (round-3 hardening): {@code roleName -> the pre-scale base max health}
     * {@code event.MobScalingSpawnHook} read straight off a real spawn's balanced {@code EntityStatMap},
     * before this mod's own HP modifier touched it. Checked before {@link #CACHE} in
     * {@link #baseMaxHealth} - live truth beats a re-derived template read. Last write wins: a role's
     * declared base does not change at runtime, so repeat observations simply refresh the same value.
     */
    private static final ConcurrentHashMap<String, Integer> OBSERVED = new ConcurrentHashMap<>();

    /** The reflective handle to {@code BuilderRole.maxHealth}, looked up (and made accessible) ONCE. */
    @Nullable private static volatile Field maxHealthField;
    private static volatile boolean fieldLookupFailed;

    private RoleBaseHealthResolver() {
    }

    /**
     * Record {@code roleName}'s observed pre-scale base max health from an actual spawn (see the class
     * javadoc). Ignores a non-positive reading (a not-yet-balanced stat map, or a bad upstream read) so a
     * bad sample can never poison the cache; a positive reading always overwrites whatever was cached.
     * Allocation-free, O(1); called from the spawn hook's hot path.
     */
    public static void recordObserved(@Nonnull String roleName, int baseMaxHealth) {
        if (baseMaxHealth > 0) {
            OBSERVED.put(roleName, baseMaxHealth);
        }
    }

    /**
     * The declared base {@code MaxHealth} for {@code roleName} (e.g. {@code "Skeleton"}): an observed
     * live-spawn reading when one exists (preferred - see the class javadoc), else the reflective
     * template read (memoized, both success and failure, for the process lifetime), else
     * {@link OptionalInt#empty()} when the role does not exist or nothing could be evaluated. Cheap on
     * every keystroke either way - a map lookup, or a memoized no-op repeat.
     */
    @Nonnull
    static OptionalInt baseMaxHealth(@Nonnull String roleName) {
        Integer observed = OBSERVED.get(roleName);
        if (observed != null) {
            return OptionalInt.of(observed);
        }
        return CACHE.computeIfAbsent(roleName, RoleBaseHealthResolver::resolve);
    }

    @Nonnull
    private static OptionalInt resolve(@Nonnull String roleName) {
        try {
            NPCPlugin plugin = NPCPlugin.get();
            if (plugin == null) {
                return OptionalInt.empty();
            }
            int index = plugin.getIndex(roleName);
            BuilderInfo info = plugin.getRoleBuilderInfo(index);
            if (info == null) {
                return OptionalInt.empty();
            }
            Builder<?> builder = info.getBuilder();
            if (builder instanceof BuilderRoleVariant variant) {
                return resolveVariant(variant);
            }
            if (!(builder instanceof BuilderRole role)) {
                return OptionalInt.empty();
            }
            ExecutionContext ctx = new ExecutionContext(role.getBuilderParameters().createScope());
            return readMaxHealth(role, ctx);
        } catch (Throwable t) {
            return OptionalInt.empty();
        }
    }

    /**
     * A variant-defined role: fold the {@code Modify} chain onto the TERMINAL template's scope exactly
     * how {@code BuilderManager.validateAllSpawnableNPCs} does, then reflect that terminal role's
     * {@code maxHealth} holder against the folded scope (see the class javadoc for the full walk-through).
     */
    @Nonnull
    private static OptionalInt resolveVariant(@Nonnull BuilderRoleVariant variant) {
        ExecutionContext ctx = new ExecutionContext(variant.getBuilderParameters().createScope());
        Scope modScope = variant.createModifierScope(ctx);

        // Walk the SAME reference chain createModifierScope just folded, to find the terminal BuilderRole
        // OBJECT (createModifierScope only hands back the folded Scope, not the role it was folded
        // against). Every step is PUBLIC - getReferenceIndex()/getBuilderManager()/tryGetCachedValidRole()
        // - so no second reflective field is needed here.
        BuilderManager manager = variant.getBuilderManager();
        Builder<Role> next = manager.tryGetCachedValidRole(variant.getReferenceIndex());
        while (next instanceof BuilderRoleVariant nextVariant) {
            next = manager.tryGetCachedValidRole(nextVariant.getReferenceIndex());
        }
        if (!(next instanceof BuilderRole terminal)) {
            return OptionalInt.empty();
        }
        ctx.setScope(modScope);
        return readMaxHealth(terminal, ctx);
    }

    /**
     * Evaluate {@code role}'s {@code maxHealth} holder against {@code ctx}'s CURRENT scope (the caller
     * has already seeded it: the plain path with the role's own scope, the variant path with the folded
     * modifier scope). Always {@code rawGet(ctx)}, never the {@code isStatic()}/{@code rawGet(null)}
     * shortcut the original plain-role-only version took - a static holder (e.g. a hand-authored
     * {@code "MaxHealth": 500}) ignores whatever context it is given and answers identically either way
     * ({@code BuilderExpressionStaticNumber.getNumber} never dereferences its argument), but a
     * {@code "Compute"}-driven one (every variant's terminal template) NEEDS the real context, so one
     * evaluation path safely covers both role shapes. Fully guarded: reflection failures, a
     * missing/wrong-type holder, and any evaluation throw all degrade to {@link OptionalInt#empty()}.
     */
    @Nonnull
    private static OptionalInt readMaxHealth(@Nonnull BuilderRole role, @Nonnull ExecutionContext ctx) {
        try {
            Field field = maxHealthField();
            if (field == null) {
                return OptionalInt.empty();
            }
            Object rawHolder = field.get(role);
            if (!(rawHolder instanceof IntHolder holder)) {
                return OptionalInt.empty();
            }
            int value = holder.rawGet(ctx);
            return value > 0 ? OptionalInt.of(value) : OptionalInt.empty();
        } catch (Throwable t) {
            return OptionalInt.empty();
        }
    }

    /** Look up + cache the {@code BuilderRole.maxHealth} field handle ONCE; {@code null} forever after a failure. */
    @Nullable
    private static Field maxHealthField() {
        if (fieldLookupFailed) {
            return null;
        }
        Field field = maxHealthField;
        if (field != null) {
            return field;
        }
        try {
            Field f = BuilderRole.class.getDeclaredField("maxHealth");
            f.setAccessible(true);
            maxHealthField = f;
            return f;
        } catch (Throwable t) {
            fieldLookupFailed = true;
            return null;
        }
    }
}

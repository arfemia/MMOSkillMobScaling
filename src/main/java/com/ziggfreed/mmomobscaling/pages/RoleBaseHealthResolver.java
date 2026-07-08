package com.ziggfreed.mmomobscaling.pages;

import java.lang.reflect.Field;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.asset.builder.holder.IntHolder;
import com.hypixel.hytale.server.npc.role.builders.BuilderRole;
import com.hypixel.hytale.server.npc.util.expression.ExecutionContext;

/**
 * Reflective, DISPLAY-ONLY resolver for a role's declared base {@code MaxHealth} (the raw authored
 * template value, e.g. {@code 92} for the vanilla {@code Skeleton} role) - backs the absolute HP number
 * beside the skeleton-preview multipliers on the mob-scaling admin page ({@code /mobscaling ui}, Global
 * tab). Never used on a gameplay path (spawn/damage/loot); a wrong or absent read only means the preview
 * shows multipliers-only for that session.
 *
 * <p><b>Why reflection is acceptable here.</b> The role registry read itself is entirely PUBLIC and
 * entity-free: {@link NPCPlugin#getIndex(String)} -> {@link NPCPlugin#getRoleBuilderInfo(int)} ->
 * {@link BuilderInfo#getBuilder()} returns the already-PARSED {@link Builder}{@code <Role>} (a
 * {@link BuilderRole} for a role asset) straight off the loaded-asset registry, no spawned
 * {@code NPCEntity} involved. The engine's OWN validation pass proves the EVALUATION is equally
 * entity-free: {@code BuilderManager.validateAllSpawnableNPCs} (shared source,
 * {@code BuilderManager.java:834}) builds its {@code ExecutionContext} from JUST
 * {@code builder.getBuilderParameters().createScope()} - no holder, no world, no player - to validate
 * every spawnable role at asset-load time, long before anything is spawned. The ONLY non-public link in
 * that chain is {@link BuilderRole}'s {@code protected final IntHolder maxHealth} field: its PUBLIC
 * accessor ({@code getMaxHealth(BuilderSupport)}) demands a {@code BuilderSupport} wrapper that in turn
 * demands a live {@code NPCEntity} + {@code Holder<EntityStore>} (see {@code RoleBuilderSystem}) purely
 * as an API-surface artifact of the shared multi-field builder-support plumbing, NOT because reading a
 * simple int field needs one. Reflection bridges exactly that one hop (read the field, never write it),
 * fully {@code try/catch(Throwable)}-guarded with permanent per-session memoization on ANY failure (a
 * missing role, an engine refactor renaming/removing the field, a module system denying the accessible
 * flag, ...) so a broken read degrades silently to "unknown" - the preview falls back to
 * multipliers-only - and is never retried on every keystroke, never a page crash.
 *
 * <p><b>Evaluation path.</b> Mirrors the engine's own entity-free pattern: a STATIC holder (no
 * {@code "Compute"} wrapper in the authored JSON - true for the vanilla {@code Skeleton} role's plain
 * {@code "MaxHealth": 92}) is read via {@code holder.rawGet(null)}, the SAME null-context call the
 * engine itself uses for a static value at parse time ({@code IntHolder.readJSON}); a non-static
 * (Compute-driven) holder falls back to a real {@link ExecutionContext} built from
 * {@code builder.getBuilderParameters().createScope()} - the exact expression from
 * {@code BuilderManager.validateAllSpawnableNPCs}.
 */
final class RoleBaseHealthResolver {

    /** Per-role-name memoized result: a resolved (&gt;0) health, or a permanently-cached ABSENT on any failure. */
    private static final ConcurrentHashMap<String, OptionalInt> CACHE = new ConcurrentHashMap<>();

    /** The reflective handle to {@code BuilderRole.maxHealth}, looked up (and made accessible) ONCE. */
    @Nullable private static volatile Field maxHealthField;
    private static volatile boolean fieldLookupFailed;

    private RoleBaseHealthResolver() {
    }

    /**
     * The declared base {@code MaxHealth} for {@code roleName} (e.g. {@code "Skeleton"}), or
     * {@link OptionalInt#empty()} when the role does not exist, is not a {@link BuilderRole}, the value
     * could not be evaluated, or the value is not positive. Memoized per role name for the process
     * lifetime (both a success and a failure), so a preview refresh on every keystroke never repeats the
     * registry walk / reflective read.
     */
    @Nonnull
    static OptionalInt baseMaxHealth(@Nonnull String roleName) {
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
            if (!(builder instanceof BuilderRole role)) {
                return OptionalInt.empty();
            }
            Field field = maxHealthField();
            if (field == null) {
                return OptionalInt.empty();
            }
            Object rawHolder = field.get(role);
            if (!(rawHolder instanceof IntHolder holder)) {
                return OptionalInt.empty();
            }
            int value = holder.isStatic()
                    ? holder.rawGet(null) // the engine's own static-value read (IntHolder.readJSON mirrors this)
                    : holder.rawGet(new ExecutionContext(builder.getBuilderParameters().createScope()));
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

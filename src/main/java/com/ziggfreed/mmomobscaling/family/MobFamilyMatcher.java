package com.ziggfreed.mmomobscaling.family;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.builtin.tagset.config.NPCGroup;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;

/**
 * Evaluates a {@link FamilyFilter} against a spawning {@link NPCEntity} - the engine-coupled half of the
 * family gate (the pure role-glob half lives on {@link FamilyFilter}). Filter-shaped, not rarity-shaped, so a
 * future variant/flavor overlay axis reuses it unchanged.
 *
 * <p>Mirrors {@code MobClassifier}: native {@code NPCGroup} ids resolve to an asset-map index LAZILY on first
 * use ({@link NPCGroup#getAssetMap()}{@code .getIndex(id)}), cache forever (including a
 * {@link AssetMapWithIndexes#NOT_FOUND} miss), and membership is {@link WorldSupport#hasTagInGroup(int, int)}
 * against the mob's role index inside a {@code try/catch}-&gt;{@code false} (so an absent tagset plugin degrades
 * to "no group match", never throws on the spawn path). A referenced group id that resolves {@code NOT_FOUND}
 * warns ONCE (an authored filter pointing at a non-existent group is a content bug worth surfacing, the way an
 * unresolvable {@code AuraEffectId} warns at its own consumption site) - after which that id is a silent
 * no-match.
 *
 * <p><b>Missing-group asymmetry (documented):</b> a {@code NOT_FOUND} group matches nothing, so a missing id
 * in {@code allowGroups} (with no other allow entry) makes the entry fail CLOSED (never eligible), while a
 * missing id in {@code denyGroups} is fail-OPEN (a no-op). Deny always wins over allow.
 */
public final class MobFamilyMatcher {

    private static final MobFamilyMatcher INSTANCE = new MobFamilyMatcher();

    /** Lazy per-id group-index cache (id -&gt; asset-map index, or {@code NOT_FOUND}); mirrors {@code MobClassifier}. */
    @Nonnull
    private final ConcurrentHashMap<String, Integer> groupIndexCache = new ConcurrentHashMap<>();

    /** Group ids already warned about as {@code NOT_FOUND} (warn-once, so a spawn storm logs a single line). */
    @Nonnull
    private final Set<String> warnedMissing = ConcurrentHashMap.newKeySet();

    private MobFamilyMatcher() {
    }

    @Nonnull
    public static MobFamilyMatcher get() {
        return INSTANCE;
    }

    /**
     * True when a content entry carrying {@code filter} may apply to {@code npc}. Deny wins; an unrestricted
     * allow side (both allow lists empty) allows all; an entirely empty filter short-circuits to eligible.
     */
    public boolean eligible(@Nonnull FamilyFilter filter, @Nonnull NPCEntity npc) {
        if (filter.isUnrestricted()) {
            return true;
        }
        String roleName = npc.getRoleName();
        int roleIndex = npc.getRoleIndex();

        // Deny wins: a single deny match (role glob OR group membership) makes the entry ineligible.
        if (roleName != null && filter.deniesRole(roleName)) {
            return false;
        }
        if (matchesAnyGroup(filter.denyGroups(), roleIndex)) {
            return false;
        }

        // Allow side: an empty allow side (both lists empty) allows all; else at least one allow match.
        if (filter.allowsAll()) {
            return true;
        }
        if (roleName != null && filter.allowsRole(roleName)) {
            return true;
        }
        return matchesAnyGroup(filter.allowGroups(), roleIndex);
    }

    /** True when {@code roleIndex} is a member of ANY of the named native {@code NPCGroup}s. */
    private boolean matchesAnyGroup(@Nonnull List<String> groupIds, int roleIndex) {
        for (String id : groupIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            int idx = groupIndex(id);
            if (idx == AssetMapWithIndexes.NOT_FOUND) {
                continue; // missing group matches nothing (warned once at resolve time)
            }
            if (inGroup(idx, roleIndex)) {
                return true;
            }
        }
        return false;
    }

    /** Native tagset membership; a plugin-absent throw degrades to "not a member". */
    private static boolean inGroup(int groupIndex, int roleIndex) {
        try {
            return WorldSupport.hasTagInGroup(groupIndex, roleIndex);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Resolve (and cache) a group id to its asset-map index; {@code NOT_FOUND} on absence, warned once. */
    private int groupIndex(@Nonnull String groupId) {
        return groupIndexCache.computeIfAbsent(groupId, id -> {
            int idx = resolveGroupIndex(id);
            if (idx == AssetMapWithIndexes.NOT_FOUND && warnedMissing.add(id)) {
                safeWarn("family filter references unknown NPCGroup '" + id
                        + "' - it will never match (author the tagset or fix the id).");
            }
            return idx;
        });
    }

    /** Resolve a group id to its asset-map index; {@code NOT_FOUND} when absent or the store is unavailable. */
    private static int resolveGroupIndex(@Nonnull String groupId) {
        try {
            return NPCGroup.getAssetMap().getIndex(groupId);
        } catch (Throwable t) {
            return AssetMapWithIndexes.NOT_FOUND;
        }
    }

    private static void safeWarn(@Nonnull String message) {
        try {
            MobScalingPlugin.LOGGER.atWarning().log("Mob-scaling content: " + message);
        } catch (Throwable ignored) {
            // log-manager-less JVMs
        }
    }
}

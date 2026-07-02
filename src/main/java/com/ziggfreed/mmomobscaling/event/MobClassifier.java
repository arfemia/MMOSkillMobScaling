package com.ziggfreed.mmomobscaling.event;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.builtin.tagset.config.NPCGroup;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * Decides whether a spawning NPC should be scaled, and its scope. Classification is layered,
 * native-asset-first (authored {@code NPCGroup} tagsets are owner/pack-editable, no Java-side registry):
 * <ol>
 *   <li>{@code Server/NPC/Groups/Mmoscaling_Excluded.json} membership -> EXCLUDED ({@code null}; ships
 *       empty, an owner opt-out list). Wins over everything.</li>
 *   <li>{@code Server/NPC/Groups/Mmoscaling_Bosses.json} membership -> {@link MobScaleResult#SCOPE_BOSS}
 *       (the spawn hook forces the {@code boss} rarity tier instead of rolling the ladder).</li>
 *   <li>Else the native role {@link Attitude}: {@code HOSTILE} scales ({@link MobScaleResult#SCOPE_HOSTILE});
 *       every non-hostile attitude (Neutral/Friendly, which covers livestock) is EXCLUDED.</li>
 * </ol>
 * Runs AFTER {@code RoleBuilderSystem} (the spawn hook's {@code getDependencies}) so the role is built.
 * Group indices resolve lazily on first classify (assets are loaded well before any spawn) and cache
 * forever; a missing group asset caches as absent (that layer is skipped, never re-queried).
 */
final class MobClassifier {

    /** The authored boss tagset id ({@code Server/NPC/Groups/Mmoscaling_Bosses.json}). */
    static final String BOSS_GROUP_ID = "Mmoscaling_Bosses";
    /** The authored owner-exclusion tagset id ({@code Server/NPC/Groups/Mmoscaling_Excluded.json}). */
    static final String EXCLUDED_GROUP_ID = "Mmoscaling_Excluded";

    /** Lazy group-index cache sentinel: not yet resolved. */
    private static final int UNRESOLVED = Integer.MIN_VALUE;

    private static volatile int bossGroupIndex = UNRESOLVED;
    private static volatile int excludedGroupIndex = UNRESOLVED;

    private MobClassifier() {
    }

    /**
     * @return the scope byte ({@link MobScaleResult#SCOPE_HOSTILE} / {@link MobScaleResult#SCOPE_BOSS}) for
     *         a scalable mob, or {@code null} to EXCLUDE (friendly/neutral/excluded-group/unbuilt role).
     */
    @Nullable
    static Byte classify(@Nonnull NPCEntity npc) {
        Role role = npc.getRole();
        if (role == null) {
            return null; // role not built (should not happen post RoleBuilderSystem) -> exclude
        }
        int roleIndex = npc.getRoleIndex();
        if (inGroup(excludedIndex(), roleIndex)) {
            return null; // authored owner exclusion wins over everything
        }
        if (inGroup(bossIndex(), roleIndex)) {
            return MobScaleResult.SCOPE_BOSS;
        }
        // NOTE: no isCanLeadFlock() early-out. It was OVER-inclusive as an exclusion: it dropped Hostile
        // combat families that set FlockCanLead (Undead zombies/ghouls/hounds/wraiths, Void crawlers, vermin,
        // cave raptors, Trork Rangers, etc.), leaving them unscaled. Livestock exclusion is already covered by
        // the Neutral-attitude check below (Template_Animal_Neutral defaults its attitude to Neutral), so the
        // attitude test alone is the correct, narrower gate. (Neutral-authored AGGRESSIVE families -
        // Scarak/Feran combat mobs - are a separate follow-up via an Mmoscaling_Included NPCGroup tagset.)
        WorldSupport ws = role.getWorldSupport();
        if (ws == null) {
            return null;
        }
        Attitude attitude = ws.getDefaultPlayerAttitude();
        return attitude == Attitude.HOSTILE ? MobScaleResult.SCOPE_HOSTILE : null;
    }

    /** Native tagset membership; a NOT_FOUND (missing/unloaded group asset) skips the layer. */
    private static boolean inGroup(int groupIndex, int roleIndex) {
        if (groupIndex == AssetMapWithIndexes.NOT_FOUND) {
            return false;
        }
        try {
            return WorldSupport.hasTagInGroup(groupIndex, roleIndex);
        } catch (Throwable t) {
            return false; // tagset plugin unavailable: fall through to the attitude layer
        }
    }

    private static int bossIndex() {
        int idx = bossGroupIndex;
        if (idx == UNRESOLVED) {
            bossGroupIndex = idx = resolveGroupIndex(BOSS_GROUP_ID);
        }
        return idx;
    }

    private static int excludedIndex() {
        int idx = excludedGroupIndex;
        if (idx == UNRESOLVED) {
            excludedGroupIndex = idx = resolveGroupIndex(EXCLUDED_GROUP_ID);
        }
        return idx;
    }

    /** Resolve a group id to its asset-map index; NOT_FOUND when absent or the store is unavailable. */
    private static int resolveGroupIndex(@Nonnull String groupId) {
        try {
            return NPCGroup.getAssetMap().getIndex(groupId);
        } catch (Throwable t) {
            return AssetMapWithIndexes.NOT_FOUND;
        }
    }
}

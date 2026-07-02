package com.ziggfreed.mmomobscaling.event;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * Decides whether a spawning NPC should be scaled, and its scope. MVP classification rides the native role
 * {@link Attitude}: a {@code HOSTILE} default attitude scales ({@link MobScaleResult#SCOPE_HOSTILE}); every
 * non-hostile attitude (Neutral/Friendly, which covers livestock) is EXCLUDED (return {@code null}). Runs
 * AFTER {@code RoleBuilderSystem} (the spawn hook's {@code getDependencies}) so the role is built.
 *
 * <p><b>Follow-up (native-leverage audit rank 2):</b> BOSS classification + owner overrides move to authored
 * {@code NPCGroup} tagset assets ({@code hasTagInGroup(roleIndex)}); this Attitude path stays the default.
 */
final class MobClassifier {

    private MobClassifier() {
    }

    /**
     * @return the scope byte ({@link MobScaleResult#SCOPE_HOSTILE}) for a scalable mob, or {@code null} to
     *         EXCLUDE (friendly/neutral/flock/unbuilt role).
     */
    @Nullable
    static Byte classify(@Nonnull NPCEntity npc) {
        Role role = npc.getRole();
        if (role == null) {
            return null; // role not built (should not happen post RoleBuilderSystem) -> exclude
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
}

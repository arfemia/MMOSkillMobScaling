package com.ziggfreed.mmomobscaling.event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.ziggfreed.common.health.HealthUtil;
import com.ziggfreed.common.instance.effect.EntityEffectService;
import com.ziggfreed.common.sound.Sound3D;
import com.ziggfreed.common.stats.condition.BossRules;
import com.ziggfreed.common.stats.condition.Nearby;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.affix.AffixCry;
import com.ziggfreed.mmomobscaling.component.EngageWatchComponent;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.config.AffixConfig;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;
import com.ziggfreed.mmomobscaling.config.SpawnScalingSettings;
import com.ziggfreed.mmomobscaling.family.MobFamilyMatcher;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;
import com.ziggfreed.mmomobscaling.scaling.PartyRule;

/**
 * What a scaled mob does once it turns on a target, over the mobs the roll stamped an {@link EngageWatchComponent}
 * onto (the query excludes every other mob, so the steady cost is proportional to the watched ones):
 *
 * <ul>
 *   <li><b>The party in front of it.</b> The first time its {@code LockedTarget} is a player, it counts the players
 *       standing within its world's {@link PartyRule#radius()} (CommonLib's one walk of who is near,
 *       {@code Nearby.playersAround}; the edge decided by {@link PartyRule#partySize}), once, as the game's goblin
 *       dungeon does at first sight. A boss or an elite (CommonLib's {@code BossRules}: its {@code Zig_Boss_Or_Elite}
 *       group or a {@code ZigRank} tag) takes the additive boss rule, anything else its party's rung. The multiplier
 *       joins the place's in the one {@code mmoscaling_hp} reconcile ({@link ScaledMobComponent#healthMult()}) and the
 *       mob is refilled to its new maximum, as the dungeon's refill window does. Nothing about a player is read but
 *       where he stands.</li>
 *   <li><b>War cries.</b> Each affix cry ({@link AffixCry}, the Rallying affix's) begins as soon as the mob has a
 *       target and its timer allows: the telegraph effect for the wind-up and the cry's sound where it stands, then
 *       the buff on the mob and on every creature within the cry's reach that is hostile to its target (CommonLib's
 *       {@code Nearby.foesAround} of that target), for the cry's length; the next comes a drawn wait later. A mob
 *       already wearing the buff (another crier's) holds its own until it runs out, as the game's scrapper does.</li>
 * </ul>
 *
 * <p>The target is looked at every {@link #LOOK_EVERY_MS} at most; a cry under way is checked every tick, so it lands
 * on time. The watch comes off once the party is counted and no cry is left. Whole body try-guarded; one warning per
 * boot for a failure, never per mob.
 */
public final class MobScalingEngageSystem extends EntityTickingSystem<EntityStore> {

    /** How often a watched mob's target is looked at, in milliseconds. */
    static final long LOOK_EVERY_MS = 250L;

    /**
     * How far past the party's radius the spatial query reaches: the engine's tree stops just short of its radius, and
     * the party rule decides the edge itself, a player exactly at the radius counting.
     */
    static final double QUERY_PAD = 1.0;

    private static final AtomicBoolean WARNED = new AtomicBoolean();

    @Nonnull
    private final ComponentType<EntityStore, EngageWatchComponent> watchType = EngageWatchComponent.getComponentType();
    @Nonnull
    private final ComponentType<EntityStore, ScaledMobComponent> scaledType = ScaledMobComponent.getComponentType();

    @Nonnull
    private final Query<EntityStore> query = Query.and(watchType, scaledType);

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> cb) {
        try {
            EngageWatchComponent watch = chunk.getComponent(index, watchType);
            ScaledMobComponent scaled = chunk.getComponent(index, scaledType);
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            if (watch == null || scaled == null || ref == null || !ref.isValid()
                    || store.getComponent(ref, DeathComponent.getComponentType()) != null) {
                return;
            }
            long now = System.currentTimeMillis();
            for (EngageWatchComponent.Cry cry : watch.cries()) {
                if (cry.landsAtMs() != 0L && now >= cry.landsAtMs()) {
                    land(store, cb, ref, cry, now);
                }
            }
            if (now < watch.nextLookAtMs()) {
                return;
            }
            watch.setNextLookAtMs(now + LOOK_EVERY_MS);
            Ref<EntityStore> target = lockedTarget(store, ref);
            if (target == null) {
                return;
            }
            if (watch.partyPending() && store.getComponent(target, PlayerRef.getComponentType()) != null) {
                countParty(store, ref, scaled);
                watch.partyCounted();
            }
            for (EngageWatchComponent.Cry cry : watch.cries()) {
                if (cry.landsAtMs() == 0L && now >= cry.nextCryAtMs()) {
                    begin(store, cb, ref, target, cry, now);
                }
            }
            if (watch.done()) {
                cb.removeComponent(ref, watchType);
            }
        } catch (Throwable t) {
            if (WARNED.compareAndSet(false, true)) {
                safeWarn("engagement tick failed (party count or war cry); further failures are not reported: " + t);
            }
        }
    }

    // ---------------------------------------------------------------------
    // The stamp (from the roll)
    // ---------------------------------------------------------------------

    /**
     * The watch a freshly rolled mob needs, or null for none: the party is still to count when its world's party rule
     * is on and the mob is in none of the rule's {@code SkipGroups} (a role that grows with the party by itself), and
     * a cry for each of its affixes that gives one. It reads only the finished roll: the party joins the health the
     * place gives, and a cry comes with an affix, however the roll came to pick it.
     */
    @Nullable
    public static EngageWatchComponent watchFor(@Nonnull SpawnScalingSettings settings, @Nonnull NPCEntity npc,
            @Nonnull MobScaleResult result) {
        PartyRule rule = settings.partyRule();
        boolean partyPending = rule.enabled() && !MobFamilyMatcher.get().inAnyGroup(rule.skipGroups(), npc);
        List<AffixCry> cries = criesOf(result);
        return EngageWatchComponent.worthWatching(partyPending, cries)
                ? new EngageWatchComponent(partyPending, cries) : null;
    }

    /** The cry of each of the roll's affixes that gives one, in the roll's order. */
    @Nonnull
    static List<AffixCry> criesOf(@Nonnull MobScaleResult result) {
        List<AffixCry> out = new ArrayList<>();
        for (String affixId : result.affixIds()) {
            Affix affix = AffixConfig.getInstance().resolve(affixId);
            if (affix != null && affix.cries()) {
                out.add(affix.cry());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // The party
    // ---------------------------------------------------------------------

    /**
     * Count the players within the world's party radius once, take the multiplier the party rule gives (the boss rule
     * for a boss or an elite), and fold it into the mob's health with a refill to the new maximum.
     */
    private static void countParty(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ScaledMobComponent scaled) {
        World world = store.getExternalData().getWorld();
        PartyRule rule = MobScalingConfig.getInstance().spawnSettingsFor(world).partyRule();
        Vector3d at = Nearby.positionOf(store, ref);
        if (!rule.enabled() || at == null) {
            return;
        }
        List<Vector3d> positions = new ArrayList<>();
        for (Ref<EntityStore> player : Nearby.playersAround(store, ref, at, rule.radius() + QUERY_PAD, null,
                Instant.now(), 0)) {
            Vector3d there = Nearby.positionOf(store, player);
            if (there != null) {
                positions.add(there);
            }
        }
        int players = rule.partySize(at, positions);
        double mult = rule.healthMultiplier(players, BossRules.isBossOrElite(store, ref));
        scaled.markParty(players, mult);
        if (mult > 1.0) {
            HealthUtil.reconcileMaxHealth(store, ref, scaled.healthMult(), MobScalingSpawnHook.HP_KEY);
            HealthUtil.fullHeal(store, ref);
        }
    }

    // ---------------------------------------------------------------------
    // War cries
    // ---------------------------------------------------------------------

    /** Begin a cry against {@code target}: the telegraph and the sound now, the buff when the wind-up ends. */
    private static void begin(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> cb,
            @Nonnull Ref<EntityStore> ref, @Nonnull Ref<EntityStore> target, @Nonnull EngageWatchComponent.Cry state,
            long now) {
        AffixCry cry = state.cry();
        if (wears(store, ref, cry.buffEffectId())) {
            state.holdUntil(now + LOOK_EVERY_MS); // already rallied by another: cry once it runs out
            return;
        }
        long windup = cry.windupMillis();
        if (cry.castEffectId() != null && windup > 0L) {
            EntityEffectService.applyTimed(ref, cry.castEffectId(), (float) cry.windupSeconds(),
                    OverlapBehavior.OVERWRITE, cb);
        }
        Vector3d at = Nearby.positionOf(store, ref);
        if (cry.soundEventId() != null && at != null) {
            Sound3D.play(cry.soundEventId(), SoundCategory.SFX, at, store, "war cry", false);
        }
        state.begin(target, now + windup);
        if (windup <= 0L) {
            land(store, cb, ref, state, now);
        }
    }

    /**
     * The cry lands: the buff on the mob and on every creature within reach that is hostile to its target (the crier
     * alone when the target is gone), for the cry's length; the next cry a drawn wait later.
     */
    private static void land(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> cb,
            @Nonnull Ref<EntityStore> ref, @Nonnull EngageWatchComponent.Cry state, long now) {
        AffixCry cry = state.cry();
        Ref<EntityStore> target = state.target();
        state.landed(now + cry.nextRepeatMillis(ThreadLocalRandom.current().nextDouble()));
        String buff = cry.buffEffectId();
        if (buff == null) {
            return;
        }
        float seconds = (float) cry.buffSeconds();
        EntityEffectService.applyTimed(ref, buff, seconds, OverlapBehavior.OVERWRITE, cb);
        Vector3d at = Nearby.positionOf(store, ref);
        if (target == null || !target.isValid() || at == null || cry.radius() <= 0.0) {
            return;
        }
        for (Ref<EntityStore> ally : Nearby.foesAround(store, target, at, cry.radius(), null, Instant.now(), 0)) {
            if (!Nearby.same(ally, ref)) {
                EntityEffectService.applyTimed(ally, buff, seconds, OverlapBehavior.OVERWRITE, cb);
            }
        }
    }

    /** Does the mob wear {@code effectId} now? False for none, an unknown id, or when it cannot tell. */
    private static boolean wears(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nullable String effectId) {
        if (effectId == null) {
            return false;
        }
        try {
            int index = EntityEffect.getAssetMap().getIndex(effectId);
            EffectControllerComponent ctrl = store.getComponent(ref, EffectControllerComponent.getComponentType());
            return index >= 0 && ctrl != null && ctrl.hasEffect(index);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The mob's {@code LockedTarget}, the one it has turned on, or null when it has none. */
    @Nullable
    private static Ref<EntityStore> lockedTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        MarkedEntitySupport marked = store.getComponent(ref, MarkedEntitySupport.getComponentType());
        Ref<EntityStore> target = marked == null ? null
                : marked.getMarkedEntityRef(MarkedEntitySupport.DEFAULT_TARGET_SLOT);
        return target != null && target.isValid() ? target : null;
    }

    private static void safeWarn(@Nonnull String message) {
        try {
            MobScalingPlugin.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less JVMs
        }
    }
}

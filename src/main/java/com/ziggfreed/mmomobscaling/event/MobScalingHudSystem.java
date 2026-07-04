package com.ziggfreed.mmomobscaling.event;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.ziggfreed.mmoskilltree.api.MMOSkillTreeAPI;
import com.ziggfreed.mmoskilltree.world.WorldRules;
import com.ziggfreed.mmoskilltree.world.WorldScope;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.config.AffixConfig;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;
import com.ziggfreed.mmomobscaling.config.RarityConfig;
import com.ziggfreed.mmomobscaling.hud.MobInspectorHud;
import com.ziggfreed.mmomobscaling.hud.ZoneDifficultyHud;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * Drives the two player-facing overlays - {@link ZoneDifficultyHud} and {@link MobInspectorHud} -
 * from ONE per-player tick, world-thread-safe by construction (a ticking system runs on the
 * entity's world thread, which both the {@code HudManager} map and {@code TargetUtil} require).
 *
 * <p><b>Lazy install, no PlayerReady hook:</b> when a HUD is enabled but absent from the player's
 * {@code HudManager}, this tick installs it. That one rule also self-heals the engine's
 * world/instance-transfer teardown ({@code Universe.resetPlayer} clears ALL custom HUDs and
 * nothing native re-attaches them), so the overlays survive teleports without lifecycle plumbing.
 *
 * <p><b>Steady-state cost:</b> per player per tick, two config reads + two throttle checks; ALL
 * real work (the power read, the crosshair raycast, a packet) happens at most once per HUD
 * interval (1s zone / 250ms inspector), and an unchanged readout is skipped inside the HUD. The
 * inspector resolves its target through the engine's own {@code TargetUtil.getTargetEntity}
 * (spatial-index sphere + ray-vs-AABB, the exact code path behind the vanilla "entity in view"
 * commands) - never a world scan. Registered even when both HUDs start disabled, so
 * {@code /mobscaling hud ... on} works without a restart (the disabled tick is two boolean reads).
 */
public final class MobScalingHudSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private final ComponentType<EntityStore, TransformComponent> transformType = TransformComponent.getComponentType();

    @Nonnull
    private final Query<EntityStore> query = Query.and(Player.getComponentType(), transformType);

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            MobScalingConfig cfg = MobScalingConfig.getInstance();
            boolean zoneOn = cfg.isZoneHudEnabled();
            boolean inspectorOn = cfg.isInspectorHudEnabled();
            if (!zoneOn && !inspectorOn) {
                return; // both overlays off: two boolean reads, nothing else
            }
            Player player = archetypeChunk.getComponent(index, Player.getComponentType());
            PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
            if (player == null || playerRef == null) {
                return;
            }
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            if (ref == null || !ref.isValid()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (zoneOn) {
                tickZoneHud(player, playerRef, store, ref, archetypeChunk, index, cfg, now);
            }
            if (inspectorOn) {
                tickInspectorHud(player, playerRef, store, ref, cfg, now);
            }
        } catch (Throwable t) {
            safeWarn("hud tick failed: " + t);
        }
    }

    // ---------------------------------------------------------------------
    // Zone difficulty overlay
    // ---------------------------------------------------------------------

    private void tickZoneHud(@Nonnull Player player, @Nonnull PlayerRef playerRef,
            @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk, int index,
            @Nonnull MobScalingConfig cfg, long now) {
        ZoneDifficultyHud hud = ZoneDifficultyHud.get(player);
        if (hud == null) {
            hud = new ZoneDifficultyHud(playerRef);
            player.getHudManager().addCustomHud(playerRef, hud);
        }
        if (!hud.dueForPush(now)) {
            return;
        }
        World world = store.getExternalData().getWorld();
        TransformComponent transform = archetypeChunk.getComponent(index, transformType);
        if (world == null || transform == null) {
            return;
        }
        WorldRules rules = WorldScope.rulesFor(world);
        boolean visible = cfg.isEnabled() && rules.mobScalingEnabled();
        if (!visible) {
            hud.pushUpdate(0, 0, 0, false, "", "", false);
            return;
        }
        int chunkX = ChunkUtil.chunkCoordinate(transform.getPosition().x);
        int chunkZ = ChunkUtil.chunkCoordinate(transform.getPosition().z);
        MobScalingSpawnHook.SpawnScaling scaling =
                MobScalingSpawnHook.resolveSpawnScaling(rules, world, chunkX, chunkZ, cfg);
        double playerPower = MMOSkillTreeAPI.getPowerLevel(store, ref);
        // scaling.regionPower() is the tracked (zone, sub-grid) aggregate - already folded into
        // scaling.difficulty(); shown only when it says something a lone player's own power does
        // not (someone is tracked there - the same source the group delta reads).
        boolean showLocation = cfg.isZoneShowLocationName();
        String zoneName = showLocation ? scaling.zoneName() : "";
        String biomeName = showLocation ? scaling.biomeName() : "";
        hud.pushUpdate(scaling.difficulty(), playerPower, scaling.regionPower(), true,
                zoneName, biomeName, showLocation);
    }

    // ---------------------------------------------------------------------
    // Mob inspector overlay
    // ---------------------------------------------------------------------

    private void tickInspectorHud(@Nonnull Player player, @Nonnull PlayerRef playerRef,
            @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull MobScalingConfig cfg, long now) {
        MobInspectorHud hud = MobInspectorHud.get(player);
        if (hud == null) {
            hud = new MobInspectorHud(playerRef);
            player.getHudManager().addCustomHud(playerRef, hud);
        }
        if (!hud.dueForPush(now)) {
            return;
        }
        hud.pushTarget(resolveTarget(store, ref, cfg));
    }

    /**
     * Resolve the crosshair target into a render snapshot; {@code null} when the player is not
     * looking at an inspectable entity (nothing hit, the hit has no health stat, or it is another
     * player - the inspector reads MOBS, not people).
     */
    @Nullable
    private static MobInspectorHud.TargetSnapshot resolveTarget(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull MobScalingConfig cfg) {
        Ref<EntityStore> target = TargetUtil.getTargetEntity(ref, (float) cfg.getInspectorRangeBlocks(), store);
        if (target == null || !target.isValid()) {
            return null;
        }
        if (store.getComponent(target, Player.getComponentType()) != null) {
            return null; // never expose another player's readout
        }
        EntityStatMap stats = store.getComponent(target, EntityStatMap.getComponentType());
        EntityStatValue health = stats != null ? stats.get(DefaultEntityStatTypes.getHealth()) : null;
        if (health == null) {
            return null; // not a living thing (decor, projectiles, ...)
        }

        Message name = null;
        DisplayNameComponent display = store.getComponent(target, DisplayNameComponent.getComponentType());
        if (display != null) {
            name = display.getDisplayName();
        }

        // The NPC role name doubles as the generated-portrait key (Icons/ModelsGenerated/<role>.png), the
        // same string the native Memories page uses. Null for a non-NPC living entity (the card then shows
        // no portrait). Read here on the world thread; the HUD only builds the path + toggles visibility.
        String modelRole = null;
        NPCEntity npc = store.getComponent(target, NPCEntity.getComponentType());
        if (npc != null) {
            modelRole = npc.getRoleName();
        }

        Rarity rarity = null;
        List<Affix> affixes = List.of();
        double difficulty = 0.0;
        boolean scaled = false;
        ScaledMobComponent scaledMob = store.getComponent(target, ScaledMobComponent.getComponentType());
        if (scaledMob != null) {
            MobScaleResult result = scaledMob.result();
            scaled = true;
            difficulty = result.difficulty();
            if (result.hasRarity()) {
                rarity = RarityConfig.getInstance().resolve(result.rarityId());
            }
            if (result.hasAffixes()) {
                List<Affix> resolved = new ArrayList<>(result.affixIds().length);
                for (String affixId : result.affixIds()) {
                    Affix affix = AffixConfig.getInstance().resolve(affixId);
                    if (affix != null) {
                        resolved.add(affix);
                    }
                }
                affixes = resolved;
            }
        }

        return new MobInspectorHud.TargetSnapshot(
                targetKey(store, target), name, rarity, affixes, difficulty, scaled,
                health.get(), health.getMax(), modelRole);
    }

    /** A stable identity string for the skip-if-unchanged cache (UUID when present). */
    @Nonnull
    private static String targetKey(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> target) {
        UUIDComponent uuid = store.getComponent(target, UUIDComponent.getComponentType());
        if (uuid != null && uuid.getUuid() != null) {
            return uuid.getUuid().toString();
        }
        return "ref:" + target.hashCode();
    }

    private static void safeWarn(@Nonnull String message) {
        try {
            MobScalingPlugin.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less JVMs
        }
    }
}

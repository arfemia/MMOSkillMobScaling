package com.ziggfreed.mmomobscaling.command;

import java.util.Locale;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.ziggfreed.common.health.HealthUtil;
import com.ziggfreed.mmoskilltree.api.MMOSkillTreeAPI;
import com.ziggfreed.mmoskilltree.world.WorldRules;
import com.ziggfreed.mmoskilltree.world.WorldScope;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;
import com.ziggfreed.mmomobscaling.event.MobScalingEffectApplySystem;
import com.ziggfreed.mmomobscaling.event.MobScalingPresenceSystem;
import com.ziggfreed.mmomobscaling.event.MobScalingSpawnHook;
import com.ziggfreed.mmomobscaling.scaling.RegionPowerTracker;

/**
 * {@code /mobscaling <purge|inspect>} - the admin maintenance + tuning tools (permission group
 * {@code hytale:Admin}; all strings are lang keys).
 *
 * <ul>
 *   <li>{@code purge} - strip ALL scaling residue (the {@code mmoscaling_hp} MAX modifier + every
 *       {@code Mmoscaling_*} INFINITE effect) off every loaded NPC in the caller's world. The
 *       full-uninstall hatch: while the mod is ENABLED the spawn hook reconciles saved mobs itself,
 *       but a disabled/uninstalled mod registers no systems and cannot self-heal - so this command
 *       registers OUTSIDE the zero-cost gate (a command carries no per-tick cost): disable scaling,
 *       run {@code /mobscaling purge} per world, then uninstall clean. Only LOADED mobs are swept;
 *       run it near the areas that matter (unloaded residue self-heals if the mod is re-enabled).</li>
 *   <li>{@code inspect} (default) - report the difficulty inputs at the caller's position: their own
 *       power level, the world floor + kill-switch, the tracked region power scalar, and the exact
 *       effective difficulty a spawn HERE would resolve (shared {@code effectiveDifficulty} code path).</li>
 * </ul>
 */
public final class MobScalingCommand extends CommandBase {

    private final OptionalArg<String> subArg;

    public MobScalingCommand() {
        // The engine resolves the command + arg descriptions as localization keys.
        super("mobscaling", "scaling.command.desc");
        this.setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        this.subArg = withOptionalArg("sub", "scaling.command.arg.sub", ArgTypes.STRING);
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        String sub = ctx.provided(subArg) ? subArg.get(ctx).toLowerCase(Locale.ROOT) : "inspect";
        switch (sub) {
            case "purge" -> purge(ctx);
            case "inspect" -> inspect(ctx);
            default -> ctx.sendMessage(Message.translation("scaling.command.usage"));
        }
    }

    /** Strip HP-modifier + infinite-effect residue off every loaded NPC in the caller's world. */
    private void purge(@Nonnull CommandContext ctx) {
        if (!(ctx.sender() instanceof PlayerRef player)) {
            ctx.sendMessage(Message.translation("scaling.command.players_only"));
            return;
        }
        World world = Universe.get().getWorld(player.getWorldUuid());
        if (world == null) {
            return;
        }
        ctx.sendMessage(Message.translation("scaling.command.purge.start"));
        world.execute(() -> {
            int purged = 0;
            try {
                Store<EntityStore> store = world.getEntityStore().getStore();
                int[] count = {0};
                store.forEachChunk(NPCEntity.getComponentType(), (chunk, cb) -> {
                    for (int i = 0; i < chunk.size(); i++) {
                        Ref<EntityStore> ref = chunk.getReferenceTo(i);
                        if (ref == null || !ref.isValid()) {
                            continue;
                        }
                        boolean hadHp = HealthUtil.reconcileMaxHealth(store, ref, 1.0, MobScalingSpawnHook.HP_KEY);
                        boolean hadFx = false;
                        EffectControllerComponent ctrl = store.getComponent(ref, EffectControllerComponent.getComponentType());
                        if (ctrl != null) {
                            hadFx = MobScalingEffectApplySystem.sweepAll(ref, ctrl, cb);
                        }
                        if (hadHp || hadFx) {
                            count[0]++;
                        }
                    }
                });
                purged = count[0];
            } catch (Throwable t) {
                safeWarn("purge sweep failed: " + t);
            }
            player.sendMessage(Message.translation("scaling.command.purge.done").param("count", purged));
        });
    }

    /** Report the difficulty inputs at the caller's position (the tuning diagnostic). */
    private void inspect(@Nonnull CommandContext ctx) {
        if (!(ctx.sender() instanceof PlayerRef player)) {
            ctx.sendMessage(Message.translation("scaling.command.players_only"));
            return;
        }
        World world = Universe.get().getWorld(player.getWorldUuid());
        if (world == null) {
            return;
        }
        world.execute(() -> {
            try {
                Ref<EntityStore> ref = player.getReference();
                if (ref == null || !ref.isValid()) {
                    return;
                }
                Store<EntityStore> store = world.getEntityStore().getStore();
                TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
                if (transform == null) {
                    return;
                }
                MobScalingConfig cfg = MobScalingConfig.getInstance();
                WorldRules rules = WorldScope.rulesFor(world);
                int chunkX = ChunkUtil.chunkCoordinate(transform.getPosition().x);
                int chunkZ = ChunkUtil.chunkCoordinate(transform.getPosition().z);
                long regionKey = RegionPowerTracker.regionKey(chunkX, chunkZ, cfg.getRegionSizeChunks());
                double regionPower = RegionPowerTracker.get().scalarFor(world.getName(), regionKey);

                player.sendMessage(Message.translation("scaling.command.inspect.header"));
                player.sendMessage(Message.translation("scaling.command.inspect.power")
                        .param("power", MMOSkillTreeAPI.getPowerLevel(store, ref)));
                player.sendMessage(Message.translation("scaling.command.inspect.world")
                        .param("floor", rules.mobDifficultyFloor())
                        .param("enabled", cfg.isEnabled() && rules.mobScalingEnabled()));
                player.sendMessage(Message.translation("scaling.command.inspect.region")
                        .param("power", regionPower)
                        .param("mode", MobScalingPresenceSystem.mode(cfg).name())
                        .param("tracked", RegionPowerTracker.get().trackedPlayers()));
                player.sendMessage(Message.translation("scaling.command.inspect.difficulty")
                        .param("difficulty", MobScalingSpawnHook.effectiveDifficulty(rules, world, chunkX, chunkZ, cfg)));
                player.sendMessage(Message.translation("scaling.command.inspect.chance")
                        .param("chance", cfg.getRaritySpawnChance()));
            } catch (Throwable t) {
                safeWarn("inspect failed: " + t);
            }
        });
    }

    private static void safeWarn(@Nonnull String message) {
        try {
            MobScalingPlugin.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less JVMs
        }
    }
}

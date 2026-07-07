package com.ziggfreed.mmomobscaling.command;

import java.util.List;
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
import com.ziggfreed.mmomobscaling.config.SpawnScalingSettings;
import com.ziggfreed.mmomobscaling.event.MobScalingEffectApplySystem;
import com.ziggfreed.mmomobscaling.event.MobScalingPresenceSystem;
import com.ziggfreed.mmomobscaling.event.MobScalingSpawnHook;
import com.ziggfreed.mmomobscaling.hud.HudPosition;
import com.ziggfreed.mmomobscaling.hud.MobInspectorHud;
import com.ziggfreed.mmomobscaling.hud.ZoneDifficultyHud;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;
import com.ziggfreed.mmomobscaling.scaling.RegionPowerTracker;
import com.ziggfreed.mmomobscaling.world.ZoneDifficultyResolver;

/**
 * {@code /mobscaling <purge|inspect|hud|preset|intensity>} - the admin maintenance + tuning tools
 * (permission group {@code hytale:Admin}; all strings are lang keys).
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
 *       power level, the world floor + kill-switch, the tracked region power scalar, the exact
 *       effective difficulty a spawn HERE would resolve (shared {@code effectiveDifficulty} code path),
 *       and the plain-mob HP / outgoing-damage / incoming-taken stat curve that difficulty resolves to
 *       (so an admin can confirm plain mobs scale), then the rarity spawn chance.</li>
 *   <li>{@code hud <zone|inspector> <on|off|POSITION> [offsetX] [offsetY]} - LIVE-tune the two
 *       player-facing overlays: flip one on/off for everyone, or re-anchor it to a named corner
 *       preset with optional pixel offsets, applied to all online players without a reconnect.
 *       RUNTIME ONLY: the change is lost on restart; the persistent authority is the
 *       {@code ZoneHud*}/{@code InspectorHud*} keys in {@code mods/MmoMobScaling/mob-scaling.json}
 *       (the command reminds the admin).</li>
 *   <li>{@code preset [name]} - with no name, report the active preset + the presets available in
 *       the loaded settings store. With a name, LIVE-swap the active preset (re-folds config from
 *       that preset asset over the jar {@code Default}), clears the memoized zone-difficulty floors
 *       so new spawns pick up the new numbers immediately, and refreshes both HUDs for all online
 *       players. RUNTIME ONLY: the swap is lost on restart; the persistent authority is the
 *       {@code ActivePreset} key in {@code mods/MmoMobScaling/mob-scaling.json} (the command reminds
 *       the admin).</li>
 *   <li>{@code intensity [multiplier]} - with no value, report the current GLOBAL intensity multiplier
 *       on the difficulty-&gt;stat curve; with a value ({@code >= 0}), LIVE-set it (denser HP / harder
 *       hits scale with the multiplier). RUNTIME ONLY: lost on restart; the persistent authority is the
 *       {@code Intensity} key in {@code mods/MmoMobScaling/mob-scaling.json}. A world with an authored
 *       per-world {@code Intensity} override is unaffected.</li>
 * </ul>
 */
public final class MobScalingCommand extends CommandBase {

    private final OptionalArg<String> subArg;
    private final OptionalArg<String> hudTargetArg;
    private final OptionalArg<String> hudValueArg;
    private final OptionalArg<String> hudOffsetXArg;
    private final OptionalArg<String> hudOffsetYArg;
    private final OptionalArg<String> presetNameArg;
    private final OptionalArg<String> intensityArg;

    public MobScalingCommand() {
        // The engine resolves the command + arg descriptions as localization keys.
        super("mobscaling", "scaling.command.desc");
        this.setPermissionGroups(HytalePermissionsProvider.GROUP_ADMIN);
        this.subArg = withOptionalArg("sub", "scaling.command.arg.sub", ArgTypes.STRING);
        this.hudTargetArg = withOptionalArg("hudTarget", "scaling.command.arg.hud_target", ArgTypes.STRING);
        this.hudValueArg = withOptionalArg("hudValue", "scaling.command.arg.hud_value", ArgTypes.STRING);
        this.hudOffsetXArg = withOptionalArg("hudOffsetX", "scaling.command.arg.hud_offset_x", ArgTypes.STRING);
        this.hudOffsetYArg = withOptionalArg("hudOffsetY", "scaling.command.arg.hud_offset_y", ArgTypes.STRING);
        this.presetNameArg = withOptionalArg("presetName", "scaling.command.arg.preset_name", ArgTypes.STRING);
        this.intensityArg = withOptionalArg("intensity", "scaling.command.arg.intensity", ArgTypes.STRING);
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        String sub = ctx.provided(subArg) ? subArg.get(ctx).toLowerCase(Locale.ROOT) : "inspect";
        switch (sub) {
            case "purge" -> purge(ctx);
            case "inspect" -> inspect(ctx);
            case "hud" -> hud(ctx);
            case "preset" -> preset(ctx);
            case "intensity" -> intensity(ctx);
            default -> ctx.sendMessage(Message.translation("scaling.command.usage"));
        }
    }

    /** Report or live-swap the active preset (owner-file persistence needs a restart-free hint). */
    private void preset(@Nonnull CommandContext ctx) {
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        if (!ctx.provided(presetNameArg)) {
            List<String> available = cfg.availablePresetNames();
            ctx.sendMessage(Message.translation("scaling.command.preset.active")
                    .param("0", cfg.getActivePreset()));
            ctx.sendMessage(Message.translation("scaling.command.preset.available")
                    .param("0", String.join(", ", available)));
            return;
        }
        String name = presetNameArg.get(ctx);
        List<String> available = cfg.availablePresetNames();
        if (!cfg.swapActivePreset(name)) {
            ctx.sendMessage(Message.translation("scaling.command.preset.unknown")
                    .param("0", name)
                    .param("1", String.join(", ", available)));
            return;
        }
        ZoneDifficultyResolver.get().clearAll();
        HudPosition zonePos = HudPosition.parse(
                cfg.getZoneHudPosition().toUpperCase(Locale.ROOT), cfg.getZoneHudOffsetX(), cfg.getZoneHudOffsetY());
        if (zonePos != null) {
            ZoneDifficultyHud.refreshPositionForAllOnline(zonePos);
        }
        HudPosition inspectorPos = HudPosition.parse(cfg.getInspectorHudPosition().toUpperCase(Locale.ROOT),
                cfg.getInspectorHudOffsetX(), cfg.getInspectorHudOffsetY());
        if (inspectorPos != null) {
            MobInspectorHud.refreshPositionForAllOnline(inspectorPos);
        }
        ctx.sendMessage(Message.translation("scaling.command.preset.swapped").param("0", cfg.getActivePreset()));
        ctx.sendMessage(Message.translation("scaling.command.hud.persist_hint"));
    }

    /**
     * Report or LIVE-tune the GLOBAL intensity multiplier on the difficulty-&gt;stat curve (1.0.1). With no
     * value, print the current multiplier; with a value ({@code >= 0}), set it. RUNTIME ONLY: lost on
     * restart; the persistent authority is the {@code Intensity} key in
     * {@code mods/MmoMobScaling/mob-scaling.json} (the command reminds the admin). A world with an authored
     * per-world {@code Intensity} override is unaffected (authoring wins).
     */
    private void intensity(@Nonnull CommandContext ctx) {
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        if (!ctx.provided(intensityArg)) {
            ctx.sendMessage(Message.translation("scaling.command.intensity.current")
                    .param("value", cfg.getIntensity()));
            return;
        }
        double value;
        try {
            value = Double.parseDouble(intensityArg.get(ctx).trim());
        } catch (NumberFormatException e) {
            ctx.sendMessage(Message.translation("scaling.command.intensity.usage"));
            return;
        }
        if (Double.isNaN(value) || value < 0.0) {
            ctx.sendMessage(Message.translation("scaling.command.intensity.usage"));
            return;
        }
        cfg.setIntensityRuntime(value);
        ctx.sendMessage(Message.translation("scaling.command.intensity.set").param("value", cfg.getIntensity()));
        ctx.sendMessage(Message.translation("scaling.command.intensity.persist_hint"));
    }

    /** Live-tune one HUD overlay: on/off for everyone, or a named-corner reposition (runtime only). */
    private void hud(@Nonnull CommandContext ctx) {
        if (!ctx.provided(hudTargetArg) || !ctx.provided(hudValueArg)) {
            ctx.sendMessage(Message.translation("scaling.command.hud.usage"));
            return;
        }
        String target = hudTargetArg.get(ctx).toLowerCase(Locale.ROOT);
        boolean zone = "zone".equals(target);
        if (!zone && !"inspector".equals(target)) {
            ctx.sendMessage(Message.translation("scaling.command.hud.usage"));
            return;
        }
        Message targetName = Message.translation(zone
                ? "scaling.command.hud.target.zone"
                : "scaling.command.hud.target.inspector");
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        String value = hudValueArg.get(ctx).toLowerCase(Locale.ROOT);

        if ("on".equals(value) || "off".equals(value)) {
            boolean enabled = "on".equals(value);
            if (zone) {
                cfg.setZoneHudEnabledRuntime(enabled);
                ZoneDifficultyHud.setEnabledForAllOnline(enabled);
            } else {
                cfg.setInspectorHudEnabledRuntime(enabled);
                MobInspectorHud.setEnabledForAllOnline(enabled);
            }
            ctx.sendMessage(Message.translation(enabled
                    ? "scaling.command.hud.enabled"
                    : "scaling.command.hud.disabled").param("target", targetName));
            ctx.sendMessage(Message.translation("scaling.command.hud.persist_hint"));
            return;
        }

        // Anything else is a named position preset (+ optional pixel offsets).
        String preset = value.toUpperCase(Locale.ROOT);
        int offsetX = zone ? cfg.getZoneHudOffsetX() : cfg.getInspectorHudOffsetX();
        int offsetY = zone ? cfg.getZoneHudOffsetY() : cfg.getInspectorHudOffsetY();
        try {
            if (ctx.provided(hudOffsetXArg)) {
                offsetX = Integer.parseInt(hudOffsetXArg.get(ctx).trim());
            }
            if (ctx.provided(hudOffsetYArg)) {
                offsetY = Integer.parseInt(hudOffsetYArg.get(ctx).trim());
            }
        } catch (NumberFormatException e) {
            ctx.sendMessage(Message.translation("scaling.command.hud.usage"));
            return;
        }
        HudPosition position = HudPosition.parse(preset, offsetX, offsetY);
        if (position == null) {
            ctx.sendMessage(Message.translation("scaling.command.hud.usage"));
            return;
        }
        if (zone) {
            cfg.setZoneHudPositionRuntime(preset, offsetX, offsetY);
            ZoneDifficultyHud.refreshPositionForAllOnline(position);
        } else {
            cfg.setInspectorHudPositionRuntime(preset, offsetX, offsetY);
            MobInspectorHud.refreshPositionForAllOnline(position);
        }
        ctx.sendMessage(Message.translation("scaling.command.hud.moved")
                .param("target", targetName)
                .param("position", preset)
                .param("x", offsetX)
                .param("y", offsetY));
        ctx.sendMessage(Message.translation("scaling.command.hud.persist_hint"));
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
                // 1.0.1: resolve against the per-world overlay so inspect reports the world's ACTUAL numbers.
                SpawnScalingSettings spawn = cfg.spawnSettingsFor(world.getName());
                WorldRules rules = WorldScope.rulesFor(world);
                int chunkX = ChunkUtil.chunkCoordinate(transform.getPosition().x);
                int chunkZ = ChunkUtil.chunkCoordinate(transform.getPosition().z);
                MobScalingSpawnHook.SpawnScaling scaling =
                        MobScalingSpawnHook.resolveSpawnScaling(rules, world, chunkX, chunkZ, spawn);

                player.sendMessage(Message.translation("scaling.command.inspect.header"));
                player.sendMessage(Message.translation("scaling.command.inspect.power")
                        .param("power", MMOSkillTreeAPI.getPowerLevel(store, ref)));
                player.sendMessage(Message.translation("scaling.command.inspect.world")
                        .param("floor", rules.mobDifficultyFloor())
                        .param("enabled", cfg.isEnabled() && rules.mobScalingEnabled()));
                // The layered floor breakdown: native zone (or the no-zone grid fallback), the
                // mapping-layer base, and the distance-from-spawn escalation riding on it.
                player.sendMessage(Message.translation("scaling.command.inspect.zone")
                        .param("zone", scaling.zoneName().isEmpty() ? "-" : scaling.zoneName())
                        .param("base", scaling.baseFloor())
                        .param("bonus", scaling.escalationBonus())
                        .param("floor", scaling.effectiveFloor()));
                player.sendMessage(Message.translation("scaling.command.inspect.region")
                        .param("power", scaling.regionPower())
                        .param("mode", MobScalingPresenceSystem.mode(spawn).name())
                        .param("tracked", RegionPowerTracker.get().trackedPlayers()));
                player.sendMessage(Message.translation("scaling.command.inspect.difficulty")
                        .param("difficulty", scaling.difficulty()));
                // What a PLAIN (non-rarity, non-affix) hostile mob's HP / damage / tankiness curve
                // resolves to at this difficulty, so an admin can confirm plain mobs now scale.
                MobScaleResult plainCurve =
                        MobScaleFold.plain(scaling.difficulty(), MobScaleResult.SCOPE_HOSTILE, spawn.statCurveModel());
                player.sendMessage(Message.translation("scaling.command.inspect.curve")
                        .param("hp", plainCurve.hpMult())
                        .param("out", plainCurve.outDmgMult())
                        .param("in", plainCurve.inDmgMult()));
                player.sendMessage(Message.translation("scaling.command.inspect.chance")
                        .param("chance", scaling.raritySpawnChance()));
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

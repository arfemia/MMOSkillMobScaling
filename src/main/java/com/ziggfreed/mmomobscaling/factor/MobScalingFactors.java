package com.ziggfreed.mmomobscaling.factor;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.factor.FactorProvider;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.factor.HytaleFactors;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;
import com.ziggfreed.mmomobscaling.roster.Rosters;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;
import com.ziggfreed.mmomobscaling.scaling.RegionPowerTracker;
import com.ziggfreed.mmomobscaling.world.RegionKeys;

/**
 * What this mod knows about a mob, published as ordinary factor readings any other mod's content can
 * address.
 *
 * <p>The numbers were already here - how rare the thing in front of you is, how hard its region has
 * become - and until now they were readable only from inside this jar. Contributing them through
 * ziggfreed-common's process-wide door means a loot table in a third mod, a quest gate, a dialogue
 * line, or a station's rare find can all ask, with no mod in the chain depending on any other: an
 * authored file names {@code mmomobscaling:mob_rarity_tier} and a server that has this mod installed
 * answers it.
 *
 * <p><b>Every reading here is about the context's {@link FactorContext#target()}</b> - the entity the
 * moment happened TO - except {@link #REGION_POWER}, which is about a PLACE and so falls back to the
 * acting subject when there is no target. That split is deliberate: a mob kill has two sides, and
 * "how rare was it" and "how lucky is the killer" must never be able to read each other's entity.
 *
 * <p>On a server WITHOUT this mod nothing claims these ids, so a gate on one fails closed and a
 * formula term on one adds zero. The same holds while the mod is installed but switched off: the
 * contribution is made inside the plugin's enabled branch, so a disabled mod publishes nothing and
 * content written against it behaves exactly as it would on a server that never had it. One authored
 * file is therefore correct everywhere.
 */
public final class MobScalingFactors {

    /** Attribution for every id claimed here, so a boot log names the mod to install. */
    public static final String OWNER = "MmoMobScaling";

    /**
     * How far up the rarity ladder the target sits: {@code 0} for a plain mob, {@code 1} for the
     * weakest authored tier, one more for each stronger tier. Numeric on purpose - it is the shape a
     * {@code Min:} gate ("elite or better") and a ladder floor both want, and it stays meaningful
     * when a pack adds a tier in the middle, because the ordering is derived from the tiers'
     * authored strength rather than from a hardcoded list.
     */
    public static final String MOB_RARITY_TIER = "mmomobscaling:mob_rarity_tier";

    /**
     * Is the target exactly this rarity? {@code Param} names the rarity id; {@code 1} when it
     * matches, {@code 0} when the mob is scaled but some other tier. The companion to the ladder
     * reading above, for content that means one specific tier rather than "this good or better".
     */
    public static final String MOB_RARITY = "mmomobscaling:mob_rarity";

    /**
     * Does the target carry this affix? {@code Param} names the affix id; {@code 1} when it is on the
     * mob, {@code 0} when it is not.
     */
    public static final String MOB_AFFIX = "mmomobscaling:mob_affix";

    /** The target's frozen effective difficulty, the number every one of its multipliers came from. */
    public static final String MOB_DIFFICULTY = "mmomobscaling:mob_difficulty";

    /**
     * How much player power this mod is currently tracking in the region the moment is happening in -
     * the same aggregate that hardens spawns there. Reads the target's position when there is one and
     * the acting subject's otherwise, so a block break and a mob kill both answer for where they
     * happened.
     */
    public static final String REGION_POWER = "mmomobscaling:region_power";

    /** Every id this mod claims, in the order the boot diagnostic prints them. */
    private static final List<String> IDS =
            List.of(MOB_RARITY_TIER, MOB_RARITY, MOB_AFFIX, MOB_DIFFICULTY, REGION_POWER);

    /**
     * This mod's OWN vocabulary, for the readings its own content takes: a rarity's authored
     * {@code Loot} block gates and scales through here. It carries the portable {@code hytale:}
     * standard library (so a mob drop can weigh the killer's own {@code MMO_Luck} stat channel) and
     * reaches everything else - this mod's own contributions above, another mod's, and any
     * asset-defined factor - through the shared layers every registry consults on a local miss.
     */
    @Nonnull
    private static final FactorRegistry REGISTRY = buildRegistry();

    private MobScalingFactors() {
    }

    /**
     * Claim every id above for the whole server. Call ONCE from {@code setup()}, inside the enabled
     * branch: a disabled mod must publish nothing, so that content gated on a scaled mob stays shut
     * rather than reading a plain world as if it were scaled.
     */
    public static void contribute() {
        FactorContributions.register(MOB_RARITY_TIER, OWNER, MobScalingFactors::rarityTier);
        FactorContributions.register(MOB_RARITY, OWNER, MobScalingFactors::rarityPresence);
        FactorContributions.register(MOB_AFFIX, OWNER, MobScalingFactors::affixPresence);
        FactorContributions.register(MOB_DIFFICULTY, OWNER, MobScalingFactors::difficulty);
        FactorContributions.register(REGION_POWER, OWNER, MobScalingFactors::regionPower);
    }

    /** The ids this mod publishes, sorted as the boot diagnostic prints them. */
    @Nonnull
    public static List<String> ids() {
        return IDS;
    }

    /**
     * One boot line naming what a pack author may address. Worth printing even though the shared
     * facade logs each first claim: this is the list in one place, at the moment it is complete.
     */
    public static void logContributed() {
        safeInfo("Mob-scaling factors published for any mod's content: " + String.join(", ", IDS));
    }

    /** The vocabulary this mod's own loot rolls resolve against. */
    @Nonnull
    public static FactorRegistry registry() {
        return REGISTRY;
    }

    // ==================== providers ====================

    /**
     * The target's rarity ordinal, or null when it is not a scaled mob at all. A scaled mob that
     * rolled PLAIN answers {@code 0}, which is a real reading rather than an absence: "this mob is
     * ordinary" is something content may legitimately act on.
     */
    @Nullable
    private static Double rarityTier(@Nonnull FactorContext ctx) {
        MobScaleResult result = scaleOf(ctx);
        if (result == null) {
            return null;
        }
        return (double) Rosters.rarity().tierOf(result.rarityId());
    }

    /** 1 when the target's rarity id is exactly {@code Param}, 0 when it is another tier. */
    @Nullable
    private static Double rarityPresence(@Nonnull FactorContext ctx) {
        String wanted = trimmedParam(ctx);
        MobScaleResult result = scaleOf(ctx);
        if (wanted == null || result == null) {
            return null;
        }
        return wanted.equalsIgnoreCase(result.rarityId()) ? 1.0 : 0.0;
    }

    /** 1 when the target carries the affix named by {@code Param}, 0 when it does not. */
    @Nullable
    private static Double affixPresence(@Nonnull FactorContext ctx) {
        String wanted = trimmedParam(ctx);
        MobScaleResult result = scaleOf(ctx);
        if (wanted == null || result == null) {
            return null;
        }
        for (String affixId : result.affixIds()) {
            if (affixId != null && wanted.equalsIgnoreCase(affixId)) {
                return 1.0;
            }
        }
        return 0.0;
    }

    /** The target's frozen effective difficulty. */
    @Nullable
    private static Double difficulty(@Nonnull FactorContext ctx) {
        MobScaleResult result = scaleOf(ctx);
        return result == null ? null : (double) result.difficulty();
    }

    /**
     * The tracked player power in the region the moment is happening in. Null when there is no world,
     * no positioned entity to locate, or the engine cannot be asked - never {@code 0}, which is a
     * genuine reading meaning "nobody is being tracked here right now".
     */
    @Nullable
    private static Double regionPower(@Nonnull FactorContext ctx) {
        try {
            Store<EntityStore> store = ctx.store();
            Ref<EntityStore> at = ctx.hasLiveTarget() ? ctx.target() : ctx.subject();
            if (store == null || at == null || !at.isValid()) {
                return null;
            }
            World world = ctx.world() != null ? ctx.world() : worldOf(store);
            if (world == null) {
                return null;
            }
            RegionPowerTracker.RegionKey key = RegionKeys.of(store, at, world,
                    MobScalingConfig.getInstance().getRegionSizeChunks());
            if (key == null) {
                return null;
            }
            return RegionPowerTracker.get().scalarFor(world.getName(), key);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== reads ====================

    /**
     * The target's frozen scaling result, or null when there is no live target, the component type is
     * not registered (the mod is off), or the entity is not a scaled mob.
     */
    @Nullable
    private static MobScaleResult scaleOf(@Nonnull FactorContext ctx) {
        try {
            if (!ctx.hasLiveTarget()) {
                return null;
            }
            ComponentType<EntityStore, ScaledMobComponent> type = ScaledMobComponent.getComponentType();
            if (type == null) {
                return null;
            }
            Store<EntityStore> store = ctx.store();
            Ref<EntityStore> target = ctx.target();
            if (store == null || target == null) {
                return null;
            }
            ScaledMobComponent comp = store.getComponent(target, type);
            return comp == null ? null : comp.result();
        } catch (Throwable t) {
            return null;
        }
    }

    /** The authored {@code Param}, trimmed; null when nothing usable was written. */
    @Nullable
    private static String trimmedParam(@Nonnull FactorContext ctx) {
        String param = ctx.param();
        if (param == null) {
            return null;
        }
        String trimmed = param.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The world behind a live store, or null in a context that has none (a unit JVM, a bare store). */
    @Nullable
    private static World worldOf(@Nonnull Store<EntityStore> store) {
        try {
            return store.getExternalData().getWorld();
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== the mod's own vocabulary ====================

    @Nonnull
    private static FactorRegistry buildRegistry() {
        FactorRegistry registry = new FactorRegistry("mmomobscaling");
        try {
            HytaleFactors.registerInto(registry, OWNER);
        } catch (Throwable t) {
            // A unit JVM without the engine classes still gets a working registry; the portable ids
            // simply resolve to nothing there, which is the standing fail-closed rule.
            safeWarn("portable hytale: factors unavailable: " + t);
        }
        return registry;
    }

    private static void safeInfo(@Nonnull String message) {
        try {
            MobScalingPlugin.LOGGER.atInfo().log(message);
        } catch (Throwable ignored) {
            // log-manager-less JVMs
        }
    }

    private static void safeWarn(@Nonnull String message) {
        try {
            MobScalingPlugin.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less JVMs
        }
    }
}

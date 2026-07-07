package com.ziggfreed.mmomobscaling.hud;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ui.hud.HudPosition;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;

/**
 * Shared base for this mod's custom HUD overlays ({@link ZoneDifficultyHud},
 * {@link MobInspectorHud}), modeled on the MMO Skill Tree's battle-tested {@code ui/hud/MmoHud}
 * (not API-frozen there, so the machinery is carried here; a ziggfreed-common lift is a follow-up).
 * Owns what every HUD otherwise re-derives: a {@link HudPosition}-driven anchor on a single root
 * element, a live reposition push, a per-HUD update throttle, and the keyed lookup/broadcast over
 * the native {@code HudManager}.
 *
 * <p>A concrete HUD supplies its identity + layout via {@link #rootSelector()}, {@link #panelWidth()},
 * {@link #panelHeight()}, {@link #configuredPosition()} and {@link #updateIntervalMs()}, calls
 * {@link #applyConfiguredPosition} in {@code build()}, and gates its push path on {@link #dueForPush}
 * + {@link #markPushed()}. Every client-shipped document path and top-level element id MUST be
 * mod-prefixed ({@code Hud/Mmoscaling*.ui} / {@code #Mmoscaling*}): the client UI namespace is FLAT
 * across mods, and a clobbered document makes {@code build()}'s anchor set fail and DISCONNECTS the
 * player (the MMO shipped that bug once; see its {@code ui/} router).
 */
public abstract class ScalingHud extends CustomUIHud {

    private final AtomicLong lastPushedMs = new AtomicLong(0L);

    protected ScalingHud(@Nonnull PlayerRef playerRef, @Nonnull String key) {
        super(playerRef, key);
    }

    // ---------------------------------------------------------------------------------
    // Subclass contract
    // ---------------------------------------------------------------------------------

    /** Selector of the single root element whose {@code Anchor} carries the position (mod-prefixed). */
    @Nonnull
    protected abstract String rootSelector();

    /** Root element width in pixels (must match the {@code .ui}); fed to {@link HudPosition#toAnchor}. */
    protected abstract int panelWidth();

    /** Root element height in pixels (must match the {@code .ui}); fed to {@link HudPosition#toAnchor}. */
    protected abstract int panelHeight();

    /** The server-wide configured position for this HUD (parsed off {@code MobScalingConfig}). */
    @Nonnull
    protected abstract HudPosition configuredPosition();

    /** Minimum gap between pushes for this HUD's throttle. */
    protected abstract long updateIntervalMs();

    // ---------------------------------------------------------------------------------
    // Shared position handling
    // ---------------------------------------------------------------------------------

    /**
     * Apply the {@link #configuredPosition()} to the root anchor during {@code build()}. Try-guarded:
     * a HUD is non-essential, so any failure falls back to the {@code .ui} file's static anchor
     * rather than risk the player install path.
     */
    protected final void applyConfiguredPosition(@Nonnull UICommandBuilder cmd) {
        try {
            cmd.setObject(rootSelector() + ".Anchor", configuredPosition().toAnchor(panelWidth(), panelHeight()));
        } catch (Throwable t) {
            safeWarn(getKey() + ": failed to apply configured position, using default: " + t.getMessage());
        }
    }

    /** Re-anchor this HUD to {@code position} live (partial update, no reconnect). */
    public final void pushPositionUpdate(@Nonnull HudPosition position) {
        UICommandBuilder cmd = new UICommandBuilder();
        cmd.setObject(rootSelector() + ".Anchor", position.toAnchor(panelWidth(), panelHeight()));
        update(false, cmd);
    }

    /**
     * Hide the whole panel (partial update, no reinstall) - the admin live-disable push. Also
     * resets the throttle clock so a rapid disable-then-enable repaints on the very next tick
     * instead of waiting out the remainder of the pre-disable window (the ticking system stops
     * stamping the throttle while a HUD is disabled, so the clock would otherwise freeze stale).
     */
    public final void pushHidden() {
        UICommandBuilder cmd = new UICommandBuilder();
        cmd.set(rootSelector() + ".Visible", false);
        update(false, cmd);
        lastPushedMs.set(0L);
        onHiddenPushed();
    }

    /**
     * Called after {@link #pushHidden} so a subclass with a skip-if-unchanged render cache can
     * invalidate it (the client state diverged from the cache; the next identical readout must
     * still be pushed). Default no-op.
     */
    protected void onHiddenPushed() {
    }

    // ---------------------------------------------------------------------------------
    // Shared throttle
    // ---------------------------------------------------------------------------------

    /** Check-only gate: true when the interval since the last push has elapsed. Pair with {@link #markPushed()}. */
    public final boolean dueForPush(long now) {
        return now - lastPushedMs.get() >= updateIntervalMs();
    }

    /** Stamp the throttle as pushed-now. Call from a push that {@link #dueForPush} gated. */
    protected final void markPushed() {
        lastPushedMs.set(System.currentTimeMillis());
    }

    // ---------------------------------------------------------------------------------
    // Keyed lookup/broadcast over the native HudManager (world thread; the map is not concurrent)
    // ---------------------------------------------------------------------------------

    /**
     * Look up the HUD registered under {@code key} for {@code player}, cast to {@code type}, or
     * {@code null} if absent / a different type. Call on the player's world thread.
     */
    @Nullable
    public static <T extends CustomUIHud> T get(@Nonnull Player player, @Nonnull String key, @Nonnull Class<T> type) {
        CustomUIHud hud = player.getHudManager().getCustomHud(key);
        return type.isInstance(hud) ? type.cast(hud) : null;
    }

    /**
     * Run {@code action} on every online player's HUD registered under {@code key}, each on their
     * OWN world's thread (players can be in different worlds), so the action may safely push a
     * partial update. A failure on one player is logged without aborting the rest. This backs every
     * broadcast HUD op (live reposition, admin enable/disable).
     *
     * <p>Known, accepted race: {@code getWorldUuid()} is a plain field written on the player's world
     * thread, read here off-thread (the command pool) - the same idiom {@code MobScalingCommand}'s
     * purge/inspect already use. Mid-world-transfer it can dispatch onto the player's PREVIOUS world,
     * where the {@code ref.isValid()} guard turns the whole action into a skipped push for that one
     * player on that one command; the ticking system self-corrects within a throttle window.
     */
    public static void forEachOnlineHud(@Nonnull String key, @Nonnull Consumer<ScalingHud> action) {
        for (PlayerRef pRef : Universe.get().getPlayers()) {
            try {
                World world = Universe.get().getWorld(pRef.getWorldUuid());
                if (world == null) {
                    continue;
                }
                world.execute(() -> {
                    try {
                        Ref<EntityStore> ref = pRef.getReference();
                        if (ref == null || !ref.isValid()) {
                            return;
                        }
                        Player player = ref.getStore().getComponent(ref, Player.getComponentType());
                        if (player == null) {
                            return;
                        }
                        CustomUIHud hud = player.getHudManager().getCustomHud(key);
                        if (hud instanceof ScalingHud scalingHud) {
                            action.accept(scalingHud);
                        }
                    } catch (Throwable t) {
                        safeWarn("HUD broadcast failed for '" + key + "': " + t.getMessage());
                    }
                });
            } catch (Throwable t) {
                safeWarn("HUD broadcast dispatch failed for '" + key + "': " + t.getMessage());
            }
        }
    }

    /** Re-anchor every online player's HUD registered under {@code key} live (no reconnect). */
    public static void refreshPositionForAllOnline(@Nonnull String key, @Nonnull HudPosition position) {
        forEachOnlineHud(key, hud -> hud.pushPositionUpdate(position));
    }

    /** Push every online player's HUD registered under {@code key} hidden (the admin live-disable). */
    public static void hideForAllOnline(@Nonnull String key) {
        forEachOnlineHud(key, ScalingHud::pushHidden);
    }

    protected static void safeWarn(@Nonnull String message) {
        try {
            MobScalingPlugin.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less JVMs
        }
    }
}

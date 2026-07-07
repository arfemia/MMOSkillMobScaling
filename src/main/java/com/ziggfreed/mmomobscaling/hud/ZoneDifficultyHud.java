package com.ziggfreed.mmomobscaling.hud;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.ui.hud.HudPosition;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;
import com.ziggfreed.mmomobscaling.i18n.LocationNameResolver;

/**
 * The ZONE DIFFICULTY overlay: a small always-on card showing the effective local spawn
 * difficulty (the same {@code MobScalingSpawnHook.effectiveDifficulty} number {@code /mobscaling
 * inspect} reports), a qualitative threat tier RELATIVE to the viewer ({@link ZoneTier}, coloured),
 * the viewer's own power level, and the tracked group (region) power when players share the region.
 * Driven by {@code MobScalingHudSystem} on a coarse throttle; hidden whenever mob scaling is off
 * for the world (the numbers would be meaningless).
 *
 * <p>All display text is client-resolved {@code Message}s over {@code scaling.hud.*} lang keys,
 * pushed on {@code .TextSpans} (never {@code .Text} - a Message on a String sink crashes the
 * client render). Layout constants MUST stay in sync with {@code Hud/MmoscalingZoneHud.ui}.
 */
public final class ZoneDifficultyHud extends ScalingHud {

    public static final String HUD_KEY = "mmoscaling:zone_difficulty";

    /** Panel size in pixels - must match {@code #MmoscalingZonePanel} in the {@code .ui} (native frame). */
    private static final int PANEL_WIDTH_PX = 320;
    private static final int PANEL_HEIGHT_PX = 124;

    /** Zone data moves slowly (region cross / power growth), so a coarse cadence is plenty. */
    private static final long UPDATE_INTERVAL_MS = 1000L;

    /** Last pushed render state; an identical recompute skips the packet. */
    @Nullable
    private volatile String lastState;

    public ZoneDifficultyHud(@Nonnull PlayerRef playerRef) {
        super(playerRef, HUD_KEY);
    }

    /** Default: upper-left corner, below the vanilla compass area. */
    @Nonnull
    public static HudPosition defaultPosition() {
        return new HudPosition(HudPosition.AnchorEdge.TOP, HudPosition.HorizontalEdge.LEFT, 16, 90);
    }

    /** The configured position: the settings preset when valid, else {@link #defaultPosition()}. */
    @Nonnull
    public static HudPosition configuredPositionFromSettings() {
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        HudPosition parsed = HudPosition.parse(
                cfg.getZoneHudPosition(), cfg.getZoneHudOffsetX(), cfg.getZoneHudOffsetY());
        return parsed != null ? parsed : defaultPosition();
    }

    @Nonnull
    @Override
    protected String rootSelector() {
        return "#MmoscalingZonePanel";
    }

    @Override
    protected int panelWidth() {
        return PANEL_WIDTH_PX;
    }

    @Override
    protected int panelHeight() {
        return PANEL_HEIGHT_PX;
    }

    @Override
    protected long updateIntervalMs() {
        return UPDATE_INTERVAL_MS;
    }

    @Nonnull
    @Override
    protected HudPosition configuredPosition() {
        return configuredPositionFromSettings();
    }

    @Override
    protected void build(@Nonnull UICommandBuilder cmd) {
        cmd.append("Hud/MmoscalingZoneHud.ui");
        applyConfiguredPosition(cmd);
        // The title (the zone name) + all data rows are set on the first data push; start hidden so the
        // authored placeholder text never flashes.
        cmd.set("#MmoscalingZonePanel.Visible", false);
    }

    /** Neutral placeholder shown for the biome sub-line when no biome name is available. */
    private static final String NO_LOCATION_PLACEHOLDER = "-";

    /**
     * Push the zone readout. {@code visible} false (mob scaling off for this world, or the HUD
     * admin-disabled) hides the whole card; {@code groupPower <= 0} (cold region) hides the group
     * row. {@code zoneName}/{@code biomeName} are blank to hide the location row entirely (the
     * {@code scaling.hud.zone.showLocationName} toggle off, or the caller has nothing to show);
     * an empty {@code zoneName} with a non-blank pair still renders (falls back to
     * {@value #NO_LOCATION_PLACEHOLDER} for the missing half) rather than a blank label. Skips the
     * packet when nothing changed since the last push.
     */
    public void pushUpdate(double difficulty, double playerPower, double groupPower, boolean visible,
            @Nonnull String zoneName, @Nonnull String biomeName, boolean showLocation) {
        markPushed();

        long diffRounded = Math.round(difficulty);
        long powerRounded = Math.round(playerPower);
        long groupRounded = Math.round(groupPower);
        ZoneTier tier = ZoneTier.fromDelta(difficulty - playerPower);
        boolean hasLocation = showLocation && (!zoneName.isBlank() || !biomeName.isBlank());
        // The friendly-name lang-key prefixes are config, so a reload could change the rendered location
        // under unchanged ids; fold them into the skip-cache key so the next tick repaints after a reload.
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        String zonePrefix = cfg.getZoneNameKeyPrefix();
        String biomePrefix = cfg.getBiomeNameKeyPrefix();
        String state = visible + "|" + diffRounded + "|" + powerRounded + "|" + groupRounded + "|" + tier
                + "|" + hasLocation + "|" + zoneName + "|" + biomeName + "|" + zonePrefix + "|" + biomePrefix;
        if (state.equals(lastState)) {
            return; // identical readout: save the packet
        }
        lastState = state;

        UICommandBuilder cmd = new UICommandBuilder();
        cmd.set("#MmoscalingZonePanel.Visible", visible);
        if (visible) {
            cmd.set("#MmoscalingZoneTier.TextSpans", Message.translation(tier.langKey()));
            cmd.set("#MmoscalingZoneTier.Style.TextColor", tier.colorHex());
            cmd.set("#MmoscalingZoneValue.TextSpans",
                    Message.translation("scaling.hud.zone.difficulty").param("value", diffRounded));
            cmd.set("#MmoscalingZonePower.TextSpans",
                    Message.translation("scaling.hud.zone.power").param("power", powerRounded));
            boolean hasGroup = groupPower > 0.0;
            cmd.set("#MmoscalingZoneGroup.Visible", hasGroup);
            if (hasGroup) {
                cmd.set("#MmoscalingZoneGroup.TextSpans",
                        Message.translation("scaling.hud.zone.group").param("power", groupRounded));
            }
            // The ZONE name is the panel TITLE (the redundant static "ZONE DIFFICULTY" header is gone); the
            // BIOME is the sub-line below it. Both resolve to CLIENT-resolved friendly names (nested
            // Messages, never pre-resolved Strings): the zone via the base game's own "server.map.region.<id>"
            // keys, the biome prettified (vanilla ships no biome name key). When the zone name is unavailable
            // (a zoneless world, or the location toggle is off) the title falls back to the generic label.
            boolean hasZone = showLocation && !zoneName.isBlank();
            boolean hasBiome = showLocation && !biomeName.isBlank();
            Message zoneTitle = hasZone
                    ? LocationNameResolver.displayName(zoneName, zonePrefix, Message.translation("scaling.hud.zone.title"))
                    : Message.translation("scaling.hud.zone.title");
            cmd.set("#MmoscalingZoneTitle.TextSpans", zoneTitle);
            cmd.set("#MmoscalingZoneName.Visible", hasBiome);
            if (hasBiome) {
                cmd.set("#MmoscalingZoneName.TextSpans",
                        LocationNameResolver.displayName(biomeName, biomePrefix, Message.raw(NO_LOCATION_PLACEHOLDER)));
            }
        }
        update(false, cmd);
    }

    @Override
    protected void onHiddenPushed() {
        lastState = null; // the client diverged from the cache; never skip the next push
    }

    /** Typed lookup; {@code null} when not installed. World thread. */
    @Nullable
    public static ZoneDifficultyHud get(@Nonnull Player player) {
        return ScalingHud.get(player, HUD_KEY, ZoneDifficultyHud.class);
    }

    /** Re-anchor every online player's zone HUD live (the admin reposition). */
    public static void refreshPositionForAllOnline(@Nonnull HudPosition position) {
        ScalingHud.refreshPositionForAllOnline(HUD_KEY, position);
    }

    /**
     * Apply an admin on/off flip live. On disable, push hidden once (the ticking system
     * early-returns while disabled, so it cannot self-correct); on enable the ticking system
     * repaints within one throttle window - nothing to push here.
     */
    public static void setEnabledForAllOnline(boolean enabled) {
        if (!enabled) {
            ScalingHud.hideForAllOnline(HUD_KEY);
        }
    }
}

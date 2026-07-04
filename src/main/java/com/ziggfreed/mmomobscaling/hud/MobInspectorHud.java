package com.ziggfreed.mmomobscaling.hud;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;
import com.ziggfreed.mmomobscaling.i18n.MobScalingTextUtil;
import com.ziggfreed.mmomobscaling.rarity.Rarity;

/**
 * The MOB INSPECTOR overlay: a two-column card for the entity under the player's crosshair - a generated
 * mob PORTRAIT ({@code Icons/ModelsGenerated/<role>.png}, the native Memories still) plus its display name
 * (already rarity-decorated by the spawn hook for scaled mobs), a coloured rarity tag
 * ({@code Rarity.NameColor}, pack-authorable), the frozen scaled difficulty, a live HP bar (fill width +
 * {@code current / max} text), and the rolled affixes as separate icon CHIPS (a codec-driven
 * {@link com.ziggfreed.mmomobscaling.asset.IconSpec} icon + the localized name, never a joined string - no
 * English grammar in params). Unscaled targets still get portrait + name + HP (a plain inspector); the
 * rarity/difficulty/affix rows hide. Driven by {@code MobScalingHudSystem}, which resolves the target via
 * the engine's own {@code TargetUtil.getTargetEntity} crosshair raycast.
 *
 * <p>All text lands on {@code .TextSpans} (never {@code .Text}); the HP fill is an {@code Anchor}
 * width push, the exact pattern the MMO's {@code AbilityCooldownHud} pip fill ships on. Layout
 * constants MUST stay in sync with {@code Hud/MmoscalingMobInspector.ui}.
 */
public final class MobInspectorHud extends ScalingHud {

    public static final String HUD_KEY = "mmoscaling:mob_inspector";

    /**
     * Panel size in pixels - must match {@code #MmoscalingInspectPanel} in the {@code .ui}. A two-column
     * card: a LEFT portrait column (the mob's {@code Icons/ModelsGenerated/<role>.png} still) and a RIGHT
     * info column with a name row that WRAPS to two lines ({@code Wrap: true}, so a long decorated name
     * never clips), rarity + difficulty tags, an HP bar with the {@code current / max} readout OVERLAID on
     * the fill (the ability-HUD pip layering: a later sibling paints on top), and an affix chip row that
     * wraps onto a second line ({@code LayoutMode: LeftCenterWrap}); each chip is an icon + the affix name.
     */
    private static final int PANEL_WIDTH_PX = 280;
    private static final int PANEL_HEIGHT_PX = 108;

    /**
     * Inner HP-bar width available for the fill - must match {@code #MmoscalingInspectHpBg} inside the
     * right INFO column (panel 280 - 2x6 panel padding - 72 portrait - 6 gap = 190 info column,
     * minus 2x1 bar padding = 188).
     */
    private static final int HP_BAR_INNER_WIDTH_PX = 188;

    /** Affix label slots shipped by the .ui ({@code #Affix0..#Affix3}); surplus affixes are not shown. */
    private static final int MAX_AFFIX_LABELS = 4;

    /** Snappy enough to feel live on a moving fight without a per-tick packet per player. */
    private static final long UPDATE_INTERVAL_MS = 250L;

    /** Last pushed render state; an identical recompute skips the packet. */
    @Nullable
    private volatile String lastState;

    /**
     * One resolved crosshair target, precomputed on the world thread by the ticking system.
     * {@code targetKey} is any stable identity string for the entity (used only for the
     * skip-if-unchanged cache); {@code rarity} is null for a plain/unscaled target; {@code name}
     * is the target's client-resolved display name, null when the entity carries none;
     * {@code modelRole} is the target's NPC role name (the {@code Icons/ModelsGenerated/<role>.png}
     * portrait key), null when the entity is not an NPC or the portrait is disabled.
     */
    public record TargetSnapshot(
            @Nonnull String targetKey,
            @Nullable Message name,
            @Nullable Rarity rarity,
            @Nonnull List<Affix> affixes,
            double difficulty,
            boolean scaled,
            float hp,
            float hpMax,
            @Nullable String modelRole) {
    }

    public MobInspectorHud(@Nonnull PlayerRef playerRef) {
        super(playerRef, HUD_KEY);
    }

    /** Default: left-anchored, directly under the zone difficulty card (which now spans y 90..208
     *  after the location-name row grew its height). */
    @Nonnull
    public static HudPosition defaultPosition() {
        return new HudPosition(HudPosition.AnchorEdge.TOP, HudPosition.HorizontalEdge.LEFT, 16, 216);
    }

    /** The configured position: the settings preset when valid, else {@link #defaultPosition()}. */
    @Nonnull
    public static HudPosition configuredPositionFromSettings() {
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        HudPosition parsed = HudPosition.parse(
                cfg.getInspectorHudPosition(), cfg.getInspectorHudOffsetX(), cfg.getInspectorHudOffsetY());
        return parsed != null ? parsed : defaultPosition();
    }

    @Nonnull
    @Override
    protected String rootSelector() {
        return "#MmoscalingInspectPanel";
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
        cmd.append("Hud/MmoscalingMobInspector.ui");
        applyConfiguredPosition(cmd);
        // Hidden until the player actually looks at something.
        cmd.set("#MmoscalingInspectPanel.Visible", false);
    }

    @Override
    protected void onHiddenPushed() {
        lastState = null; // the client diverged from the cache; never skip the next push
    }

    /**
     * Push the current crosshair target ({@code null} = nothing targeted, hides the card). Skips
     * the packet when the rendered state is unchanged since the last push.
     */
    public void pushTarget(@Nullable TargetSnapshot target) {
        markPushed();

        String state = renderState(target);
        if (state.equals(lastState)) {
            return; // identical readout: save the packet
        }
        lastState = state;

        UICommandBuilder cmd = new UICommandBuilder();
        if (target == null) {
            cmd.set("#MmoscalingInspectPanel.Visible", false);
            update(false, cmd);
            return;
        }

        cmd.set("#MmoscalingInspectPanel.Visible", true);

        // Portrait: the target mob's generated model icon (Icons/ModelsGenerated/<role>.png), the same
        // pre-rendered still the native Memories page shows. A live 3D preview is not server-drivable;
        // a static portrait is. Hidden when disabled or the entity has no NPC role (the AssetImage's
        // FallbackTexturePath covers a role whose portrait was never generated).
        boolean hasPortrait = MobScalingConfig.getInstance().isInspectorPortraitEnabled()
                && target.modelRole() != null && !target.modelRole().isBlank();
        cmd.set("#MmoscalingInspectPortrait.Visible", hasPortrait);
        cmd.set("#MmoscalingInspectPortraitGap.Visible", hasPortrait); // collapse the column gap too
        if (hasPortrait) {
            cmd.set("#MmoscalingInspectPortrait.AssetPath", "Icons/ModelsGenerated/" + target.modelRole() + ".png");
        }

        // Name (already rarity-decorated for scaled mobs by the spawn hook's DisplayNameComponent stamp).
        boolean hasName = target.name() != null;
        cmd.set("#MmoscalingInspectName.Visible", hasName);
        if (hasName) {
            cmd.set("#MmoscalingInspectName.TextSpans", target.name());
        }

        // Rarity tag + frozen difficulty (scaled targets only).
        Rarity rarity = target.rarity();
        boolean hasRarity = rarity != null;
        cmd.set("#MmoscalingInspectRarity.Visible", hasRarity);
        if (hasRarity) {
            cmd.set("#MmoscalingInspectRarity.TextSpans",
                    Message.translation(MobScalingTextUtil.rarityNameKey(rarity)));
            cmd.set("#MmoscalingInspectRarity.Style.TextColor", rarity.displayColor());
        }
        cmd.set("#MmoscalingInspectLevel.Visible", target.scaled());
        if (target.scaled()) {
            cmd.set("#MmoscalingInspectLevel.TextSpans",
                    Message.translation("scaling.hud.inspect.difficulty")
                            .param("value", Math.round(target.difficulty())));
        }

        // HP bar fill + numeric readout.
        float max = Math.max(1f, target.hpMax());
        float pct = Math.max(0f, Math.min(1f, target.hp() / max));
        cmd.setObject("#MmoscalingInspectHpFill.Anchor",
                hpFillAnchor((int) Math.round(pct * HP_BAR_INNER_WIDTH_PX)));
        cmd.set("#MmoscalingInspectHp.TextSpans",
                Message.translation("scaling.hud.inspect.hp")
                        .param("current", Math.max(0, Math.round(target.hp())))
                        .param("max", Math.round(max)));

        // Affixes: one chip per affix (a codec-driven icon + the localized name), no string joining.
        List<Affix> affixes = target.affixes();
        cmd.set("#MmoscalingInspectAffixRow.Visible", !affixes.isEmpty());
        for (int i = 0; i < MAX_AFFIX_LABELS; i++) {
            String sel = "#MmoscalingInspectAffixRow #Affix" + i;
            if (i >= affixes.size()) {
                cmd.set(sel + ".Visible", false);
                continue;
            }
            Affix affix = affixes.get(i);
            cmd.set(sel + ".Visible", true);
            cmd.set(sel + " #Name.TextSpans",
                    Message.translation(MobScalingTextUtil.affixNameKey(affix)));
            IconRenderer.applyIcon(cmd, sel, affix.iconItemId(), affix.iconTexturePath());
        }

        update(false, cmd);
    }

    /**
     * The skip-cache key for a push: identity + everything that renders. The name is keyed by the
     * {@code Message} INSTANCE identity (a re-stamped {@code DisplayNameComponent} carries a new
     * Message object, so a live rename invalidates; the content itself is client-resolved and not
     * readable here), and the rarity by id + its rendered {@code displayColor()} (a hot content
     * reload folds a NEW {@code Rarity} record whose colour may change under an unchanged id).
     */
    @Nonnull
    private static String renderState(@Nullable TargetSnapshot t) {
        if (t == null) {
            return "none";
        }
        StringBuilder sb = new StringBuilder(96)
                .append(t.targetKey())
                .append('|').append(t.name() != null ? System.identityHashCode(t.name()) : -1)
                .append('|').append(t.rarity() != null ? t.rarity().id() + t.rarity().displayColor() : "")
                .append('|').append(t.scaled() ? Math.round(t.difficulty()) : -1)
                .append('|').append(Math.round(t.hp()))
                .append('/').append(Math.round(t.hpMax()))
                // Portrait role + the live portrait toggle (a config reload that flips it must repaint).
                .append('|').append(t.modelRole() != null ? t.modelRole() : "")
                .append('|').append(MobScalingConfig.getInstance().isInspectorPortraitEnabled());
        for (Affix affix : t.affixes()) {
            // id + icon leaves: a hot content reload may fold a new icon under an unchanged affix id.
            sb.append('+').append(affix.id())
                    .append('=').append(affix.iconItemId()).append('~').append(affix.iconTexturePath());
        }
        return sb.toString();
    }

    /** Horizontal fill anchor: pin left/top/bottom, vary width (the ability-HUD pip-fill pattern). */
    @Nonnull
    private static Anchor hpFillAnchor(int width) {
        Anchor a = new Anchor();
        a.setLeft(Value.of(0));
        a.setTop(Value.of(0));
        a.setBottom(Value.of(0));
        a.setWidth(Value.of(width));
        return a;
    }

    /** Typed lookup; {@code null} when not installed. World thread. */
    @Nullable
    public static MobInspectorHud get(@Nonnull Player player) {
        return ScalingHud.get(player, HUD_KEY, MobInspectorHud.class);
    }

    /** Re-anchor every online player's inspector HUD live (the admin reposition). */
    public static void refreshPositionForAllOnline(@Nonnull HudPosition position) {
        ScalingHud.refreshPositionForAllOnline(HUD_KEY, position);
    }

    /**
     * Apply an admin on/off flip live. On disable, push hidden once (the ticking system
     * early-returns while disabled); on enable the ticking system repaints within one throttle
     * window - nothing to push here.
     */
    public static void setEnabledForAllOnline(boolean enabled) {
        if (!enabled) {
            ScalingHud.hideForAllOnline(HUD_KEY);
        }
    }
}

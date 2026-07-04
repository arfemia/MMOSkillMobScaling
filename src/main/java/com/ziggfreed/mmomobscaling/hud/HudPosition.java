package com.ziggfreed.mmomobscaling.hud;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;

/**
 * Server-wide layout config for one of this mod's HUD overlays, mirroring the MMO Skill Tree's
 * proven {@code ui/hud/HudPosition} (the MMO's class is not API-frozen, so this mod carries its
 * own copy; lifting the primitive into ziggfreed-common is a follow-up). {@link #toAnchor(int, int)}
 * always sets {@code Width}/{@code Height} and pins EXACTLY ONE edge per axis, so an offset moves
 * the panel instead of being neutralized by an opposing edge stretch.
 *
 * <p>Config-facing form: a named corner preset ({@code TOP_LEFT}, {@code TOP_CENTER}, ...,
 * {@code BOTTOM_RIGHT}) plus pixel offsets, parsed by {@link #parse}. The offsets measure from the
 * pinned edge(s); for a {@code CENTER} axis they shift the centred element.
 */
public final class HudPosition {

    public enum AnchorEdge {
        TOP, BOTTOM, CENTER
    }

    public enum HorizontalEdge {
        LEFT, CENTER, RIGHT
    }

    private final AnchorEdge anchorEdge;
    private final HorizontalEdge horizontalEdge;
    private final int offsetX;
    private final int offsetY;

    public HudPosition(@Nonnull AnchorEdge anchorEdge, @Nonnull HorizontalEdge horizontalEdge,
            int offsetX, int offsetY) {
        this.anchorEdge = anchorEdge;
        this.horizontalEdge = horizontalEdge;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    /**
     * Parse a named corner preset ({@code "TOP_LEFT"}, {@code "CENTER_RIGHT"}, {@code "BOTTOM_CENTER"},
     * plain {@code "CENTER"}, case-insensitive) + offsets into a {@code HudPosition}; {@code null} for an
     * unrecognized preset (the caller falls back to its default and may warn).
     */
    @Nullable
    public static HudPosition parse(@Nullable String preset, int offsetX, int offsetY) {
        if (preset == null) {
            return null;
        }
        return switch (preset.trim().toUpperCase(Locale.ROOT)) {
            case "TOP_LEFT" -> new HudPosition(AnchorEdge.TOP, HorizontalEdge.LEFT, offsetX, offsetY);
            case "TOP_CENTER" -> new HudPosition(AnchorEdge.TOP, HorizontalEdge.CENTER, offsetX, offsetY);
            case "TOP_RIGHT" -> new HudPosition(AnchorEdge.TOP, HorizontalEdge.RIGHT, offsetX, offsetY);
            case "CENTER_LEFT" -> new HudPosition(AnchorEdge.CENTER, HorizontalEdge.LEFT, offsetX, offsetY);
            case "CENTER" -> new HudPosition(AnchorEdge.CENTER, HorizontalEdge.CENTER, offsetX, offsetY);
            case "CENTER_RIGHT" -> new HudPosition(AnchorEdge.CENTER, HorizontalEdge.RIGHT, offsetX, offsetY);
            case "BOTTOM_LEFT" -> new HudPosition(AnchorEdge.BOTTOM, HorizontalEdge.LEFT, offsetX, offsetY);
            case "BOTTOM_CENTER" -> new HudPosition(AnchorEdge.BOTTOM, HorizontalEdge.CENTER, offsetX, offsetY);
            case "BOTTOM_RIGHT" -> new HudPosition(AnchorEdge.BOTTOM, HorizontalEdge.RIGHT, offsetX, offsetY);
            default -> null;
        };
    }

    /** True when {@code preset} names a valid position (the command validates before applying). */
    public static boolean isValidPreset(@Nullable String preset) {
        return parse(preset, 0, 0) != null;
    }

    @Nonnull
    public AnchorEdge getAnchorEdge() {
        return anchorEdge;
    }

    @Nonnull
    public HorizontalEdge getHorizontalEdge() {
        return horizontalEdge;
    }

    public int getOffsetX() {
        return offsetX;
    }

    public int getOffsetY() {
        return offsetY;
    }

    /**
     * Build a Hytale {@link Anchor} sized to the supplied pixel dimensions and positioned according
     * to this {@code HudPosition}: {@code Width}/{@code Height} always set, exactly one horizontal
     * and one vertical edge pinned.
     */
    @Nonnull
    public Anchor toAnchor(int panelWidth, int panelHeight) {
        Anchor anchor = new Anchor();
        anchor.setWidth(Value.of(panelWidth));
        anchor.setHeight(Value.of(panelHeight));

        switch (horizontalEdge) {
            case LEFT -> anchor.setLeft(Value.of(offsetX));
            case RIGHT -> anchor.setRight(Value.of(offsetX));
            // NO Horizontal here: in Hytale's DSL "Horizontal: N" is a LEFT+RIGHT inset (stretch
            // to fill minus N), so "Horizontal: 0" would stretch the panel to FULL SCREEN width
            // instead of centering it (see KweebecBossHud.ui's comment for the same gotcha). A
            // fixed Width with no Horizontal centers horizontally by default; offsetX still nudges
            // via Left so a CENTER preset with a nonzero offset is not silently ignored.
            case CENTER -> {
                if (offsetX != 0) {
                    anchor.setLeft(Value.of(offsetX));
                }
            }
        }

        switch (anchorEdge) {
            case TOP -> anchor.setTop(Value.of(offsetY));
            case BOTTOM -> anchor.setBottom(Value.of(offsetY));
            case CENTER -> anchor.setVertical(Value.of(offsetY));
        }

        return anchor;
    }

    @Override
    public String toString() {
        return "HudPosition{" + anchorEdge + "/" + horizontalEdge + ", x=" + offsetX + ", y=" + offsetY + "}";
    }
}

package com.ziggfreed.mmomobscaling.hud;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * The ONE seam that paints a codec-driven {@link com.ziggfreed.mmomobscaling.asset.IconSpec} into a HUD
 * chip (DRY: every icon-bearing HUD row routes through here rather than re-deciding item-vs-texture per
 * site). A chip ships two sibling icon widgets and this toggles the right one by {@code .Visible}:
 * <ul>
 *   <li>an {@code ItemIcon #IcoItem} whose {@code .ItemId} is the item whose generated icon to draw
 *       (item ids give the widest coverage, exactly the {@code /give} id), and</li>
 *   <li>an {@code AssetImage #IcoTex} whose {@code .AssetPath} is a Common-rooted texture path.</li>
 * </ul>
 * Item id WINS over texture path; when both are blank BOTH widgets hide (the chip renders its label only).
 * Both values are pushed as plain Strings ({@code cmd.set}), the always-safe form (no PatchStyle red-X).
 * The child ids ({@code #IcoItem} / {@code #IcoTex}) are fixed by convention across every icon chip.
 */
public final class IconRenderer {

    /** Relative child ids every icon chip must declare (an {@code ItemIcon} and an {@code AssetImage}). */
    public static final String ITEM_ICON_ID = "#IcoItem";
    public static final String TEXTURE_ICON_ID = "#IcoTex";

    private IconRenderer() {
    }

    /**
     * Paint the icon for a chip. {@code chipSelector} is the chip's element selector (e.g.
     * {@code "#MmoscalingInspectAffixRow #Affix0"}); the two icon widgets are its {@code #IcoItem} /
     * {@code #IcoTex} children. Item id wins; both blank hides both widgets.
     */
    public static void applyIcon(@Nonnull UICommandBuilder cmd, @Nonnull String chipSelector,
            @Nullable String itemId, @Nullable String texturePath) {
        String itemSel = chipSelector + " " + ITEM_ICON_ID;
        String texSel = chipSelector + " " + TEXTURE_ICON_ID;
        boolean hasItem = itemId != null && !itemId.isBlank();
        boolean hasTexture = !hasItem && texturePath != null && !texturePath.isBlank();

        cmd.set(itemSel + ".Visible", hasItem);
        if (hasItem) {
            cmd.set(itemSel + ".ItemId", itemId);
        }
        cmd.set(texSel + ".Visible", hasTexture);
        if (hasTexture) {
            cmd.set(texSel + ".AssetPath", texturePath);
        }
    }
}

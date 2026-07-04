package com.ziggfreed.mmomobscaling.asset;

import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * A SHARED, reusable icon reference for any pack-authorable mob-scaling asset (affixes today; rarities
 * or currencies later). Pattern A nested group ({@code "Icon": { "ItemId": ..., "TexturePath": ... }}),
 * its own {@link BuilderCodec}, PascalCase keys - so a new render site adds ONE codec append
 * ({@code new KeyedCodec<>("Icon", IconSpec.CODEC, false)}) instead of re-deriving an icon schema.
 *
 * <p>An icon is EITHER an item id (rendered as the item's generated icon via a {@code ItemIcon} element,
 * the widest coverage, exactly the {@code /give} item id) OR a Common-rooted texture PATH (rendered via an
 * {@code AssetImage}, e.g. {@code UI/StatusEffects/Stamina.png}). Both leaves are nullable; when both are
 * set the ITEM ID wins (checked at the render site). An absent {@code Icon} group / two blank leaves means
 * "no icon" (the chip renders its label only). Nothing here resolves or renders - the HUD's
 * {@code hud/IconRenderer} reads {@link #itemId()} / {@link #texturePath()} and toggles the matching widget.
 */
public final class IconSpec {

    public static final BuilderCodec<IconSpec> CODEC = BuilderCodec.builder(IconSpec.class, IconSpec::new)
            // The item id whose generated icon to render (e.g. "Armor_Bronze_Chest"); wins over TexturePath.
            .append(new KeyedCodec<>("ItemId", Codec.STRING, false), (i, v) -> i.itemId = v, i -> i.itemId)
            .add()
            // A Common-rooted texture path (e.g. "UI/StatusEffects/Stamina.png"); used only when ItemId is blank.
            .append(new KeyedCodec<>("TexturePath", Codec.STRING, false),
                    (i, v) -> i.texturePath = v, i -> i.texturePath)
            .add()
            .build();

    @Nullable private String itemId;
    @Nullable private String texturePath;

    public IconSpec() {
    }

    /** The item id to render as an icon (its generated item icon); {@code null}/blank = none. Wins over {@link #texturePath()}. */
    @Nullable
    public String itemId() {
        return blankToNull(itemId);
    }

    /** A Common-rooted texture path to render as an icon; {@code null}/blank = none. Used only when {@link #itemId()} is null. */
    @Nullable
    public String texturePath() {
        return blankToNull(texturePath);
    }

    @Nullable
    private static String blankToNull(@Nullable String v) {
        return (v == null || v.isBlank()) ? null : v;
    }
}

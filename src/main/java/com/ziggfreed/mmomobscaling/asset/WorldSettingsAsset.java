package com.ziggfreed.mmomobscaling.asset;

import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.ziggfreed.common.asset.AbstractRawJsonAsset;

/**
 * One per-world settings file, delivered from {@code Server/MmoMobScaling/Worlds/*.json} (1.0.2 -
 * the per-world schema that REPLACED the inline {@code WorldOverrides[]} array on
 * {@link MobScalingSettingsAsset}). Raw-{@code Payload} (Pattern B) ON PURPOSE: a world file may
 * carry a top-level {@code "Parent": "<other-world-file-id>"} that must merge ACROSS layers the
 * engine store cannot see (jar + pack store bodies PLUS the owner-dir
 * {@code mods/MmoMobScaling/worlds/*.json} files), so the body survives raw for the
 * {@code JsonParentResolver} pre-merge and each RESOLVED body decodes through the ONE structured
 * schema authority, {@link WorldSettings#CODEC} - the same decode on every layer.
 *
 * <p>The asset store is pure discovery / merge / reload; {@code WorldSettingsConfig} owns the
 * pool + fold. Filename = id = the {@code Parent} target; the world SELECTOR is the body's own
 * {@code Match} field (a body with no {@code Match} is a pool-only BASE other files inherit from).
 *
 * <p>Pack JSON shape: <pre>{@code { "Name": "...", "Payload": { ...WorldSettings body... } }}</pre>
 */
public final class WorldSettingsAsset extends AbstractRawJsonAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, WorldSettingsAsset>> {

    public static final AssetBuilderCodec<String, WorldSettingsAsset> CODEC =
            rawCodec(WorldSettingsAsset.class, WorldSettingsAsset::new);
}

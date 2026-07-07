package com.ziggfreed.mmomobscaling.pages;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.hud.HudPosition;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.WorldOverride;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;
import com.ziggfreed.mmomobscaling.config.MobScalingOwnerWriter;
import com.ziggfreed.mmomobscaling.hud.MobInspectorHud;
import com.ziggfreed.mmomobscaling.hud.ZoneDifficultyHud;

/**
 * The in-game admin config page for MMO Mob Scaling ({@code /mobscaling ui}). Four tabs - Global knobs,
 * Zone HUD, Mob Inspector HUD, and a per-world {@code WorldOverrides} CRUD editor - over the shared
 * ziggfreed-common decorated frame. Every edit persists through the ONE write-back path
 * ({@link MobScalingOwnerWriter} -> the owner file -> {@code refreshFromDisk}) so it survives a restart,
 * and HUD / preset edits live-apply to all online players. All labelled buttons are RICH
 * ({@link ZigRichButton} / {@link SettingsUiUtil#setToggle}); all display text is a client-resolved
 * {@link Message} on {@code .TextSpans}.
 *
 * <p><b>Access:</b> gated by the {@code /mobscaling ui} command's {@code hytale:Admin} permission group
 * (the only way to open this page); the page reopens itself, so a client cannot open it unprompted.
 */
public final class MobScalingAdminPage extends InteractiveCustomUIPage<MobScalingAdminPage.EventData> {

    private static final String UI = "Pages/MmoscalingAdminPage.ui";
    private static final String ROW = "Pages/ZigListRow.ui";
    private static final String WORLD_LIST = "#MmoscalingWorldList";

    // The 9 named corner presets (technical ids, shown literally in the position dropdowns).
    private static final String[] POSITIONS = {
            "TOP_LEFT", "TOP_CENTER", "TOP_RIGHT",
            "CENTER_LEFT", "CENTER", "CENTER_RIGHT",
            "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT"
    };
    // Tri-state dropdown for a per-world nullable Boolean leaf.
    private static final String[] TRISTATE_VALUES = {"inherit", "on", "off"};

    private String activeTab = "global";

    // Cached text-input values (survive a reopen; seeded from config, updated on ValueChanged).
    private String intensityInput;
    private String rarityInput;
    private String minCapInput;
    private String maxCapInput;
    private String escStartInput;
    private String escBlocksInput;
    private String escMaxBonusInput;
    private String escRarityInput;
    private String zonePosInput;
    private String zoneOffsetXInput;
    private String zoneOffsetYInput;
    private String inspectorPosInput;
    private String inspectorOffsetXInput;
    private String inspectorOffsetYInput;
    private String inspectorRangeInput;

    // Per-world editor state.
    private String worldMatchInput = "";
    private String worldIntensityInput = "";
    private String worldRarityInput = "";
    private String worldMinCapInput = "";
    private String worldMaxCapInput = "";
    private String worldPlayerScalingInput = "inherit";
    private String worldEscInput = "inherit";

    @Nullable private Message statusMessage;
    private boolean statusIsError;

    public MobScalingAdminPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, EventData.CODEC);
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        this.intensityInput = num(cfg.getIntensity());
        this.rarityInput = num(cfg.getRaritySpawnChance());
        this.minCapInput = num(cfg.getDifficultyMinCap());
        this.maxCapInput = num(cfg.getDifficultyMaxCap());
        this.escStartInput = num(cfg.getEscalationStartDistanceBlocks());
        this.escBlocksInput = num(cfg.getEscalationBlocksPerPoint());
        this.escMaxBonusInput = num(cfg.getEscalationMaxBonus());
        this.escRarityInput = num(cfg.getEscalationRarityChancePerPoint());
        this.zonePosInput = posOr(cfg.getZoneHudPosition());
        this.zoneOffsetXInput = String.valueOf(cfg.getZoneHudOffsetX());
        this.zoneOffsetYInput = String.valueOf(cfg.getZoneHudOffsetY());
        this.inspectorPosInput = posOr(cfg.getInspectorHudPosition());
        this.inspectorOffsetXInput = String.valueOf(cfg.getInspectorHudOffsetX());
        this.inspectorOffsetYInput = String.valueOf(cfg.getInspectorHudOffsetY());
        this.inspectorRangeInput = num(cfg.getInspectorRangeBlocks());
    }

    // ---------------------------------------------------------------------
    // Build
    // ---------------------------------------------------------------------

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        cmd.append(UI);
        MobScalingConfig cfg = MobScalingConfig.getInstance();

        cmd.set("#MmoscalingTitle.TextSpans", tr("scaling.ui.title"));
        SettingsUiUtil.bindButton(events, "#CloseButton", "close");

        buildTabs(cmd, events);
        cmd.set("#MmoscalingSectionGlobal.Visible", activeTab.equals("global"));
        cmd.set("#MmoscalingSectionZoneHud.Visible", activeTab.equals("zonehud"));
        cmd.set("#MmoscalingSectionInspector.Visible", activeTab.equals("inspector"));
        cmd.set("#MmoscalingSectionWorlds.Visible", activeTab.equals("worlds"));

        buildGlobal(cmd, events, cfg);
        buildZoneHud(cmd, events, cfg);
        buildInspector(cmd, events, cfg);
        buildWorlds(cmd, events, cfg);

        SettingsUiUtil.setStatus(cmd, "#MmoscalingStatus", statusMessage, statusIsError);
    }

    private void buildTabs(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        label(cmd, "#MmoscalingTabGlobal", "scaling.ui.tab.global");
        label(cmd, "#MmoscalingTabZoneHud", "scaling.ui.tab.zone_hud");
        label(cmd, "#MmoscalingTabInspector", "scaling.ui.tab.inspector");
        label(cmd, "#MmoscalingTabWorlds", "scaling.ui.tab.worlds");
        SettingsUiUtil.setTabActive(cmd, "#MmoscalingTabGlobal", activeTab.equals("global"));
        SettingsUiUtil.setTabActive(cmd, "#MmoscalingTabZoneHud", activeTab.equals("zonehud"));
        SettingsUiUtil.setTabActive(cmd, "#MmoscalingTabInspector", activeTab.equals("inspector"));
        SettingsUiUtil.setTabActive(cmd, "#MmoscalingTabWorlds", activeTab.equals("worlds"));
        SettingsUiUtil.bindButton(events, "#MmoscalingTabGlobal", "tab", "Tab", "global");
        SettingsUiUtil.bindButton(events, "#MmoscalingTabZoneHud", "tab", "Tab", "zonehud");
        SettingsUiUtil.bindButton(events, "#MmoscalingTabInspector", "tab", "Tab", "inspector");
        SettingsUiUtil.bindButton(events, "#MmoscalingTabWorlds", "tab", "Tab", "worlds");
    }

    private void buildGlobal(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull MobScalingConfig cfg) {
        rowLabel(cmd, "#MmoscalingEnabledLabel", "scaling.ui.global.enabled");
        toggle(cmd, "#MmoscalingEnabledToggle", cfg.isEnabled());
        SettingsUiUtil.bindButton(events, "#MmoscalingEnabledToggle", "toggleEnabled");
        cmd.set("#MmoscalingEnabledNote.TextSpans", tr("scaling.ui.global.enabled_note"));

        rowLabel(cmd, "#MmoscalingPresetLabel", "scaling.ui.global.preset");
        List<String> presets = cfg.availablePresetNames();
        if (presets.isEmpty()) {
            presets = List.of(cfg.getActivePreset());
        }
        String[] presetArr = presets.toArray(new String[0]);
        SettingsUiUtil.populate(cmd, "#MmoscalingPresetDropdown", presetArr, presetArr, cfg.getActivePreset());
        SettingsUiUtil.bindDropdown(events, "#MmoscalingPresetDropdown", "selectPreset");

        fieldSave(cmd, events, "#MmoscalingIntensityLabel", "scaling.ui.global.intensity",
                "#MmoscalingIntensityField", intensityInput, "@IntensityInput",
                "#MmoscalingIntensitySave", "saveIntensity");
        fieldSave(cmd, events, "#MmoscalingRarityLabel", "scaling.ui.global.rarity",
                "#MmoscalingRarityField", rarityInput, "@RarityInput",
                "#MmoscalingRaritySave", "saveRarity");

        rowLabel(cmd, "#MmoscalingPlayerScalingLabel", "scaling.ui.global.player_scaling");
        toggle(cmd, "#MmoscalingPlayerScalingToggle", cfg.isPlayerScalingEnabled());
        SettingsUiUtil.bindButton(events, "#MmoscalingPlayerScalingToggle", "togglePlayerScaling");

        cmd.set("#MmoscalingCapsHeader.TextSpans", tr("scaling.ui.global.caps_header"));
        field(cmd, events, "#MmoscalingMinCapLabel", "scaling.ui.global.min_cap",
                "#MmoscalingMinCapField", minCapInput, "@MinCapInput");
        field(cmd, events, "#MmoscalingMaxCapLabel", "scaling.ui.global.max_cap",
                "#MmoscalingMaxCapField", maxCapInput, "@MaxCapInput");
        saveButton(cmd, events, "#MmoscalingCapsSave", "scaling.ui.button.save_caps", "saveCaps");

        cmd.set("#MmoscalingEscHeader.TextSpans", tr("scaling.ui.global.esc_header"));
        rowLabel(cmd, "#MmoscalingEscEnabledLabel", "scaling.ui.global.esc_enabled");
        toggle(cmd, "#MmoscalingEscEnabledToggle", cfg.isDistanceEscalationEnabled());
        SettingsUiUtil.bindButton(events, "#MmoscalingEscEnabledToggle", "toggleEscEnabled");
        field(cmd, events, "#MmoscalingEscStartLabel", "scaling.ui.global.esc_start",
                "#MmoscalingEscStartField", escStartInput, "@EscStartInput");
        field(cmd, events, "#MmoscalingEscBlocksLabel", "scaling.ui.global.esc_blocks",
                "#MmoscalingEscBlocksField", escBlocksInput, "@EscBlocksInput");
        field(cmd, events, "#MmoscalingEscMaxBonusLabel", "scaling.ui.global.esc_max_bonus",
                "#MmoscalingEscMaxBonusField", escMaxBonusInput, "@EscMaxBonusInput");
        field(cmd, events, "#MmoscalingEscRarityLabel", "scaling.ui.global.esc_rarity",
                "#MmoscalingEscRarityField", escRarityInput, "@EscRarityInput");
        saveButton(cmd, events, "#MmoscalingEscSave", "scaling.ui.button.save_esc", "saveEsc");
    }

    private void buildZoneHud(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull MobScalingConfig cfg) {
        rowLabel(cmd, "#MmoscalingZoneEnabledLabel", "scaling.ui.zone.enabled");
        toggle(cmd, "#MmoscalingZoneEnabledToggle", cfg.isZoneHudEnabled());
        SettingsUiUtil.bindButton(events, "#MmoscalingZoneEnabledToggle", "toggleZoneEnabled");

        rowLabel(cmd, "#MmoscalingZoneShowLocLabel", "scaling.ui.zone.show_location");
        toggle(cmd, "#MmoscalingZoneShowLocToggle", cfg.isZoneShowLocationName());
        SettingsUiUtil.bindButton(events, "#MmoscalingZoneShowLocToggle", "toggleZoneShowLoc");

        rowLabel(cmd, "#MmoscalingZonePosLabel", "scaling.ui.hud.position");
        SettingsUiUtil.populate(cmd, "#MmoscalingZonePosDropdown", POSITIONS, POSITIONS, zonePosInput);
        SettingsUiUtil.bindDropdown(events, "#MmoscalingZonePosDropdown", "zonePosChanged");

        field(cmd, events, "#MmoscalingZoneOffsetXLabel", "scaling.ui.hud.offset_x",
                "#MmoscalingZoneOffsetXField", zoneOffsetXInput, "@ZoneOffsetXInput");
        field(cmd, events, "#MmoscalingZoneOffsetYLabel", "scaling.ui.hud.offset_y",
                "#MmoscalingZoneOffsetYField", zoneOffsetYInput, "@ZoneOffsetYInput");
        saveButton(cmd, events, "#MmoscalingZonePosSave", "scaling.ui.button.save_position", "saveZonePos");
    }

    private void buildInspector(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull MobScalingConfig cfg) {
        rowLabel(cmd, "#MmoscalingInspectorEnabledLabel", "scaling.ui.inspector.enabled");
        toggle(cmd, "#MmoscalingInspectorEnabledToggle", cfg.isInspectorHudEnabled());
        SettingsUiUtil.bindButton(events, "#MmoscalingInspectorEnabledToggle", "toggleInspectorEnabled");

        rowLabel(cmd, "#MmoscalingInspectorPortraitLabel", "scaling.ui.inspector.portrait");
        toggle(cmd, "#MmoscalingInspectorPortraitToggle", cfg.isInspectorPortraitEnabled());
        SettingsUiUtil.bindButton(events, "#MmoscalingInspectorPortraitToggle", "toggleInspectorPortrait");

        rowLabel(cmd, "#MmoscalingInspectorPosLabel", "scaling.ui.hud.position");
        SettingsUiUtil.populate(cmd, "#MmoscalingInspectorPosDropdown", POSITIONS, POSITIONS, inspectorPosInput);
        SettingsUiUtil.bindDropdown(events, "#MmoscalingInspectorPosDropdown", "inspectorPosChanged");

        field(cmd, events, "#MmoscalingInspectorOffsetXLabel", "scaling.ui.hud.offset_x",
                "#MmoscalingInspectorOffsetXField", inspectorOffsetXInput, "@InspectorOffsetXInput");
        field(cmd, events, "#MmoscalingInspectorOffsetYLabel", "scaling.ui.hud.offset_y",
                "#MmoscalingInspectorOffsetYField", inspectorOffsetYInput, "@InspectorOffsetYInput");
        saveButton(cmd, events, "#MmoscalingInspectorPosSave", "scaling.ui.button.save_position", "saveInspectorPos");

        fieldSave(cmd, events, "#MmoscalingInspectorRangeLabel", "scaling.ui.inspector.range",
                "#MmoscalingInspectorRangeField", inspectorRangeInput, "@InspectorRangeInput",
                "#MmoscalingInspectorRangeSave", "saveInspectorRange");
    }

    private void buildWorlds(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull MobScalingConfig cfg) {
        cmd.clear(WORLD_LIST);
        List<WorldOverride> overrides = cfg.worldOverrideView();
        java.util.Set<String> owned = MobScalingOwnerWriter.ownerAuthoredMatches();
        cmd.set("#MmoscalingWorldEmpty.Visible", overrides.isEmpty());
        if (overrides.isEmpty()) {
            cmd.set("#MmoscalingWorldEmpty.TextSpans", tr("scaling.ui.world.empty"));
        }
        for (int i = 0; i < overrides.size(); i++) {
            WorldOverride ov = overrides.get(i);
            String match = ov.getMatch() == null ? "" : ov.getMatch();
            String rowSel = WORLD_LIST + "[" + i + "]";
            cmd.append(WORLD_LIST, ROW);
            cmd.set(rowSel + " #Title.Text", match);
            cmd.set(rowSel + " #Sub.Text", worldSummary(ov));
            boolean isOwner = owned.contains(match.trim().toLowerCase(Locale.ROOT));
            cmd.set(rowSel + " #Badge.Visible", true);
            cmd.set(rowSel + " #Badge.TextSpans", tr(isOwner ? "scaling.ui.world.badge_override"
                    : "scaling.ui.world.badge_default"));
            ZigRichButton.text(cmd, rowSel + " #EditBtn", tr("scaling.ui.button.edit"));
            SettingsUiUtil.bindButton(events, rowSel + " #EditBtn", "editWorld", "Match", match);
            // Only an owner-authored override is removable (a jar/pack default re-folds if removed).
            cmd.set(rowSel + " #RemoveBtn.Visible", isOwner);
            if (isOwner) {
                ZigRichButton.text(cmd, rowSel + " #RemoveBtn", tr("scaling.ui.button.remove"));
                SettingsUiUtil.bindButton(events, rowSel + " #RemoveBtn", "removeWorld", "Match", match);
            }
        }

        cmd.set("#MmoscalingWorldEditorHeader.TextSpans", tr("scaling.ui.world.editor_header"));
        field(cmd, events, "#MmoscalingWorldMatchLabel", "scaling.ui.world.match",
                "#MmoscalingWorldMatchField", worldMatchInput, "@WorldMatchInput");
        field(cmd, events, "#MmoscalingWorldIntensityLabel", "scaling.ui.world.intensity",
                "#MmoscalingWorldIntensityField", worldIntensityInput, "@WorldIntensityInput");
        field(cmd, events, "#MmoscalingWorldRarityLabel", "scaling.ui.world.rarity",
                "#MmoscalingWorldRarityField", worldRarityInput, "@WorldRarityInput");
        field(cmd, events, "#MmoscalingWorldMinCapLabel", "scaling.ui.world.min_cap",
                "#MmoscalingWorldMinCapField", worldMinCapInput, "@WorldMinCapInput");
        field(cmd, events, "#MmoscalingWorldMaxCapLabel", "scaling.ui.world.max_cap",
                "#MmoscalingWorldMaxCapField", worldMaxCapInput, "@WorldMaxCapInput");

        rowLabel(cmd, "#MmoscalingWorldPlayerScalingLabel", "scaling.ui.world.player_scaling");
        SettingsUiUtil.populate(cmd, "#MmoscalingWorldPlayerScalingDropdown",
                triLabels(), TRISTATE_VALUES, worldPlayerScalingInput);
        SettingsUiUtil.bindDropdown(events, "#MmoscalingWorldPlayerScalingDropdown", "worldPlayerScalingChanged");

        rowLabel(cmd, "#MmoscalingWorldEscLabel", "scaling.ui.world.escalation");
        SettingsUiUtil.populate(cmd, "#MmoscalingWorldEscDropdown",
                triLabels(), TRISTATE_VALUES, worldEscInput);
        SettingsUiUtil.bindDropdown(events, "#MmoscalingWorldEscDropdown", "worldEscChanged");

        cmd.set("#MmoscalingWorldHint.TextSpans", tr("scaling.ui.world.hint"));
        saveButton(cmd, events, "#MmoscalingWorldSave", "scaling.ui.button.save_world", "saveWorld");
        saveButton(cmd, events, "#MmoscalingWorldClear", "scaling.ui.button.clear", "clearWorld");
    }

    // ---------------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------------

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull EventData data) {
        cacheInputs(data);
        String action = data.action == null ? "" : data.action;
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        this.statusMessage = null;

        switch (action) {
            case "close" -> { close(); return; }
            case "tab" -> { if (data.tab != null) activeTab = data.tab; }

            // Global.
            case "toggleEnabled" -> { MobScalingOwnerWriter.saveEnabled(!cfg.isEnabled()); ok("scaling.ui.status.saved_restart"); }
            case "selectPreset" -> selectPreset(data.dropdownValue);
            case "saveIntensity" -> {
                Double v = parseNonNegative(intensityInput);
                if (v == null) err("scaling.ui.status.invalid_number");
                else { MobScalingOwnerWriter.saveIntensity(v); ok("scaling.ui.status.saved"); }
            }
            case "saveRarity" -> {
                Double v = parseChance(rarityInput);
                if (v == null) err("scaling.ui.status.invalid_chance");
                else { MobScalingOwnerWriter.saveRaritySpawnChance(v); ok("scaling.ui.status.saved"); }
            }
            case "togglePlayerScaling" -> { MobScalingOwnerWriter.savePlayerScalingEnabled(!cfg.isPlayerScalingEnabled()); ok("scaling.ui.status.saved"); }
            case "saveCaps" -> saveCaps();
            case "toggleEscEnabled" -> { MobScalingOwnerWriter.saveEscalationEnabled(!cfg.isDistanceEscalationEnabled()); ok("scaling.ui.status.saved"); }
            case "saveEsc" -> saveEsc();

            // Zone HUD.
            case "toggleZoneEnabled" -> {
                boolean next = !cfg.isZoneHudEnabled();
                MobScalingOwnerWriter.saveZoneHudEnabled(next);
                ZoneDifficultyHud.setEnabledForAllOnline(next);
                ok("scaling.ui.status.saved");
            }
            case "toggleZoneShowLoc" -> { MobScalingOwnerWriter.saveZoneShowLocationName(!cfg.isZoneShowLocationName()); ok("scaling.ui.status.saved"); }
            case "zonePosChanged" -> { if (data.dropdownValue != null) zonePosInput = data.dropdownValue; return; }
            case "saveZonePos" -> saveZonePos();

            // Inspector HUD.
            case "toggleInspectorEnabled" -> {
                boolean next = !cfg.isInspectorHudEnabled();
                MobScalingOwnerWriter.saveInspectorHudEnabled(next);
                MobInspectorHud.setEnabledForAllOnline(next);
                ok("scaling.ui.status.saved");
            }
            case "toggleInspectorPortrait" -> { MobScalingOwnerWriter.saveInspectorPortraitEnabled(!cfg.isInspectorPortraitEnabled()); ok("scaling.ui.status.saved"); }
            case "inspectorPosChanged" -> { if (data.dropdownValue != null) inspectorPosInput = data.dropdownValue; return; }
            case "saveInspectorPos" -> saveInspectorPos();
            case "saveInspectorRange" -> {
                Double v = parseNonNegative(inspectorRangeInput);
                if (v == null) err("scaling.ui.status.invalid_number");
                else { MobScalingOwnerWriter.saveInspectorRange(v); ok("scaling.ui.status.saved"); }
            }

            // Worlds.
            case "worldPlayerScalingChanged" -> { if (data.dropdownValue != null) worldPlayerScalingInput = data.dropdownValue; return; }
            case "worldEscChanged" -> { if (data.dropdownValue != null) worldEscInput = data.dropdownValue; return; }
            case "editWorld" -> editWorld(data.match);
            case "removeWorld" -> {
                if (data.match != null) { MobScalingOwnerWriter.removeWorldOverride(data.match); ok("scaling.ui.status.removed"); }
            }
            case "saveWorld" -> saveWorld();
            case "clearWorld" -> clearWorld();

            default -> { }
        }
        reopen(ref, store);
    }

    private void selectPreset(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        if (!cfg.swapActivePreset(name)) {
            err("scaling.ui.status.unknown_preset");
            return;
        }
        MobScalingOwnerWriter.saveActivePreset(cfg.getActivePreset());
        // Re-seed the config-derived fields from the freshly folded preset.
        reseedFromConfig(cfg);
        refreshHuds(cfg);
        ok("scaling.ui.status.saved");
    }

    private void saveCaps() {
        Double min = parseNonNegative(minCapInput);
        Double max = parseNonNegative(maxCapInput);
        if (min == null || max == null) {
            err("scaling.ui.status.invalid_number");
            return;
        }
        if (max < min) {
            err("scaling.ui.status.invalid_caps");
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Difficulty.MinCap", min);
        m.put("Difficulty.MaxCap", max);
        MobScalingOwnerWriter.saveLeaves(m);
        ok("scaling.ui.status.saved");
    }

    private void saveEsc() {
        Double start = parseNonNegative(escStartInput);
        Double blocks = parseNonNegative(escBlocksInput);
        Double maxBonus = parseNonNegative(escMaxBonusInput);
        Double rarity = parseNonNegative(escRarityInput);
        if (start == null || blocks == null || maxBonus == null || rarity == null) {
            err("scaling.ui.status.invalid_number");
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Difficulty.DistanceEscalation.StartDistanceBlocks", start);
        m.put("Difficulty.DistanceEscalation.BlocksPerPoint", Math.max(1.0, blocks));
        m.put("Difficulty.DistanceEscalation.MaxBonus", maxBonus);
        m.put("Difficulty.DistanceEscalation.RarityChancePerPoint", rarity);
        MobScalingOwnerWriter.saveLeaves(m);
        ok("scaling.ui.status.saved");
    }

    private void saveZonePos() {
        Integer x = parseInt(zoneOffsetXInput);
        Integer y = parseInt(zoneOffsetYInput);
        String preset = posOr(zonePosInput);
        if (x == null || y == null || !HudPosition.isValidPreset(preset)) {
            err("scaling.ui.status.invalid_number");
            return;
        }
        MobScalingOwnerWriter.saveZoneHudPosition(preset, x, y);
        HudPosition pos = HudPosition.parse(preset, x, y);
        if (pos != null) {
            ZoneDifficultyHud.refreshPositionForAllOnline(pos);
        }
        ok("scaling.ui.status.saved");
    }

    private void saveInspectorPos() {
        Integer x = parseInt(inspectorOffsetXInput);
        Integer y = parseInt(inspectorOffsetYInput);
        String preset = posOr(inspectorPosInput);
        if (x == null || y == null || !HudPosition.isValidPreset(preset)) {
            err("scaling.ui.status.invalid_number");
            return;
        }
        MobScalingOwnerWriter.saveInspectorHudPosition(preset, x, y);
        HudPosition pos = HudPosition.parse(preset, x, y);
        if (pos != null) {
            MobInspectorHud.refreshPositionForAllOnline(pos);
        }
        ok("scaling.ui.status.saved");
    }

    private void editWorld(@Nullable String match) {
        if (match == null) {
            return;
        }
        WorldOverride ov = MobScalingConfig.getInstance().effectiveWorldOverride(match);
        this.worldMatchInput = match;
        this.worldIntensityInput = ov != null && ov.getIntensity() != null ? num(ov.getIntensity()) : "";
        this.worldRarityInput = ov != null && ov.getRaritySpawnChance() != null ? num(ov.getRaritySpawnChance()) : "";
        this.worldPlayerScalingInput = ov != null && ov.getPlayerScalingEnabled() != null
                ? (ov.getPlayerScalingEnabled() ? "on" : "off") : "inherit";
        String minCap = "";
        String maxCap = "";
        String esc = "inherit";
        if (ov != null && ov.getDifficulty() != null) {
            if (ov.getDifficulty().getMinCap() != null) minCap = num(ov.getDifficulty().getMinCap());
            if (ov.getDifficulty().getMaxCap() != null) maxCap = num(ov.getDifficulty().getMaxCap());
            if (ov.getDifficulty().getDistanceEscalation() != null
                    && ov.getDifficulty().getDistanceEscalation().getEnabled() != null) {
                esc = ov.getDifficulty().getDistanceEscalation().getEnabled() ? "on" : "off";
            }
        }
        this.worldMinCapInput = minCap;
        this.worldMaxCapInput = maxCap;
        this.worldEscInput = esc;
    }

    private void saveWorld() {
        String match = worldMatchInput == null ? "" : worldMatchInput.trim();
        if (match.isBlank()) {
            err("scaling.ui.status.match_required");
            return;
        }
        Map<String, Object> leaves = new LinkedHashMap<>();
        Double intensity = parseNonNegativeOrBlank(worldIntensityInput);
        if (bad(worldIntensityInput, intensity)) { err("scaling.ui.status.invalid_number"); return; }
        if (intensity != null) leaves.put("Intensity", intensity);
        Double rarity = parseChanceOrBlank(worldRarityInput);
        if (bad(worldRarityInput, rarity)) { err("scaling.ui.status.invalid_chance"); return; }
        if (rarity != null) leaves.put("RaritySpawnChance", rarity);
        Double minCap = parseNonNegativeOrBlank(worldMinCapInput);
        if (bad(worldMinCapInput, minCap)) { err("scaling.ui.status.invalid_number"); return; }
        if (minCap != null) leaves.put("Difficulty.MinCap", minCap);
        Double maxCap = parseNonNegativeOrBlank(worldMaxCapInput);
        if (bad(worldMaxCapInput, maxCap)) { err("scaling.ui.status.invalid_number"); return; }
        if (maxCap != null) leaves.put("Difficulty.MaxCap", maxCap);
        if ("on".equals(worldPlayerScalingInput)) leaves.put("PlayerScalingEnabled", Boolean.TRUE);
        else if ("off".equals(worldPlayerScalingInput)) leaves.put("PlayerScalingEnabled", Boolean.FALSE);
        if ("on".equals(worldEscInput)) leaves.put("Difficulty.DistanceEscalation.Enabled", Boolean.TRUE);
        else if ("off".equals(worldEscInput)) leaves.put("Difficulty.DistanceEscalation.Enabled", Boolean.FALSE);

        MobScalingOwnerWriter.upsertWorldOverride(match, leaves);
        ok("scaling.ui.status.saved");
    }

    private void clearWorld() {
        this.worldMatchInput = "";
        this.worldIntensityInput = "";
        this.worldRarityInput = "";
        this.worldMinCapInput = "";
        this.worldMaxCapInput = "";
        this.worldPlayerScalingInput = "inherit";
        this.worldEscInput = "inherit";
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private void cacheInputs(@Nonnull EventData d) {
        if (d.intensityInput != null) intensityInput = d.intensityInput;
        if (d.rarityInput != null) rarityInput = d.rarityInput;
        if (d.minCapInput != null) minCapInput = d.minCapInput;
        if (d.maxCapInput != null) maxCapInput = d.maxCapInput;
        if (d.escStartInput != null) escStartInput = d.escStartInput;
        if (d.escBlocksInput != null) escBlocksInput = d.escBlocksInput;
        if (d.escMaxBonusInput != null) escMaxBonusInput = d.escMaxBonusInput;
        if (d.escRarityInput != null) escRarityInput = d.escRarityInput;
        if (d.zoneOffsetXInput != null) zoneOffsetXInput = d.zoneOffsetXInput;
        if (d.zoneOffsetYInput != null) zoneOffsetYInput = d.zoneOffsetYInput;
        if (d.inspectorOffsetXInput != null) inspectorOffsetXInput = d.inspectorOffsetXInput;
        if (d.inspectorOffsetYInput != null) inspectorOffsetYInput = d.inspectorOffsetYInput;
        if (d.inspectorRangeInput != null) inspectorRangeInput = d.inspectorRangeInput;
        if (d.worldMatchInput != null) worldMatchInput = d.worldMatchInput;
        if (d.worldIntensityInput != null) worldIntensityInput = d.worldIntensityInput;
        if (d.worldRarityInput != null) worldRarityInput = d.worldRarityInput;
        if (d.worldMinCapInput != null) worldMinCapInput = d.worldMinCapInput;
        if (d.worldMaxCapInput != null) worldMaxCapInput = d.worldMaxCapInput;
    }

    private void reseedFromConfig(@Nonnull MobScalingConfig cfg) {
        this.intensityInput = num(cfg.getIntensity());
        this.rarityInput = num(cfg.getRaritySpawnChance());
        this.minCapInput = num(cfg.getDifficultyMinCap());
        this.maxCapInput = num(cfg.getDifficultyMaxCap());
        this.escStartInput = num(cfg.getEscalationStartDistanceBlocks());
        this.escBlocksInput = num(cfg.getEscalationBlocksPerPoint());
        this.escMaxBonusInput = num(cfg.getEscalationMaxBonus());
        this.escRarityInput = num(cfg.getEscalationRarityChancePerPoint());
    }

    private void refreshHuds(@Nonnull MobScalingConfig cfg) {
        HudPosition zone = HudPosition.parse(cfg.getZoneHudPosition().toUpperCase(Locale.ROOT),
                cfg.getZoneHudOffsetX(), cfg.getZoneHudOffsetY());
        if (zone != null) {
            ZoneDifficultyHud.refreshPositionForAllOnline(zone);
        }
        HudPosition insp = HudPosition.parse(cfg.getInspectorHudPosition().toUpperCase(Locale.ROOT),
                cfg.getInspectorHudOffsetX(), cfg.getInspectorHudOffsetY());
        if (insp != null) {
            MobInspectorHud.refreshPositionForAllOnline(insp);
        }
    }

    private void reopen(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().openCustomPage(ref, store, this);
        }
    }

    /** A label + field + inline Save button row (Save persists the field on click). */
    private void fieldSave(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull String labelSel, @Nonnull String labelKey, @Nonnull String fieldSel,
            @Nullable String value, @Nonnull String codecKey, @Nonnull String saveSel, @Nonnull String action) {
        field(cmd, events, labelSel, labelKey, fieldSel, value, codecKey);
        ZigRichButton.text(cmd, saveSel, tr("scaling.ui.button.save"));
        SettingsUiUtil.bindButton(events, saveSel, action);
    }

    /** A label + text field row (no inline Save; a grouped Save button persists it). */
    private void field(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull String labelSel, @Nonnull String labelKey, @Nonnull String fieldSel,
            @Nullable String value, @Nonnull String codecKey) {
        rowLabel(cmd, labelSel, labelKey);
        cmd.set(fieldSel + ".Value", value == null ? "" : value);
        SettingsUiUtil.bindTextField(events, fieldSel, codecKey);
    }

    private void saveButton(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull String sel, @Nonnull String labelKey, @Nonnull String action) {
        ZigRichButton.text(cmd, sel, tr(labelKey));
        SettingsUiUtil.bindButton(events, sel, action);
    }

    private void toggle(@Nonnull UICommandBuilder cmd, @Nonnull String sel, boolean on) {
        SettingsUiUtil.setToggle(cmd, sel, on, tr("scaling.ui.toggle.on"), tr("scaling.ui.toggle.off"));
    }

    private void rowLabel(@Nonnull UICommandBuilder cmd, @Nonnull String sel, @Nonnull String key) {
        cmd.set(sel + ".TextSpans", tr(key));
    }

    private void label(@Nonnull UICommandBuilder cmd, @Nonnull String sel, @Nonnull String key) {
        ZigRichButton.text(cmd, sel, tr(key));
    }

    /** Tri-state dropdown option labels (technical: a per-world nullable leaf = Inherit / On / Off). */
    @Nonnull
    private static String[] triLabels() {
        return new String[]{"Inherit", "On", "Off"};
    }

    @Nonnull
    private String worldSummary(@Nonnull WorldOverride ov) {
        StringBuilder sb = new StringBuilder();
        if (ov.getIntensity() != null) append(sb, "int " + num(ov.getIntensity()));
        if (ov.getRaritySpawnChance() != null) append(sb, "rarity " + num(ov.getRaritySpawnChance()));
        if (ov.getPlayerScalingEnabled() != null) {
            append(sb, "scaling " + (ov.getPlayerScalingEnabled() ? "on" : "off"));
        }
        if (ov.getDifficulty() != null) {
            if (ov.getDifficulty().getMinCap() != null || ov.getDifficulty().getMaxCap() != null) {
                append(sb, "caps " + num(nz(ov.getDifficulty().getMinCap())) + "-" + num(nz(ov.getDifficulty().getMaxCap())));
            }
            if (ov.getDifficulty().getDistanceEscalation() != null
                    && ov.getDifficulty().getDistanceEscalation().getEnabled() != null) {
                append(sb, "esc " + (ov.getDifficulty().getDistanceEscalation().getEnabled() ? "on" : "off"));
            }
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static void append(@Nonnull StringBuilder sb, @Nonnull String s) {
        if (sb.length() > 0) sb.append("  |  ");
        sb.append(s);
    }

    private static double nz(@Nullable Double v) {
        return v == null ? 0.0 : v;
    }

    private void ok(@Nonnull String key) {
        this.statusMessage = tr(key);
        this.statusIsError = false;
    }

    private void err(@Nonnull String key) {
        this.statusMessage = tr(key);
        this.statusIsError = true;
    }

    @Nonnull
    private static Message tr(@Nonnull String key) {
        return Message.translation(key);
    }

    /** Uppercased preset name, or empty for a blank input. */
    @Nonnull
    private static String posOr(@Nullable String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    @Nonnull
    private static String num(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    @Nullable
    private static Double parseNonNegative(@Nullable String s) {
        if (s == null) return null;
        try {
            double v = Double.parseDouble(s.trim());
            if (Double.isNaN(v) || v < 0.0) return null;
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static Double parseChance(@Nullable String s) {
        Double v = parseNonNegative(s);
        if (v == null || v > 1.0) return null;
        return v;
    }

    /** Blank -> null (inherit); a present-but-invalid value stays null and {@link #bad} flags it. */
    @Nullable
    private static Double parseNonNegativeOrBlank(@Nullable String s) {
        if (s == null || s.isBlank()) return null;
        return parseNonNegative(s);
    }

    @Nullable
    private static Double parseChanceOrBlank(@Nullable String s) {
        if (s == null || s.isBlank()) return null;
        return parseChance(s);
    }

    /** True when the input was non-blank but failed to parse (a real error, not an intentional blank). */
    private static boolean bad(@Nullable String raw, @Nullable Double parsed) {
        return raw != null && !raw.isBlank() && parsed == null;
    }

    @Nullable
    private static Integer parseInt(@Nullable String s) {
        if (s == null) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Event data
    // ---------------------------------------------------------------------

    public static final class EventData {
        public String action;
        public String tab;
        public String match;
        public String dropdownValue;
        public String intensityInput;
        public String rarityInput;
        public String minCapInput;
        public String maxCapInput;
        public String escStartInput;
        public String escBlocksInput;
        public String escMaxBonusInput;
        public String escRarityInput;
        public String zoneOffsetXInput;
        public String zoneOffsetYInput;
        public String inspectorOffsetXInput;
        public String inspectorOffsetYInput;
        public String inspectorRangeInput;
        public String worldMatchInput;
        public String worldIntensityInput;
        public String worldRarityInput;
        public String worldMinCapInput;
        public String worldMaxCapInput;

        public static final BuilderCodec<EventData> CODEC = BuilderCodec.builder(EventData.class, EventData::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, v, i) -> d.action = v, (d, i) -> d.action).add()
                .append(new KeyedCodec<>("Tab", Codec.STRING), (d, v, i) -> d.tab = v, (d, i) -> d.tab).add()
                .append(new KeyedCodec<>("Match", Codec.STRING), (d, v, i) -> d.match = v, (d, i) -> d.match).add()
                .append(new KeyedCodec<>("@DropdownValue", Codec.STRING), (d, v, i) -> d.dropdownValue = v, (d, i) -> d.dropdownValue).add()
                .append(new KeyedCodec<>("@IntensityInput", Codec.STRING), (d, v, i) -> d.intensityInput = v, (d, i) -> d.intensityInput).add()
                .append(new KeyedCodec<>("@RarityInput", Codec.STRING), (d, v, i) -> d.rarityInput = v, (d, i) -> d.rarityInput).add()
                .append(new KeyedCodec<>("@MinCapInput", Codec.STRING), (d, v, i) -> d.minCapInput = v, (d, i) -> d.minCapInput).add()
                .append(new KeyedCodec<>("@MaxCapInput", Codec.STRING), (d, v, i) -> d.maxCapInput = v, (d, i) -> d.maxCapInput).add()
                .append(new KeyedCodec<>("@EscStartInput", Codec.STRING), (d, v, i) -> d.escStartInput = v, (d, i) -> d.escStartInput).add()
                .append(new KeyedCodec<>("@EscBlocksInput", Codec.STRING), (d, v, i) -> d.escBlocksInput = v, (d, i) -> d.escBlocksInput).add()
                .append(new KeyedCodec<>("@EscMaxBonusInput", Codec.STRING), (d, v, i) -> d.escMaxBonusInput = v, (d, i) -> d.escMaxBonusInput).add()
                .append(new KeyedCodec<>("@EscRarityInput", Codec.STRING), (d, v, i) -> d.escRarityInput = v, (d, i) -> d.escRarityInput).add()
                .append(new KeyedCodec<>("@ZoneOffsetXInput", Codec.STRING), (d, v, i) -> d.zoneOffsetXInput = v, (d, i) -> d.zoneOffsetXInput).add()
                .append(new KeyedCodec<>("@ZoneOffsetYInput", Codec.STRING), (d, v, i) -> d.zoneOffsetYInput = v, (d, i) -> d.zoneOffsetYInput).add()
                .append(new KeyedCodec<>("@InspectorOffsetXInput", Codec.STRING), (d, v, i) -> d.inspectorOffsetXInput = v, (d, i) -> d.inspectorOffsetXInput).add()
                .append(new KeyedCodec<>("@InspectorOffsetYInput", Codec.STRING), (d, v, i) -> d.inspectorOffsetYInput = v, (d, i) -> d.inspectorOffsetYInput).add()
                .append(new KeyedCodec<>("@InspectorRangeInput", Codec.STRING), (d, v, i) -> d.inspectorRangeInput = v, (d, i) -> d.inspectorRangeInput).add()
                .append(new KeyedCodec<>("@WorldMatchInput", Codec.STRING), (d, v, i) -> d.worldMatchInput = v, (d, i) -> d.worldMatchInput).add()
                .append(new KeyedCodec<>("@WorldIntensityInput", Codec.STRING), (d, v, i) -> d.worldIntensityInput = v, (d, i) -> d.worldIntensityInput).add()
                .append(new KeyedCodec<>("@WorldRarityInput", Codec.STRING), (d, v, i) -> d.worldRarityInput = v, (d, i) -> d.worldRarityInput).add()
                .append(new KeyedCodec<>("@WorldMinCapInput", Codec.STRING), (d, v, i) -> d.worldMinCapInput = v, (d, i) -> d.worldMinCapInput).add()
                .append(new KeyedCodec<>("@WorldMaxCapInput", Codec.STRING), (d, v, i) -> d.worldMaxCapInput = v, (d, i) -> d.worldMaxCapInput).add()
                .build();
    }
}

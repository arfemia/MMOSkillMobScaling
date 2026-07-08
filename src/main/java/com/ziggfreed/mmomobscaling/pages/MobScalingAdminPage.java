package com.ziggfreed.mmomobscaling.pages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.form.FieldSpec;
import com.ziggfreed.common.ui.form.FormResult;
import com.ziggfreed.common.ui.form.SettingsForm;
import com.ziggfreed.common.ui.hud.HudPosition;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Difficulty;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.DistanceEscalation;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Hud;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.InspectorHud;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.OpenWorld;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.StatCurve;
import com.ziggfreed.mmomobscaling.asset.WorldSettings;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;
import com.ziggfreed.mmomobscaling.config.MobScalingOwnerWriter;
import com.ziggfreed.mmomobscaling.config.WorldSettingsConfig;
import com.ziggfreed.mmomobscaling.hud.MobInspectorHud;
import com.ziggfreed.mmomobscaling.hud.ZoneDifficultyHud;

/**
 * The in-game admin config page for MMO Mob Scaling ({@code /mobscaling ui}). SPEC-DRIVEN: four
 * {@link SettingsForm} instances (Global, Zone HUD, Inspector HUD, and a per-world
 * {@code Worlds/*.json} editor) each render from an ordered {@link FieldSpec} list
 * ({@link #buildGlobalSpecs()} / {@link #buildZoneSpecs()} / {@link #buildInspectorSpecs()} /
 * {@link #buildWorldSpecs()}) through the shared ziggfreed-common {@code ui/form} engine - a new knob
 * later is one spec line here plus one lang key, never a new {@code .ui} row or a new codec field on
 * {@link EventData}. The Worlds tab is a TWO-PANEL layout (a scrolling world list on the left, the
 * add/edit editor on the right); every hint/note WRAPS ({@code ZigFormNoteRow}, multi-line).
 *
 * <p><b>Never reopens itself.</b> Every event answers with a PARTIAL {@link #sendUpdate}, so the scroll
 * position never resets. A world-list change (save/remove) clears + re-appends + rebinds the list rows
 * in the SAME update - the official {@code ChangeModelPage} pattern (jar {@code Model} plugin,
 * {@code buildModelList}). {@code EventData} carries exactly five keys: {@code Action}/{@code Tab}/
 * {@code WorldId}/{@code Field}/{@code @Value}; a {@code "field"} action just caches the raw value
 * (no packet), a {@code "press"} flips + persists a toggle, everything else builds a small
 * {@link UICommandBuilder} and finishes with a status line + {@link #sendUpdate}.
 *
 * <p>Global/HUD edits persist through the ONE write-back path ({@link MobScalingOwnerWriter} -> the
 * owner file -> {@code refreshFromDisk}); world edits write their own file
 * ({@link MobScalingOwnerWriter#saveWorldFile}/{@code deleteWorldFile} -> the worlds refold). The world
 * editor seeds from the AUTHORED (pre-{@code Parent}-merge) body
 * ({@link WorldSettingsConfig#authoredById}), NOT the folded-effective view: with ~40 exposed knobs,
 * seeding the Parent-merged view and saving back would materialize the whole parent chain into the
 * child file and silently break inheritance - authored-seeding keeps blank field = inherit faithful.
 * HUD / preset edits live-apply to all online players. All labelled buttons are RICH
 * ({@link ZigRichButton} / {@link SettingsUiUtil#setToggle}); all display text is a client-resolved
 * {@link Message} on {@code .TextSpans}.
 *
 * <p><b>Access:</b> gated by the {@code /mobscaling ui} command's {@code hytale:Admin} permission group
 * (the only way to open this page).
 */
public final class MobScalingAdminPage extends InteractiveCustomUIPage<MobScalingAdminPage.EventData> {

    private static final String UI = "Pages/MmoscalingAdminPage.ui";
    private static final String ROW = "Pages/ZigListRow.ui";

    private static final String STATUS_SEL = "#MmoscalingStatus";
    private static final String PRESET_DROPDOWN_SEL = "#MmoscalingPresetDropdown";
    private static final String GLOBAL_FORM_SEL = "#MmoscalingGlobalForm";
    private static final String ZONE_FORM_SEL = "#MmoscalingZoneForm";
    private static final String INSPECTOR_FORM_SEL = "#MmoscalingInspectorForm";
    private static final String WORLD_FORM_SEL = "#MmoscalingWorldForm";
    private static final String WORLD_LIST = "#MmoscalingWorldList";
    private static final String WORLD_EMPTY_SEL = "#MmoscalingWorldEmpty";

    // World-form field ids referenced outside the spec table (id derivation, self-Parent check).
    private static final String F_WORLD_ID = "worldId";
    private static final String F_WORLD_MATCH = "worldMatch";
    private static final String F_WORLD_PARENT = "worldParent";
    // The worldId spec's leaf path is a SENTINEL, not a real codec key: popped from the collected
    // leaves before every save (the world file has no "$Id" field - the filename IS the id).
    private static final String WORLD_ID_LEAF = "$Id";

    // Leaf paths shared between two spec tables (global/world) or a spec + its instant-toggle saver,
    // named once so the two call sites can never drift apart.
    private static final String LEAF_MIN_CAP = "Difficulty.MinCap";
    private static final String LEAF_MAX_CAP = "Difficulty.MaxCap";
    private static final String LEAF_ONLY_RAISE = "OpenWorld.OnlyRaiseDifficulty";
    private static final String LEAF_PARTY_JOIN = "OpenWorld.AllowDifficultyIncreaseOnPartyJoin";
    private static final String LEAF_COMPOSITION = "OpenWorld.CompositionEnabled";

    // The 9 named corner presets (technical ids, shown literally in the position dropdowns).
    private static final String[] POSITIONS = {
            "TOP_LEFT", "TOP_CENTER", "TOP_RIGHT",
            "CENTER_LEFT", "CENTER", "CENTER_RIGHT",
            "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT"
    };
    private static final String[] PRESET_MODES = {"SIMPLE", "TUNED", "ADVANCED"};
    private static final String[] AGGREGATION_MODES = {"SOLO", "AVERAGE", "PEAK", "WEIGHTED", "DISABLED"};
    private static final String[] AGGREGATION_MODES_INHERIT =
            {"inherit", "SOLO", "AVERAGE", "PEAK", "WEIGHTED", "DISABLED"};

    private static final List<FieldSpec> GLOBAL_SPECS = buildGlobalSpecs();
    private static final List<FieldSpec> ZONE_SPECS = buildZoneSpecs();
    private static final List<FieldSpec> INSPECTOR_SPECS = buildInspectorSpecs();
    private static final List<FieldSpec> WORLD_SPECS = buildWorldSpecs();

    private final SettingsForm globalForm;
    private final SettingsForm zoneForm;
    private final SettingsForm inspectorForm;
    private final SettingsForm worldForm;

    // Instant-persist toggle registry (global/zone/inspector "press" actions): id -> current-state
    // read, persist call, optional live HUD apply, and the status key to show. Built once in the
    // constructor (bound to the singleton MobScalingConfig, which never changes identity).
    private final Map<String, ToggleDef> toggleDefs;

    private String activeTab = "global";

    @Nullable private Message statusMessage;
    private boolean statusIsError;

    public MobScalingAdminPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, EventData.CODEC);
        Message toggleOn = tr("scaling.ui.toggle.on");
        Message toggleOff = tr("scaling.ui.toggle.off");
        this.globalForm = new SettingsForm(GLOBAL_SPECS, toggleOn, toggleOff);
        this.zoneForm = new SettingsForm(ZONE_SPECS, toggleOn, toggleOff);
        this.inspectorForm = new SettingsForm(INSPECTOR_SPECS, toggleOn, toggleOff);
        this.worldForm = new SettingsForm(WORLD_SPECS, toggleOn, toggleOff);

        MobScalingConfig cfg = MobScalingConfig.getInstance();
        reseedGlobalFromConfig(cfg);
        reseedZoneFromConfig(cfg);
        reseedInspectorFromConfig(cfg);
        seedWorldForm("", null, null);
        this.toggleDefs = buildToggleDefs(cfg);
    }

    // ---------------------------------------------------------------------
    // Build (full page, once per open)
    // ---------------------------------------------------------------------

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        cmd.append(UI);
        MobScalingConfig cfg = MobScalingConfig.getInstance();

        cmd.set("#MmoscalingTitle.TextSpans", tr("scaling.ui.title"));
        SettingsUiUtil.bindButton(events, "#CloseButton", "close");

        buildTabs(cmd, events);
        setSectionVisibility(cmd);

        buildPresetRow(cmd, events, cfg);
        globalForm.buildRows(cmd, events, GLOBAL_FORM_SEL, MobScalingAdminPage::tr);
        actionButton(cmd, events, "#MmoscalingGlobalSave", "scaling.ui.button.save_tab", "saveGlobal");

        zoneForm.buildRows(cmd, events, ZONE_FORM_SEL, MobScalingAdminPage::tr);
        actionButton(cmd, events, "#MmoscalingZoneSave", "scaling.ui.button.save_tab", "saveZone");

        inspectorForm.buildRows(cmd, events, INSPECTOR_FORM_SEL, MobScalingAdminPage::tr);
        actionButton(cmd, events, "#MmoscalingInspectorSave", "scaling.ui.button.save_tab", "saveInspector");

        buildWorldList(cmd, events);
        rowLabel(cmd, "#MmoscalingWorldEditorHeader", "scaling.ui.world.editor_header");
        worldForm.buildRows(cmd, events, WORLD_FORM_SEL, MobScalingAdminPage::tr);
        actionButton(cmd, events, "#MmoscalingWorldNew", "scaling.ui.world.new", "clearWorld");
        actionButton(cmd, events, "#MmoscalingWorldSave", "scaling.ui.button.save_world", "saveWorld");
        actionButton(cmd, events, "#MmoscalingWorldClear", "scaling.ui.button.clear", "clearWorld");

        SettingsUiUtil.setStatus(cmd, STATUS_SEL, statusMessage, statusIsError);
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

    private void setSectionVisibility(@Nonnull UICommandBuilder cmd) {
        cmd.set("#MmoscalingSectionGlobal.Visible", activeTab.equals("global"));
        cmd.set("#MmoscalingSectionZoneHud.Visible", activeTab.equals("zonehud"));
        cmd.set("#MmoscalingSectionInspector.Visible", activeTab.equals("inspector"));
        cmd.set("#MmoscalingSectionWorlds.Visible", activeTab.equals("worlds"));
    }

    /** The preset dropdown stays hand-built (its entries are dynamic, unlike every fixed FieldSpec). */
    private void buildPresetRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull MobScalingConfig cfg) {
        rowLabel(cmd, "#MmoscalingPresetLabel", "scaling.ui.global.preset");
        List<String> presets = cfg.availablePresetNames();
        if (presets.isEmpty()) {
            presets = List.of(cfg.getActivePreset());
        }
        String[] presetArr = presets.toArray(new String[0]);
        SettingsUiUtil.populate(cmd, PRESET_DROPDOWN_SEL, presetArr, presetArr, cfg.getActivePreset());
        // Hand-bound (not SettingsUiUtil.bindDropdown, which pushes "@DropdownValue" - this page's
        // EventData only carries the SettingsForm-shaped "@Value" key).
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, PRESET_DROPDOWN_SEL,
                com.hypixel.hytale.server.core.ui.builder.EventData.of("Action", "selectPreset")
                        .append("@Value", PRESET_DROPDOWN_SEL + ".Value"),
                false);
    }

    /** Clear + re-append + rebind every world row (the {@code ChangeModelPage.buildModelList} pattern). */
    private void buildWorldList(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        cmd.clear(WORLD_LIST);
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        Map<String, WorldSettings> view = worlds.foldedView();
        Set<String> owned = worlds.ownerAuthoredIds();
        cmd.set(WORLD_EMPTY_SEL + ".Visible", view.isEmpty());
        if (view.isEmpty()) {
            cmd.set(WORLD_EMPTY_SEL + ".TextSpans", tr("scaling.ui.world.empty"));
        }
        int i = 0;
        for (Map.Entry<String, WorldSettings> e : view.entrySet()) {
            String id = e.getKey();
            WorldSettings ws = e.getValue();
            String rowSel = WORLD_LIST + "[" + i++ + "]";
            cmd.append(WORLD_LIST, ROW);
            cmd.set(rowSel + " #Title.Text", id);
            cmd.set(rowSel + " #Sub.Text", worldSummary(worlds, id, ws));
            boolean isOwner = owned.contains(id);
            cmd.set(rowSel + " #Badge.Visible", true);
            cmd.set(rowSel + " #Badge.TextSpans", tr(isOwner ? "scaling.ui.world.badge_override"
                    : "scaling.ui.world.badge_default"));
            ZigRichButton.text(cmd, rowSel + " #EditBtn", tr("scaling.ui.button.edit"));
            SettingsUiUtil.bindButton(events, rowSel + " #EditBtn", "editWorld", "WorldId", id);
            // Only an owner-dir FILE is removable (deleting it re-exposes a same-id jar/pack file).
            cmd.set(rowSel + " #RemoveBtn.Visible", isOwner);
            if (isOwner) {
                ZigRichButton.text(cmd, rowSel + " #RemoveBtn", tr("scaling.ui.button.remove"));
                SettingsUiUtil.bindButton(events, rowSel + " #RemoveBtn", "removeWorld", "WorldId", id);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Events (never reopens; every branch answers with a partial sendUpdate)
    // ---------------------------------------------------------------------

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull EventData data) {
        String action = data.action == null ? "" : data.action;
        switch (action) {
            case "close" -> close();
            case "tab" -> handleTab(data.tab);
            case "field" -> handleField(data.field, data.value);
            case "press" -> handlePress(data.field);
            case "selectPreset" -> handleSelectPreset(data.value);
            case "saveGlobal" -> handleSaveGlobal();
            case "saveZone" -> handleSaveZone();
            case "saveInspector" -> handleSaveInspector();
            case "editWorld" -> handleEditWorld(data.worldId);
            case "removeWorld" -> handleRemoveWorld(data.worldId);
            case "saveWorld" -> handleSaveWorld();
            case "clearWorld" -> handleClearWorld();
            default -> { }
        }
    }

    /** A value-changed event: cache-only, no packet (the control already reflects the typed value). */
    private void handleField(@Nullable String fieldId, @Nullable String value) {
        if (fieldId == null || value == null) {
            return;
        }
        if (!globalForm.cache(fieldId, value) && !zoneForm.cache(fieldId, value)
                && !inspectorForm.cache(fieldId, value)) {
            worldForm.cache(fieldId, value);
        }
    }

    private void handleTab(@Nullable String tab) {
        if (tab == null || tab.isBlank()) {
            return;
        }
        this.activeTab = tab;
        UICommandBuilder cmd = new UICommandBuilder();
        setSectionVisibility(cmd);
        SettingsUiUtil.setTabActive(cmd, "#MmoscalingTabGlobal", activeTab.equals("global"));
        SettingsUiUtil.setTabActive(cmd, "#MmoscalingTabZoneHud", activeTab.equals("zonehud"));
        SettingsUiUtil.setTabActive(cmd, "#MmoscalingTabInspector", activeTab.equals("inspector"));
        SettingsUiUtil.setTabActive(cmd, "#MmoscalingTabWorlds", activeTab.equals("worlds"));
        sendUpdate(cmd, null, false);
    }

    /** An instant-persist toggle click, driven by {@link #toggleDefs}. */
    private void handlePress(@Nullable String fieldId) {
        if (fieldId == null) {
            return;
        }
        ToggleDef def = toggleDefs.get(fieldId);
        if (def == null) {
            return;
        }
        boolean next = !def.current().getAsBoolean();
        def.save().accept(next);
        if (def.liveApply() != null) {
            def.liveApply().accept(next);
        }
        def.form().seedValue(fieldId, next ? "on" : "off");
        UICommandBuilder cmd = new UICommandBuilder();
        def.form().applyValue(cmd, def.containerSel(), fieldId);
        ok(def.statusKey());
        finish(cmd);
    }

    private void handleSelectPreset(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        UICommandBuilder cmd = new UICommandBuilder();
        if (!cfg.swapActivePreset(name)) {
            err("scaling.ui.status.unknown_preset");
            finish(cmd);
            return;
        }
        MobScalingOwnerWriter.saveActivePreset(cfg.getActivePreset());
        reseedGlobalFromConfig(cfg);
        reseedZoneFromConfig(cfg);
        reseedInspectorFromConfig(cfg);
        globalForm.applyValues(cmd, GLOBAL_FORM_SEL);
        zoneForm.applyValues(cmd, ZONE_FORM_SEL);
        inspectorForm.applyValues(cmd, INSPECTOR_FORM_SEL);
        refreshHuds(cfg);
        ok("scaling.ui.status.saved");
        finish(cmd);
    }

    private void handleSaveGlobal() {
        UICommandBuilder cmd = new UICommandBuilder();
        FormResult result = globalForm.collectLeaves(false);
        if (!result.ok()) {
            emitInvalidField(result);
            finish(cmd);
            return;
        }
        Map<String, Object> leaves = result.leaves();
        if (leaves.get(LEAF_MIN_CAP) instanceof Double min && leaves.get(LEAF_MAX_CAP) instanceof Double max
                && max < min) {
            err("scaling.ui.status.invalid_caps");
            finish(cmd);
            return;
        }
        MobScalingOwnerWriter.saveLeaves(leaves);
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        reseedGlobalFromConfig(cfg); // reflect fold-clamped values (e.g. MaxCap >= MinCap) back
        globalForm.applyValues(cmd, GLOBAL_FORM_SEL);
        ok("scaling.ui.status.saved");
        finish(cmd);
    }

    private void handleSaveZone() {
        UICommandBuilder cmd = new UICommandBuilder();
        FormResult result = zoneForm.collectLeaves(false);
        if (!result.ok()) {
            emitInvalidField(result);
            finish(cmd);
            return;
        }
        MobScalingOwnerWriter.saveLeaves(result.leaves());
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        HudPosition pos = HudPosition.parse(cfg.getZoneHudPosition(), cfg.getZoneHudOffsetX(), cfg.getZoneHudOffsetY());
        if (pos != null) {
            ZoneDifficultyHud.refreshPositionForAllOnline(pos);
        }
        reseedZoneFromConfig(cfg);
        zoneForm.applyValues(cmd, ZONE_FORM_SEL);
        ok("scaling.ui.status.saved");
        finish(cmd);
    }

    private void handleSaveInspector() {
        UICommandBuilder cmd = new UICommandBuilder();
        FormResult result = inspectorForm.collectLeaves(false);
        if (!result.ok()) {
            emitInvalidField(result);
            finish(cmd);
            return;
        }
        MobScalingOwnerWriter.saveLeaves(result.leaves());
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        HudPosition pos = HudPosition.parse(cfg.getInspectorHudPosition(), cfg.getInspectorHudOffsetX(),
                cfg.getInspectorHudOffsetY());
        if (pos != null) {
            MobInspectorHud.refreshPositionForAllOnline(pos);
        }
        reseedInspectorFromConfig(cfg);
        inspectorForm.applyValues(cmd, INSPECTOR_FORM_SEL);
        ok("scaling.ui.status.saved");
        finish(cmd);
    }

    /** Seed the editor from the AUTHORED body (see the class javadoc) + the id itself; no status change. */
    private void handleEditWorld(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        seedWorldForm(id, worlds.authoredById(id), worlds.parentOf(id));
        UICommandBuilder cmd = new UICommandBuilder();
        worldForm.applyValues(cmd, WORLD_FORM_SEL);
        sendUpdate(cmd, null, false);
    }

    private void handleRemoveWorld(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        MobScalingOwnerWriter.deleteWorldFile(id);
        seedWorldForm("", null, null); // never leave the editor pointing at a deleted file
        UICommandBuilder cmd = new UICommandBuilder();
        worldForm.applyValues(cmd, WORLD_FORM_SEL);
        UIEventBuilder events = new UIEventBuilder();
        buildWorldList(cmd, events);
        ok("scaling.ui.status.world_deleted");
        finish(cmd, events);
    }

    /**
     * Id derives from the World id field, falling back to Match (both sanitized); at least one of the
     * two must be non-blank (a blank Match with an id is legal - it authors a pool-only BASE file). A
     * self-{@code Parent} is rejected. On success the id field reflects the final id and the world list
     * is rebuilt in the same update.
     */
    private void handleSaveWorld() {
        UICommandBuilder cmd = new UICommandBuilder();
        String rawId = worldForm.value(F_WORLD_ID).trim();
        String rawMatch = worldForm.value(F_WORLD_MATCH).trim();
        if (rawId.isEmpty() && rawMatch.isEmpty()) {
            err("scaling.ui.status.id_or_match_required");
            finish(cmd);
            return;
        }
        String id = WorldSettingsConfig.sanitizeFileId(rawId.isEmpty() ? rawMatch : rawId);
        String rawParent = worldForm.value(F_WORLD_PARENT).trim();
        // Compare through the SAME sanitizer both sides go through for the filename, not the raw
        // typed text - otherwise worldId "a b" + Parent "a b" (both sanitize to "a_b") slips past.
        if (!rawParent.isEmpty() && WorldSettingsConfig.sanitizeFileId(rawParent).equalsIgnoreCase(id)) {
            err("scaling.ui.status.invalid_parent");
            finish(cmd);
            return;
        }
        FormResult result = worldForm.collectLeaves(true);
        if (!result.ok()) {
            emitInvalidField(result);
            finish(cmd);
            return;
        }
        // blankIsInherit=true already puts a null "Match" leaf for a blank field (TEXT collectLeaves
        // rule), which is exactly "no Match" = a pool-only base - nothing extra to enforce here.
        Map<String, Object> leaves = new LinkedHashMap<>(result.leaves());
        leaves.remove(WORLD_ID_LEAF); // the sentinel: never a real codec key on the world file
        if (MobScalingOwnerWriter.saveWorldFile(id, leaves)) {
            worldForm.seedValue(F_WORLD_ID, id);
            worldForm.applyValue(cmd, WORLD_FORM_SEL, F_WORLD_ID);
            UIEventBuilder events = new UIEventBuilder();
            buildWorldList(cmd, events);
            ok("scaling.ui.status.saved");
            finish(cmd, events);
        } else {
            err("scaling.ui.status.save_failed");
            finish(cmd);
        }
    }

    private void handleClearWorld() {
        seedWorldForm("", null, null);
        UICommandBuilder cmd = new UICommandBuilder();
        worldForm.applyValues(cmd, WORLD_FORM_SEL);
        clearStatus();
        finish(cmd);
    }

    // ---------------------------------------------------------------------
    // Seeding (config / world settings -> the form value caches)
    // ---------------------------------------------------------------------

    private void reseedGlobalFromConfig(@Nonnull MobScalingConfig cfg) {
        Map<String, String> seed = new LinkedHashMap<>();
        seed.put("enabled", onOff(cfg.isEnabled()));
        seed.put("presetMode", blankToFirst(cfg.getPresetMode(), PRESET_MODES));
        seed.put("intensity", num(cfg.getIntensity()));
        seed.put("rarity", num(cfg.getRaritySpawnChance()));
        seed.put("playerScaling", onOff(cfg.isPlayerScalingEnabled()));
        seed.put("aggregation", blankToFirst(cfg.getOpenWorldAggregationMode(), AGGREGATION_MODES));
        seed.put("regionSize", String.valueOf(cfg.getRegionSizeChunks()));
        seed.put("bandWidth", num(cfg.getGroupDeltaBandWidth()));
        seed.put("onlyRaise", onOff(cfg.isOnlyRaiseDifficulty()));
        seed.put("partyJoin", onOff(cfg.isAllowDifficultyIncreaseOnPartyJoin()));
        seed.put("lateArrival", num(cfg.getLateArrivalBumpFactor()));
        seed.put("composition", onOff(cfg.isCompositionEnabled()));
        seed.put("floor", num(cfg.getDifficultyFloor()));
        seed.put("minCap", num(cfg.getDifficultyMinCap()));
        seed.put("maxCap", num(cfg.getDifficultyMaxCap()));
        seed.put("escEnabled", onOff(cfg.isDistanceEscalationEnabled()));
        seed.put("escStart", num(cfg.getEscalationStartDistanceBlocks()));
        seed.put("escBlocks", num(cfg.getEscalationBlocksPerPoint()));
        seed.put("escMaxBonus", num(cfg.getEscalationMaxBonus()));
        seed.put("escRarity", num(cfg.getEscalationRarityChancePerPoint()));
        seed.put("hpPerPoint", num(cfg.getStatCurveHpPerPoint()));
        seed.put("outPerPoint", num(cfg.getStatCurveOutDamagePerPoint()));
        seed.put("inReduction", num(cfg.getStatCurveInDamageReductionPerPoint()));
        seed.put("maxHp", num(cfg.getStatCurveMaxHpMult()));
        seed.put("maxOut", num(cfg.getStatCurveMaxOutDamageMult()));
        seed.put("minIn", num(cfg.getStatCurveMinInDamageMult()));
        globalForm.seed(seed);
    }

    private void reseedZoneFromConfig(@Nonnull MobScalingConfig cfg) {
        Map<String, String> seed = new LinkedHashMap<>();
        seed.put("zoneEnabled", onOff(cfg.isZoneHudEnabled()));
        seed.put("zoneShowLoc", onOff(cfg.isZoneShowLocationName()));
        seed.put("zonePos", blankToFirst(cfg.getZoneHudPosition(), POSITIONS));
        seed.put("zoneOffX", String.valueOf(cfg.getZoneHudOffsetX()));
        seed.put("zoneOffY", String.valueOf(cfg.getZoneHudOffsetY()));
        seed.put("zonePrefix", cfg.getZoneNameKeyPrefix());
        seed.put("biomePrefix", cfg.getBiomeNameKeyPrefix());
        zoneForm.seed(seed);
    }

    private void reseedInspectorFromConfig(@Nonnull MobScalingConfig cfg) {
        Map<String, String> seed = new LinkedHashMap<>();
        seed.put("inspEnabled", onOff(cfg.isInspectorHudEnabled()));
        seed.put("inspPortrait", onOff(cfg.isInspectorPortraitEnabled()));
        seed.put("inspPos", blankToFirst(cfg.getInspectorHudPosition(), POSITIONS));
        seed.put("inspOffX", String.valueOf(cfg.getInspectorHudOffsetX()));
        seed.put("inspOffY", String.valueOf(cfg.getInspectorHudOffsetY()));
        seed.put("inspRange", num(cfg.getInspectorRangeBlocks()));
        inspectorForm.seed(seed);
    }

    /**
     * Seed the world editor from an (id, authored body, authored Parent) triple. {@code ws == null}
     * (a brand-new / cleared editor, or an id with no body) seeds every leaf blank/Inherit.
     */
    private void seedWorldForm(@Nonnull String id, @Nullable WorldSettings ws, @Nullable String parent) {
        Difficulty diff = ws == null ? null : ws.getDifficulty();
        DistanceEscalation esc = diff == null ? null : diff.getDistanceEscalation();
        StatCurve curve = diff == null ? null : diff.getStatCurve();
        OpenWorld ow = ws == null ? null : ws.getOpenWorld();
        Hud zoneHud = ws == null ? null : ws.getZoneHud();
        InspectorHud inspHud = ws == null ? null : ws.getInspectorHud();
        WorldSettings.Pool pool = ws == null ? null : ws.getPool();
        WorldSettings.IdGate rarities = pool == null ? null : pool.getRarities();
        WorldSettings.VariantGate variants = pool == null ? null : pool.getVariants();
        WorldSettings.AffixGate affixes = pool == null ? null : pool.getAffixes();

        Map<String, String> seed = new LinkedHashMap<>();
        seed.put(F_WORLD_ID, id);
        seed.put(F_WORLD_MATCH, textOrBlank(ws == null ? null : ws.getMatch()));
        seed.put(F_WORLD_PARENT, textOrBlank(parent));
        seed.put("wEnabled", triOrInherit(ws == null ? null : ws.getEnabled()));
        seed.put("wIntensity", numOrBlank(ws == null ? null : ws.getIntensity()));
        seed.put("wRarity", numOrBlank(ws == null ? null : ws.getRaritySpawnChance()));
        seed.put("wFloor", numOrBlank(diff == null ? null : diff.getFloor()));
        seed.put("wMinCap", numOrBlank(diff == null ? null : diff.getMinCap()));
        seed.put("wMaxCap", numOrBlank(diff == null ? null : diff.getMaxCap()));
        seed.put("wEscEnabled", triOrInherit(esc == null ? null : esc.getEnabled()));
        seed.put("wEscStart", numOrBlank(esc == null ? null : esc.getStartDistanceBlocks()));
        seed.put("wEscBlocks", numOrBlank(esc == null ? null : esc.getBlocksPerPoint()));
        seed.put("wEscMaxBonus", numOrBlank(esc == null ? null : esc.getMaxBonus()));
        seed.put("wEscRarity", numOrBlank(esc == null ? null : esc.getRarityChancePerPoint()));
        seed.put("wPlayerScaling", triOrInherit(ow == null ? null : ow.getPlayerScalingEnabled()));
        seed.put("wAggregation", dropdownOrInherit(ow == null ? null : ow.getAggregationMode()));
        seed.put("wBandWidth", numOrBlank(ow == null ? null : ow.getGroupDeltaBandWidth()));
        seed.put("wOnlyRaise", triOrInherit(ow == null ? null : ow.getOnlyRaiseDifficulty()));
        seed.put("wPartyJoin", triOrInherit(ow == null ? null : ow.getAllowDifficultyIncreaseOnPartyJoin()));
        seed.put("wLateArrival", numOrBlank(ow == null ? null : ow.getLateArrivalBumpFactor()));
        seed.put("wComposition", triOrInherit(ow == null ? null : ow.getCompositionEnabled()));
        seed.put("wHpPerPoint", numOrBlank(curve == null ? null : curve.getHpPerPoint()));
        seed.put("wOutPerPoint", numOrBlank(curve == null ? null : curve.getOutDamagePerPoint()));
        seed.put("wInReduction", numOrBlank(curve == null ? null : curve.getInDamageReductionPerPoint()));
        seed.put("wMaxHp", numOrBlank(curve == null ? null : curve.getMaxHpMult()));
        seed.put("wMaxOut", numOrBlank(curve == null ? null : curve.getMaxOutDamageMult()));
        seed.put("wMinIn", numOrBlank(curve == null ? null : curve.getMinInDamageMult()));
        seed.put("wRarAllow", csvOrBlank(rarities == null ? null : rarities.getAllow()));
        seed.put("wRarDeny", csvOrBlank(rarities == null ? null : rarities.getDeny()));
        seed.put("wVarAllow", csvOrBlank(variants == null ? null : variants.getAllow()));
        seed.put("wVarDeny", csvOrBlank(variants == null ? null : variants.getDeny()));
        seed.put("wVarChance", numOrBlank(variants == null ? null : variants.getChanceMultiplier()));
        seed.put("wAffAllow", csvOrBlank(affixes == null ? null : affixes.getAllow()));
        seed.put("wAffDeny", csvOrBlank(affixes == null ? null : affixes.getDeny()));
        seed.put("wAffSlots", intOrBlank(affixes == null ? null : affixes.getExtraSlots()));
        seed.put("wZoneHud", triOrInherit(zoneHud == null ? null : zoneHud.getEnabled()));
        seed.put("wInspHud", triOrInherit(inspHud == null ? null : inspHud.getEnabled()));
        worldForm.seed(seed);
    }

    private void refreshHuds(@Nonnull MobScalingConfig cfg) {
        HudPosition zone = HudPosition.parse(cfg.getZoneHudPosition(), cfg.getZoneHudOffsetX(), cfg.getZoneHudOffsetY());
        if (zone != null) {
            ZoneDifficultyHud.refreshPositionForAllOnline(zone);
        }
        HudPosition insp = HudPosition.parse(cfg.getInspectorHudPosition(), cfg.getInspectorHudOffsetX(),
                cfg.getInspectorHudOffsetY());
        if (insp != null) {
            MobInspectorHud.refreshPositionForAllOnline(insp);
        }
    }

    // ---------------------------------------------------------------------
    // Instant-persist toggles ("press" actions)
    // ---------------------------------------------------------------------

    /** One instant-persist toggle: which form/container repaints it, how to read/save/live-apply it. */
    private record ToggleDef(@Nonnull SettingsForm form, @Nonnull String containerSel,
            @Nonnull BooleanSupplier current, @Nonnull Consumer<Boolean> save,
            @Nullable Consumer<Boolean> liveApply, @Nonnull String statusKey) {
    }

    @Nonnull
    private Map<String, ToggleDef> buildToggleDefs(@Nonnull MobScalingConfig cfg) {
        Map<String, ToggleDef> m = new LinkedHashMap<>();
        m.put("enabled", new ToggleDef(globalForm, GLOBAL_FORM_SEL, cfg::isEnabled,
                MobScalingOwnerWriter::saveEnabled, null, "scaling.ui.status.saved_restart"));
        m.put("playerScaling", new ToggleDef(globalForm, GLOBAL_FORM_SEL, cfg::isPlayerScalingEnabled,
                MobScalingOwnerWriter::savePlayerScalingEnabled, null, "scaling.ui.status.saved"));
        m.put("onlyRaise", new ToggleDef(globalForm, GLOBAL_FORM_SEL, cfg::isOnlyRaiseDifficulty,
                v -> MobScalingOwnerWriter.saveLeaf(LEAF_ONLY_RAISE, v), null, "scaling.ui.status.saved"));
        m.put("partyJoin", new ToggleDef(globalForm, GLOBAL_FORM_SEL, cfg::isAllowDifficultyIncreaseOnPartyJoin,
                v -> MobScalingOwnerWriter.saveLeaf(LEAF_PARTY_JOIN, v), null, "scaling.ui.status.saved"));
        m.put("composition", new ToggleDef(globalForm, GLOBAL_FORM_SEL, cfg::isCompositionEnabled,
                v -> MobScalingOwnerWriter.saveLeaf(LEAF_COMPOSITION, v), null, "scaling.ui.status.saved"));
        m.put("escEnabled", new ToggleDef(globalForm, GLOBAL_FORM_SEL, cfg::isDistanceEscalationEnabled,
                MobScalingOwnerWriter::saveEscalationEnabled, null, "scaling.ui.status.saved"));
        m.put("zoneEnabled", new ToggleDef(zoneForm, ZONE_FORM_SEL, cfg::isZoneHudEnabled,
                MobScalingOwnerWriter::saveZoneHudEnabled, ZoneDifficultyHud::setEnabledForAllOnline,
                "scaling.ui.status.saved"));
        m.put("zoneShowLoc", new ToggleDef(zoneForm, ZONE_FORM_SEL, cfg::isZoneShowLocationName,
                MobScalingOwnerWriter::saveZoneShowLocationName, null, "scaling.ui.status.saved"));
        m.put("inspEnabled", new ToggleDef(inspectorForm, INSPECTOR_FORM_SEL, cfg::isInspectorHudEnabled,
                MobScalingOwnerWriter::saveInspectorHudEnabled, MobInspectorHud::setEnabledForAllOnline,
                "scaling.ui.status.saved"));
        m.put("inspPortrait", new ToggleDef(inspectorForm, INSPECTOR_FORM_SEL, cfg::isInspectorPortraitEnabled,
                MobScalingOwnerWriter::saveInspectorPortraitEnabled, null, "scaling.ui.status.saved"));
        return m;
    }

    // ---------------------------------------------------------------------
    // Spec tables (the schema for the four SettingsForm instances)
    // ---------------------------------------------------------------------

    @Nonnull
    private static List<FieldSpec> buildGlobalSpecs() {
        List<FieldSpec> s = new ArrayList<>();
        s.add(FieldSpec.toggle("enabled", "scaling.ui.global.enabled"));
        s.add(FieldSpec.note("enabledNote", "scaling.ui.global.enabled_note"));
        s.add(FieldSpec.dropdown("presetMode", "PresetMode", "scaling.ui.global.preset_mode", PRESET_MODES));
        s.add(FieldSpec.number("intensity", "Intensity", "scaling.ui.global.intensity"));
        s.add(FieldSpec.chance("rarity", "RaritySpawnChance", "scaling.ui.global.rarity"));
        s.add(FieldSpec.header("hdrOpenWorld", "scaling.ui.global.open_world_header"));
        s.add(FieldSpec.toggle("playerScaling", "scaling.ui.global.player_scaling"));
        s.add(FieldSpec.dropdown("aggregation", "OpenWorld.AggregationMode", "scaling.ui.global.aggregation",
                AGGREGATION_MODES));
        s.add(FieldSpec.integer("regionSize", "OpenWorld.RegionSizeChunks", "scaling.ui.global.region_size"));
        s.add(FieldSpec.number("bandWidth", "OpenWorld.GroupDeltaBandWidth", "scaling.ui.global.band_width"));
        s.add(FieldSpec.toggle("onlyRaise", "scaling.ui.global.only_raise"));
        s.add(FieldSpec.toggle("partyJoin", "scaling.ui.global.party_join"));
        s.add(FieldSpec.number("lateArrival", "OpenWorld.LateArrivalBumpFactor", "scaling.ui.global.late_arrival"));
        s.add(FieldSpec.toggle("composition", "scaling.ui.global.composition"));
        s.add(FieldSpec.header("hdrDifficulty", "scaling.ui.global.difficulty_header"));
        s.add(FieldSpec.number("floor", "Difficulty.Floor", "scaling.ui.global.floor"));
        s.add(FieldSpec.number("minCap", LEAF_MIN_CAP, "scaling.ui.global.min_cap"));
        s.add(FieldSpec.number("maxCap", LEAF_MAX_CAP, "scaling.ui.global.max_cap"));
        s.add(FieldSpec.header("hdrEsc", "scaling.ui.global.esc_header"));
        s.add(FieldSpec.toggle("escEnabled", "scaling.ui.global.esc_enabled"));
        s.add(FieldSpec.number("escStart", "Difficulty.DistanceEscalation.StartDistanceBlocks",
                "scaling.ui.global.esc_start"));
        s.add(FieldSpec.number("escBlocks", "Difficulty.DistanceEscalation.BlocksPerPoint",
                "scaling.ui.global.esc_blocks"));
        s.add(FieldSpec.number("escMaxBonus", "Difficulty.DistanceEscalation.MaxBonus",
                "scaling.ui.global.esc_max_bonus"));
        s.add(FieldSpec.number("escRarity", "Difficulty.DistanceEscalation.RarityChancePerPoint",
                "scaling.ui.global.esc_rarity"));
        s.add(FieldSpec.header("hdrCurve", "scaling.ui.global.stat_curve_header"));
        s.add(FieldSpec.number("hpPerPoint", "Difficulty.StatCurve.HpPerPoint", "scaling.ui.curve.hp_per_point"));
        s.add(FieldSpec.number("outPerPoint", "Difficulty.StatCurve.OutDamagePerPoint",
                "scaling.ui.curve.out_per_point"));
        s.add(FieldSpec.number("inReduction", "Difficulty.StatCurve.InDamageReductionPerPoint",
                "scaling.ui.curve.in_reduction"));
        s.add(FieldSpec.number("maxHp", "Difficulty.StatCurve.MaxHpMult", "scaling.ui.curve.max_hp"));
        s.add(FieldSpec.number("maxOut", "Difficulty.StatCurve.MaxOutDamageMult", "scaling.ui.curve.max_out"));
        s.add(FieldSpec.number("minIn", "Difficulty.StatCurve.MinInDamageMult", "scaling.ui.curve.min_in"));
        return List.copyOf(s);
    }

    @Nonnull
    private static List<FieldSpec> buildZoneSpecs() {
        List<FieldSpec> s = new ArrayList<>();
        s.add(FieldSpec.toggle("zoneEnabled", "scaling.ui.zone.enabled"));
        s.add(FieldSpec.toggle("zoneShowLoc", "scaling.ui.zone.show_location"));
        s.add(FieldSpec.dropdown("zonePos", "ZoneHud.Position", "scaling.ui.hud.position", POSITIONS));
        s.add(FieldSpec.integer("zoneOffX", "ZoneHud.OffsetX", "scaling.ui.hud.offset_x"));
        s.add(FieldSpec.integer("zoneOffY", "ZoneHud.OffsetY", "scaling.ui.hud.offset_y"));
        s.add(FieldSpec.text("zonePrefix", "ZoneHud.ZoneNameKeyPrefix", "scaling.ui.zone.zone_name_prefix"));
        s.add(FieldSpec.text("biomePrefix", "ZoneHud.BiomeNameKeyPrefix", "scaling.ui.zone.biome_name_prefix"));
        return List.copyOf(s);
    }

    @Nonnull
    private static List<FieldSpec> buildInspectorSpecs() {
        List<FieldSpec> s = new ArrayList<>();
        s.add(FieldSpec.toggle("inspEnabled", "scaling.ui.inspector.enabled"));
        s.add(FieldSpec.toggle("inspPortrait", "scaling.ui.inspector.portrait"));
        s.add(FieldSpec.dropdown("inspPos", "InspectorHud.Position", "scaling.ui.hud.position", POSITIONS));
        s.add(FieldSpec.integer("inspOffX", "InspectorHud.OffsetX", "scaling.ui.hud.offset_x"));
        s.add(FieldSpec.integer("inspOffY", "InspectorHud.OffsetY", "scaling.ui.hud.offset_y"));
        s.add(FieldSpec.number("inspRange", "InspectorHud.RangeBlocks", "scaling.ui.inspector.range"));
        return List.copyOf(s);
    }

    @Nonnull
    private static List<FieldSpec> buildWorldSpecs() {
        List<FieldSpec> s = new ArrayList<>();
        s.add(FieldSpec.text(F_WORLD_ID, WORLD_ID_LEAF, "scaling.ui.world.id"));
        s.add(FieldSpec.text(F_WORLD_MATCH, "Match", "scaling.ui.world.match"));
        s.add(FieldSpec.text(F_WORLD_PARENT, "Parent", "scaling.ui.world.parent"));
        s.add(FieldSpec.tristate("wEnabled", "Enabled", "scaling.ui.world.enabled"));
        s.add(FieldSpec.header("wHdrTuning", "scaling.ui.world.tuning_header"));
        s.add(FieldSpec.number("wIntensity", "Intensity", "scaling.ui.world.intensity"));
        s.add(FieldSpec.chance("wRarity", "RaritySpawnChance", "scaling.ui.world.rarity"));
        s.add(FieldSpec.header("wHdrDifficulty", "scaling.ui.global.difficulty_header"));
        s.add(FieldSpec.number("wFloor", "Difficulty.Floor", "scaling.ui.world.floor"));
        s.add(FieldSpec.number("wMinCap", LEAF_MIN_CAP, "scaling.ui.global.min_cap"));
        s.add(FieldSpec.number("wMaxCap", LEAF_MAX_CAP, "scaling.ui.global.max_cap"));
        s.add(FieldSpec.header("wHdrEsc", "scaling.ui.global.esc_header"));
        s.add(FieldSpec.tristate("wEscEnabled", "Difficulty.DistanceEscalation.Enabled",
                "scaling.ui.global.esc_enabled"));
        s.add(FieldSpec.number("wEscStart", "Difficulty.DistanceEscalation.StartDistanceBlocks",
                "scaling.ui.global.esc_start"));
        s.add(FieldSpec.number("wEscBlocks", "Difficulty.DistanceEscalation.BlocksPerPoint",
                "scaling.ui.global.esc_blocks"));
        s.add(FieldSpec.number("wEscMaxBonus", "Difficulty.DistanceEscalation.MaxBonus",
                "scaling.ui.global.esc_max_bonus"));
        s.add(FieldSpec.number("wEscRarity", "Difficulty.DistanceEscalation.RarityChancePerPoint",
                "scaling.ui.global.esc_rarity"));
        s.add(FieldSpec.header("wHdrOpenWorld", "scaling.ui.global.open_world_header"));
        s.add(FieldSpec.tristate("wPlayerScaling", "OpenWorld.PlayerScalingEnabled",
                "scaling.ui.world.player_scaling"));
        s.add(FieldSpec.dropdown("wAggregation", "OpenWorld.AggregationMode", "scaling.ui.global.aggregation",
                AGGREGATION_MODES_INHERIT));
        s.add(FieldSpec.number("wBandWidth", "OpenWorld.GroupDeltaBandWidth", "scaling.ui.global.band_width"));
        s.add(FieldSpec.tristate("wOnlyRaise", LEAF_ONLY_RAISE, "scaling.ui.global.only_raise"));
        s.add(FieldSpec.tristate("wPartyJoin", LEAF_PARTY_JOIN, "scaling.ui.global.party_join"));
        s.add(FieldSpec.number("wLateArrival", "OpenWorld.LateArrivalBumpFactor", "scaling.ui.global.late_arrival"));
        s.add(FieldSpec.tristate("wComposition", LEAF_COMPOSITION, "scaling.ui.global.composition"));
        // NO RegionSizeChunks per-world: it decodes on WorldSettings but the region grid stays global.
        s.add(FieldSpec.header("wHdrCurve", "scaling.ui.global.stat_curve_header"));
        s.add(FieldSpec.number("wHpPerPoint", "Difficulty.StatCurve.HpPerPoint", "scaling.ui.curve.hp_per_point"));
        s.add(FieldSpec.number("wOutPerPoint", "Difficulty.StatCurve.OutDamagePerPoint",
                "scaling.ui.curve.out_per_point"));
        s.add(FieldSpec.number("wInReduction", "Difficulty.StatCurve.InDamageReductionPerPoint",
                "scaling.ui.curve.in_reduction"));
        s.add(FieldSpec.number("wMaxHp", "Difficulty.StatCurve.MaxHpMult", "scaling.ui.curve.max_hp"));
        s.add(FieldSpec.number("wMaxOut", "Difficulty.StatCurve.MaxOutDamageMult", "scaling.ui.curve.max_out"));
        s.add(FieldSpec.number("wMinIn", "Difficulty.StatCurve.MinInDamageMult", "scaling.ui.curve.min_in"));
        s.add(FieldSpec.header("wHdrPool", "scaling.ui.world.pool_header"));
        s.add(FieldSpec.csv("wRarAllow", "Pool.Rarities.Allow", "scaling.ui.world.pool_rarities_allow"));
        s.add(FieldSpec.csv("wRarDeny", "Pool.Rarities.Deny", "scaling.ui.world.pool_rarities_deny"));
        s.add(FieldSpec.csv("wVarAllow", "Pool.Variants.Allow", "scaling.ui.world.pool_variants_allow"));
        s.add(FieldSpec.csv("wVarDeny", "Pool.Variants.Deny", "scaling.ui.world.pool_variants_deny"));
        s.add(FieldSpec.number("wVarChance", "Pool.Variants.ChanceMultiplier", "scaling.ui.world.pool_variant_chance"));
        s.add(FieldSpec.csv("wAffAllow", "Pool.Affixes.Allow", "scaling.ui.world.pool_affixes_allow"));
        s.add(FieldSpec.csv("wAffDeny", "Pool.Affixes.Deny", "scaling.ui.world.pool_affixes_deny"));
        s.add(FieldSpec.integer("wAffSlots", "Pool.Affixes.ExtraSlots", "scaling.ui.world.pool_extra_slots"));
        s.add(FieldSpec.header("wHdrHud", "scaling.ui.world.hud_header"));
        s.add(FieldSpec.tristate("wZoneHud", "ZoneHud.Enabled", "scaling.ui.world.zone_hud"));
        s.add(FieldSpec.tristate("wInspHud", "InspectorHud.Enabled", "scaling.ui.world.inspector_hud"));
        s.add(FieldSpec.note("wHint", "scaling.ui.world.hint"));
        return List.copyOf(s);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Build a status Message naming the failing field: {@code {field}} is a nested resolved Message. */
    private void emitInvalidField(@Nonnull FormResult result) {
        String labelKey = result.errorLabelKey();
        Message field = labelKey != null ? tr(labelKey) : Message.raw("?");
        err(tr("scaling.ui.status.invalid_field").param("field", field));
    }

    private void actionButton(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull String sel, @Nonnull String labelKey, @Nonnull String action) {
        ZigRichButton.text(cmd, sel, tr(labelKey));
        SettingsUiUtil.bindButton(events, sel, action);
    }

    private void rowLabel(@Nonnull UICommandBuilder cmd, @Nonnull String sel, @Nonnull String key) {
        cmd.set(sel + ".TextSpans", tr(key));
    }

    private void label(@Nonnull UICommandBuilder cmd, @Nonnull String sel, @Nonnull String key) {
        ZigRichButton.text(cmd, sel, tr(key));
    }

    /** DATA summary line for a world row (Match/Parent/knobs are literal values, not display text). */
    @Nonnull
    private String worldSummary(@Nonnull WorldSettingsConfig worlds, @Nonnull String id,
            @Nonnull WorldSettings ws) {
        StringBuilder sb = new StringBuilder();
        append(sb, ws.isMatchable() ? ws.getMatch() : "(base)");
        String parent = worlds.parentOf(id);
        if (parent != null) append(sb, "parent " + parent);
        if (ws.getEnabled() != null && !ws.getEnabled()) append(sb, "OFF");
        if (ws.getIntensity() != null) append(sb, "int " + num(ws.getIntensity()));
        if (ws.getRaritySpawnChance() != null) append(sb, "rarity " + num(ws.getRaritySpawnChance()));
        if (ws.getOpenWorld() != null && ws.getOpenWorld().getPlayerScalingEnabled() != null) {
            append(sb, "scaling " + (ws.getOpenWorld().getPlayerScalingEnabled() ? "on" : "off"));
        }
        if (ws.getDifficulty() != null) {
            if (ws.getDifficulty().getFloor() != null) {
                append(sb, "floor " + num(ws.getDifficulty().getFloor()));
            }
            if (ws.getDifficulty().getMinCap() != null || ws.getDifficulty().getMaxCap() != null) {
                append(sb, "caps " + num(nz(ws.getDifficulty().getMinCap())) + "-" + num(nz(ws.getDifficulty().getMaxCap())));
            }
            if (ws.getDifficulty().getDistanceEscalation() != null
                    && ws.getDifficulty().getDistanceEscalation().getEnabled() != null) {
                append(sb, "esc " + (ws.getDifficulty().getDistanceEscalation().getEnabled() ? "on" : "off"));
            }
        }
        if (ws.getPool() != null) append(sb, "pool");
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static void append(@Nonnull StringBuilder sb, @Nonnull String s) {
        if (sb.length() > 0) sb.append("  |  ");
        sb.append(s);
    }

    private static double nz(@Nullable Double v) {
        return v == null ? 0.0 : v;
    }

    // ---------------------------------------------------------------------
    // Status line
    // ---------------------------------------------------------------------

    private void ok(@Nonnull String key) {
        this.statusMessage = tr(key);
        this.statusIsError = false;
    }

    private void err(@Nonnull String key) {
        err(tr(key));
    }

    private void err(@Nonnull Message msg) {
        this.statusMessage = msg;
        this.statusIsError = true;
    }

    private void clearStatus() {
        this.statusMessage = null;
        this.statusIsError = false;
    }

    /** Push the current status line into {@code cmd} and send the (no-rebind) partial update. */
    private void finish(@Nonnull UICommandBuilder cmd) {
        finish(cmd, null);
    }

    /** Push the current status line into {@code cmd} and send the partial update, rebinding {@code events}. */
    private void finish(@Nonnull UICommandBuilder cmd, @Nullable UIEventBuilder events) {
        SettingsUiUtil.setStatus(cmd, STATUS_SEL, statusMessage, statusIsError);
        sendUpdate(cmd, events, false);
    }

    @Nonnull
    private static Message tr(@Nonnull String key) {
        return Message.translation(key);
    }

    // ---------------------------------------------------------------------
    // Raw-value <-> display-string conversions (seeding helpers)
    // ---------------------------------------------------------------------

    @Nonnull
    private static String onOff(boolean v) {
        return v ? "on" : "off";
    }

    @Nonnull
    private static String textOrBlank(@Nullable String v) {
        return v == null ? "" : v;
    }

    @Nonnull
    private static String numOrBlank(@Nullable Double v) {
        return v == null ? "" : num(v);
    }

    @Nonnull
    private static String intOrBlank(@Nullable Integer v) {
        return v == null ? "" : String.valueOf(v);
    }

    @Nonnull
    private static String triOrInherit(@Nullable Boolean v) {
        return v == null ? "inherit" : (v ? "on" : "off");
    }

    @Nonnull
    private static String dropdownOrInherit(@Nullable String v) {
        return v == null || v.isBlank() ? "inherit" : v;
    }

    /**
     * A folded DROPDOWN value that is blank falls back to the dropdown's FIRST entry, so the seeded
     * cache matches what the client actually displays. Without this, a blank cache value leaves the
     * dropdown's own {@code .Value} unset (see {@code SettingsUiUtil.populate}), so the client shows
     * its first entry while the cache still holds {@code ""} - a later Save would then read the blank
     * cache and REMOVE the leaf instead of persisting the value the admin sees selected. Only for the
     * three fixed-entry dropdowns with no "inherit" pseudo-value (presetMode/aggregation/zonePos/
     * inspPos); the per-world {@code wAggregation} dropdown already seeds an explicit "inherit" via
     * {@link #dropdownOrInherit} and does not need this.
     */
    @Nonnull
    private static String blankToFirst(@Nullable String v, @Nonnull String[] values) {
        if (v != null && !v.isBlank()) {
            return v;
        }
        return values.length > 0 ? values[0] : "";
    }

    @Nonnull
    private static String csvOrBlank(@Nullable String[] v) {
        return v == null || v.length == 0 ? "" : String.join(", ", v);
    }

    @Nonnull
    private static String num(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    // ---------------------------------------------------------------------
    // Event data (five keys: every SettingsForm row + every hand-bound control speaks this shape)
    // ---------------------------------------------------------------------

    public static final class EventData {
        public String action;
        public String tab;
        public String worldId;
        public String field;
        public String value;

        public static final BuilderCodec<EventData> CODEC = BuilderCodec.builder(EventData.class, EventData::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, v, i) -> d.action = v, (d, i) -> d.action).add()
                .append(new KeyedCodec<>("Tab", Codec.STRING), (d, v, i) -> d.tab = v, (d, i) -> d.tab).add()
                .append(new KeyedCodec<>("WorldId", Codec.STRING), (d, v, i) -> d.worldId = v, (d, i) -> d.worldId).add()
                .append(new KeyedCodec<>("Field", Codec.STRING), (d, v, i) -> d.field = v, (d, i) -> d.field).add()
                .append(new KeyedCodec<>("@Value", Codec.STRING), (d, v, i) -> d.value = v, (d, i) -> d.value).add()
                .build();
    }
}

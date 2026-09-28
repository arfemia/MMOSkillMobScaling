package com.ziggfreed.mmomobscaling.pages;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
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
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.form.FieldSpec;
import com.ziggfreed.common.ui.form.FormResult;
import com.ziggfreed.common.ui.form.SettingsForm;
import com.ziggfreed.common.world.WorldSelector;
import com.ziggfreed.mmomobscaling.asset.DifficultyMappingAsset;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Clamps;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Difficulty;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.DistanceEscalation;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Hud;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.InspectorHud;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.OpenWorld;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.StatCurve;
import com.ziggfreed.mmomobscaling.asset.WorldSettings;
import com.ziggfreed.mmomobscaling.config.DifficultyConfig;
import com.ziggfreed.mmomobscaling.config.DifficultyOwnerLayer;
import com.ziggfreed.mmomobscaling.config.MmoPowerBounds;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;
import com.ziggfreed.mmomobscaling.config.MobScalingOwnerWriter;
import com.ziggfreed.mmomobscaling.config.OwnerFiles;
import com.ziggfreed.mmomobscaling.config.RarityConfig;
import com.ziggfreed.mmomobscaling.config.WorldSettingsConfig;
import com.ziggfreed.mmomobscaling.hud.MobInspectorHud;
import com.ziggfreed.mmomobscaling.hud.ZoneDifficultyHud;
import com.ziggfreed.mmomobscaling.i18n.MobScalingTextUtil;
import com.ziggfreed.mmomobscaling.pages.ScalingPreview.Sample;
import com.ziggfreed.mmomobscaling.pages.ScalingPreview.Tier;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;
import com.ziggfreed.mmomobscaling.world.DifficultyMapping;

/**
 * The in-game admin config page for MMO Mob Scaling ({@code /mobscaling ui}). SPEC-DRIVEN: five
 * {@link SettingsForm} instances (Global, Zone HUD, Inspector HUD, a per-world {@code Worlds/*.json}
 * editor and a zone/biome floor editor over {@code Difficulty/*.json}) each render from an ordered
 * {@link FieldSpec} list ({@link #buildGlobalSpecs()} / {@link #buildZoneSpecs()} /
 * {@link #buildInspectorSpecs()} / {@link #buildWorldSpecs()} / {@link #buildFloorSpecs()}) through the
 * shared ziggfreed-common {@code ui/form} engine - a new knob later is one spec line here plus one lang
 * key, never a new {@code .ui} row or a new codec field on {@link EventData}. The Worlds and Floors tabs
 * are TWO-PANEL layouts (a scrolling list on the left, the add/edit editor on the right); every
 * hint/note WRAPS ({@code ZigFormNoteRow}, multi-line).
 *
 * <p><b>The Global tab's right column is the one place a consequence is shown</b>, and it is hand-built
 * because nothing there is a leaf to persist: a plain Skeleton through the CURRENT (uncommitted) curve
 * at five sample difficulties, a typed probe difficulty, the rarity ladder at the probed difficulty, and
 * a read-only panel of what this mod can see of the MMO's power scale. Every figure comes out of
 * {@link ScalingPreview}, which runs the same {@link MobScaleFold} the spawn path runs on a curve and
 * clamps built through {@link MobScalingConfig#buildCurve} / {@link MobScalingConfig#buildClamps}, the
 * one constructor pair every layer uses, so the preview can never disagree with a Save.
 *
 * <p><b>Never reopens itself.</b> Every event answers with a PARTIAL {@link #sendUpdate}, so the scroll
 * position never resets. A list change (save/remove) clears + re-appends + rebinds the list rows in the
 * SAME update - the official {@code ChangeModelPage} pattern (jar {@code Model} plugin,
 * {@code buildModelList}). {@code EventData} carries exactly five keys: {@code Action}/{@code Tab}/
 * {@code Id}/{@code Field}/{@code @Value} ({@code Id} is the row id of whichever list a button sits
 * in); a {@code "field"} action just caches the raw value (no packet), a {@code "press"} flips +
 * persists a toggle, everything else builds a small {@link UICommandBuilder} and finishes with a status
 * line + {@link #sendUpdate}.
 *
 * <p>Global/HUD edits persist through the ONE write-back path ({@link MobScalingOwnerWriter} -> the
 * owner file -> {@code refreshFromDisk}); world edits write their own file
 * ({@link MobScalingOwnerWriter#saveWorldFile}/{@code deleteWorldFile} -> the worlds refold) and floor
 * edits theirs ({@link MobScalingOwnerWriter#saveDifficultyMapping}/{@code deleteDifficultyMapping}
 * -> the owner layer refold). Both editors seed from the AUTHORED body of the owner file
 * ({@link WorldSettingsConfig#authoredById}, {@link DifficultyOwnerLayer#authoredById}), NOT the
 * folded-effective view: seeding the merged view and saving back would materialize every inherited leaf
 * into the file and silently break inheritance - authored-seeding keeps blank field = inherit faithful,
 * and the per-field hint says what a blank field currently inherits. HUD / preset edits live-apply to
 * all online players. All labelled buttons are RICH ({@link ZigRichButton} /
 * {@link SettingsUiUtil#setToggle}); all display text is a client-resolved {@link Message} on
 * {@code .TextSpans}, and every display number is a TYPED param the player's own client formats.
 *
 * <p><b>Access:</b> gated by the {@code /mobscaling ui} command's {@code hytale:Admin} permission group
 * (the only way to open this page).
 */
public final class MobScalingAdminPage extends InteractiveCustomUIPage<MobScalingAdminPage.EventData> {

    private static final String UI = "Pages/MmoscalingAdminPage.ui";
    // The world-list row template: a MOD-LOCAL row (not common's Pages/ZigListRow.ui) so a long world
    // id/match pattern wraps to two lines instead of truncating in this page's 300px list panel.
    private static final String ROW = "Pages/MmoscalingWorldRow.ui";
    private static final String PREVIEW_ROW = "Pages/MmoscalingPreviewRow.ui";

    private static final String STATUS_SEL = "#MmoscalingStatus";
    private static final String PRESET_DROPDOWN_SEL = "#MmoscalingPresetDropdown";
    private static final String GLOBAL_FORM_SEL = "#MmoscalingGlobalForm";
    private static final String ZONE_FORM_SEL = "#MmoscalingZoneForm";
    private static final String INSPECTOR_FORM_SEL = "#MmoscalingInspectorForm";
    private static final String WORLD_FORM_SEL = "#MmoscalingWorldForm";
    private static final String WORLD_LIST = "#MmoscalingWorldList";
    private static final String WORLD_EMPTY_SEL = "#MmoscalingWorldEmpty";
    private static final String FLOOR_FORM_SEL = "#MmoscalingFloorForm";
    private static final String FLOOR_LIST = "#MmoscalingFloorList";
    private static final String FLOOR_EMPTY_SEL = "#MmoscalingFloorEmpty";
    private static final String PREVIEW_LIST = "#MmoscalingPreviewList";
    private static final String PREVIEW_TITLE_SEL = "#MmoscalingPreviewTitle";
    private static final String PREVIEW_NOTE_SEL = "#MmoscalingPreviewNote";
    // The manual difficulty probe: a label + TextField below the five fixed samples, plus the one extra
    // preview row it drives (#MmoscalingProbeList holds exactly one appended row and is shown only while
    // the field parses). Wired OUTSIDE globalForm/SettingsForm (its own "previewD" event, see
    // buildPreviewPanel/handlePreviewDifficulty) since it has no leaf path to persist.
    private static final String PREVIEW_CUSTOM_LABEL_SEL = "#MmoscalingPreviewCustomLabel";
    private static final String PREVIEW_CUSTOM_FIELD_SEL = "#MmoscalingPreviewCustomField";
    private static final String PROBE_LIST = "#MmoscalingProbeList";
    // The rarity ladder at the probed difficulty (the baseline floor while nothing is typed): a title, a
    // note and one appended preview row per rung (plain + every folded tier), re-appended on refresh.
    private static final String LADDER_TITLE_SEL = "#MmoscalingLadderTitle";
    private static final String LADDER_NOTE_SEL = "#MmoscalingLadderNote";
    private static final String LADDER_LIST = "#MmoscalingLadderList";
    // The read-only MMO panel: what this mod can see of the player power scale, and where it is edited.
    private static final String MMO_TITLE_SEL = "#MmoscalingMmoTitle";
    private static final String MMO_POWER_SEL = "#MmoscalingMmoPower";
    private static final String MMO_OWN_POWER_SEL = "#MmoscalingMmoOwnPower";
    private static final String MMO_CAP_SEL = "#MmoscalingMmoCap";
    private static final String MMO_WHERE_SEL = "#MmoscalingMmoWhere";
    // Five evenly-spaced sample difficulties between the current MinCap and MaxCap (the Global-tab preview).
    private static final int PREVIEW_SAMPLES = 5;
    // The preview's fixed sample role (matches mmomobscaling.ui.global.preview_title, "Preview: Skeleton").
    private static final String PREVIEW_ROLE_NAME = "Skeleton";
    // The per-maintainer style for an editor hint's "Inherits: X" segment: white + bold, label and
    // substituted value alike (Message#color/#bold mutate + return the SAME instance, so both the
    // wrapping frame message and the nested value param need the call - see inheritsSegment).
    private static final String INHERITS_COLOR = "#ffffff";
    // The colour of a preview row's "rail holding" marker (the zone card's HARD amber, so it reads as a warning).
    private static final String RAIL_COLOR = "#ffb74d";

    // World-form field ids referenced outside the spec table (id derivation, self-Parent check).
    private static final String F_WORLD_ID = "worldId";
    private static final String F_WORLD_MATCH = "worldMatch";
    private static final String F_WORLD_CONFIGS = "worldConfigs";
    private static final String F_WORLD_EXCLUDES = "worldExcludes";
    private static final String F_WORLD_PARENT = "worldParent";
    // Floor-form field ids referenced outside the spec table (id derivation, the completeness check).
    private static final String F_FLOOR_ID = "floorId";
    private static final String F_FLOOR_TYPE = "floorType";
    private static final String F_FLOOR_TARGET = "floorTarget";
    private static final String F_FLOOR_VALUE = "floorValue";
    // The two editors' id specs share one SENTINEL leaf path, not a real codec key: popped from the
    // collected leaves before every save (neither file has an "$Id" field - the filename IS the id).
    private static final String ID_LEAF = "$Id";
    // The three leaves of a difficulty mapping file (DifficultyMappingAsset's codec keys).
    private static final String LEAF_TARGET_TYPE = "TargetType";
    private static final String LEAF_TARGET_ID = "TargetId";
    private static final String LEAF_FLOOR = "Floor";
    // The floor editor's target-type dropdown VALUES: Inherit (keep the shipped mapping's answer), then the
    // two codec words DifficultyMapping.TargetType.parse accepts. The value is what a save writes; the
    // label the admin sees is the localized word behind it (targetTypeLabelKey, localizeFloorTypeEntries).
    private static final String WORD_INHERIT = "inherit";
    private static final String WORD_ZONE = "Zone";
    private static final String WORD_BIOME = "Biome";
    private static final String[] TARGET_TYPES_INHERIT = {WORD_INHERIT, WORD_ZONE, WORD_BIOME};

    // Leaf paths shared between two spec tables (global/world) or a spec + its instant-toggle saver,
    // named once so the two call sites can never drift apart.
    private static final String LEAF_MIN_CAP = "Difficulty.MinCap";
    private static final String LEAF_MAX_CAP = "Difficulty.MaxCap";
    private static final String LEAF_ONLY_RAISE = "OpenWorld.OnlyRaiseDifficulty";

    // The 9 named corner presets (technical ids, shown literally in the position dropdowns).
    private static final String[] POSITIONS = {
            "TOP_LEFT", "TOP_CENTER", "TOP_RIGHT",
            "CENTER_LEFT", "CENTER", "CENTER_RIGHT",
            "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT"
    };
    // The per-world dropdowns lead with the Inherit pseudo-value (a blank/inherit leaf falls through the
    // Parent chain to the global corner / mode).
    private static final String[] POSITIONS_INHERIT = {
            "inherit",
            "TOP_LEFT", "TOP_CENTER", "TOP_RIGHT",
            "CENTER_LEFT", "CENTER", "CENTER_RIGHT",
            "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT"
    };
    private static final String[] AGGREGATION_MODES = {"SOLO", "AVERAGE", "PEAK", "WEIGHTED", "DISABLED"};
    private static final String[] AGGREGATION_MODES_INHERIT =
            {"inherit", "SOLO", "AVERAGE", "PEAK", "WEIGHTED", "DISABLED"};

    private static final List<FieldSpec> GLOBAL_SPECS = buildGlobalSpecs();
    private static final List<FieldSpec> ZONE_SPECS = buildZoneSpecs();
    private static final List<FieldSpec> INSPECTOR_SPECS = buildInspectorSpecs();
    private static final List<FieldSpec> WORLD_SPECS = buildWorldSpecs();
    private static final List<FieldSpec> FLOOR_SPECS = buildFloorSpecs();

    /** One tab: its event id, its rich button, the section it shows, its label key. */
    private record Tab(@Nonnull String id, @Nonnull String buttonSel, @Nonnull String sectionSel,
            @Nonnull String labelKey) {
    }

    private static final List<Tab> TABS = List.of(
            new Tab("global", "#MmoscalingTabGlobal", "#MmoscalingSectionGlobal", "mmomobscaling.ui.tab.global"),
            new Tab("zonehud", "#MmoscalingTabZoneHud", "#MmoscalingSectionZoneHud", "mmomobscaling.ui.tab.zone_hud"),
            new Tab("inspector", "#MmoscalingTabInspector", "#MmoscalingSectionInspector", "mmomobscaling.ui.tab.inspector"),
            new Tab("worlds", "#MmoscalingTabWorlds", "#MmoscalingSectionWorlds", "mmomobscaling.ui.tab.worlds"),
            new Tab("floors", "#MmoscalingTabFloors", "#MmoscalingSectionFloors", "mmomobscaling.ui.tab.floors"));

    /** The floor list's order: zones before biomes, a wildcard after the named targets, then by name. */
    private static final Comparator<DifficultyMapping> FLOOR_ORDER = Comparator
            .comparing(DifficultyMapping::targetType)
            .thenComparing(DifficultyMapping::isWildcard)
            .thenComparing(DifficultyMapping::targetId, String.CASE_INSENSITIVE_ORDER);

    private final SettingsForm globalForm;
    private final SettingsForm zoneForm;
    private final SettingsForm inspectorForm;
    private final SettingsForm worldForm;
    private final SettingsForm floorForm;

    // Instant-persist toggle registry (global/zone/inspector "press" actions): id -> current-state
    // read, persist call, optional live HUD apply, and the status key to show. Built once in the
    // constructor (bound to the singleton MobScalingConfig, which never changes identity).
    private final Map<String, ToggleDef> toggleDefs;

    private String activeTab = "global";

    // The manual difficulty-probe field's cached raw text; lives OUTSIDE every SettingsForm (it has no
    // leaf path, nothing to persist) but follows the same "cache on field, refresh on demand" shape.
    // Starts blank (a fresh page open shows no probe row, and the ladder follows the baseline floor).
    @Nonnull private String customPreviewInput = "";

    @Nullable private Message statusMessage;
    private boolean statusIsError;

    public MobScalingAdminPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, EventData.CODEC);
        Message toggleOn = tr("mmomobscaling.ui.toggle.on");
        Message toggleOff = tr("mmomobscaling.ui.toggle.off");
        this.globalForm = new SettingsForm(GLOBAL_SPECS, toggleOn, toggleOff);
        this.zoneForm = new SettingsForm(ZONE_SPECS, toggleOn, toggleOff);
        this.inspectorForm = new SettingsForm(INSPECTOR_SPECS, toggleOn, toggleOff);
        this.worldForm = new SettingsForm(WORLD_SPECS, toggleOn, toggleOff);
        this.floorForm = new SettingsForm(FLOOR_SPECS, toggleOn, toggleOff);

        MobScalingConfig cfg = MobScalingConfig.getInstance();
        reseedGlobalFromConfig(cfg);
        reseedZoneFromConfig(cfg);
        reseedInspectorFromConfig(cfg);
        seedWorldForm("", null, null);
        seedFloorForm("", null);
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

        cmd.set("#MmoscalingTitle.TextSpans", tr("mmomobscaling.ui.title"));
        SettingsUiUtil.bindButton(events, "#CloseButton", "close");

        buildTabs(cmd, events);

        buildPresetRow(cmd, events, cfg);
        globalForm.buildRows(cmd, events, GLOBAL_FORM_SEL, MobScalingAdminPage::tr);
        actionButton(cmd, events, "#MmoscalingGlobalSave", "mmomobscaling.ui.button.save_tab", "saveGlobal");
        buildPreviewPanel(cmd, events, MmoPowerBounds.of(store, ref));

        zoneForm.buildRows(cmd, events, ZONE_FORM_SEL, MobScalingAdminPage::tr);
        actionButton(cmd, events, "#MmoscalingZoneSave", "mmomobscaling.ui.button.save_tab", "saveZone");

        inspectorForm.buildRows(cmd, events, INSPECTOR_FORM_SEL, MobScalingAdminPage::tr);
        actionButton(cmd, events, "#MmoscalingInspectorSave", "mmomobscaling.ui.button.save_tab", "saveInspector");

        buildWorldList(cmd, events);
        rowLabel(cmd, "#MmoscalingWorldEditorHeader", "mmomobscaling.ui.world.editor_header");
        worldForm.buildRows(cmd, events, WORLD_FORM_SEL, MobScalingAdminPage::tr);
        actionButton(cmd, events, "#MmoscalingWorldNew", "mmomobscaling.ui.world.new", "clearWorld");
        actionButton(cmd, events, "#MmoscalingWorldSave", "mmomobscaling.ui.button.save_world", "saveWorld");
        actionButton(cmd, events, "#MmoscalingWorldClear", "mmomobscaling.ui.button.clear", "clearWorld");

        buildFloorList(cmd, events);
        rowLabel(cmd, "#MmoscalingFloorEditorHeader", "mmomobscaling.ui.floor.editor_header");
        floorForm.buildRows(cmd, events, FLOOR_FORM_SEL, MobScalingAdminPage::tr);
        localizeFloorTypeEntries(cmd);
        actionButton(cmd, events, "#MmoscalingFloorNew", "mmomobscaling.ui.floor.new", "clearFloor");
        actionButton(cmd, events, "#MmoscalingFloorSave", "mmomobscaling.ui.button.save_world", "saveFloor");
        actionButton(cmd, events, "#MmoscalingFloorClear", "mmomobscaling.ui.button.clear", "clearFloor");

        SettingsUiUtil.setStatus(cmd, STATUS_SEL, statusMessage, statusIsError);
    }

    /** Build-time: label + bind every tab button, then paint the active state. */
    private void buildTabs(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        for (Tab tab : TABS) {
            label(cmd, tab.buttonSel(), tab.labelKey());
            SettingsUiUtil.bindButton(events, tab.buttonSel(), "tab", "Tab", tab.id());
        }
        applyTabState(cmd);
    }

    /** Tint the active tab and show exactly its section (build and every tab switch share this). */
    private void applyTabState(@Nonnull UICommandBuilder cmd) {
        for (Tab tab : TABS) {
            boolean active = activeTab.equals(tab.id());
            SettingsUiUtil.setTabActive(cmd, tab.buttonSel(), active);
            cmd.set(tab.sectionSel() + ".Visible", active);
        }
    }

    /** The preset dropdown stays hand-built (its entries are dynamic, unlike every fixed FieldSpec). */
    private void buildPresetRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull MobScalingConfig cfg) {
        rowLabel(cmd, "#MmoscalingPresetLabel", "mmomobscaling.ui.global.preset");
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
            cmd.set(WORLD_EMPTY_SEL + ".TextSpans", tr("mmomobscaling.ui.world.empty"));
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
            cmd.set(rowSel + " #Badge.TextSpans", tr(isOwner ? "mmomobscaling.ui.world.badge_override"
                    : "mmomobscaling.ui.world.badge_default"));
            ZigRichButton.text(cmd, rowSel + " #EditBtn", tr("mmomobscaling.ui.button.edit"));
            SettingsUiUtil.bindButton(events, rowSel + " #EditBtn", "editWorld", "Id", id);
            // Only an owner-dir FILE is removable (deleting it re-exposes a same-id jar/pack file).
            cmd.set(rowSel + " #RemoveBtn.Visible", isOwner);
            if (isOwner) {
                ZigRichButton.text(cmd, rowSel + " #RemoveBtn", tr("mmomobscaling.ui.button.remove"));
                SettingsUiUtil.bindButton(events, rowSel + " #RemoveBtn", "removeWorld", "Id", id);
            }
        }
    }

    /**
     * Clear + re-append + rebind every zone/biome floor row (the same {@code buildModelList} pattern as
     * the world list, on the same row template): every FOLDED mapping ({@link DifficultyConfig#all},
     * shipped overlaid by owner), zones before biomes, wildcards last, with its target and floor on the
     * sub line and a shipped/override badge. Only an owner-dir file is removable (deleting it puts the
     * shipped mapping of that id back).
     */
    private void buildFloorList(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        cmd.clear(FLOOR_LIST);
        List<DifficultyMapping> rows = new ArrayList<>();
        for (DifficultyMapping m : DifficultyConfig.getInstance().all().values()) {
            if (m != null) {
                rows.add(m);
            }
        }
        rows.sort(FLOOR_ORDER);
        Set<String> owned = DifficultyOwnerLayer.getInstance().ownerAuthoredIds();
        cmd.set(FLOOR_EMPTY_SEL + ".Visible", rows.isEmpty());
        if (rows.isEmpty()) {
            cmd.set(FLOOR_EMPTY_SEL + ".TextSpans", tr("mmomobscaling.ui.floor.empty"));
        }
        int i = 0;
        for (DifficultyMapping m : rows) {
            String rowSel = FLOOR_LIST + "[" + i++ + "]";
            cmd.append(FLOOR_LIST, ROW);
            cmd.set(rowSel + " #Title.Text", m.id());
            cmd.set(rowSel + " #Sub.TextSpans", tr("mmomobscaling.ui.floor.row_sub")
                    .param("type", targetTypeName(m.targetType()))
                    .param("target", m.targetId())
                    .param("floor", oneDecimal(m.floor())));
            boolean isOwner = owned.contains(OwnerFiles.idKey(m.id()));
            cmd.set(rowSel + " #Badge.Visible", true);
            cmd.set(rowSel + " #Badge.TextSpans", tr(isOwner ? "mmomobscaling.ui.world.badge_override"
                    : "mmomobscaling.ui.world.badge_default"));
            ZigRichButton.text(cmd, rowSel + " #EditBtn", tr("mmomobscaling.ui.button.edit"));
            SettingsUiUtil.bindButton(events, rowSel + " #EditBtn", "editFloor", "Id", m.id());
            cmd.set(rowSel + " #RemoveBtn.Visible", isOwner);
            if (isOwner) {
                ZigRichButton.text(cmd, rowSel + " #RemoveBtn", tr("mmomobscaling.ui.button.remove"));
                SettingsUiUtil.bindButton(events, rowSel + " #RemoveBtn", "removeFloor", "Id", m.id());
            }
        }
    }

    // ---------------------------------------------------------------------
    // Preview column (Global tab, right): the skeleton samples, the probe, the ladder, the MMO side
    // ---------------------------------------------------------------------

    /**
     * Build-time only: paint the titles and notes, append the fixed sample rows plus the probe's one
     * row, paint + bind the manual difficulty-probe field (a label + TextField OUTSIDE globalForm, its
     * own {@code "previewD"} event since it has no leaf path to persist), paint the MMO panel's
     * build-time lines ({@code ownPower} is the viewing admin's own power, read once here where the
     * store is in hand), then fill everything through {@link #refreshPreview}.
     */
    private void buildPreviewPanel(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nullable Double ownPower) {
        cmd.set(PREVIEW_TITLE_SEL + ".TextSpans", tr("mmomobscaling.ui.global.preview_title"));
        cmd.set(PREVIEW_NOTE_SEL + ".TextSpans", tr("mmomobscaling.ui.global.preview_note"));
        for (int i = 0; i < PREVIEW_SAMPLES; i++) {
            cmd.append(PREVIEW_LIST, PREVIEW_ROW);
        }
        rowLabel(cmd, PREVIEW_CUSTOM_LABEL_SEL, "mmomobscaling.ui.global.preview_custom");
        cmd.set(PREVIEW_CUSTOM_FIELD_SEL + ".Value", customPreviewInput);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, PREVIEW_CUSTOM_FIELD_SEL,
                com.hypixel.hytale.server.core.ui.builder.EventData.of("Action", "previewD")
                        .append("@Value", PREVIEW_CUSTOM_FIELD_SEL + ".Value"),
                false);
        cmd.append(PROBE_LIST, PREVIEW_ROW);
        cmd.set(LADDER_NOTE_SEL + ".TextSpans", tr("mmomobscaling.ui.global.ladder_note"));
        buildMmoPanel(cmd, ownPower);
        refreshPreview(cmd);
    }

    /**
     * Recompute + push everything on the preview column that depends on the Global form: the five
     * sample rows (a plain Skeleton at five evenly-spaced difficulties between the CURRENT Min/Max caps),
     * the probe row, the ladder and the MMO cap line, all from the CURRENT form values (a blank/invalid
     * field falls back to the live config - never the form's Save validation, this is a read-only
     * preview). The curve and the clamps are built through the same {@code MobScalingConfig.buildCurve}
     * / {@code buildClamps} the fold uses ({@link #buildPreviewCurve} / {@link #buildPreviewClamps}) and
     * every figure comes out of {@link ScalingPreview}, which runs the fold itself, so the column can
     * never disagree with a Save. A rarity or variant is not a sample row of its own: it reads the same
     * curve further along, which is what the ladder below the probe shows. Called from every Global-tab
     * path that can change the curve or the caps (field/press/saveGlobal/selectPreset); the probe's OWN
     * {@code "previewD"} event repaints just the probe row and the ladder via
     * {@link #refreshProbeAndLadder} (the five fixed rows do not depend on the typed value).
     */
    private void refreshPreview(@Nonnull UICommandBuilder cmd) {
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        double min = previewValue("minCap", cfg.getDifficultyMinCap());
        double max = previewValue("maxCap", cfg.getDifficultyMaxCap());
        if (max < min) {
            max = min; // an inverted cap pair is a footgun, same guard as MobScalingConfig.applyFold
        }
        MobScaleFold.DifficultyStatCurve curve = buildPreviewCurve();
        Baseline base = previewBaseline();
        for (int i = 1; i <= PREVIEW_SAMPLES; i++) {
            double d = min + (max - min) * i / (double) PREVIEW_SAMPLES;
            paintPreviewRow(cmd, PREVIEW_LIST + "[" + (i - 1) + "]", ScalingPreview.sample(curve, d), base);
        }
        refreshProbeAndLadder(cmd, curve, buildPreviewClamps(), base);
        refreshMmoCapLine(cmd, min, max);
    }

    /**
     * The probe row and the rarity ladder. The probe row shows only while the field parses to a
     * difficulty &gt;= 1, deliberately UNCLAMPED to the live Min/Max band (it probes an arbitrary
     * difficulty, not a sample of the operating range; the curve's own rails still bound the result). The
     * ladder is read at the probed difficulty or, with nothing typed, at the baseline floor from the form,
     * and its rows are cleared + re-appended each time (a preview row carries no button, so there is
     * nothing to rebind), so a tier a pack adds takes its place on the ladder without a page reopen.
     */
    private void refreshProbeAndLadder(@Nonnull UICommandBuilder cmd, @Nonnull MobScaleFold.DifficultyStatCurve curve,
            @Nonnull MobScaleFold.Clamps clamps, @Nonnull Baseline base) {
        Double probe = parseProbeDifficulty(customPreviewInput);
        cmd.set(PROBE_LIST + ".Visible", probe != null);
        if (probe != null) {
            paintPreviewRow(cmd, PROBE_LIST + "[0]", ScalingPreview.sample(curve, probe), base);
        }
        double at = probe != null ? probe
                : Math.max(1.0, previewValue("floor", MobScalingConfig.getInstance().getDifficultyFloor()));
        // The title's difficulty is the number every rung multiplies, so it is shown as read (one decimal),
        // never rounded to a whole: a rung's D must be the title times the tier's multiplier by hand.
        cmd.set(LADDER_TITLE_SEL + ".TextSpans",
                tr("mmomobscaling.ui.global.ladder_title").param("diff", oneDecimal(at)));
        cmd.clear(LADDER_LIST);
        int i = 0;
        for (Tier tier : ScalingPreview.ladder(curve, clamps, RarityConfig.getInstance().all().values(), at)) {
            String rowSel = LADDER_LIST + "[" + i++ + "]";
            cmd.append(LADDER_LIST, PREVIEW_ROW);
            cmd.set(rowSel + " #Line.TextSpans", ladderLine(tier, base));
            cmd.set(rowSel + " #Detail.TextSpans", detailLine(tier.sample()));
        }
    }

    /** One sample row: the difficulty and the three stamped factors on the line, kill time + rails on the detail. */
    private static void paintPreviewRow(@Nonnull UICommandBuilder cmd, @Nonnull String rowSel,
            @Nonnull Sample sample, @Nonnull Baseline base) {
        cmd.set(rowSel + " #Line.TextSpans", tr("mmomobscaling.ui.global.preview_row")
                .param("diff", Math.round(sample.difficulty()))
                .param("hp", multCell(sample.hp(), base.health()))
                .param("out", multCell(sample.out(), base.hit()))
                .param("in", Math.round(sample.in() * 100.0)));
        cmd.set(rowSel + " #Detail.TextSpans", detailLine(sample));
    }

    /**
     * A ladder rung's line: the tier's name in its own colour ({@code Plain} for the reference rung), its
     * difficulty multiplier, the difficulty the curve was read at, then the same cells a sample row shows.
     * The two leading numbers must multiply out by hand against the ladder title: the multiplier is shown
     * at the precision a rarity asset authors it ({@link #twoDecimals}, so {@code x1.35} and not
     * {@code x1.4}), and the rung's difficulty is the exact curve read at one decimal (a title of 30 at
     * {@code x1.35} reads {@code D40.5}), never rounded to a whole number that would disagree with the
     * product.
     */
    @Nonnull
    private static Message ladderLine(@Nonnull Tier tier, @Nonnull Baseline base) {
        Rarity rarity = tier.rarity();
        Message name = rarity == null ? tr("mmomobscaling.ui.global.ladder_plain")
                : tr(MobScalingTextUtil.rarityNameKey(rarity)).color(rarity.displayColor());
        Sample sample = tier.sample();
        return tr("mmomobscaling.ui.global.ladder_row")
                .param("name", name)
                .param("mult", twoDecimals(tier.difficultyMultiplier()))
                .param("diff", oneDecimal(sample.difficulty()))
                .param("hp", multCell(sample.hp(), base.health()))
                .param("out", multCell(sample.out(), base.hit()))
                .param("in", Math.round(sample.in() * 100.0));
    }

    /**
     * A multiplier cell: {@code x2.56}, or {@code x2.56 (179)} with the absolute figure (the role's base
     * rounded through the EXACT multiplier) when that base resolved. Both numbers are TYPED params the
     * client formats; the key carries the {@code x} and the parentheses. The multiplier shows two decimals
     * because the absolute beside it is its product: at one decimal a base of 70 through a factor of 2.56
     * read {@code x2.6 (179)}, and 70 times 2.6 is 182, so the numbers on one rung did not multiply out
     * against the rung above. At two decimals the shown product and the real one stay within one of
     * each other for any base under 100.
     */
    @Nonnull
    private static Message multCell(double mult, @Nonnull OptionalDouble base) {
        Message cell = tr(base.isPresent() ? "mmomobscaling.ui.global.preview_mult_abs"
                : "mmomobscaling.ui.global.preview_mult").param("mult", twoDecimals(mult));
        if (base.isPresent()) {
            cell.param("abs", Math.round(base.getAsDouble() * mult));
        }
        return cell;
    }

    /**
     * A row's detail line: the time to kill relative to an unscaled mob ({@code hp / in}, the number the
     * curve is derived to hold against a player's growth), plus an amber marker when one of the curve's
     * rails is what decided the row - the one case where turning a slope up changes nothing, which an
     * owner has no other way to see. The marker is joined at the top level, never nested as a param (a
     * composite param renders empty).
     */
    @Nonnull
    private static Message detailLine(@Nonnull Sample sample) {
        Message kill = tr("mmomobscaling.ui.global.preview_detail").param("ttk", oneDecimal(sample.timeToKill()));
        if (!sample.railed()) {
            return kill;
        }
        String railKey = sample.hpRailed() && sample.outRailed() ? "mmomobscaling.ui.global.preview_rail_both"
                : sample.hpRailed() ? "mmomobscaling.ui.global.preview_rail_hp"
                : "mmomobscaling.ui.global.preview_rail_out";
        return Message.join(kill, Message.raw("   "), tr(railKey).color(RAIL_COLOR));
    }

    /**
     * The sample role's resolved bases, either of which may be absent (the cell then shows the
     * multiplier alone): the health {@link RoleBaseHealthResolver} resolved (an observed spawn, else the
     * role template) and the last hit {@link RoleBaseHitResolver} saw a mob of that role land.
     */
    private record Baseline(@Nonnull OptionalDouble health, @Nonnull OptionalDouble hit) {
    }

    @Nonnull
    private static Baseline previewBaseline() {
        OptionalInt health = RoleBaseHealthResolver.baseMaxHealth(PREVIEW_ROLE_NAME);
        return new Baseline(health.isPresent() ? OptionalDouble.of(health.getAsInt()) : OptionalDouble.empty(),
                RoleBaseHitResolver.baseHit(PREVIEW_ROLE_NAME));
    }

    /**
     * The CURRENT Global-form difficulty stat curve alone (no cap/min/max clamp to a sample range) -
     * shared by the sample rows, the probe and the ladder, built through the one
     * {@code MobScalingConfig.buildCurve} the fold itself uses (so an out-of-range typed value previews
     * exactly how Save will fold it).
     */
    @Nonnull
    private MobScaleFold.DifficultyStatCurve buildPreviewCurve() {
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        return MobScalingConfig.buildCurve(
                previewValue("ehpPerPoint", cfg.getStatCurveEffectiveHpPerPoint()),
                previewValue("hpShare", cfg.getStatCurveVisibleHpShare()),
                previewValue("outScale", cfg.getStatCurveOutDamageScale()),
                previewValue("outShape", cfg.getStatCurveOutDamageShape()),
                previewValue("maxEhp", cfg.getStatCurveMaxEffectiveHpMult()),
                previewValue("maxOut", cfg.getStatCurveMaxOutDamageMult()));
    }

    /** The CURRENT Global-form safety clamps, built through the one {@code MobScalingConfig.buildClamps} the fold uses. */
    @Nonnull
    private MobScaleFold.Clamps buildPreviewClamps() {
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        return MobScalingConfig.buildClamps(
                previewValue("minHp", cfg.getClampMinHpMult()),
                previewValue("maxIn", cfg.getClampMaxInDamageMult()),
                previewValue("minOut", cfg.getClampMinOutDamageMult()),
                previewValue("minLoot", cfg.getClampMinLootMult()),
                previewValue("maxLoot", cfg.getClampMaxLootMult()));
    }

    /**
     * Build-time lines of the read-only MMO panel: the power floor and ceiling this mod can read through
     * the frozen API (or one line saying it cannot), the viewing admin's own power, and where the numbers
     * are edited. Every read is guarded ({@link MmoPowerBounds}): a missing or older MMO jar degrades to
     * "not available" instead of failing the page. This mod never widens that API and never writes an
     * MMO file.
     */
    private static void buildMmoPanel(@Nonnull UICommandBuilder cmd, @Nullable Double ownPower) {
        cmd.set(MMO_TITLE_SEL + ".TextSpans", tr("mmomobscaling.ui.mmo.header"));
        Double min = MmoPowerBounds.min();
        Double max = MmoPowerBounds.max();
        cmd.set(MMO_POWER_SEL + ".TextSpans", min != null && max != null
                ? tr("mmomobscaling.ui.mmo.power").param("min", oneDecimal(min)).param("max", oneDecimal(max))
                : tr("mmomobscaling.ui.mmo.unavailable"));
        cmd.set(MMO_OWN_POWER_SEL + ".Visible", ownPower != null);
        if (ownPower != null) {
            cmd.set(MMO_OWN_POWER_SEL + ".TextSpans",
                    tr("mmomobscaling.ui.mmo.own_power").param("power", Math.round(ownPower)));
        }
        cmd.set(MMO_WHERE_SEL + ".TextSpans", tr("mmomobscaling.ui.mmo.where"));
    }

    /**
     * The one MMO-panel line that depends on the form: how the CURRENT Min/Max difficulty caps sit against
     * the power floor and ceiling, the same comparison the boot audit's caps cross-check makes (a ceiling
     * above player power is the supported way to let the far zones out-scale a maxed group; one below it
     * folds every strong group onto one difficulty). Hidden when the bounds are unreadable.
     */
    private static void refreshMmoCapLine(@Nonnull UICommandBuilder cmd, double minCap, double maxCap) {
        Double powerMin = MmoPowerBounds.min();
        Double powerMax = MmoPowerBounds.max();
        if (powerMin == null || powerMax == null) {
            cmd.set(MMO_CAP_SEL + ".Visible", false);
            return;
        }
        String capKey = maxCap < powerMax - 1e-9 ? "mmomobscaling.ui.mmo.cap_below"
                : maxCap > powerMax + 1e-9 ? "mmomobscaling.ui.mmo.cap_above"
                : "mmomobscaling.ui.mmo.cap_equal";
        Message line = tr(capKey).param("cap", oneDecimal(maxCap)).param("max", oneDecimal(powerMax));
        if (Math.abs(minCap - powerMin) > 1e-9) {
            line = Message.join(line, Message.raw("\n"), tr("mmomobscaling.ui.mmo.floor_mismatch")
                    .param("min", oneDecimal(minCap)).param("powerMin", oneDecimal(powerMin)));
        }
        cmd.set(MMO_CAP_SEL + ".Visible", true);
        cmd.set(MMO_CAP_SEL + ".TextSpans", line);
    }

    /** A parseable, finite difficulty &gt;= 1, or {@code null} for blank/invalid/out-of-range input. */
    @Nullable
    private static Double parseProbeDifficulty(@Nonnull String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            double v = Double.parseDouble(trimmed);
            return (!Double.isNaN(v) && !Double.isInfinite(v) && v >= 1.0) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The Global form's CURRENT cached value for {@code fieldId}, parsed as a non-negative finite
     * double; {@code fallback} (the live config value) on blank, unparseable, or negative - the same
     * "blank/invalid falls back" rule {@code SettingsForm.collectLeaves} enforces for a NUMBER field, but
     * degrading to a fallback instead of blocking a save (this is a read-only preview, not a persist).
     */
    private double previewValue(@Nonnull String fieldId, double fallback) {
        String raw = globalForm.value(fieldId).trim();
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            double v = Double.parseDouble(raw);
            return (Double.isNaN(v) || Double.isInfinite(v) || v < 0) ? fallback : v;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * A display figure rounded to one decimal, bound as a TYPED double so the client formats it: a
     * difficulty, a kill time, a power bound, a floor. Not for a multiplier that sits beside its own
     * product ({@link #twoDecimals}).
     */
    private static double oneDecimal(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * A display multiplier rounded to two decimals, the precision the rarity assets author their
     * {@code DifficultyMultiplier} at, bound as a TYPED double. Used wherever the preview shows a factor
     * next to the number it produced (a rung's multiplier beside its difficulty, a cell's multiplier
     * beside its absolute), so an admin multiplying the shown numbers by hand lands on the shown result.
     */
    private static double twoDecimals(double v) {
        return Math.round(v * 100.0) / 100.0;
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
            case "previewD" -> handlePreviewDifficulty(data.value);
            case "selectPreset" -> handleSelectPreset(data.value);
            case "saveGlobal" -> handleSaveGlobal();
            case "saveZone" -> handleSaveZone();
            case "saveInspector" -> handleSaveInspector();
            case "editWorld" -> handleEditWorld(data.id);
            case "removeWorld" -> handleRemoveWorld(data.id);
            case "saveWorld" -> handleSaveWorld();
            case "clearWorld" -> handleClearWorld();
            case "editFloor" -> handleEditFloor(data.id);
            case "removeFloor" -> handleRemoveFloor(data.id);
            case "saveFloor" -> handleSaveFloor();
            case "clearFloor" -> handleClearFloor();
            default -> { }
        }
    }

    /**
     * A value-changed event: cache-only for zone/inspector/world (no packet - the control already
     * reflects the typed value). A GLOBAL-form field additionally sends a small PREVIEW-ONLY update (the
     * skeleton preview reads live global values every keystroke); form values themselves are never
     * re-pushed here, that would fight the user's typing.
     */
    private void handleField(@Nullable String fieldId, @Nullable String value) {
        if (fieldId == null || value == null) {
            return;
        }
        boolean isGlobal = globalForm.cache(fieldId, value);
        if (!isGlobal && !zoneForm.cache(fieldId, value) && !inspectorForm.cache(fieldId, value)
                && !worldForm.cache(fieldId, value)) {
            floorForm.cache(fieldId, value);
        }
        if (isGlobal) {
            UICommandBuilder cmd = new UICommandBuilder();
            refreshPreview(cmd);
            sendUpdate(cmd, null, false);
        }
    }

    /**
     * The manual difficulty-probe field's OWN {@code ValueChanged} event - cache the typed text then
     * repaint ONLY the probe row and the ladder (the five fixed sample rows never depend on it, so there
     * is nothing else to refresh here). Lives outside {@code globalForm} entirely: no leaf path, nothing
     * to persist, never a save-blocking validation error - just show or hide a row and move the ladder.
     */
    private void handlePreviewDifficulty(@Nullable String value) {
        this.customPreviewInput = value == null ? "" : value;
        UICommandBuilder cmd = new UICommandBuilder();
        refreshProbeAndLadder(cmd, buildPreviewCurve(), buildPreviewClamps(), previewBaseline());
        sendUpdate(cmd, null, false);
    }

    private void handleTab(@Nullable String tab) {
        if (tab == null || tab.isBlank()) {
            return;
        }
        this.activeTab = tab;
        UICommandBuilder cmd = new UICommandBuilder();
        applyTabState(cmd);
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
        if (def.form() == globalForm) {
            refreshPreview(cmd);
        }
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
            err("mmomobscaling.ui.status.unknown_preset");
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
        refreshPreview(cmd);
        refreshHuds();
        ok("mmomobscaling.ui.status.saved");
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
            err("mmomobscaling.ui.status.invalid_caps");
            finish(cmd);
            return;
        }
        MobScalingOwnerWriter.saveLeaves(leaves);
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        reseedGlobalFromConfig(cfg); // reflect fold-clamped values (e.g. MaxCap >= MinCap) back
        globalForm.applyValues(cmd, GLOBAL_FORM_SEL);
        refreshPreview(cmd);
        ok("mmomobscaling.ui.status.saved");
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
        // The save is the GLOBAL corner; a world authoring its own keeps it, so each online HUD
        // re-resolves the corner its own world configures.
        ZoneDifficultyHud.refreshPositionForAllOnline();
        reseedZoneFromConfig(cfg);
        zoneForm.applyValues(cmd, ZONE_FORM_SEL);
        ok("mmomobscaling.ui.status.saved");
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
        MobInspectorHud.refreshPositionForAllOnline();
        reseedInspectorFromConfig(cfg);
        inspectorForm.applyValues(cmd, INSPECTOR_FORM_SEL);
        ok("mmomobscaling.ui.status.saved");
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
        refreshWorldHints(cmd, id);
        sendUpdate(cmd, null, false);
    }

    private void handleRemoveWorld(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        MobScalingOwnerWriter.deleteWorldFile(id);
        refreshHuds(); // the deleted file may have parked a HUD in its own corner
        seedWorldForm("", null, null); // never leave the editor pointing at a deleted file
        UICommandBuilder cmd = new UICommandBuilder();
        worldForm.applyValues(cmd, WORLD_FORM_SEL);
        UIEventBuilder events = new UIEventBuilder();
        buildWorldList(cmd, events);
        ok("mmomobscaling.ui.status.world_deleted");
        finish(cmd, events);
    }

    /**
     * Id derives from the World id field, falling back to the first Match pattern, then the first
     * GameplayConfig key (all sanitized); at least one of the three must be non-blank (a blank selector
     * with an id is legal - it authors a pool-only BASE file). A self-{@code Parent} is rejected. On
     * success the id field reflects the final id and the world list is rebuilt in the same update.
     */
    private void handleSaveWorld() {
        UICommandBuilder cmd = new UICommandBuilder();
        String rawId = worldForm.value(F_WORLD_ID).trim();
        String firstTarget = firstCsvEntry(worldForm.value(F_WORLD_MATCH));
        if (firstTarget == null) {
            firstTarget = firstCsvEntry(worldForm.value(F_WORLD_CONFIGS));
        }
        if (rawId.isEmpty() && firstTarget == null) {
            err("mmomobscaling.ui.status.id_or_match_required");
            finish(cmd);
            return;
        }
        String id = OwnerFiles.sanitizeFileId(rawId.isEmpty() ? firstTarget : rawId);
        String rawParent = worldForm.value(F_WORLD_PARENT).trim();
        // Compare through the SAME sanitizer both sides go through for the filename, not the raw
        // typed text - otherwise worldId "a b" + Parent "a b" (both sanitize to "a_b") slips past.
        if (!rawParent.isEmpty() && OwnerFiles.sanitizeFileId(rawParent).equalsIgnoreCase(id)) {
            err("mmomobscaling.ui.status.invalid_parent");
            finish(cmd);
            return;
        }
        FormResult result = worldForm.collectLeaves(true);
        if (!result.ok()) {
            emitInvalidField(result);
            finish(cmd);
            return;
        }
        // The three Where leaves are CSV fields, so each collects as the LIST the schema wants, and an
        // empty one collects as a null leaf (a removal) - three blank selector fields are exactly "no
        // Where" = a pool-only base. Nothing extra to enforce here.
        Map<String, Object> leaves = new LinkedHashMap<>(result.leaves());
        leaves.remove(ID_LEAF); // the sentinel: never a real codec key on the world file
        // The two name-key prefixes are TEXT fields where an EMPTY string is a value (no prefix) and it
        // seeds as the same blank an unauthored prefix does, so a file that deliberately authors an
        // empty prefix keeps it across a Save instead of falling back to the inherited one.
        WorldFormLeaves.keepAuthoredEmptyText(leaves, WorldSettingsConfig.getInstance().authoredById(id));
        if (MobScalingOwnerWriter.saveWorldFile(id, leaves)) {
            refreshHuds(); // a per-world HUD corner is one of the leaves this may have changed
            worldForm.seedValue(F_WORLD_ID, id);
            worldForm.applyValue(cmd, WORLD_FORM_SEL, F_WORLD_ID);
            refreshWorldHints(cmd, id);
            UIEventBuilder events = new UIEventBuilder();
            buildWorldList(cmd, events);
            ok("mmomobscaling.ui.status.saved");
            finish(cmd, events);
        } else {
            err("mmomobscaling.ui.status.save_failed");
            finish(cmd);
        }
    }

    private void handleClearWorld() {
        seedWorldForm("", null, null);
        UICommandBuilder cmd = new UICommandBuilder();
        worldForm.applyValues(cmd, WORLD_FORM_SEL);
        refreshWorldHints(cmd, ""); // blank id: every hint resets to static-only
        clearStatus();
        finish(cmd);
    }

    // ---------------------------------------------------------------------
    // Floors tab (zone / biome difficulty floors, one owner file per mapping)
    // ---------------------------------------------------------------------

    /** Seed the floor editor from the owner file's OWN leaves (see the class javadoc) + the id; no status change. */
    private void handleEditFloor(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        seedFloorForm(id, DifficultyOwnerLayer.getInstance().authoredById(id));
        UICommandBuilder cmd = new UICommandBuilder();
        floorForm.applyValues(cmd, FLOOR_FORM_SEL);
        refreshFloorHints(cmd, id);
        sendUpdate(cmd, null, false);
    }

    /**
     * Remove the owner file behind a floor row. The writer answers whether a file actually went (no
     * owner dir, no file under that id or a filesystem refusal all answer false, and none of those
     * refolds), so the status says what happened: only a real removal clears the editor and rebuilds the
     * list; a refusal leaves both as they were and reports it.
     */
    private void handleRemoveFloor(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        UICommandBuilder cmd = new UICommandBuilder();
        if (!MobScalingOwnerWriter.deleteDifficultyMapping(id)) {
            err("mmomobscaling.ui.status.floor_delete_failed");
            finish(cmd);
            return;
        }
        seedFloorForm("", null); // never leave the editor pointing at a deleted file
        floorForm.applyValues(cmd, FLOOR_FORM_SEL);
        refreshFloorHints(cmd, "");
        UIEventBuilder events = new UIEventBuilder();
        buildFloorList(cmd, events);
        ok("mmomobscaling.ui.status.floor_deleted");
        finish(cmd, events);
    }

    /**
     * Id derives from the file-name field, falling back to the zone/biome name (sanitized; the {@code *}
     * wildcard cannot name a file, so it needs a file name). A file named after a SHIPPED mapping
     * overlays it per leaf, so it may carry only the leaves the admin authored (a blank field / Inherit
     * collects as a null leaf and inherits the shipped value); a brand-new id has nothing to inherit
     * from and is refused unless all three leaves are set, which is the same rule the owner layer
     * applies to a hand-written file, caught here before a file that would only warn at fold is written.
     * On success the id field reflects the final id and the floor list is rebuilt in the same update.
     */
    private void handleSaveFloor() {
        UICommandBuilder cmd = new UICommandBuilder();
        String rawId = floorForm.value(F_FLOOR_ID).trim();
        String rawTarget = floorForm.value(F_FLOOR_TARGET).trim();
        if (rawId.isEmpty() && (rawTarget.isEmpty() || "*".equals(rawTarget))) {
            err("mmomobscaling.ui.status.floor_id_required");
            finish(cmd);
            return;
        }
        String id = OwnerFiles.sanitizeFileId(rawId.isEmpty() ? rawTarget : rawId);
        FormResult result = floorForm.collectLeaves(true);
        if (!result.ok()) {
            emitInvalidField(result);
            finish(cmd);
            return;
        }
        Map<String, Object> leaves = new LinkedHashMap<>(result.leaves());
        leaves.remove(ID_LEAF); // the sentinel: never a real codec key on the mapping file
        boolean overlaysShipped = DifficultyConfig.getInstance().packMapping(id) != null;
        if (!overlaysShipped && (leaves.get(LEAF_TARGET_TYPE) == null || leaves.get(LEAF_TARGET_ID) == null
                || leaves.get(LEAF_FLOOR) == null)) {
            err("mmomobscaling.ui.status.floor_incomplete");
            finish(cmd);
            return;
        }
        if (MobScalingOwnerWriter.saveDifficultyMapping(id, leaves)) {
            floorForm.seedValue(F_FLOOR_ID, id);
            floorForm.applyValue(cmd, FLOOR_FORM_SEL, F_FLOOR_ID);
            refreshFloorHints(cmd, id);
            UIEventBuilder events = new UIEventBuilder();
            buildFloorList(cmd, events);
            ok("mmomobscaling.ui.status.saved");
            finish(cmd, events);
        } else {
            err("mmomobscaling.ui.status.floor_save_failed");
            finish(cmd);
        }
    }

    private void handleClearFloor() {
        seedFloorForm("", null);
        UICommandBuilder cmd = new UICommandBuilder();
        floorForm.applyValues(cmd, FLOOR_FORM_SEL);
        refreshFloorHints(cmd, ""); // blank id: every hint resets to static-only
        clearStatus();
        finish(cmd);
    }

    /**
     * Seed the floor editor from an (id, owner file's own leaves) pair. {@code authored == null} (a
     * brand-new / cleared editor, or a shipped mapping no owner file overlays yet) seeds every leaf
     * blank/Inherit, and the hints then say what each blank field inherits from the shipped mapping.
     */
    private void seedFloorForm(@Nonnull String id, @Nullable DifficultyMappingAsset authored) {
        Map<String, String> seed = new LinkedHashMap<>();
        seed.put(F_FLOOR_ID, id);
        seed.put(F_FLOOR_TYPE, targetTypeWord(authored == null ? null : authored.getTargetType()));
        seed.put(F_FLOOR_TARGET, textOrBlank(authored == null ? null : authored.getTargetId()));
        seed.put(F_FLOOR_VALUE, numOrBlank(authored == null ? null : authored.getFloor()));
        floorForm.seed(seed);
    }

    /**
     * Recompute + push every floor-editor {@code #Hint}: the spec's static help text alone for an
     * authored field, or that text PLUS a computed "Inherits: X" line for a field still blank/Inherit
     * while {@code id} names a SHIPPED mapping ({@link DifficultyConfig#packMapping}, the layer an owner
     * file overlays). A blank id, or one nothing ships, shows static-only: there is nothing to inherit.
     */
    private void refreshFloorHints(@Nonnull UICommandBuilder cmd, @Nonnull String id) {
        DifficultyMapping shipped = id.isBlank() ? null : DifficultyConfig.getInstance().packMapping(id);
        for (FieldSpec spec : FLOOR_SPECS) {
            String hintKey = spec.hintKey();
            if (hintKey == null) {
                continue; // the NOTE, or a spec authored with no hint
            }
            String fieldId = spec.id();
            String current = floorForm.value(fieldId).trim();
            boolean blank = current.isEmpty() || "inherit".equals(current);
            Message inheritsValue = shipped == null ? null : inheritedFloorLeaf(fieldId, shipped);
            Message hint = tr(hintKey);
            if (blank && inheritsValue != null) {
                hint = Message.join(hint, Message.raw("\n"), inheritsSegment(inheritsValue));
            }
            floorForm.applyHint(cmd, FLOOR_FORM_SEL, fieldId, hint);
        }
    }

    /**
     * The shipped mapping's value for one floor-editor field, as a nested {@link Message}: the localized
     * type word, the literal target name, or the floor bound as a typed number; {@code null} for the
     * file-name field, which inherits nothing.
     */
    @Nullable
    private static Message inheritedFloorLeaf(@Nonnull String fieldId, @Nonnull DifficultyMapping shipped) {
        return switch (fieldId) {
            case F_FLOOR_TYPE -> targetTypeName(shipped.targetType());
            case F_FLOOR_TARGET -> Message.raw(shipped.targetId());
            case F_FLOOR_VALUE -> tr("mmomobscaling.ui.number").param("n", oneDecimal(shipped.floor()));
            default -> null;
        };
    }

    /** The localized word for a mapping's target type (the Floors list's sub line and the inherits hint). */
    @Nonnull
    private static Message targetTypeName(@Nonnull DifficultyMapping.TargetType type) {
        return tr(targetTypeLabelKey(codecWord(type)));
    }

    /** The codec's own word for a target type ({@code Zone} / {@code Biome}), the dropdown value a save writes. */
    @Nonnull
    private static String codecWord(@Nonnull DifficultyMapping.TargetType type) {
        return type == DifficultyMapping.TargetType.ZONE ? WORD_ZONE : WORD_BIOME;
    }

    /** The lang key behind one floor-type dropdown value: the two codec words, else the Inherit pseudo-value. */
    @Nonnull
    private static String targetTypeLabelKey(@Nonnull String value) {
        return switch (value) {
            case WORD_ZONE -> "mmomobscaling.ui.floor.type_zone";
            case WORD_BIOME -> "mmomobscaling.ui.floor.type_biome";
            default -> "mmomobscaling.ui.floor.type_inherit";
        };
    }

    /**
     * Build-time: relabel the floor editor's type dropdown with the localized words. The shared form
     * paints a DROPDOWN's entries as literal label == value pairs, so on its own the row would show the
     * codec words {@code Zone} / {@code Biome} and the {@code inherit} pseudo-value untranslated, one
     * panel away from the same words localized on the Floors list. Only the labels change: the values
     * stay the codec's own (what {@code collectLeaves} writes and {@link #seedFloorForm} seeds), and a
     * later {@code applyValues} touches {@code .Value} alone, so the entries survive every partial update.
     */
    private void localizeFloorTypeEntries(@Nonnull UICommandBuilder cmd) {
        for (int i = 0; i < FLOOR_SPECS.size(); i++) {
            if (!F_FLOOR_TYPE.equals(FLOOR_SPECS.get(i).id())) {
                continue;
            }
            List<DropdownEntryInfo> entries = new ArrayList<>(TARGET_TYPES_INHERIT.length);
            for (String value : TARGET_TYPES_INHERIT) {
                entries.add(new DropdownEntryInfo(LocalizableString.fromMessageId(targetTypeLabelKey(value)), value));
            }
            SettingsUiUtil.populate(cmd, FLOOR_FORM_SEL + "[" + i + "] #Dropdown", entries, floorForm.value(F_FLOOR_TYPE));
            return;
        }
    }

    /**
     * An authored {@code TargetType} string as the floor editor's dropdown value: the codec's own casing
     * ({@code Zone} / {@code Biome}) whatever case the file used, or {@code inherit} when absent or
     * unknown (an unknown word is what the owner layer skips with a warning; the editor shows Inherit
     * rather than a value the dropdown cannot display).
     */
    @Nonnull
    private static String targetTypeWord(@Nullable String raw) {
        DifficultyMapping.TargetType type = DifficultyMapping.TargetType.parse(raw);
        return type == null ? WORD_INHERIT : codecWord(type);
    }

    /** The first non-blank entry of a comma-separated field, trimmed; {@code null} when there is none. */
    @Nullable
    private static String firstCsvEntry(@Nonnull String raw) {
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Seeding (config / world settings -> the form value caches)
    // ---------------------------------------------------------------------

    private void reseedGlobalFromConfig(@Nonnull MobScalingConfig cfg) {
        Map<String, String> seed = new LinkedHashMap<>();
        seed.put("enabled", onOff(cfg.isEnabled()));
        seed.put("floor", num(cfg.getDifficultyFloor()));
        seed.put("minCap", num(cfg.getDifficultyMinCap()));
        seed.put("maxCap", num(cfg.getDifficultyMaxCap()));
        seed.put("ehpPerPoint", num(cfg.getStatCurveEffectiveHpPerPoint()));
        seed.put("hpShare", num(cfg.getStatCurveVisibleHpShare()));
        seed.put("outScale", num(cfg.getStatCurveOutDamageScale()));
        seed.put("outShape", num(cfg.getStatCurveOutDamageShape()));
        seed.put("maxEhp", num(cfg.getStatCurveMaxEffectiveHpMult()));
        seed.put("maxOut", num(cfg.getStatCurveMaxOutDamageMult()));
        seed.put("minHp", num(cfg.getClampMinHpMult()));
        seed.put("maxIn", num(cfg.getClampMaxInDamageMult()));
        seed.put("minOut", num(cfg.getClampMinOutDamageMult()));
        seed.put("minLoot", num(cfg.getClampMinLootMult()));
        seed.put("maxLoot", num(cfg.getClampMaxLootMult()));
        seed.put("rarity", num(cfg.getRaritySpawnChance()));
        seed.put("escEnabled", onOff(cfg.isDistanceEscalationEnabled()));
        seed.put("escStart", num(cfg.getEscalationStartDistanceBlocks()));
        seed.put("escBlocks", num(cfg.getEscalationBlocksPerPoint()));
        seed.put("escMaxBonus", num(cfg.getEscalationMaxBonus()));
        seed.put("escRarity", num(cfg.getEscalationRarityChancePerPoint()));
        seed.put("playerScaling", onOff(cfg.isPlayerScalingEnabled()));
        seed.put("playerRing", num(cfg.getPlayerScalingStartRingBlocks()));
        seed.put("aggregation", blankToFirst(cfg.getOpenWorldAggregationMode(), AGGREGATION_MODES));
        seed.put("regionSize", String.valueOf(cfg.getRegionSizeChunks()));
        seed.put("bandWidth", num(cfg.getGroupDeltaBandWidth()));
        seed.put("onlyRaise", onOff(cfg.isOnlyRaiseDifficulty()));
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
        Clamps clampsGroup = diff == null ? null : diff.getClamps();
        OpenWorld ow = ws == null ? null : ws.getOpenWorld();
        Hud zoneHud = ws == null ? null : ws.getZoneHud();
        InspectorHud inspHud = ws == null ? null : ws.getInspectorHud();
        WorldSettings.Pool pool = ws == null ? null : ws.getPool();
        WorldSettings.IdGate rarities = pool == null ? null : pool.getRarities();
        WorldSettings.VariantGate variants = pool == null ? null : pool.getVariants();
        WorldSettings.AffixGate affixes = pool == null ? null : pool.getAffixes();

        WorldSelector where = ws == null ? null : ws.getWhere();

        Map<String, String> seed = new LinkedHashMap<>();
        seed.put(F_WORLD_ID, id);
        seed.put(F_WORLD_MATCH, csvOrBlank(where == null ? null : where.getMatch()));
        seed.put(F_WORLD_CONFIGS, csvOrBlank(where == null ? null : where.getGameplayConfig()));
        seed.put(F_WORLD_EXCLUDES, csvOrBlank(where == null ? null : where.getExcludeMatch()));
        seed.put(F_WORLD_PARENT, textOrBlank(parent));
        seed.put("wEnabled", triOrInherit(ws == null ? null : ws.getEnabled()));
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
        seed.put("wPlayerRing", numOrBlank(ow == null ? null : ow.getPlayerScalingStartRingBlocks()));
        seed.put("wAggregation", dropdownOrInherit(ow == null ? null : ow.getAggregationMode()));
        seed.put("wRegionSize", intOrBlank(ow == null ? null : ow.getRegionSizeChunks()));
        seed.put("wBandWidth", numOrBlank(ow == null ? null : ow.getGroupDeltaBandWidth()));
        seed.put("wOnlyRaise", triOrInherit(ow == null ? null : ow.getOnlyRaiseDifficulty()));
        seed.put("wEhpPerPoint", numOrBlank(curve == null ? null : curve.getEffectiveHpPerPoint()));
        seed.put("wHpShare", numOrBlank(curve == null ? null : curve.getVisibleHpShare()));
        seed.put("wOutScale", numOrBlank(curve == null ? null : curve.getOutDamageScale()));
        seed.put("wOutShape", numOrBlank(curve == null ? null : curve.getOutDamageShape()));
        seed.put("wMaxEhp", numOrBlank(curve == null ? null : curve.getMaxEffectiveHpMult()));
        seed.put("wMaxOut", numOrBlank(curve == null ? null : curve.getMaxOutDamageMult()));
        seed.put("wMinHp", numOrBlank(clampsGroup == null ? null : clampsGroup.getMinHpMult()));
        seed.put("wMaxIn", numOrBlank(clampsGroup == null ? null : clampsGroup.getMaxInDamageMult()));
        seed.put("wMinOut", numOrBlank(clampsGroup == null ? null : clampsGroup.getMinOutDamageMult()));
        seed.put("wMinLoot", numOrBlank(clampsGroup == null ? null : clampsGroup.getMinLootMult()));
        seed.put("wMaxLoot", numOrBlank(clampsGroup == null ? null : clampsGroup.getMaxLootMult()));
        seed.put("wRarAllow", csvOrBlank(rarities == null ? null : rarities.getAllow()));
        seed.put("wRarDeny", csvOrBlank(rarities == null ? null : rarities.getDeny()));
        seed.put("wVarAllow", csvOrBlank(variants == null ? null : variants.getAllow()));
        seed.put("wVarDeny", csvOrBlank(variants == null ? null : variants.getDeny()));
        seed.put("wVarChance", numOrBlank(variants == null ? null : variants.getChanceMultiplier()));
        seed.put("wAffAllow", csvOrBlank(affixes == null ? null : affixes.getAllow()));
        seed.put("wAffDeny", csvOrBlank(affixes == null ? null : affixes.getDeny()));
        seed.put("wAffSlots", intOrBlank(affixes == null ? null : affixes.getExtraSlots()));
        seed.put("wZoneHud", triOrInherit(zoneHud == null ? null : zoneHud.getEnabled()));
        seed.put("wZoneShowLoc", triOrInherit(zoneHud == null ? null : zoneHud.getShowLocationName()));
        seed.put("wZonePos", dropdownOrInherit(zoneHud == null ? null : zoneHud.getPosition()));
        seed.put("wZoneOffX", intOrBlank(zoneHud == null ? null : zoneHud.getOffsetX()));
        seed.put("wZoneOffY", intOrBlank(zoneHud == null ? null : zoneHud.getOffsetY()));
        seed.put("wZonePrefix", textOrBlank(zoneHud == null ? null : zoneHud.getZoneNameKeyPrefix()));
        seed.put("wBiomePrefix", textOrBlank(zoneHud == null ? null : zoneHud.getBiomeNameKeyPrefix()));
        seed.put("wInspHud", triOrInherit(inspHud == null ? null : inspHud.getEnabled()));
        seed.put("wInspPortrait", triOrInherit(inspHud == null ? null : inspHud.getPortraitEnabled()));
        seed.put("wInspPos", dropdownOrInherit(inspHud == null ? null : inspHud.getPosition()));
        seed.put("wInspOffX", intOrBlank(inspHud == null ? null : inspHud.getOffsetX()));
        seed.put("wInspOffY", intOrBlank(inspHud == null ? null : inspHud.getOffsetY()));
        seed.put("wInspRange", numOrBlank(inspHud == null ? null : inspHud.getRangeBlocks()));
        worldForm.seed(seed);
    }

    /**
     * Recompute + push every world-form {@code #Hint}: the spec's static help text alone for a field the
     * admin has authored, or that static text PLUS a computed "Inherits: X" line for a field still
     * blank/Inherit. {@code id} blank (a brand-new/cleared editor) always shows static-only - there is
     * nothing yet to resolve an effective value against. Safe to call after {@link #seedWorldForm} (edit,
     * or a successful save re-pointing at the just-written id) or with a blank id ({@link #handleClearWorld}).
     */
    private void refreshWorldHints(@Nonnull UICommandBuilder cmd, @Nonnull String id) {
        Map<String, Message> inherited = id.isBlank() ? Map.of() : effectiveWorldDisplayValues(id);
        for (FieldSpec spec : WORLD_SPECS) {
            String hintKey = spec.hintKey();
            if (hintKey == null) {
                continue; // a HEADER/NOTE, or a spec authored with no hint
            }
            String fieldId = spec.id();
            String current = worldForm.value(fieldId).trim();
            boolean blank = current.isEmpty() || "inherit".equals(current);
            Message inheritsValue = inherited.get(fieldId);
            Message hint = tr(hintKey);
            if (blank && inheritsValue != null) {
                hint = Message.join(hint, Message.raw("\n"), inheritsSegment(inheritsValue));
            }
            worldForm.applyHint(cmd, WORLD_FORM_SEL, fieldId, hint);
        }
    }

    /**
     * The "Inherits: {value}" line styled WHITE + BOLD end to end - both the localized label and the
     * substituted value (round-3, per maintainer request). {@link Message#color}/{@link Message#bold}
     * mutate their receiver IN PLACE and return it, so both the wrapping frame message ({@code
     * mmomobscaling.ui.world.inherits}) AND the nested {@code value} param need the call: a span with no
     * explicit style of its own falls back to the Hint label's own muted default (see
     * {@code ZigFormFieldRow.ui}'s {@code #Hint} style), not its parent message node's color, so styling
     * only the outer frame would leave the substituted value unstyled. Safe to mutate {@code
     * inheritsValue} directly - {@link Message#translation}/{@link Message#raw} (and every
     * {@code effectiveWorldDisplayValues} helper that builds one) return a FRESH instance per call, never
     * shared/cached, so this can never leak style onto some other render of the same key. The static
     * hint segment above this stays default-styled (its own color is being lightened in
     * ziggfreed-common's shared row templates by a parallel change - not touched here).
     */
    @Nonnull
    private static Message inheritsSegment(@Nonnull Message inheritsValue) {
        Message value = inheritsValue.color(INHERITS_COLOR).bold(true);
        return tr("mmomobscaling.ui.world.inherits").param("value", value).color(INHERITS_COLOR).bold(true);
    }

    /**
     * The EFFECTIVE (display-formatted) value of every world-editor leaf for {@code id}, as a nested
     * {@link Message} (never a raw literal) so on/off and allow-all/deny-none resolve through the
     * EXISTING localized labels (never hardcoded English): the {@code Parent}-merged
     * {@link WorldSettingsConfig#effectiveById} leaf where authored, else the GLOBAL live
     * {@link MobScalingConfig} value (a Pool gate's global is allow-all / an empty deny list / neutral
     * scale / zero extra slots; a per-world HUD tri-state's global is the zone/inspector enabled flag). A
     * numeric/technical value (a number, a dropdown mode, a comma-joined id list) is NOT translatable
     * prose, so it wraps as a literal {@link Message#raw}. Carries no entry for the five world-identity
     * fields ({@code worldId}, the three {@code Where} fields {@code worldMatch}/{@code worldConfigs}/
     * {@code worldExcludes}, and {@code worldParent}) - they have no global fallback to inherit.
     */
    @Nonnull
    private static Map<String, Message> effectiveWorldDisplayValues(@Nonnull String id) {
        WorldSettings eff = WorldSettingsConfig.getInstance().effectiveById(id);
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        Difficulty diff = eff == null ? null : eff.getDifficulty();
        DistanceEscalation esc = diff == null ? null : diff.getDistanceEscalation();
        StatCurve curve = diff == null ? null : diff.getStatCurve();
        Clamps clampsGroup = diff == null ? null : diff.getClamps();
        OpenWorld ow = eff == null ? null : eff.getOpenWorld();
        Hud zoneHud = eff == null ? null : eff.getZoneHud();
        InspectorHud inspHud = eff == null ? null : eff.getInspectorHud();
        WorldSettings.Pool pool = eff == null ? null : eff.getPool();
        WorldSettings.IdGate rarities = pool == null ? null : pool.getRarities();
        WorldSettings.VariantGate variants = pool == null ? null : pool.getVariants();
        WorldSettings.AffixGate affixes = pool == null ? null : pool.getAffixes();

        Map<String, Message> m = new LinkedHashMap<>();
        m.put("wEnabled", onOffDisplay(
                eff != null && eff.getEnabled() != null ? eff.getEnabled() : cfg.isWorldScalingEnabled()));
        m.put("wRarity", Message.raw(num(eff != null && eff.getRaritySpawnChance() != null
                ? eff.getRaritySpawnChance() : cfg.getRaritySpawnChance())));
        m.put("wFloor", Message.raw(
                num(diff != null && diff.getFloor() != null ? diff.getFloor() : cfg.getDifficultyFloor())));
        m.put("wMinCap", Message.raw(
                num(diff != null && diff.getMinCap() != null ? diff.getMinCap() : cfg.getDifficultyMinCap())));
        m.put("wMaxCap", Message.raw(
                num(diff != null && diff.getMaxCap() != null ? diff.getMaxCap() : cfg.getDifficultyMaxCap())));
        m.put("wEscEnabled", onOffDisplay(
                esc != null && esc.getEnabled() != null ? esc.getEnabled() : cfg.isDistanceEscalationEnabled()));
        m.put("wEscStart", Message.raw(num(esc != null && esc.getStartDistanceBlocks() != null
                ? esc.getStartDistanceBlocks() : cfg.getEscalationStartDistanceBlocks())));
        m.put("wEscBlocks", Message.raw(num(esc != null && esc.getBlocksPerPoint() != null
                ? esc.getBlocksPerPoint() : cfg.getEscalationBlocksPerPoint())));
        m.put("wEscMaxBonus", Message.raw(num(esc != null && esc.getMaxBonus() != null
                ? esc.getMaxBonus() : cfg.getEscalationMaxBonus())));
        m.put("wEscRarity", Message.raw(num(esc != null && esc.getRarityChancePerPoint() != null
                ? esc.getRarityChancePerPoint() : cfg.getEscalationRarityChancePerPoint())));
        m.put("wPlayerScaling", onOffDisplay(ow != null && ow.getPlayerScalingEnabled() != null
                ? ow.getPlayerScalingEnabled() : cfg.isPlayerScalingEnabled()));
        m.put("wPlayerRing", Message.raw(num(ow != null && ow.getPlayerScalingStartRingBlocks() != null
                ? ow.getPlayerScalingStartRingBlocks() : cfg.getPlayerScalingStartRingBlocks())));
        m.put("wAggregation", Message.raw(
                ow != null && ow.getAggregationMode() != null && !ow.getAggregationMode().isBlank()
                        ? ow.getAggregationMode() : cfg.getOpenWorldAggregationMode()));
        m.put("wBandWidth", Message.raw(num(ow != null && ow.getGroupDeltaBandWidth() != null
                ? ow.getGroupDeltaBandWidth() : cfg.getGroupDeltaBandWidth())));
        m.put("wRegionSize", Message.raw(String.valueOf(ow != null && ow.getRegionSizeChunks() != null
                ? ow.getRegionSizeChunks() : cfg.getRegionSizeChunks())));
        m.put("wOnlyRaise", onOffDisplay(ow != null && ow.getOnlyRaiseDifficulty() != null
                ? ow.getOnlyRaiseDifficulty() : cfg.isOnlyRaiseDifficulty()));
        m.put("wEhpPerPoint", Message.raw(num(curve != null && curve.getEffectiveHpPerPoint() != null
                ? curve.getEffectiveHpPerPoint() : cfg.getStatCurveEffectiveHpPerPoint())));
        m.put("wHpShare", Message.raw(num(curve != null && curve.getVisibleHpShare() != null
                ? curve.getVisibleHpShare() : cfg.getStatCurveVisibleHpShare())));
        m.put("wOutScale", Message.raw(num(curve != null && curve.getOutDamageScale() != null
                ? curve.getOutDamageScale() : cfg.getStatCurveOutDamageScale())));
        m.put("wOutShape", Message.raw(num(curve != null && curve.getOutDamageShape() != null
                ? curve.getOutDamageShape() : cfg.getStatCurveOutDamageShape())));
        m.put("wMaxEhp", Message.raw(num(curve != null && curve.getMaxEffectiveHpMult() != null
                ? curve.getMaxEffectiveHpMult() : cfg.getStatCurveMaxEffectiveHpMult())));
        m.put("wMaxOut", Message.raw(num(curve != null && curve.getMaxOutDamageMult() != null
                ? curve.getMaxOutDamageMult() : cfg.getStatCurveMaxOutDamageMult())));
        m.put("wMinHp", Message.raw(num(clampsGroup != null && clampsGroup.getMinHpMult() != null
                ? clampsGroup.getMinHpMult() : cfg.getClampMinHpMult())));
        m.put("wMaxIn", Message.raw(num(clampsGroup != null && clampsGroup.getMaxInDamageMult() != null
                ? clampsGroup.getMaxInDamageMult() : cfg.getClampMaxInDamageMult())));
        m.put("wMinOut", Message.raw(num(clampsGroup != null && clampsGroup.getMinOutDamageMult() != null
                ? clampsGroup.getMinOutDamageMult() : cfg.getClampMinOutDamageMult())));
        m.put("wMinLoot", Message.raw(num(clampsGroup != null && clampsGroup.getMinLootMult() != null
                ? clampsGroup.getMinLootMult() : cfg.getClampMinLootMult())));
        m.put("wMaxLoot", Message.raw(num(clampsGroup != null && clampsGroup.getMaxLootMult() != null
                ? clampsGroup.getMaxLootMult() : cfg.getClampMaxLootMult())));
        m.put("wRarAllow", csvOrFallback(rarities == null ? null : rarities.getAllow(), "mmomobscaling.ui.world.inherits_all"));
        m.put("wRarDeny", csvOrFallback(rarities == null ? null : rarities.getDeny(), "mmomobscaling.ui.world.inherits_none"));
        m.put("wVarAllow", csvOrFallback(variants == null ? null : variants.getAllow(), "mmomobscaling.ui.world.inherits_all"));
        m.put("wVarDeny", csvOrFallback(variants == null ? null : variants.getDeny(), "mmomobscaling.ui.world.inherits_none"));
        m.put("wVarChance", Message.raw(num(variants != null && variants.getChanceMultiplier() != null
                ? variants.getChanceMultiplier() : cfg.getVariantChanceMultiplier())));
        m.put("wAffAllow", csvOrFallback(affixes == null ? null : affixes.getAllow(), "mmomobscaling.ui.world.inherits_all"));
        m.put("wAffDeny", csvOrFallback(affixes == null ? null : affixes.getDeny(), "mmomobscaling.ui.world.inherits_none"));
        m.put("wAffSlots", Message.raw(String.valueOf(
                affixes != null && affixes.getExtraSlots() != null ? affixes.getExtraSlots() : cfg.getExtraAffixSlots())));
        m.put("wZoneHud", onOffDisplay(
                zoneHud != null && zoneHud.getEnabled() != null ? zoneHud.getEnabled() : cfg.isZoneHudEnabled()));
        m.put("wZoneShowLoc", onOffDisplay(zoneHud != null && zoneHud.getShowLocationName() != null
                ? zoneHud.getShowLocationName() : cfg.isZoneShowLocationName()));
        m.put("wZonePos", Message.raw(zoneHud != null && zoneHud.getPosition() != null && !zoneHud.getPosition().isBlank()
                ? zoneHud.getPosition() : cfg.getZoneHudPosition()));
        m.put("wZoneOffX", Message.raw(String.valueOf(zoneHud != null && zoneHud.getOffsetX() != null
                ? zoneHud.getOffsetX() : cfg.getZoneHudOffsetX())));
        m.put("wZoneOffY", Message.raw(String.valueOf(zoneHud != null && zoneHud.getOffsetY() != null
                ? zoneHud.getOffsetY() : cfg.getZoneHudOffsetY())));
        m.put("wZonePrefix", prefixOrNone(zoneHud != null && zoneHud.getZoneNameKeyPrefix() != null
                ? zoneHud.getZoneNameKeyPrefix() : cfg.getZoneNameKeyPrefix()));
        m.put("wBiomePrefix", prefixOrNone(zoneHud != null && zoneHud.getBiomeNameKeyPrefix() != null
                ? zoneHud.getBiomeNameKeyPrefix() : cfg.getBiomeNameKeyPrefix()));
        m.put("wInspHud", onOffDisplay(
                inspHud != null && inspHud.getEnabled() != null ? inspHud.getEnabled() : cfg.isInspectorHudEnabled()));
        m.put("wInspPortrait", onOffDisplay(inspHud != null && inspHud.getPortraitEnabled() != null
                ? inspHud.getPortraitEnabled() : cfg.isInspectorPortraitEnabled()));
        m.put("wInspPos", Message.raw(inspHud != null && inspHud.getPosition() != null && !inspHud.getPosition().isBlank()
                ? inspHud.getPosition() : cfg.getInspectorHudPosition()));
        m.put("wInspOffX", Message.raw(String.valueOf(inspHud != null && inspHud.getOffsetX() != null
                ? inspHud.getOffsetX() : cfg.getInspectorHudOffsetX())));
        m.put("wInspOffY", Message.raw(String.valueOf(inspHud != null && inspHud.getOffsetY() != null
                ? inspHud.getOffsetY() : cfg.getInspectorHudOffsetY())));
        m.put("wInspRange", Message.raw(num(inspHud != null && inspHud.getRangeBlocks() != null
                ? inspHud.getRangeBlocks() : cfg.getInspectorRangeBlocks())));
        return m;
    }

    /**
     * Re-anchor both overlays for every online player, each to the corner ITS world configures (the
     * per-world view, falling through to the global). Called after any save that can move a HUD: a
     * preset swap, a Zone/Inspector tab save, and a world-file save or delete.
     */
    private static void refreshHuds() {
        ZoneDifficultyHud.refreshPositionForAllOnline();
        MobInspectorHud.refreshPositionForAllOnline();
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
                MobScalingOwnerWriter::saveEnabled, null, "mmomobscaling.ui.status.saved_restart"));
        m.put("playerScaling", new ToggleDef(globalForm, GLOBAL_FORM_SEL, cfg::isPlayerScalingEnabled,
                MobScalingOwnerWriter::savePlayerScalingEnabled, null, "mmomobscaling.ui.status.saved"));
        m.put("onlyRaise", new ToggleDef(globalForm, GLOBAL_FORM_SEL, cfg::isOnlyRaiseDifficulty,
                v -> MobScalingOwnerWriter.saveLeaf(LEAF_ONLY_RAISE, v), null, "mmomobscaling.ui.status.saved"));
        m.put("escEnabled", new ToggleDef(globalForm, GLOBAL_FORM_SEL, cfg::isDistanceEscalationEnabled,
                MobScalingOwnerWriter::saveEscalationEnabled, null, "mmomobscaling.ui.status.saved"));
        m.put("zoneEnabled", new ToggleDef(zoneForm, ZONE_FORM_SEL, cfg::isZoneHudEnabled,
                MobScalingOwnerWriter::saveZoneHudEnabled, ZoneDifficultyHud::setEnabledForAllOnline,
                "mmomobscaling.ui.status.saved"));
        m.put("zoneShowLoc", new ToggleDef(zoneForm, ZONE_FORM_SEL, cfg::isZoneShowLocationName,
                MobScalingOwnerWriter::saveZoneShowLocationName, null, "mmomobscaling.ui.status.saved"));
        m.put("inspEnabled", new ToggleDef(inspectorForm, INSPECTOR_FORM_SEL, cfg::isInspectorHudEnabled,
                MobScalingOwnerWriter::saveInspectorHudEnabled, MobInspectorHud::setEnabledForAllOnline,
                "mmomobscaling.ui.status.saved"));
        m.put("inspPortrait", new ToggleDef(inspectorForm, INSPECTOR_FORM_SEL, cfg::isInspectorPortraitEnabled,
                MobScalingOwnerWriter::saveInspectorPortraitEnabled, null, "mmomobscaling.ui.status.saved"));
        return m;
    }

    // ---------------------------------------------------------------------
    // Spec tables (the schema for the four SettingsForm instances)
    // ---------------------------------------------------------------------

    /**
     * Difficulty-first order (round-2 admin-UX hardening): {@code enabled} note, then Difficulty
     * (floor/caps), then the stat Curve (the effective-HP slope leads it: the one tank slope, then how it
     * splits between the bar and silent reduction, the damage scale and its shape, the two ceilings), then the safety

     * Clamps, then rarity + distance escalation (rarity leads it - a rarity roll is exactly what
     * escalation raises the chance of), then Open World last.
     */
    @Nonnull
    private static List<FieldSpec> buildGlobalSpecs() {
        List<FieldSpec> s = new ArrayList<>();
        s.add(FieldSpec.toggle("enabled", "mmomobscaling.ui.global.enabled").withHint("mmomobscaling.ui.hint.enabled"));
        s.add(FieldSpec.note("enabledNote", "mmomobscaling.ui.global.enabled_note"));
        s.add(FieldSpec.header("hdrDifficulty", "mmomobscaling.ui.global.difficulty_header"));
        s.add(FieldSpec.number("floor", "Difficulty.Floor", "mmomobscaling.ui.global.floor")
                .withHint("mmomobscaling.ui.hint.floor"));
        s.add(FieldSpec.number("minCap", LEAF_MIN_CAP, "mmomobscaling.ui.global.min_cap")
                .withHint("mmomobscaling.ui.hint.min_cap"));
        s.add(FieldSpec.number("maxCap", LEAF_MAX_CAP, "mmomobscaling.ui.global.max_cap")
                .withHint("mmomobscaling.ui.hint.max_cap"));
        s.add(FieldSpec.header("hdrCurve", "mmomobscaling.ui.global.stat_curve_header"));
        addCurveSpecs(s, "");
        s.add(FieldSpec.header("hdrClamps", "mmomobscaling.ui.global.clamps_header"));
        addClampSpecs(s, "");
        s.add(FieldSpec.header("hdrEsc", "mmomobscaling.ui.global.esc_header"));
        s.add(FieldSpec.chance("rarity", "RaritySpawnChance", "mmomobscaling.ui.global.rarity")
                .withHint("mmomobscaling.ui.hint.rarity"));
        s.add(FieldSpec.toggle("escEnabled", "mmomobscaling.ui.global.esc_enabled").withHint("mmomobscaling.ui.hint.esc_enabled"));
        s.add(FieldSpec.number("escStart", "Difficulty.DistanceEscalation.StartDistanceBlocks",
                "mmomobscaling.ui.global.esc_start").withHint("mmomobscaling.ui.hint.esc_start"));
        s.add(FieldSpec.number("escBlocks", "Difficulty.DistanceEscalation.BlocksPerPoint",
                "mmomobscaling.ui.global.esc_blocks").withHint("mmomobscaling.ui.hint.esc_blocks"));
        s.add(FieldSpec.number("escMaxBonus", "Difficulty.DistanceEscalation.MaxBonus",
                "mmomobscaling.ui.global.esc_max_bonus").withHint("mmomobscaling.ui.hint.esc_max_bonus"));
        s.add(FieldSpec.number("escRarity", "Difficulty.DistanceEscalation.RarityChancePerPoint",
                "mmomobscaling.ui.global.esc_rarity").withHint("mmomobscaling.ui.hint.esc_rarity"));
        s.add(FieldSpec.header("hdrOpenWorld", "mmomobscaling.ui.global.open_world_header"));
        s.add(FieldSpec.toggle("playerScaling", "mmomobscaling.ui.global.player_scaling")
                .withHint("mmomobscaling.ui.hint.player_scaling"));
        s.add(FieldSpec.number("playerRing", "OpenWorld.PlayerScalingStartRingBlocks",
                "mmomobscaling.ui.global.player_ring").withHint("mmomobscaling.ui.hint.player_ring"));
        s.add(FieldSpec.dropdown("aggregation", "OpenWorld.AggregationMode", "mmomobscaling.ui.global.aggregation",
                AGGREGATION_MODES).withHint("mmomobscaling.ui.hint.aggregation"));
        s.add(FieldSpec.integer("regionSize", "OpenWorld.RegionSizeChunks", "mmomobscaling.ui.global.region_size")
                .withHint("mmomobscaling.ui.hint.region_size"));
        s.add(FieldSpec.number("bandWidth", "OpenWorld.GroupDeltaBandWidth", "mmomobscaling.ui.global.band_width")
                .withHint("mmomobscaling.ui.hint.band_width"));
        s.add(FieldSpec.toggle("onlyRaise", "mmomobscaling.ui.global.only_raise").withHint("mmomobscaling.ui.hint.only_raise"));
        return List.copyOf(s);
    }

    @Nonnull
    private static List<FieldSpec> buildZoneSpecs() {
        List<FieldSpec> s = new ArrayList<>();
        s.add(FieldSpec.toggle("zoneEnabled", "mmomobscaling.ui.zone.enabled").withHint("mmomobscaling.ui.hint.zone_enabled"));
        s.add(FieldSpec.toggle("zoneShowLoc", "mmomobscaling.ui.zone.show_location")
                .withHint("mmomobscaling.ui.hint.zone_show_location"));
        s.add(FieldSpec.dropdown("zonePos", "ZoneHud.Position", "mmomobscaling.ui.hud.position", POSITIONS)
                .withHint("mmomobscaling.ui.hint.hud_position"));
        s.add(FieldSpec.integer("zoneOffX", "ZoneHud.OffsetX", "mmomobscaling.ui.hud.offset_x")
                .withHint("mmomobscaling.ui.hint.hud_offset_x"));
        s.add(FieldSpec.integer("zoneOffY", "ZoneHud.OffsetY", "mmomobscaling.ui.hud.offset_y")
                .withHint("mmomobscaling.ui.hint.hud_offset_y"));
        s.add(FieldSpec.text("zonePrefix", "ZoneHud.ZoneNameKeyPrefix", "mmomobscaling.ui.zone.zone_name_prefix")
                .withHint("mmomobscaling.ui.hint.zone_name_prefix"));
        s.add(FieldSpec.text("biomePrefix", "ZoneHud.BiomeNameKeyPrefix", "mmomobscaling.ui.zone.biome_name_prefix")
                .withHint("mmomobscaling.ui.hint.biome_name_prefix"));
        return List.copyOf(s);
    }

    @Nonnull
    private static List<FieldSpec> buildInspectorSpecs() {
        List<FieldSpec> s = new ArrayList<>();
        s.add(FieldSpec.toggle("inspEnabled", "mmomobscaling.ui.inspector.enabled")
                .withHint("mmomobscaling.ui.hint.insp_enabled"));
        s.add(FieldSpec.toggle("inspPortrait", "mmomobscaling.ui.inspector.portrait")
                .withHint("mmomobscaling.ui.hint.insp_portrait"));
        s.add(FieldSpec.dropdown("inspPos", "InspectorHud.Position", "mmomobscaling.ui.hud.position", POSITIONS)
                .withHint("mmomobscaling.ui.hint.hud_position"));
        s.add(FieldSpec.integer("inspOffX", "InspectorHud.OffsetX", "mmomobscaling.ui.hud.offset_x")
                .withHint("mmomobscaling.ui.hint.hud_offset_x"));
        s.add(FieldSpec.integer("inspOffY", "InspectorHud.OffsetY", "mmomobscaling.ui.hud.offset_y")
                .withHint("mmomobscaling.ui.hint.hud_offset_y"));
        s.add(FieldSpec.number("inspRange", "InspectorHud.RangeBlocks", "mmomobscaling.ui.inspector.range")
                .withHint("mmomobscaling.ui.hint.insp_range"));
        return List.copyOf(s);
    }

    /**
     * Hint-reuse convention (round-2 hardening): a world field that reuses a GLOBAL label key (identical
     * meaning, just a per-world override) also reuses that GLOBAL hint key. A TRISTATE, a pool gate, or a
     * world-identity field (id, the three {@code Where} selectors, parent) has no true global equivalent (Inherit is a distinct
     * affordance from a plain toggle, and a pool gate/identity leaf has nothing to reuse from) and gets
     * its OWN {@code w_*}/{@code pool_*}/{@code world_*} key instead.
     */
    @Nonnull
    private static List<FieldSpec> buildWorldSpecs() {
        List<FieldSpec> s = new ArrayList<>();
        s.add(FieldSpec.text(F_WORLD_ID, ID_LEAF, "mmomobscaling.ui.world.id").withHint("mmomobscaling.ui.hint.world_id"));
        // The shared Where selector, every axis: three CSV fields, each collecting the LIST leaf the
        // codec wants (a blank one collects as a removal, so three blanks author a pool-only base).
        s.add(FieldSpec.csv(F_WORLD_MATCH, MobScalingOwnerWriter.WHERE_MATCH, "mmomobscaling.ui.world.match")
                .withHint("mmomobscaling.ui.hint.world_match"));
        s.add(FieldSpec.csv(F_WORLD_CONFIGS, MobScalingOwnerWriter.WHERE_GAMEPLAY_CONFIG,
                "mmomobscaling.ui.world.gameplay_configs").withHint("mmomobscaling.ui.hint.world_gameplay_configs"));
        s.add(FieldSpec.csv(F_WORLD_EXCLUDES, MobScalingOwnerWriter.WHERE_EXCLUDE_MATCH,
                "mmomobscaling.ui.world.exclude_match").withHint("mmomobscaling.ui.hint.world_exclude_match"));
        s.add(FieldSpec.text(F_WORLD_PARENT, "Parent", "mmomobscaling.ui.world.parent")
                .withHint("mmomobscaling.ui.hint.world_parent"));
        s.add(FieldSpec.tristate("wEnabled", "Enabled", "mmomobscaling.ui.world.enabled")
                .withHint("mmomobscaling.ui.hint.w_enabled"));
        s.add(FieldSpec.header("wHdrTuning", "mmomobscaling.ui.world.tuning_header"));
        s.add(FieldSpec.chance("wRarity", "RaritySpawnChance", "mmomobscaling.ui.world.rarity")
                .withHint("mmomobscaling.ui.hint.rarity"));
        s.add(FieldSpec.header("wHdrDifficulty", "mmomobscaling.ui.global.difficulty_header"));
        s.add(FieldSpec.number("wFloor", "Difficulty.Floor", "mmomobscaling.ui.world.floor")
                .withHint("mmomobscaling.ui.hint.floor"));
        s.add(FieldSpec.number("wMinCap", LEAF_MIN_CAP, "mmomobscaling.ui.global.min_cap")
                .withHint("mmomobscaling.ui.hint.min_cap"));
        s.add(FieldSpec.number("wMaxCap", LEAF_MAX_CAP, "mmomobscaling.ui.global.max_cap")
                .withHint("mmomobscaling.ui.hint.max_cap"));
        s.add(FieldSpec.header("wHdrEsc", "mmomobscaling.ui.global.esc_header"));
        s.add(FieldSpec.tristate("wEscEnabled", "Difficulty.DistanceEscalation.Enabled",
                "mmomobscaling.ui.global.esc_enabled").withHint("mmomobscaling.ui.hint.w_esc_enabled"));
        s.add(FieldSpec.number("wEscStart", "Difficulty.DistanceEscalation.StartDistanceBlocks",
                "mmomobscaling.ui.global.esc_start").withHint("mmomobscaling.ui.hint.esc_start"));
        s.add(FieldSpec.number("wEscBlocks", "Difficulty.DistanceEscalation.BlocksPerPoint",
                "mmomobscaling.ui.global.esc_blocks").withHint("mmomobscaling.ui.hint.esc_blocks"));
        s.add(FieldSpec.number("wEscMaxBonus", "Difficulty.DistanceEscalation.MaxBonus",
                "mmomobscaling.ui.global.esc_max_bonus").withHint("mmomobscaling.ui.hint.esc_max_bonus"));
        s.add(FieldSpec.number("wEscRarity", "Difficulty.DistanceEscalation.RarityChancePerPoint",
                "mmomobscaling.ui.global.esc_rarity").withHint("mmomobscaling.ui.hint.esc_rarity"));
        s.add(FieldSpec.header("wHdrOpenWorld", "mmomobscaling.ui.global.open_world_header"));
        s.add(FieldSpec.tristate("wPlayerScaling", "OpenWorld.PlayerScalingEnabled",
                "mmomobscaling.ui.world.player_scaling").withHint("mmomobscaling.ui.hint.w_player_scaling"));
        s.add(FieldSpec.number("wPlayerRing", "OpenWorld.PlayerScalingStartRingBlocks",
                "mmomobscaling.ui.global.player_ring").withHint("mmomobscaling.ui.hint.player_ring"));
        s.add(FieldSpec.dropdown("wAggregation", "OpenWorld.AggregationMode", "mmomobscaling.ui.global.aggregation",
                AGGREGATION_MODES_INHERIT).withHint("mmomobscaling.ui.hint.aggregation"));
        s.add(FieldSpec.integer("wRegionSize", "OpenWorld.RegionSizeChunks", "mmomobscaling.ui.global.region_size")
                .withHint("mmomobscaling.ui.hint.region_size"));
        s.add(FieldSpec.number("wBandWidth", "OpenWorld.GroupDeltaBandWidth", "mmomobscaling.ui.global.band_width")
                .withHint("mmomobscaling.ui.hint.band_width"));
        s.add(FieldSpec.tristate("wOnlyRaise", LEAF_ONLY_RAISE, "mmomobscaling.ui.global.only_raise")
                .withHint("mmomobscaling.ui.hint.w_only_raise"));
        s.add(FieldSpec.header("wHdrCurve", "mmomobscaling.ui.global.stat_curve_header"));
        addCurveSpecs(s, "w");
        s.add(FieldSpec.header("wHdrClamps", "mmomobscaling.ui.global.clamps_header"));
        addClampSpecs(s, "w");
        s.add(FieldSpec.header("wHdrPool", "mmomobscaling.ui.world.pool_header"));
        s.add(FieldSpec.csv("wRarAllow", "Pool.Rarities.Allow", "mmomobscaling.ui.world.pool_rarities_allow")
                .withHint("mmomobscaling.ui.hint.pool_rarities_allow"));
        s.add(FieldSpec.csv("wRarDeny", "Pool.Rarities.Deny", "mmomobscaling.ui.world.pool_rarities_deny")
                .withHint("mmomobscaling.ui.hint.pool_rarities_deny"));
        s.add(FieldSpec.csv("wVarAllow", "Pool.Variants.Allow", "mmomobscaling.ui.world.pool_variants_allow")
                .withHint("mmomobscaling.ui.hint.pool_variants_allow"));
        s.add(FieldSpec.csv("wVarDeny", "Pool.Variants.Deny", "mmomobscaling.ui.world.pool_variants_deny")
                .withHint("mmomobscaling.ui.hint.pool_variants_deny"));
        s.add(FieldSpec.number("wVarChance", "Pool.Variants.ChanceMultiplier", "mmomobscaling.ui.world.pool_variant_chance")
                .withHint("mmomobscaling.ui.hint.pool_variant_chance"));
        s.add(FieldSpec.csv("wAffAllow", "Pool.Affixes.Allow", "mmomobscaling.ui.world.pool_affixes_allow")
                .withHint("mmomobscaling.ui.hint.pool_affixes_allow"));
        s.add(FieldSpec.csv("wAffDeny", "Pool.Affixes.Deny", "mmomobscaling.ui.world.pool_affixes_deny")
                .withHint("mmomobscaling.ui.hint.pool_affixes_deny"));
        s.add(FieldSpec.integer("wAffSlots", "Pool.Affixes.ExtraSlots", "mmomobscaling.ui.world.pool_extra_slots")
                .withHint("mmomobscaling.ui.hint.pool_extra_slots"));
        // The two HUD groups, every leaf per world: the tri-states get their own w_* hints (Inherit is a
        // distinct affordance), the plain dropdown/integer/number/text leaves reuse the global ones.
        s.add(FieldSpec.header("wHdrZoneHud", "mmomobscaling.ui.world.zone_hud_header"));
        s.add(FieldSpec.tristate("wZoneHud", "ZoneHud.Enabled", "mmomobscaling.ui.zone.enabled")
                .withHint("mmomobscaling.ui.hint.w_zone_hud"));
        s.add(FieldSpec.tristate("wZoneShowLoc", "ZoneHud.ShowLocationName", "mmomobscaling.ui.zone.show_location")
                .withHint("mmomobscaling.ui.hint.w_zone_show_location"));
        s.add(FieldSpec.dropdown("wZonePos", "ZoneHud.Position", "mmomobscaling.ui.hud.position", POSITIONS_INHERIT)
                .withHint("mmomobscaling.ui.hint.hud_position"));
        s.add(FieldSpec.integer("wZoneOffX", "ZoneHud.OffsetX", "mmomobscaling.ui.hud.offset_x")
                .withHint("mmomobscaling.ui.hint.hud_offset_x"));
        s.add(FieldSpec.integer("wZoneOffY", "ZoneHud.OffsetY", "mmomobscaling.ui.hud.offset_y")
                .withHint("mmomobscaling.ui.hint.hud_offset_y"));
        s.add(FieldSpec.text("wZonePrefix", "ZoneHud.ZoneNameKeyPrefix", "mmomobscaling.ui.zone.zone_name_prefix")
                .withHint("mmomobscaling.ui.hint.zone_name_prefix"));
        s.add(FieldSpec.text("wBiomePrefix", "ZoneHud.BiomeNameKeyPrefix", "mmomobscaling.ui.zone.biome_name_prefix")
                .withHint("mmomobscaling.ui.hint.biome_name_prefix"));
        s.add(FieldSpec.header("wHdrInspHud", "mmomobscaling.ui.world.inspector_hud_header"));
        s.add(FieldSpec.tristate("wInspHud", "InspectorHud.Enabled", "mmomobscaling.ui.inspector.enabled")
                .withHint("mmomobscaling.ui.hint.w_inspector_hud"));
        s.add(FieldSpec.tristate("wInspPortrait", "InspectorHud.PortraitEnabled", "mmomobscaling.ui.inspector.portrait")
                .withHint("mmomobscaling.ui.hint.w_insp_portrait"));
        s.add(FieldSpec.dropdown("wInspPos", "InspectorHud.Position", "mmomobscaling.ui.hud.position", POSITIONS_INHERIT)
                .withHint("mmomobscaling.ui.hint.hud_position"));
        s.add(FieldSpec.integer("wInspOffX", "InspectorHud.OffsetX", "mmomobscaling.ui.hud.offset_x")
                .withHint("mmomobscaling.ui.hint.hud_offset_x"));
        s.add(FieldSpec.integer("wInspOffY", "InspectorHud.OffsetY", "mmomobscaling.ui.hud.offset_y")
                .withHint("mmomobscaling.ui.hint.hud_offset_y"));
        s.add(FieldSpec.number("wInspRange", "InspectorHud.RangeBlocks", "mmomobscaling.ui.inspector.range")
                .withHint("mmomobscaling.ui.hint.insp_range"));
        s.add(FieldSpec.note("wHint", "mmomobscaling.ui.world.hint"));
        return List.copyOf(s);
    }

    /**
     * The floor editor: the file name (the sentinel {@link #ID_LEAF}, popped before a save), then the
     * three leaves of a {@code DifficultyMappingAsset} - the target type as an Inherit-led dropdown of
     * the codec's two words, the native zone/biome name, the floor - and the precedence note. A blank
     * field / Inherit collects as a null leaf, so a file named after a shipped mapping carries only what
     * the admin authored and inherits the rest per leaf (the owner layer's contract).
     */
    @Nonnull
    private static List<FieldSpec> buildFloorSpecs() {
        List<FieldSpec> s = new ArrayList<>();
        s.add(FieldSpec.text(F_FLOOR_ID, ID_LEAF, "mmomobscaling.ui.floor.id").withHint("mmomobscaling.ui.hint.floor_id"));
        s.add(FieldSpec.dropdown(F_FLOOR_TYPE, LEAF_TARGET_TYPE, "mmomobscaling.ui.floor.type", TARGET_TYPES_INHERIT)
                .withHint("mmomobscaling.ui.hint.floor_type"));
        s.add(FieldSpec.text(F_FLOOR_TARGET, LEAF_TARGET_ID, "mmomobscaling.ui.floor.target")
                .withHint("mmomobscaling.ui.hint.floor_target"));
        s.add(FieldSpec.number(F_FLOOR_VALUE, LEAF_FLOOR, "mmomobscaling.ui.floor.value")
                .withHint("mmomobscaling.ui.hint.floor_value"));
        s.add(FieldSpec.note("floorHint", "mmomobscaling.ui.floor.hint"));
        return List.copyOf(s);
    }

    /**
     * The six {@code Difficulty.StatCurve} leaves, in curve order, appended once for the Global form
     * ({@code prefix} empty) and once for the per-world form ({@code prefix} {@code "w"}, the id
     * convention {@link #buildWorldSpecs} uses; a plain NUMBER leaf reuses the global label + hint keys).
     */
    private static void addCurveSpecs(@Nonnull List<FieldSpec> s, @Nonnull String prefix) {
        s.add(FieldSpec.number(fieldId(prefix, "ehpPerPoint"), "Difficulty.StatCurve.EffectiveHpPerPoint",
                "mmomobscaling.ui.curve.ehp_per_point").withHint("mmomobscaling.ui.hint.ehp_per_point"));
        s.add(FieldSpec.number(fieldId(prefix, "hpShare"), "Difficulty.StatCurve.VisibleHpShare",
                "mmomobscaling.ui.curve.hp_share").withHint("mmomobscaling.ui.hint.hp_share"));
        s.add(FieldSpec.number(fieldId(prefix, "outScale"), "Difficulty.StatCurve.OutDamageScale",
                "mmomobscaling.ui.curve.out_scale").withHint("mmomobscaling.ui.hint.out_scale"));
        s.add(FieldSpec.number(fieldId(prefix, "outShape"), "Difficulty.StatCurve.OutDamageShape",
                "mmomobscaling.ui.curve.out_shape").withHint("mmomobscaling.ui.hint.out_shape"));

        s.add(FieldSpec.number(fieldId(prefix, "maxEhp"), "Difficulty.StatCurve.MaxEffectiveHpMult",
                "mmomobscaling.ui.curve.max_ehp").withHint("mmomobscaling.ui.hint.max_ehp"));
        s.add(FieldSpec.number(fieldId(prefix, "maxOut"), "Difficulty.StatCurve.MaxOutDamageMult",
                "mmomobscaling.ui.curve.max_out").withHint("mmomobscaling.ui.hint.max_out"));
    }

    /** The five {@code Difficulty.Clamps} leaves, appended the same way as {@link #addCurveSpecs}. */
    private static void addClampSpecs(@Nonnull List<FieldSpec> s, @Nonnull String prefix) {
        s.add(FieldSpec.number(fieldId(prefix, "minHp"), "Difficulty.Clamps.MinHpMult",
                "mmomobscaling.ui.clamps.min_hp").withHint("mmomobscaling.ui.hint.clamp_min_hp"));
        s.add(FieldSpec.number(fieldId(prefix, "maxIn"), "Difficulty.Clamps.MaxInDamageMult",
                "mmomobscaling.ui.clamps.max_in").withHint("mmomobscaling.ui.hint.clamp_max_in"));
        s.add(FieldSpec.number(fieldId(prefix, "minOut"), "Difficulty.Clamps.MinOutDamageMult",
                "mmomobscaling.ui.clamps.min_out").withHint("mmomobscaling.ui.hint.clamp_min_out"));
        s.add(FieldSpec.number(fieldId(prefix, "minLoot"), "Difficulty.Clamps.MinLootMult",
                "mmomobscaling.ui.clamps.min_loot").withHint("mmomobscaling.ui.hint.clamp_min_loot"));
        s.add(FieldSpec.number(fieldId(prefix, "maxLoot"), "Difficulty.Clamps.MaxLootMult",
                "mmomobscaling.ui.clamps.max_loot").withHint("mmomobscaling.ui.hint.clamp_max_loot"));
    }

    /** {@code "ehpPerPoint"} on the Global form, {@code "wEhpPerPoint"} on the per-world form. */
    @Nonnull
    private static String fieldId(@Nonnull String prefix, @Nonnull String base) {
        return prefix.isEmpty() ? base : prefix + Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Build a status Message naming the failing field: {@code {field}} is a nested resolved Message. */
    private void emitInvalidField(@Nonnull FormResult result) {
        String labelKey = result.errorLabelKey();
        Message field = labelKey != null ? tr(labelKey) : Message.raw("?");
        err(tr("mmomobscaling.ui.status.invalid_field").param("field", field));
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
        append(sb, ws.isMatchable() ? ws.whereSummary() : "(base)");
        String parent = worlds.parentOf(id);
        if (parent != null) append(sb, "parent " + parent);
        if (ws.getEnabled() != null && !ws.getEnabled()) append(sb, "OFF");
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

    /**
     * The EXISTING localized toggle label for a HINT's "Inherits: X" line (nested {@link Message}, never
     * a hardcoded English literal) - reuses the SAME {@code mmomobscaling.ui.toggle.on}/{@code .off} keys the
     * toggle rows themselves render, unlike {@link #onOff}'s raw lowercase cache value.
     */
    @Nonnull
    private static Message onOffDisplay(boolean v) {
        return tr(v ? "mmomobscaling.ui.toggle.on" : "mmomobscaling.ui.toggle.off");
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
     * fixed-entry dropdowns with no "inherit" pseudo-value (aggregation/zonePos/inspPos); the per-world
     * {@code wAggregation} dropdown already seeds an explicit "inherit" via
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

    /**
     * A name-key prefix for a HINT's "Inherits: X" line: a literal {@link Message#raw} when set, the
     * localized {@code mmomobscaling.ui.world.inherits_none} when it is the EMPTY prefix (a real value:
     * the raw zone/biome id is prettified instead of looked up).
     */
    @Nonnull
    private static Message prefixOrNone(@Nonnull String prefix) {
        return prefix.isBlank() ? tr("mmomobscaling.ui.world.inherits_none") : Message.raw(prefix);
    }

    /**
     * Like {@link #csvOrBlank} but for a HINT's "Inherits: X" line: an actual id list is a literal
     * {@link Message#raw} (technical ids, not translatable prose), an empty/absent list resolves
     * {@code fallbackKey} (the localized {@code mmomobscaling.ui.world.inherits_all}/{@code _none}).
     */
    @Nonnull
    private static Message csvOrFallback(@Nullable String[] v, @Nonnull String fallbackKey) {
        return v == null || v.length == 0 ? tr(fallbackKey) : Message.raw(String.join(", ", v));
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
        /** The row id of whichever list the pressed button sits in: a world file id, a floor mapping id. */
        public String id;
        public String field;
        public String value;

        public static final BuilderCodec<EventData> CODEC = BuilderCodec.builder(EventData.class, EventData::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, v, i) -> d.action = v, (d, i) -> d.action).add()
                .append(new KeyedCodec<>("Tab", Codec.STRING), (d, v, i) -> d.tab = v, (d, i) -> d.tab).add()
                .append(new KeyedCodec<>("Id", Codec.STRING), (d, v, i) -> d.id = v, (d, i) -> d.id).add()
                .append(new KeyedCodec<>("Field", Codec.STRING), (d, v, i) -> d.field = v, (d, i) -> d.field).add()
                .append(new KeyedCodec<>("@Value", Codec.STRING), (d, v, i) -> d.value = v, (d, i) -> d.value).add()
                .build();
    }
}

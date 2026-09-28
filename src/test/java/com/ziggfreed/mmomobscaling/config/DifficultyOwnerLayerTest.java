package com.ziggfreed.mmomobscaling.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.mmomobscaling.asset.DifficultyMappingAsset;
import com.ziggfreed.mmomobscaling.world.DifficultyMapping;
import com.ziggfreed.mmomobscaling.world.DifficultyMapping.TargetType;

/**
 * The zone/biome difficulty floor OWNER layer ({@code mods/MmoMobScaling/difficulty/<id>.json}):
 * the full {@code jar < pack < owner} chain per leaf (a pack replaces a jar mapping by id, an owner
 * file then inherits the pack's target and overrides its floor), a new id that must carry every leaf,
 * the {@code Payload} wrapper, the derived index following every refold, and the write-back path
 * through {@link MobScalingOwnerWriter}. The shipped layer is what the engine folds from jar + pack, so
 * it is seeded here through {@code mergePackLayer} exactly as the store event does.
 */
class DifficultyOwnerLayerTest {

    private static DifficultyMapping zone(String id, String target, double floor) {
        return new DifficultyMapping(id, TargetType.ZONE, target, floor);
    }

    /** The shipped layer two files overlay: the same seed every test starts from. */
    private static void shipped() {
        DifficultyConfig.getInstance().mergePackLayer(Map.of(
                "Zone2", zone("Zone2", "Zone2", 22.0),
                "Zone3", zone("Zone3", "Zone3", 38.0),
                "Ocean", new DifficultyMapping("Ocean", TargetType.BIOME, "Ocean1", 12.0)));
    }

    private static DifficultyOwnerLayer layerIn(Path dir) {
        DifficultyOwnerLayer layer = DifficultyOwnerLayer.getInstance();
        layer.setOwnerDir(dir);
        layer.refold();
        return layer;
    }

    @AfterEach
    void reset() {
        DifficultyOwnerLayer layer = DifficultyOwnerLayer.getInstance();
        layer.setOwnerDir(null);
        layer.refold();
        DifficultyConfig.getInstance().mergePackLayer(Map.of());
    }

    @Test
    void ownerFileOverlaysTheShippedMappingPerLeafAndANewIdMustBeComplete(@TempDir Path tmp) throws Exception {
        shipped();
        // A retune of a shipped zone: Floor alone, the target inherited from the shipped mapping.
        Files.writeString(tmp.resolve("Zone2.json"), "{ \"Floor\": 60.0 }", StandardCharsets.UTF_8);
        // A brand-new mapping: all three leaves.
        Files.writeString(tmp.resolve("Frontier.json"),
                "{ \"TargetType\": \"Zone\", \"TargetId\": \"Zone9\", \"Floor\": 70.0 }", StandardCharsets.UTF_8);
        // A partial file for an id nothing ships: nothing to inherit the target from, so it is skipped.
        Files.writeString(tmp.resolve("Orphan.json"), "{ \"Floor\": 5.0 }", StandardCharsets.UTF_8);
        // A shipped BIOME mapping re-pointed at a different biome, floor inherited.
        Files.writeString(tmp.resolve("ocean.json"), "{ \"TargetId\": \"Ocean2\" }", StandardCharsets.UTF_8);

        DifficultyOwnerLayer layer = layerIn(tmp);
        DifficultyConfig cfg = DifficultyConfig.getInstance();

        DifficultyMapping zone2 = cfg.resolve("zone2");
        assertNotNull(zone2);
        assertEquals(TargetType.ZONE, zone2.targetType(), "TargetType inherited from the shipped mapping");
        assertEquals("Zone2", zone2.targetId(), "TargetId inherited from the shipped mapping");
        assertEquals("Zone2", zone2.id(), "an overlay keeps the shipped id's authored spelling, not the file key");
        assertEquals(60.0, zone2.floor(), 1e-9, "the owner Floor wins");
        assertEquals(60.0, cfg.zoneFloor("Zone2_Tier1"), 1e-9, "the derived index rebuilt on the owner fold");
        assertEquals(38.0, cfg.zoneFloor("Zone3"), 1e-9, "a shipped mapping no file names is untouched");
        assertEquals(70.0, cfg.zoneFloor("Zone9"), 1e-9, "a complete new file adds a mapping");
        assertEquals("frontier", cfg.resolve("Frontier").id(), "a mapping nothing ships under carries the file key as its id");
        assertNull(cfg.resolve("orphan"), "a partial file with nothing to inherit from publishes nothing");
        DifficultyMapping ocean = cfg.resolve("ocean");
        assertNotNull(ocean);
        assertEquals("Ocean2", ocean.targetId(), "the owner TargetId wins");
        assertEquals(12.0, ocean.floor(), 1e-9, "the shipped Floor is inherited");
        assertEquals(TargetType.BIOME, ocean.targetType(), "the shipped TargetType is inherited");
        assertEquals(Set.of("zone2", "frontier", "ocean"), layer.ownerAuthoredIds(),
                "only the files that published are badged as owner-authored");
        assertEquals(22.0, cfg.packMapping("Zone2").floor(), 1e-9,
                "the shipped layer itself is untouched underneath");
    }

    @Test
    void authoredByIdReadsTheOwnerFilesOwnLeavesAndNothingElse(@TempDir Path tmp) throws Exception {
        shipped();
        Files.writeString(tmp.resolve("Zone2.json"), "{ \"Floor\": 60.0 }", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("Frontier.json"),
                "{ \"TargetType\": \"zone\", \"TargetId\": \"Zone9\", \"Floor\": 70.0 }", StandardCharsets.UTF_8);
        DifficultyOwnerLayer layer = layerIn(tmp);

        // A partial file: only the leaf it authors is present, the shipped target is NOT copied in -
        // which is what lets an editor seeded from it keep the other fields blank (inherit).
        var zone2 = layer.authoredById("Zone2");
        assertNotNull(zone2);
        assertEquals(60.0, zone2.getFloor(), 1e-9);
        assertNull(zone2.getTargetType(), "the shipped TargetType is inherited at fold, never authored here");
        assertNull(zone2.getTargetId());
        // The read keys by the same id key as the fold, so any spelling of the id reaches the file.
        assertNotNull(layer.authoredById("zone2"));
        assertNotNull(layer.authoredById("ZONE2"));
        // A complete file comes back whole, the type word as the file spelled it (the editor re-cases it).
        var frontier = layer.authoredById("frontier");
        assertNotNull(frontier);
        assertEquals("zone", frontier.getTargetType());
        assertEquals("Zone9", frontier.getTargetId());
        // A shipped mapping no owner file overlays, and an unknown id, both read as nothing authored.
        assertNull(layer.authoredById("Zone3"));
        assertNull(layer.authoredById("nowhere"));
        // No owner dir at all: nothing authored anywhere.
        layer.setOwnerDir(null);
        assertNull(layer.authoredById("Zone2"));
    }

    @Test
    void theFullJarPackOwnerChainFoldsPerLeaf(@TempDir Path tmp) throws Exception {
        // The three layers a floor can come from, end to end. The JAR ships a mapping; a PACK replaces it by
        // id WHOLESALE (the engine merges jar + pack into the one store this layer sees, last pack wins, so
        // the pack body arrives here already in the jar file's place); an OWNER file then overlays the
        // result PER LEAF. Each leaf must come from the highest layer that authors it.
        DifficultyMappingAsset jarZone2 = decode("/Server/MmoMobScaling/Difficulty/Zone2.json");
        DifficultyMappingAsset jarZone3 = decode("/Server/MmoMobScaling/Difficulty/Zone3.json");
        DifficultyMapping shippedZone2 = jarZone2.toMapping("Zone2");
        DifficultyMapping shippedZone3 = jarZone3.toMapping("Zone3");
        assertNotNull(shippedZone2);
        assertNotNull(shippedZone3);
        // The pack's Zone2 re-points the mapping at a different zone and moves the floor; nothing of the
        // jar's Zone2 survives underneath it, since replace-by-id is wholesale.
        DifficultyMapping packZone2 = new DifficultyMapping("Zone2", TargetType.ZONE, "Zone2_Frontier",
                shippedZone2.floor() + 40.0);
        DifficultyConfig.getInstance().mergePackLayer(Map.of("Zone2", packZone2, "Zone3", shippedZone3));
        DifficultyConfig cfg = DifficultyConfig.getInstance();
        assertEquals(packZone2.floor(), cfg.zoneFloor("Zone2_Frontier"), 1e-9, "the pack layer stands in for the jar");
        assertNull(cfg.zoneFloorSpecific("Zone2_Tier1"), "the jar's Zone2 target is gone with the jar body");

        // The owner authors only a Floor: the TARGET is inherited from the PACK's Zone2, not the jar's.
        Files.writeString(tmp.resolve("Zone2.json"), "{ \"Floor\": 3.5 }", StandardCharsets.UTF_8);
        layerIn(tmp);
        DifficultyMapping effective = cfg.resolve("zone2");
        assertNotNull(effective);
        assertEquals("Zone2_Frontier", effective.targetId(), "TargetId comes from the pack, the layer beneath the owner");
        assertEquals(TargetType.ZONE, effective.targetType(), "TargetType comes from the pack too");
        assertEquals(3.5, effective.floor(), 1e-9, "Floor comes from the owner");
        assertEquals(3.5, cfg.zoneFloor("Zone2_Frontier"), 1e-9, "the index follows the owner floor at the pack target");
        assertEquals(shippedZone3.floor(), cfg.zoneFloor("Zone3"), 1e-9,
                "a mapping only the jar authors and nobody overlays folds through unchanged");
        assertEquals(packZone2.floor(), cfg.packMapping("Zone2").floor(), 1e-9,
                "the pack layer itself is untouched underneath the owner overlay");
    }

    /** One shipped jar mapping, decoded through the real codec off the test classpath. */
    private static DifficultyMappingAsset decode(String resource) throws Exception {
        try (java.io.InputStream in = DifficultyOwnerLayerTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "jar mapping on the test classpath: " + resource);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return DifficultyMappingAsset.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
        }
    }

    @Test
    void anUnknownTargetTypeIsSkippedNotInherited(@TempDir Path tmp) throws Exception {
        shipped();
        Files.writeString(tmp.resolve("Zone2.json"), "{ \"TargetType\": \"Planet\", \"Floor\": 60.0 }",
                StandardCharsets.UTF_8);
        layerIn(tmp);
        assertEquals(22.0, DifficultyConfig.getInstance().zoneFloor("Zone2"), 1e-9,
                "an authored TargetType the codec does not know skips the file rather than silently inheriting");
    }

    @Test
    void payloadWrappedFileIsPeeledAndTheReadmeIsSeeded(@TempDir Path tmp) throws Exception {
        shipped();
        Path dir = tmp.resolve("difficulty");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Zone3.json"),
                "{ \"Name\": \"copied from a pack\", \"Payload\": { \"Floor\": 45.0 } }", StandardCharsets.UTF_8);
        layerIn(dir);
        assertEquals(45.0, DifficultyConfig.getInstance().zoneFloor("Zone3"), 1e-9, "a pack-style wrapper is peeled");
        assertTrue(Files.exists(dir.resolve(OwnerFiles.README)), "the folder carries the format readme");
    }

    @Test
    void refoldOnTheShippedFoldResolvesAPartialFileWrittenBeforeIt(@TempDir Path tmp) throws Exception {
        // In production the owner dir is set at setup() and scanned when the shipped mappings arrive
        // (the store event), because a partial file can only inherit from a shipped mapping that is loaded.
        Files.writeString(tmp.resolve("Zone2.json"), "{ \"Floor\": 60.0 }", StandardCharsets.UTF_8);
        DifficultyOwnerLayer layer = DifficultyOwnerLayer.getInstance();
        layer.setOwnerDir(tmp);
        assertNull(DifficultyConfig.getInstance().resolve("zone2"), "setting the dir scaffolds, it does not scan");
        shipped();
        layer.refold(); // what MobScalingAssetRegistrar.onDifficultyLoaded does after mergePackLayer
        assertEquals(60.0, DifficultyConfig.getInstance().zoneFloor("Zone2"), 1e-9,
                "the partial file resolves once the shipped mapping it inherits from is in");
    }

    @Test
    void writerSavesAPartialFileRefoldsLiveAndDeleteRestoresTheShippedFloor(@TempDir Path tmp) throws Exception {
        shipped();
        DifficultyOwnerLayer layer = layerIn(tmp);
        DifficultyConfig cfg = DifficultyConfig.getInstance();

        assertTrue(MobScalingOwnerWriter.saveDifficultyFloor("Zone2", 55.0));
        Path file = tmp.resolve("zone2.json");
        assertTrue(Files.exists(file), "one file per mapping, the sanitized id as its stem");
        String body = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"Floor\": 55.0"), body);
        assertFalse(body.contains("TargetType"), "a floor retune writes only the Floor leaf: " + body);
        assertEquals(55.0, cfg.zoneFloor("Zone2"), 1e-9, "the live fold reflects the write with no restart");
        assertEquals("Zone2", cfg.resolve("zone2").targetId(), "the target still comes from the shipped mapping");
        assertTrue(MobScalingOwnerWriter.ownerAuthoredDifficultyIds().contains("zone2"));

        // A second write merges into the same file, the earlier leaf preserved.
        assertTrue(MobScalingOwnerWriter.saveDifficultyMapping("Zone2", Map.of("TargetId", "Zone2_Tier3")));
        assertEquals(55.0, cfg.resolve("zone2").floor(), 1e-9, "the earlier Floor survives a partial merge");
        assertEquals("Zone2_Tier3", cfg.resolve("zone2").targetId(), "the new TargetId lands");

        assertTrue(MobScalingOwnerWriter.deleteDifficultyMapping("Zone2"));
        assertFalse(Files.exists(file));
        assertEquals(22.0, cfg.zoneFloor("Zone2"), 1e-9, "the shipped floor stands again");
        assertFalse(layer.ownerAuthoredIds().contains("zone2"));
        assertFalse(MobScalingOwnerWriter.deleteDifficultyMapping("Zone2"), "nothing left to delete");
    }

    @Test
    void writerReachesAHandCasedMappingFile(@TempDir Path tmp) throws Exception {
        // A hand-named ZONE2.json is the file for "Zone2": the write merges into it and the delete removes it,
        // and no lower-cased twin is created beside it.
        shipped();
        Files.writeString(tmp.resolve("ZONE2.json"), "{ \"Floor\": 40.0 }", StandardCharsets.UTF_8);
        DifficultyOwnerLayer layer = layerIn(tmp);
        DifficultyConfig cfg = DifficultyConfig.getInstance();
        assertEquals(40.0, cfg.zoneFloor("Zone2"), 1e-9, "the hand-cased file folds under its id");
        assertEquals("ZONE2.json", layer.ownerFileFor("Zone2").getFileName().toString());

        assertTrue(MobScalingOwnerWriter.saveDifficultyFloor("Zone2", 55.0));
        assertEquals(55.0, cfg.zoneFloor("Zone2"), 1e-9);
        try (var files = Files.list(tmp)) {
            assertEquals(1, files.filter(p -> p.getFileName().toString().endsWith(".json")).count(),
                    "the write landed in the existing file, not a second one");
        }
        assertTrue(Files.readString(tmp.resolve("ZONE2.json"), StandardCharsets.UTF_8).contains("55.0"));

        assertTrue(MobScalingOwnerWriter.deleteDifficultyMapping("Zone2"));
        assertEquals(22.0, cfg.zoneFloor("Zone2"), 1e-9, "the shipped floor stands again");
        try (var files = Files.list(tmp)) {
            assertEquals(0, files.filter(p -> p.getFileName().toString().endsWith(".json")).count());
        }
    }

    @Test
    void writerIsANoOpWithoutAnOwnerDir() {
        shipped();
        DifficultyOwnerLayer.getInstance().setOwnerDir(null);
        assertFalse(MobScalingOwnerWriter.saveDifficultyFloor("Zone2", 55.0), "no owner dir, nothing written");
        assertEquals(22.0, DifficultyConfig.getInstance().zoneFloor("Zone2"), 1e-9);
    }

    @Test
    void anIdTheSanitizerRewritesIsOneEntryAcrossTheLayers(@TempDir Path tmp) throws Exception {
        // A pack ships a mapping under an id with a space in it, and the owner retunes it from a file whose
        // stem keys the same way. Both layers must meet at the ONE id key (zone2_big): one entry in the fold,
        // one id listed, the owner floor on top, resolvable by the raw spelling and by the key alike.
        DifficultyConfig cfg = DifficultyConfig.getInstance();
        cfg.mergePackLayer(Map.of("Zone2 Big", zone("Zone2 Big", "Zone2_Big", 22.0)));
        Files.writeString(tmp.resolve("Zone2 Big.json"), "{ \"Floor\": 60.0 }", StandardCharsets.UTF_8);
        DifficultyOwnerLayer layer = layerIn(tmp);

        assertEquals(List.of("zone2_big"), cfg.ids(), "one id, one entry, under the one key");
        assertEquals(1, cfg.all().size(), "the pack spelling and the owner spelling did not fork the fold");
        assertEquals(60.0, cfg.resolve("Zone2 Big").floor(), 1e-9, "resolvable by the raw id");
        assertEquals(60.0, cfg.resolve("zone2_big").floor(), 1e-9, "and by the key");
        assertEquals(60.0, cfg.zoneFloor("Zone2_Big"), 1e-9, "the index carries the owner floor");
        assertEquals(22.0, cfg.packMapping("Zone2 Big").floor(), 1e-9, "the shipped layer is untouched underneath");
        assertEquals(Set.of("zone2_big"), layer.ownerAuthoredIds());
    }
}

package com.ziggfreed.mmomobscaling.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

/**
 * The folder mechanics every one-file-per-id owner folder shares: the filename sanitizer, the ONE id key
 * the scan and the file resolution both go through (with its same-id collision policy), the scaffold +
 * readme, the {@code Payload}-peeling scan that skips a malformed file, and the atomic write.
 * {@code WorldSettingsConfigTest} and {@code DifficultyOwnerLayerTest} exercise the two folders built on it.
 */
class OwnerFilesTest {

    @Test
    void resolveFileFindsAHandCasedFileAndOtherwiseNamesTheCanonicalOne(@TempDir Path tmp) throws Exception {
        // A hand-named Arena.json is THE file for the id "arena" (on a case-sensitive filesystem the
        // canonical arena.json would be a different path, and a write there would fork the rule).
        Files.writeString(tmp.resolve("Arena.json"), "{}");
        List<String> warnings = new ArrayList<>();
        Path found = OwnerFiles.resolveFile(tmp, "arena", warnings::add);
        assertEquals("Arena.json", found.getFileName().toString(), "the existing file is found ignoring case");
        assertEquals(found, OwnerFiles.resolveFile(tmp, " ARENA_* ", warnings::add), "however the id is spelled");
        Path fresh = OwnerFiles.resolveFile(tmp, "Frontier", warnings::add);
        assertEquals("frontier.json", fresh.getFileName().toString(),
                "with nothing to find, a new file takes the canonical lower-cased name");
        assertEquals(tmp.resolve("nowhere").resolve("x.json"),
                OwnerFiles.resolveFile(tmp.resolve("nowhere"), "X", warnings::add), "a missing dir resolves to the canonical path");
        assertTrue(warnings.isEmpty(), "one file per id: nothing to warn about: " + warnings);
    }

    @Test
    void theScanAndTheResolveKeyAFileTheSameWayEvenWhenTheStemIsSanitized(@TempDir Path tmp) throws Exception {
        // A stem sanitizeFileId REWRITES (a space becomes an underscore): the scan must file the body under
        // the same key a save for that id resolves to, or the rule is read from one file and written to a
        // second one beside it.
        Files.writeString(tmp.resolve("Arena Big.json"), "{ \"Floor\": 4.0 }");
        List<String> warnings = new ArrayList<>();

        Map<String, JsonObject> bodies = OwnerFiles.scanJsonBodies(tmp, warnings::add);
        assertEquals("arena_big", OwnerFiles.idKey("Arena Big"), "the id key is the sanitized stem");
        assertEquals(4.0, bodies.get("arena_big").get("Floor").getAsDouble(), 1e-9,
                "the scan files the body under the id key, not the raw lower-cased stem");
        assertEquals(1, bodies.size(), bodies.keySet().toString());

        Path expected = tmp.resolve("Arena Big.json");
        assertEquals(expected, OwnerFiles.resolveFile(tmp, "Arena Big", warnings::add),
                "a save for the display spelling reaches the existing file");
        assertEquals(expected, OwnerFiles.resolveFile(tmp, "arena_big", warnings::add),
                "and so does a save for the key itself: the scan's key and the resolve's key are one function");
        assertEquals(expected, OwnerFiles.resolveFile(tmp, "ARENA BIG*", warnings::add), "however the id is spelled");
        assertTrue(warnings.isEmpty(), "one file per id: nothing to warn about: " + warnings);
    }

    @Test
    void severalFilesNamingOneIdAreSettledDeterministicallyAndWarned() {
        // A case-sensitive filesystem can hold Arena.json beside arena.json (and any filesystem can hold
        // Arena Big.json beside arena_big.json); a Windows checkout cannot hold the case pair, so the policy
        // is exercised on the pure chooser the scan and the resolver both go through.
        Path dir = Path.of("owner");
        Path upper = dir.resolve("ARENA.json");
        Path mixed = dir.resolve("Arena.json");
        Path canonical = dir.resolve("arena.json");
        List<String> warnings = new ArrayList<>();

        assertEquals(canonical, OwnerFiles.chooseAmongSameIdFiles(List.of(upper, mixed, canonical), "arena.json", warnings::add),
                "the file spelled the canonical way wins, whatever order the directory lists them in");
        assertEquals(1, warnings.size(), "one warning naming the collision: " + warnings);
        assertTrue(warnings.get(0).contains("ARENA.json") && warnings.get(0).contains("Arena.json")
                && warnings.get(0).contains("arena.json is the one read and written"), warnings.get(0));

        warnings.clear();
        assertEquals(upper, OwnerFiles.chooseAmongSameIdFiles(List.of(mixed, upper), "arena.json", warnings::add),
                "without the canonical spelling the first in code-point order wins (upper case sorts first)");
        assertEquals(1, warnings.size(), warnings.toString());

        warnings.clear();
        assertEquals(mixed, OwnerFiles.chooseAmongSameIdFiles(List.of(mixed), "arena.json", warnings::add),
                "a single file is simply the file");
        assertNull(OwnerFiles.chooseAmongSameIdFiles(List.of(), "arena.json", warnings::add), "no file, no answer");
        assertTrue(warnings.isEmpty(), "no collision, no warning: " + warnings);
    }

    @Test
    void sanitizeFileIdDropsWildcardsAndSeparators() {
        assertEquals("instance-dungeon_of_fear_i", OwnerFiles.sanitizeFileId("instance-dungeon_of_fear_i*"));
        assertEquals("arena", OwnerFiles.sanitizeFileId(" Arena_* "));
        assertEquals("a_b", OwnerFiles.sanitizeFileId("a/b"));
        assertEquals("world", OwnerFiles.sanitizeFileId("***"));
    }

    @Test
    void ensureDirScaffoldsOnceAndNeverClobbersTheReadme(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("folder");
        List<String> warnings = new ArrayList<>();
        OwnerFiles.ensureDir(dir, "first text", warnings::add);
        assertTrue(Files.isDirectory(dir), "the folder is created up front");
        Path readme = dir.resolve(OwnerFiles.README);
        assertEquals("first text", Files.readString(readme, StandardCharsets.UTF_8));
        OwnerFiles.ensureDir(dir, "second text", warnings::add);
        assertEquals("first text", Files.readString(readme, StandardCharsets.UTF_8), "an existing readme is kept");
        assertTrue(warnings.isEmpty(), "nothing to warn about: " + warnings);
    }

    @Test
    void scanPeelsPayloadSkipsMalformedAndIgnoresTheReadme(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("Bare.json"), "{ \"Floor\": 1.0 }");
        Files.writeString(tmp.resolve("wrapped.json"), "{ \"Name\": \"x\", \"Payload\": { \"Floor\": 2.0 } }");
        Files.writeString(tmp.resolve("broken.json"), "{ not json");
        Files.writeString(tmp.resolve("list.json"), "[1, 2]");
        Files.writeString(tmp.resolve(OwnerFiles.README), "ignored");
        List<String> warnings = new ArrayList<>();

        Map<String, JsonObject> bodies = OwnerFiles.scanJsonBodies(tmp, warnings::add);

        assertEquals(2, bodies.size(), "two good bodies: " + bodies.keySet());
        assertEquals(1.0, bodies.get("bare").get("Floor").getAsDouble(), 1e-9, "the id is the stem's id key");

        assertEquals(2.0, bodies.get("wrapped").get("Floor").getAsDouble(), 1e-9, "a Payload wrapper is peeled");
        assertEquals(2, warnings.size(), "the malformed and the non-object file each warn once: " + warnings);
        assertTrue(OwnerFiles.scanJsonBodies(tmp.resolve("missing"), warnings::add).isEmpty(),
                "a missing dir scans to nothing");
    }

    @Test
    void writeJsonIsAtomicAndPrettyPrinted(@TempDir Path tmp) throws Exception {
        List<String> warnings = new ArrayList<>();
        JsonObject body = new JsonObject();
        body.addProperty("Floor", 3.0);
        Path target = tmp.resolve("nested").resolve("zone.json");
        assertTrue(OwnerFiles.writeJson(target, body, warnings::add));
        String text = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(text.contains("\"Floor\": 3.0"), text);
        assertFalse(Files.exists(tmp.resolve("nested").resolve("zone.json.tmp")), "the temp sibling is moved away");
        assertTrue(warnings.isEmpty(), warnings.toString());
    }
}

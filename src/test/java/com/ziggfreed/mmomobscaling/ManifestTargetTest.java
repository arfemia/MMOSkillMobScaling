package com.ziggfreed.mmomobscaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.common.semver.SemverRange;

/**
 * The shipped manifest targets what this build is compiled and tested against, read the way the
 * server reads it.
 *
 * <p><b>The server range.</b> The server checks a plugin's {@code ServerVersion} with
 * {@code SemverRange.satisfies} against its own version, matching pre-releases npm-strict, and on a
 * miss still loads the plugin but lists it as outdated (a WARNING, a SEVERE roll-up and a red notice
 * to players allowed to see outdated mods). So the range admits the server jar on this classpath, the
 * one the build compiles against, and that line's release, and claims no later line.
 *
 * <p><b>The floors.</b> The server checks each {@code Dependencies} range against the installed
 * dependency's own version and refuses to load the plugin at all on a miss. Under this mod's lockstep
 * rule ({@code gradle.properties}) a floor IS the version of the jar this build compiles against, so a
 * server still running an older one refuses this jar by name instead of running it beside a build it
 * was never made for.
 *
 * <p>Every manifest read here is a processed one on the test classpath (this module's after resource
 * templating, a dependency's inside its jar), so the test reads exactly what a server reads. It calls
 * only the engine's semver types, never {@code PluginManifest}, whose class init builds a server
 * logger this JVM does not have.
 */
class ManifestTargetTest {

    private static final String OWN_NAME = "MmoMobScaling";
    private static final String LIBRARY_NAME = "ZiggfreedCommon";
    private static final String LIBRARY_DEPENDENCY = "Ziggfreed:ZiggfreedCommon";
    private static final String MMO_NAME = "MMOSkillTree";
    private static final String MMO_DEPENDENCY = "Ziggfreed:MMOSkillTree";

    @Test
    void theServerRangeAdmitsTheServerThisBuildCompilesAgainst() throws IOException, URISyntaxException {
        SemverRange range = serverRange();
        Semver server = serverThisBuildCompilesAgainst();

        assertTrue(range.satisfies(server), "a server running the build this jar compiles against would list it"
                + " as outdated: " + range + " does not admit " + server);
    }

    @Test
    void theServerRangeAdmitsThatLinesReleaseAndClaimsNoLaterLine() throws IOException, URISyntaxException {
        SemverRange range = serverRange();
        Semver server = serverThisBuildCompilesAgainst();
        Semver release = new Semver(server.getMajor(), server.getMinor(), server.getPatch());
        Semver nextLine = new Semver(server.getMajor(), server.getMinor() + 1, 0);

        assertTrue(range.satisfies(release), "the release of the line this jar is built on must load it clean: "
                + range + " does not admit " + release);
        assertFalse(range.satisfies(nextLine), "a jar built on one server line must not claim the next: "
                + range + " admits " + nextLine);
    }

    @Test
    void theLibraryFloorIsTheLibraryThisBuildCompilesAgainst() throws IOException {
        String floor = dependencyRange(LIBRARY_DEPENDENCY);
        String compiledAgainst = versionOf(LIBRARY_NAME);

        assertTrue(Semver.fromString(compiledAgainst).satisfies(SemverRange.fromString(floor)),
                "the floor must admit the library this jar compiles against: " + floor + " vs " + compiledAgainst);
        assertEquals(">=" + compiledAgainst, floor,
                "the lockstep rule: the floor is the library this jar compiles against, so an older one refuses it by name");
    }

    @Test
    void theMmoSkillTreeFloorIsTheMmoSkillTreeThisBuildCompilesAgainst() throws IOException {
        String floor = dependencyRange(MMO_DEPENDENCY);
        String compiledAgainst = versionOf(MMO_NAME);

        assertTrue(Semver.fromString(compiledAgainst).satisfies(SemverRange.fromString(floor)),
                "the floor must admit the MMO Skill Tree this jar compiles against: " + floor + " vs " + compiledAgainst);
        assertEquals(">=" + compiledAgainst, floor,
                "the lockstep rule: the floor is the MMO Skill Tree this jar compiles against, so an older one refuses it by name");
    }

    /** This module's processed {@code ServerVersion}, parsed as the server parses it. */
    private static SemverRange serverRange() throws IOException {
        JsonObject own = manifestNamed(OWN_NAME);
        assertTrue(own.has("ServerVersion"), "the manifest names the server line it targets");
        return SemverRange.fromString(own.get("ServerVersion").getAsString());
    }

    /** The version the server jar on this classpath (the one the build compiles against) names in its own manifest. */
    private static Semver serverThisBuildCompilesAgainst() throws IOException, URISyntaxException {
        Path serverJar = Path.of(Semver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JarFile jar = new JarFile(serverJar.toFile())) {
            Manifest manifest = jar.getManifest();
            assertNotNull(manifest, "the server jar carries a manifest: " + serverJar);
            String version = manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
            assertNotNull(version, "the server jar names its version: " + serverJar);
            return Semver.fromString(version);
        }
    }

    /** This module's declared range for one hard dependency id; the test fails when it is not declared. */
    private static String dependencyRange(String dependency) throws IOException {
        JsonObject dependencies = manifestNamed(OWN_NAME).getAsJsonObject("Dependencies");
        assertNotNull(dependencies, "the manifest declares its hard dependencies");
        assertTrue(dependencies.has(dependency), dependency + " is a hard dependency");
        return dependencies.get(dependency).getAsString();
    }

    /** The {@code Version} of the processed manifest named {@code name}: the jar this build compiles against. */
    private static String versionOf(String name) throws IOException {
        JsonObject manifest = manifestNamed(name);
        assertTrue(manifest.has("Version"), name + "'s manifest names its version");
        return manifest.get("Version").getAsString();
    }

    /**
     * The processed {@code manifest.json} whose {@code Name} is {@code name}. Every jar on the test
     * classpath may ship one, so they are told apart by name, never by classpath order.
     */
    private static JsonObject manifestNamed(String name) throws IOException {
        Enumeration<URL> found = ManifestTargetTest.class.getClassLoader().getResources("manifest.json");
        while (found.hasMoreElements()) {
            URL url = found.nextElement();
            try (InputStream in = url.openStream();
                    Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed.isJsonObject()) {
                    JsonObject manifest = parsed.getAsJsonObject();
                    if (manifest.has("Name") && name.equals(manifest.get("Name").getAsString())) {
                        return manifest;
                    }
                }
            }
        }
        return fail("no manifest.json named '" + name + "' on the test classpath");
    }
}

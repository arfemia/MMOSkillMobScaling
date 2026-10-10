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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ziggfreed.mmomobscaling.caster.CasterEntry;
import com.ziggfreed.mmomobscaling.caster.CasterRoster;
import com.ziggfreed.mmomobscaling.roster.Rosters;

/**
 * The caster roster OWNER layer ({@code mods/MmoMobScaling/casters/<id>.json}): a file overlays the
 * same-id shipped roster per leaf, so an owner switches a shipped roster on with one leaf and no pack,
 * and a file for an id nothing ships needs its own {@code Role}.
 */
class CasterOwnerLayerTest {

    private static CasterRoster shippedOff() {
        CasterEntry entry = new CasterEntry(CasterEntry.Kind.ABILITY, "fireball", null, 0.0, List.of(),
                CasterEntry.Scope.BOSS, false, 14000L, 3000L, null);
        return new CasterRoster("Demo_Boss_Caster", "Dragon_Fire", null, List.of(entry), false);
    }

    @AfterEach
    void reset() {
        CasterOwnerLayer layer = CasterOwnerLayer.getInstance();
        layer.setOwnerDir(null);
        layer.refold();
        CasterRosterConfig.getInstance().mergePackLayer(Map.of());
        Rosters.rebuild();
    }

    @Test
    void anOwnerFileHoldingOnlyEnabledSwitchesAShippedRosterOnAndKeepsTheRest(@TempDir Path tmp) throws Exception {
        CasterRoster shipped = shippedOff();
        CasterRosterConfig.getInstance().mergePackLayer(Map.of("Demo_Boss_Caster", shipped));
        Files.writeString(tmp.resolve("demo_boss_caster.json"), "{ \"Enabled\": true }", StandardCharsets.UTF_8);
        // A new id with no Role has nothing to inherit one from, so it is skipped.
        Files.writeString(tmp.resolve("Orphan.json"), "{ \"Enabled\": true }", StandardCharsets.UTF_8);

        CasterOwnerLayer layer = CasterOwnerLayer.getInstance();
        layer.setOwnerDir(tmp);
        layer.refold();
        Rosters.rebuild();

        CasterRoster folded = CasterRosterConfig.getInstance().resolve("Demo_Boss_Caster");
        assertNotNull(folded);
        assertTrue(folded.enabled(), "the owner's Enabled wins");
        assertEquals("Dragon_Fire", folded.roleId(), "Role is inherited from the shipped roster");
        assertEquals(shipped.abilities(), folded.abilities(), "Abilities are inherited from the shipped roster");
        assertEquals("Demo_Boss_Caster", folded.id(), "an overlay keeps the shipped id's spelling");
        assertEquals(List.of(folded), Rosters.casterRosters(), "the switched-on roster reaches the arm set");
        assertNull(CasterRosterConfig.getInstance().resolve("orphan"), "a new id without a Role publishes nothing");
        assertFalse(CasterRosterConfig.getInstance().packRoster("Demo_Boss_Caster").enabled(),
                "the shipped layer underneath is untouched");
        assertTrue(Files.exists(tmp.resolve("README.txt")), "the folder is seeded with its readme");
    }
}

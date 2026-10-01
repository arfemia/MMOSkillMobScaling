package com.ziggfreed.mmomobscaling.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.subject.PlayerRefSubjectHandle;
import com.ziggfreed.common.subject.Subject;

/**
 * The subject a rarity or variant {@code Loot} block pays its reward kinds through. The library's
 * {@code Item}, {@code Lootable} and {@code Stamped_Item} kinds ask the subject for a live
 * {@code Player}; a bare {@code PlayerRef} handle answered that with nothing, so every one of those
 * rewards paid nothing. The handle is now the library's {@link PlayerRefSubjectHandle}, which
 * answers for the player as well as the reference.
 *
 * <p>No test can build a real player, so this pins the pure seam's shape and, read off the source,
 * that the loot sinks build their subject through it and nowhere else.
 */
class MobScalingRewardSubjectTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "ziggfreed", "mmomobscaling",
            "event", "MobScalingLootDropSystem.java");

    @Test
    void theRewardSubject_carriesAHandleThatAnswersForThePlayer() {
        Subject subject = MobScalingLootDropSystem.rewardSubject(null, null);

        assertNotNull(subject.handleAs(PlayerRefSubjectHandle.class),
                "the handle answers a Player read and a PlayerRef read alike");
        assertEquals("", subject.name(), "a missing username reads as empty, never null");
    }

    @Test
    void theLootSinks_buildTheirSubjectThroughTheSeamOnly() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("builder.rewards(RewardKinds.shared(), rewardSubject("),
                "the reward sink pays through the seam's subject");
        assertFalse(source.contains("new Subject("),
                "no subject is built by hand with a bare reference as its handle");
    }
}

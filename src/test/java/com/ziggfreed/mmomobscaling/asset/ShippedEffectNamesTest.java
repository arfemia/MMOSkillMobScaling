package com.ziggfreed.mmomobscaling.asset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.validation.EffectNameCheck;

/**
 * R67 over this jar's effects: every effect that shows a tile on the native buff bar (it carries a
 * {@code StatusEffectIcon}) names itself in a key an en-US lang file here carries exactly as the client
 * looks it up, the lang file's name then the key ({@link EffectNameCheck}, the library's checker). A Name
 * another shipped locale lacks prints between lang waves and fails under {@code -PlangGate} (R123).
 */
class ShippedEffectNamesTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");

    @Test
    void everyShippedEffectWithAnIconNamesItselfInAKeyItsLangFilesCarry() {
        EffectNameCheck.Report report = EffectNameCheck.check(RESOURCES);

        System.out.println("[R67] " + report.summary());
        report.lines().forEach(System.out::println);
        assertTrue(report.englishKeys() > 0, "no en-US lang file under " + RESOURCES.toAbsolutePath());
        assertTrue(report.effects() > 0, "no effect under " + RESOURCES.toAbsolutePath() + ", so nothing was checked");
        assertFalse(report.failed(), () -> String.join("\n", report.lines()));
    }
}

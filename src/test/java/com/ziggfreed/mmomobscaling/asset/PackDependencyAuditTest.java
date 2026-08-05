package com.ziggfreed.mmomobscaling.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.mmomobscaling.asset.PackDependencyAudit.PackInfo;

/**
 * Exercises the PURE decision half of {@link PackDependencyAudit} over hand-authored pack records.
 * The engine-facing collector ({@code run()}) needs a live server and stays for in-game validation,
 * mirroring how {@code MobClassifier}'s engine calls are left untested.
 */
class PackDependencyAuditTest {

    private static final String OURS = "MmoMobScaling";

    private static PackInfo authoring(String name, boolean declaresDependency) {
        return new PackInfo(name, true, declaresDependency);
    }

    private static PackInfo bystander(String name) {
        return new PackInfo(name, false, false);
    }

    private static PackInfo self() {
        return new PackInfo(OURS, true, false);
    }

    @Test
    void authoringPackLoadingBeforeUsIsFlaggedAsIgnored() {
        List<String> findings = PackDependencyAudit.findings(
                List.of(authoring("MyScalingPack", false), self()), OURS, true);
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).contains("MyScalingPack"), findings.toString());
        assertTrue(findings.get(0).contains("IGNORED"), findings.toString());
        assertTrue(findings.get(0).contains(PackDependencyAudit.OWN_DEPENDENCY_ID), findings.toString());
    }

    @Test
    void correctlyDeclaredPackAfterUsIsClean() {
        List<PackInfo> packs = List.of(self(), authoring("MyScalingPack", true));
        assertTrue(PackDependencyAudit.findings(packs, OURS, true).isEmpty(), "no warning for a correct pack");
        assertEquals(1, PackDependencyAudit.confirmations(packs, OURS, true).size(),
                "a correct pack gets a positive confirmation line instead of silence");
    }

    @Test
    void packAfterUsWithoutTheDependencyGetsTheSofterFinding() {
        List<PackInfo> packs = List.of(self(), authoring("MyScalingPack", false));
        List<String> findings = PackDependencyAudit.findings(packs, OURS, true);
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).contains("not guaranteed"), findings.toString());
        assertTrue(PackDependencyAudit.confirmations(packs, OURS, true).isEmpty(),
                "a pack that works only by luck is not confirmed as correct");
    }

    @Test
    void nonAuthoringPacksAreIgnoredInEveryPosition() {
        assertTrue(PackDependencyAudit.findings(
                List.of(bystander("SomeOtherMod"), self(), bystander("Another")), OURS, true).isEmpty());
    }

    @Test
    void disabledScalingReportsTheDisabledCauseRegardlessOfOrder() {
        List<String> before = PackDependencyAudit.findings(
                List.of(authoring("MyScalingPack", true), self()), OURS, false);
        List<String> after = PackDependencyAudit.findings(
                List.of(self(), authoring("MyScalingPack", true)), OURS, false);
        assertEquals(1, before.size(), before.toString());
        assertEquals(1, after.size(), after.toString());
        assertTrue(before.get(0).contains("DISABLED"), before.toString());
        assertEquals(before, after, "the disabled cause does not depend on load order");
        assertTrue(PackDependencyAudit.confirmations(
                List.of(self(), authoring("MyScalingPack", true)), OURS, false).isEmpty());
    }

    @Test
    void unidentifiableOwnPackSkipsTheOrderingHalfOnly() {
        // Our own pack could not be identified: never claim "loads before us", but a missing dependency
        // is still worth reporting because it is order-independent.
        List<String> findings = PackDependencyAudit.findings(
                List.of(authoring("MyScalingPack", false)), null, true);
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).contains("not guaranteed"), findings.toString());
        assertTrue(PackDependencyAudit.findings(
                List.of(authoring("MyScalingPack", true)), null, true).isEmpty(),
                "a declared dependency is clean even when our own pack is unidentifiable");
    }

    @Test
    void emptyPackListIsCleanAndDoesNotThrow() {
        assertTrue(PackDependencyAudit.findings(List.of(), OURS, true).isEmpty());
        assertTrue(PackDependencyAudit.confirmations(List.of(), OURS, true).isEmpty());
    }

    @Test
    void ourOwnPackIsNeverReportedAgainstItself() {
        assertTrue(PackDependencyAudit.findings(List.of(self()), OURS, true).isEmpty(),
                "the mod's own jar pack authors our assets by definition");
    }
}

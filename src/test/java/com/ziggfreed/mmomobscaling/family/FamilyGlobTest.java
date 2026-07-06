package com.ziggfreed.mmomobscaling.family;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure glob-matching tests for {@link FamilyGlob} - the case-insensitive delegation to the native
 * {@code StringUtil.isGlobMatching} routine (native {@code IncludeRoles} semantics).
 */
class FamilyGlobTest {

    @Test
    void prefixWildcardMatchesFamily() {
        assertTrue(FamilyGlob.matches("Spider*", "Spider"), "prefix glob matches the bare family");
        assertTrue(FamilyGlob.matches("Spider*", "Spider_Cave"), "prefix glob matches a variant");
        assertFalse(FamilyGlob.matches("Spider*", "Skeleton_Fighter"), "prefix glob excludes another family");
    }

    @Test
    void caseInsensitive() {
        assertTrue(FamilyGlob.matches("spider*", "Spider_Cave"), "lower-case pattern matches PascalCase role");
        assertTrue(FamilyGlob.matches("SPIDER*", "spider_cave"), "upper-case pattern matches lower role");
    }

    @Test
    void innerAndSuffixWildcards() {
        assertTrue(FamilyGlob.matches("*Trork*", "Intelligent_Trork_Ranger"), "inner wildcard matches a substring");
        assertTrue(FamilyGlob.matches("*_Cave", "Spider_Cave"), "suffix wildcard matches the tail");
        assertFalse(FamilyGlob.matches("*_Cave", "Spider_Forest"), "suffix wildcard rejects a different tail");
    }

    @Test
    void singleCharWildcard() {
        assertTrue(FamilyGlob.matches("Spider_?ave", "Spider_Cave"), "? matches exactly one char");
        assertFalse(FamilyGlob.matches("Spider_?", "Spider_Cave"), "? matches one char, not many");
    }

    @Test
    void exactMatchNoWildcard() {
        assertTrue(FamilyGlob.matches("Spider_Cave", "spider_cave"), "no-wildcard is case-insensitive equality");
        assertFalse(FamilyGlob.matches("Spider", "Spider_Cave"), "no-wildcard requires a full match");
    }
}

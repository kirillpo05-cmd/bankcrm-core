package com.client360.interaction.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The {@code q} filter's escaping (SPEC.md §6.3). Pinned without a database because it is the kind
 * of code that breaks silently: an unescaped {@code %} does not fail, it just matches everything.
 */
class SubjectPatternTest {

    @Test
    void wrapsTheTermForASubstringMatch() {
        assertThat(InteractionRepository.subjectPattern("Mortgage")).isEqualTo("%mortgage%");
    }

    /** A percent sign the caller typed is a percent sign, not "anything". */
    @Test
    void escapesPercent() {
        assertThat(InteractionRepository.subjectPattern("5%")).isEqualTo("%5\\%%");
    }

    @Test
    void escapesUnderscore() {
        assertThat(InteractionRepository.subjectPattern("rate_query")).isEqualTo("%rate\\_query%");
    }

    /** The escape character itself is escaped first, or it would swallow the next one. */
    @Test
    void escapesTheEscapeCharacter() {
        assertThat(InteractionRepository.subjectPattern("a\\b")).isEqualTo("%a\\\\b%");
    }

    @Test
    void blankMeansNoFilter() {
        assertThat(InteractionRepository.subjectPattern(null)).isNull();
        assertThat(InteractionRepository.subjectPattern("   ")).isNull();
    }
}

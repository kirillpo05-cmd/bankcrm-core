package com.client360.client.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.client360.common.api.ApiException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Masking and normalization. These decide whether the same person is findable by the identifier
 * they were created with, so they are worth pinning down without a database.
 */
class MasksTest {

    private final PhoneNumbers phones = new PhoneNumbers("PL");
    private final Masks masks = new Masks(phones);

    @Nested
    class Masking {

        @Test
        void masksEmailToFirstAndLastLocalCharacter() {
            assertThat(masks.email("anna.kowalska@example.com")).isEqualTo("a****a@example.com");
        }

        /** A mask that grew with the value would leak the value's length. */
        @Test
        void maskRunIsFixedLengthRegardlessOfInput() {
            assertThat(masks.email("ab@x.com")).isEqualTo("a****@x.com");
            assertThat(masks.email("averyveryverylonglocalpart@x.com")).isEqualTo("a****t@x.com");
        }

        @Test
        void masksPhoneKeepingCountryCodeAndLastThreeDigits() {
            assertThat(masks.phone("+48511234567")).isEqualTo("+48 511 *** 567");
        }

        @Test
        void masksTaxIdToFirstAndLastFour_CP_BR_13() {
            assertThat(masks.taxId("PL8804170123")).isEqualTo("PL88****0123");
        }

        @Test
        void masksNullAsNullRatherThanTheStringNull() {
            assertThat(masks.email(null)).isNull();
            assertThat(masks.phone(null)).isNull();
            assertThat(masks.taxId(null)).isNull();
        }
    }

    @Nested
    class Normalization {

        /** CP-EC-05: case is normalized, so A@x.com and a@x.com are one person. */
        @Test
        void lowercasesAndTrimsEmail_CP_EC_05() {
            assertThat(Emails.normalize("  Anna.Kowalska@Example.COM ")).isEqualTo("anna.kowalska@example.com");
        }

        /** CP-EC-05: a +tag is a different address at some providers; stripping it merges people. */
        @Test
        void keepsPlusTagInEmail_CP_EC_05() {
            assertThat(Emails.normalize("anna+bank@example.com")).isEqualTo("anna+bank@example.com");
        }

        /** CP-EC-04: both forms must hash to the same value, or the record becomes unfindable. */
        @Test
        void normalizesNationalFormatToE164_CP_EC_04() {
            String fromNational = phones.normalize("511 234 567", "phone");
            String fromInternational = phones.normalize("+48 511 234 567", "phone");
            assertThat(fromNational).isEqualTo("+48511234567").isEqualTo(fromInternational);
        }

        /**
         * The reason normalization validates instead of merely formatting. Poland dropped the
         * trunk "0" in 2009, so {@code 0511 234 567} is not a Polish number — but it still parses,
         * to {@code +480511234567}. Accepting it would hash the same person to a second value and
         * make them unfindable by the number they were created with, which is the exact failure
         * CP-EC-04 exists to prevent.
         */
        @Test
        void rejectsRetiredTrunkPrefixRatherThanMisNormalizingIt_CP_EC_04() {
            assertThatThrownBy(() -> phones.normalize("0511 234 567", "phone")).isInstanceOf(ApiException.class);
        }

        @Test
        void rejectsNumberThatCannotBeReal() {
            assertThatThrownBy(() -> phones.normalize("12", "phone"))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status().value())
                    .isEqualTo(400);
        }
    }
}

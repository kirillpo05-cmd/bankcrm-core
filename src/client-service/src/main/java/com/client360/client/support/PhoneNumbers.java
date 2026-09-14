package com.client360.client.support;

import com.client360.common.api.ApiException;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phone normalization to E.164 before hashing (SPEC.md §4.7, CP-EC-04).
 *
 * <p>A national-format number such as {@code 0511 234 567} carries no country, so it is parsed
 * against {@code defaultRegion}. SPEC.md §5.2.2 has no country column on {@code clients}, so the
 * region is configuration rather than per-client data — stated as an assumption, not inferred:
 * every seeded team is {@code Europe/Warsaw} and the worked examples are Polish numbers.
 *
 * <p>Write and lookup both go through this bean. Two different normalizations of one number would
 * silently make a client unfindable by the identifier they were created with.
 */
@Component
public class PhoneNumbers {

    private final PhoneNumberUtil util = PhoneNumberUtil.getInstance();
    private final String defaultRegion;

    public PhoneNumbers(@Value("${client360.phone.default-region:PL}") String defaultRegion) {
        this.defaultRegion = defaultRegion;
    }

    /** @throws ApiException {@code 400 VALIDATION_FAILED} when the number cannot be a real one */
    public String normalize(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        PhoneNumber parsed;
        try {
            parsed = util.parse(raw.trim(), defaultRegion);
        } catch (NumberParseException e) {
            throw ApiException.validation(field, "must be a valid phone number in E.164 form, e.g. +48511234567");
        }
        if (!util.isValidNumber(parsed)) {
            throw ApiException.validation(field, "must be a valid phone number in E.164 form, e.g. +48511234567");
        }
        return util.format(parsed, PhoneNumberFormat.E164);
    }

    /** Splits an E.164 number for display masking; never throws on stored data. */
    PhoneNumber parseStored(String e164) {
        try {
            return util.parse(e164, defaultRegion);
        } catch (NumberParseException e) {
            return null;
        }
    }
}

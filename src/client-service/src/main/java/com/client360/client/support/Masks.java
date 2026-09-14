package com.client360.client.support;

import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import org.springframework.stereotype.Component;

/**
 * Partial renderings of sensitive values for search results (SPEC.md §5.3 {@code maskedEmail},
 * {@code maskedPhone}) and for the {@code taxId} echoed by a create.
 *
 * <p>The masked run is a fixed four characters regardless of the real length: a mask that grew
 * with the value would leak it. Enough is shown to let a manager tell two records apart, which is
 * the entire purpose — an out-of-scope hit in {@code lookup} shows these and nothing else.
 */
@Component
public class Masks {

    private static final String RUN = "****";

    private final PhoneNumbers phones;

    public Masks(PhoneNumbers phones) {
        this.phones = phones;
    }

    /** {@code anna.kowalska@example.com} → {@code a****a@example.com}. */
    public String email(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        int at = plaintext.lastIndexOf('@');
        if (at < 0) {
            return RUN;
        }
        String local = plaintext.substring(0, at);
        String domain = plaintext.substring(at);
        return switch (local.length()) {
            case 0 -> RUN + domain;
            case 1, 2 -> local.charAt(0) + RUN + domain;
            default -> local.charAt(0) + RUN + local.charAt(local.length() - 1) + domain;
        };
    }

    /** {@code +48511234567} → {@code +48 511 *** 567}. */
    public String phone(String e164) {
        if (e164 == null) {
            return null;
        }
        PhoneNumber parsed = phones.parseStored(e164);
        if (parsed == null) {
            return RUN;
        }
        String national = String.valueOf(parsed.getNationalNumber());
        String country = "+" + parsed.getCountryCode();
        if (national.length() <= 4) {
            return country + " ***";
        }
        String head = national.substring(0, Math.min(3, national.length() - 3));
        String tail = national.substring(national.length() - 3);
        return country + " " + head + " *** " + tail;
    }

    /** {@code PL8804170123} → {@code PL88****0123}. */
    public String taxId(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        if (plaintext.length() < 8) {
            return RUN;
        }
        return plaintext.substring(0, 4) + RUN + plaintext.substring(plaintext.length() - 4);
    }
}

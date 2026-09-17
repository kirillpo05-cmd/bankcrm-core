package com.client360.interaction.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IL-EC-05: a card number typed into a note is masked to its last four digits <em>before</em> the
 * body is encrypted, and the caller is told it happened.
 *
 * <p>Masking before encryption is the whole point. Encrypted-but-present means the PAN is in the
 * database, recoverable by anyone who can read a row and hold the key — and PCI DSS does not care
 * that it was at rest. Once masked here, there is nothing to recover.
 *
 * <p>Detection is length plus Luhn, not a brand table. A Luhn-valid 13-to-19 digit run is treated
 * as a card number even if it is something else: a false positive costs four digits of a
 * reference number, a false negative costs a PAN in storage.
 */
public final class PanMasker {

    /**
     * 13–19 digits, optionally grouped by single spaces or hyphens. The boundaries stop a longer
     * digit run — an IBAN, a phone number with a country code — from having its middle matched.
     */
    private static final Pattern CANDIDATE = Pattern.compile("(?<![0-9])(?:[0-9][ -]?){12,18}[0-9](?![0-9])");

    private PanMasker() {}

    public static Result mask(String body) {
        if (body == null || body.isBlank()) {
            return new Result(body, 0);
        }
        Matcher matcher = CANDIDATE.matcher(body);
        StringBuilder masked = new StringBuilder();
        int found = 0;
        while (matcher.find()) {
            String candidate = matcher.group();
            String digits = candidate.replaceAll("[^0-9]", "");
            if (digits.length() >= 13 && digits.length() <= 19 && passesLuhn(digits)) {
                found++;
                matcher.appendReplacement(masked, Matcher.quoteReplacement(redact(digits)));
            }
        }
        matcher.appendTail(masked);
        return new Result(masked.toString(), found);
    }

    /** {@code 4111111111111111} → {@code ************1111}. Length is preserved, value is not. */
    private static String redact(String digits) {
        return "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
    }

    private static boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubling = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }

    /**
     * @param body the body as it will be stored
     * @param maskedCount how many card numbers were masked; non-zero means the caller is warned
     */
    public record Result(String body, int maskedCount) {

        public boolean maskedAnything() {
            return maskedCount > 0;
        }
    }
}

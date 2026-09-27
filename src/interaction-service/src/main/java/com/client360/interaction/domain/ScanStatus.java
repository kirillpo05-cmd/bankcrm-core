package com.client360.interaction.domain;

/**
 * {@code interaction.scan_status} (SPEC.md §6.2.3). An upload is {@code PENDING} until a scanner
 * has looked at it, and §6.3 refuses the download until then — a file nobody has checked is not
 * served to a manager on the strength of having been uploaded by a colleague.
 *
 * <p>No scanner is wired yet, so nothing moves a row off {@code PENDING}. That is visible in the
 * API rather than hidden: the download answers {@code 409 ATTACHMENT_SCAN_PENDING}, which is the
 * truth, instead of quietly serving unscanned bytes.
 */
public enum ScanStatus {
    PENDING,
    CLEAN,
    INFECTED,
    FAILED;

    /** §6.3: only a clean file is downloadable. */
    public boolean isDownloadable() {
        return this == CLEAN;
    }
}

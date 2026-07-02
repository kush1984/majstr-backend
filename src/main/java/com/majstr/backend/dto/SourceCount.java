package com.majstr.backend.dto;

/**
 * Spring Data projection for the "count grouped by referral source" queries that
 * back the admin by-source report. {@code cnt} (not {@code count} — reserved).
 */
public interface SourceCount {
    String getSource();
    long getCnt();
}

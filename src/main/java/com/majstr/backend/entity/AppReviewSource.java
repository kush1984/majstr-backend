package com.majstr.backend.entity;

/** Where a rating of Majstr was given (V218). The column's CHECK lists the same values. */
public enum AppReviewSource {
    /** He went to Профіль → «Оцінити Majstr» himself. */
    PROFILE,
    /** The home screen asked him, after a success. */
    PROMPT
}

package com.majstr.backend.dto;

/**
 * The master's "Запроси майстра" panel data: their personal code plus a
 * three-number summary — how many they invited, how many of those paid (each paid
 * invitee = one reward), and how many PRO months that earned.
 */
public record ReferralStatsResponse(
        String referralCode,
        long invited,
        long paid,
        long monthsEarned
) {}

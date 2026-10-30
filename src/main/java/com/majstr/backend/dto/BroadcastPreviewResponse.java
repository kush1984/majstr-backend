package com.majstr.backend.dto;

/** The letter as {@code recipientName} would get it. */
public record BroadcastPreviewResponse(String recipientName, String subject, String html) {}

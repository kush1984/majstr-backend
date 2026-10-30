package com.majstr.backend.dto;

import com.majstr.backend.entity.BroadcastSegment;

/** One entry of the «Розсилки» dropdown: the segment, its criteria in words, and how many it holds now. */
public record BroadcastSegmentView(BroadcastSegment segment, String label, String criteria, int count) {}

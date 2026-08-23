package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The raw text a scanned fiscal QR code carried, exactly as the scanner read it — a URL, a bare
 * query string, or something that turns out not to be a fiscal code at all.
 *
 * <p>Deliberately unparsed on the client: what counts as a readable payload is a backend rule
 * (vendors differ), and a scanner that pre-interprets it would have to be updated in two places.
 * The bound is generous but present — a QR cannot hold more than a couple of kilobytes anyway, and
 * an unbounded string is an unbounded log line.
 */
public record FiscalQrRequest(
        @NotBlank @Size(max = 2000) String payload
) {}

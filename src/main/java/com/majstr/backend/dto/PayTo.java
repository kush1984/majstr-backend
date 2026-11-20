package com.majstr.backend.dto;

/**
 * Where the client pays the master (V217) — the portal's «Оплатити». Null on a view when the master
 * filled nothing; inside, every field is null unless he filled it. Bank-neutral, and the money never
 * passes through us.
 */
public record PayTo(
        /** Whose account it is: the legal name, else the full name. */
        String recipientName,
        /** Any bank's card, digits only. */
        String card,
        String iban,
        /** The NBU payment QR as a link; null unless IBAN, РНОКПП and a name are all usable. */
        String qrUrl,
        /** What to write as the payment purpose; also inside the QR. */
        String purpose,
        String link,
        /** The link's host, printed under the button so the client sees where it leads. */
        String linkHost
) {}

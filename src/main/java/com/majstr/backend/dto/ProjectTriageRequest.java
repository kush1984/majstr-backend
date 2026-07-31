package com.majstr.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A whole documentation set offered for triage as TEXT — one entry per sheet.
 *
 * <p>Text, not files, because the question being asked is "what is this sheet?", and a sheet answers
 * that in its own title block. Sending the text costs a fraction of sending the pages, so a
 * forty-four-sheet archive can be understood in one cheap call before a single expensive one is
 * spent. Sheets with no text layer are simply not offered — nothing here can classify them, and they
 * stay candidates on their own evidence.</p>
 */
public record ProjectTriageRequest(
        // `List<@Valid Sheet>`, not `List<Sheet>`: bean validation does NOT descend into a
        // collection's elements on its own, so without the element annotation the per-sheet
        // @Size caps below are declared and never enforced — 60 sheets of unbounded text would be
        // accepted and buffered.
        @NotEmpty @Size(max = 60) List<@Valid Sheet> sheets
) {
    /**
     * @param id   the client's own handle for this sheet — echoed back untouched, never interpreted
     * @param name file name or «page 7»; a hint only, and the prompt is told it may be wrong
     * @param text what pdf text extraction produced, in its original order
     */
    public record Sheet(
            @Size(max = 120) String id,
            @Size(max = 300) String name,
            @Size(max = 6000) String text
    ) {}
}

package com.majstr.backend.controller;

import com.majstr.backend.dto.DictationSynonymRequest;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.importer.DictationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teach a spoken-wording synonym for one of the master's catalog rows. Deliberately NOT under
 * {@code /api/estimates/{id}/dictation} — a synonym is per-master, not per-estimate, and putting an
 * estimate id in the URL would suggest it belonged to that document.
 */
@RestController
@RequestMapping("/api/dictation/synonyms")
@RequiredArgsConstructor
@Tag(name = "Dictation synonyms", description = "Teach «say X, mean THIS catalog row» for future dictations")
@SecurityRequirement(name = "bearer-jwt")
public class DictationSynonymController {

    private final DictationService dictationService;

    @Operation(summary = "Teach a synonym for a catalog row — the next dictation matches this "
            + "wording outright. Overwrites the existing target for the same wording, per master.")
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void teach(@Valid @RequestBody DictationSynonymRequest req,
                      @AuthenticationPrincipal UserPrincipal principal) {
        dictationService.teachSynonym(principal.id(), req);
    }
}

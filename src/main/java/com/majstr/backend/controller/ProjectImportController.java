package com.majstr.backend.controller;

import com.majstr.backend.dto.MeasurementsResponse;
import com.majstr.backend.dto.ProjectImportCommitRequest;
import com.majstr.backend.dto.ProjectImportParseResponse;
import com.majstr.backend.dto.ProjectTriageRequest;
import com.majstr.backend.dto.ProjectTriageResponse;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.measurement.ProjectImportService;
import com.majstr.backend.service.measurement.ProjectTriageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/**
 * Import project documentation (a designer's PDF sheets / photos) into the
 * object's measurements. One file per {@code parse} call — the client-side
 * classifier already decided the file's {@code kind} and floor, and only the
 * useful sheets are uploaded. Parse writes nothing; {@code commit} creates the
 * master-confirmed rooms + element packages in one transaction.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/measurements/project")
@RequiredArgsConstructor
@Tag(name = "Project documentation import", description = "Designer's PDFs/photos → measurement rooms with an element package (LLM structures, the server computes)")
public class ProjectImportController {

    private final ProjectImportService importService;
    private final ProjectTriageService triageService;

    @Operation(summary = "Sort a whole set's sheets by their titles — one cheap text call, nothing persisted")
    @PostMapping("/triage")
    public ProjectTriageResponse triage(@PathVariable UUID projectId,
                                       @Valid @RequestBody ProjectTriageRequest req,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        return triageService.triage(principal.id(), projectId, req);
    }

    @Operation(summary = "Recognise one documentation file into a review draft (nothing persisted)")
    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProjectImportParseResponse parse(@PathVariable UUID projectId,
                                            @RequestParam("file") MultipartFile file,
                                            @RequestParam("kind") String kind,
                                            @AuthenticationPrincipal UserPrincipal principal) throws IOException {
        return importService.parse(principal.id(), projectId, parseKind(kind),
                file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    @Operation(summary = "Create the confirmed rooms + element packages (one transaction, results recomputed)")
    @PostMapping("/commit")
    public MeasurementsResponse commit(@PathVariable UUID projectId,
                                       @Valid @RequestBody ProjectImportCommitRequest req,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        return importService.commit(principal.id(), projectId, req);
    }

    /**
     * A label we do not recognise is {@code UNKNOWN} — not a rejection.
     *
     * <p>The kind is the client's GUESS at what a sheet is, and the client has kinds of its own that
     * the server never needed («OTHER», «ELECTRICAL») plus pages it cannot classify at all.
     * Refusing those with «unsupported» punished the master for our classifier being wrong about his
     * own drawing; the sheet reads either way, and the prompt is told the label is unreliable.</p>
     */
    private static ProjectImportService.Kind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return ProjectImportService.Kind.UNKNOWN;
        }
        try {
            return ProjectImportService.Kind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ProjectImportService.Kind.UNKNOWN;
        }
    }
}

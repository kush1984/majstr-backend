package com.majstr.backend.controller;

import com.majstr.backend.dto.ActProgressResponse;
import com.majstr.backend.dto.ActShareStateResponse;
import com.majstr.backend.dto.WorkActCreateRequest;
import com.majstr.backend.dto.WorkActItemsRequest;
import com.majstr.backend.dto.WorkActReceiptRequest;
import com.majstr.backend.dto.WorkActReceiptResponse;
import com.majstr.backend.dto.WorkActResponse;
import com.majstr.backend.dto.WorkActSignOfflineRequest;
import com.majstr.backend.dto.WorkActStatusRequest;
import com.majstr.backend.dto.WorkActUpdateRequest;
import com.lowagie.text.DocumentException;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.ProjectPortalService;
import com.majstr.backend.service.ProjectPhotoService.PhotoFile;
import com.majstr.backend.service.WorkActReceiptService;
import com.majstr.backend.service.WorkActService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Work acts", description = "Акти виконаних робіт — documents built from signed estimates")
@SecurityRequirement(name = "bearer-jwt")
public class WorkActController {

    private final WorkActService workActService;
    private final ProjectPortalService portalService;
    private final WorkActReceiptService receiptService;

    // ---- under a project --------------------------------------------------

    @Operation(summary = "List the object's work acts (newest first)")
    @GetMapping("/api/projects/{projectId}/acts")
    public List<WorkActResponse> list(@PathVariable UUID projectId,
                                      @AuthenticationPrincipal UserPrincipal principal) {
        return workActService.list(projectId, principal.id());
    }

    @Operation(summary = "Progress: each SIGNED-estimate line with done-so-far and remaining")
    @GetMapping("/api/projects/{projectId}/acts/progress")
    public ActProgressResponse progress(@PathVariable UUID projectId,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        return workActService.progress(projectId, principal.id());
    }

    @Operation(summary = "Create a draft act",
            description = "One open (DRAFT/SENT) act per object; blocked once a FINAL act exists. "
                    + "Offline-authored creates may send X-Entity-Uuid for idempotent replay.")
    @PostMapping("/api/projects/{projectId}/acts")
    public ResponseEntity<WorkActResponse> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody WorkActCreateRequest req,
            @RequestHeader(value = "X-Entity-Uuid", required = false) UUID entityId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(workActService.create(projectId, req, principal.id(), entityId));
    }

    // ---- single act -------------------------------------------------------

    @Operation(summary = "Get an act with its frozen lines and totals")
    @GetMapping("/api/acts/{id}")
    public WorkActResponse get(@PathVariable UUID id,
                               @AuthenticationPrincipal UserPrincipal principal) {
        return workActService.get(id, principal.id());
    }

    @Operation(summary = "Update the act's header (409 WORK_ACT_SIGNED once signed)")
    @PatchMapping("/api/acts/{id}")
    public WorkActResponse updateHeader(@PathVariable UUID id,
                                        @Valid @RequestBody WorkActUpdateRequest req,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        return workActService.updateHeader(id, req, principal.id());
    }

    @Operation(summary = "Replace the act's lines wholesale (line_total + cumulative_before are server-authored)")
    @PutMapping("/api/acts/{id}/items")
    public WorkActResponse replaceItems(@PathVariable UUID id,
                                        @Valid @RequestBody WorkActItemsRequest req,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        return workActService.replaceItems(id, req, principal.id());
    }

    @Operation(summary = "Delete an act — only DRAFT / REJECTED")
    @DeleteMapping("/api/acts/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        workActService.delete(id, principal.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Sign the act on the client's behalf (offline path); "
            + "creates the ADDENDUM estimate if it has additional positions")
    @PostMapping("/api/acts/{id}/sign-offline")
    public WorkActResponse signOffline(@PathVariable UUID id,
                                       @Valid @RequestBody WorkActSignOfflineRequest req,
                                       @AuthenticationPrincipal UserPrincipal principal)
            throws IOException, DocumentException {
        return workActService.signOffline(id, req, principal.id());
    }

    @Operation(summary = "Owner-side status move: SENT→DRAFT (recall), SENT→REJECTED (client "
            + "declined), REJECTED→DRAFT — anything else is a 409")
    @PatchMapping("/api/acts/{id}/status")
    public WorkActResponse changeStatus(@PathVariable UUID id,
                                        @Valid @RequestBody WorkActStatusRequest req,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        return workActService.changeStatus(id, req.status(), principal.id());
    }

    @Operation(summary = "Download the act as a PDF")
    @GetMapping(value = "/api/acts/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id,
                                      @AuthenticationPrincipal UserPrincipal principal)
            throws IOException, DocumentException {
        byte[] body = workActService.renderPdf(id, principal.id());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"act-" + id + ".pdf\"")
                .body(body);
    }

    // ---- receipts & invoices («Чеки та рахунки») --------------------------

    @Operation(summary = "List the act's receipts")
    @GetMapping("/api/acts/{id}/receipts")
    public List<WorkActReceiptResponse> receipts(@PathVariable UUID id,
                                                 @AuthenticationPrincipal UserPrincipal principal) {
        return receiptService.list(id, principal.id());
    }

    @Operation(summary = "Attach a receipt: a label, an amount and an optional photo of the paper "
            + "(409 WORK_ACT_SIGNED once signed — receipts are part of the doc_hash)")
    @PostMapping(value = "/api/acts/{id}/receipts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<WorkActReceiptResponse> addReceipt(
            @PathVariable UUID id,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam("label") String label,
            @RequestParam("amount") BigDecimal amount,
            @RequestParam(value = "issuedAt", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issuedAt,
            @AuthenticationPrincipal UserPrincipal principal) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(receiptService.add(id, principal.id(), file, label, amount, issuedAt));
    }

    @Operation(summary = "Edit a receipt's label / amount / date (the photo is set once, at upload)")
    @PatchMapping("/api/acts/{id}/receipts/{receiptId}")
    public WorkActReceiptResponse updateReceipt(@PathVariable UUID id,
                                                @PathVariable UUID receiptId,
                                                @Valid @RequestBody WorkActReceiptRequest req,
                                                @AuthenticationPrincipal UserPrincipal principal) {
        return receiptService.update(id, receiptId, principal.id(), req);
    }

    @Operation(summary = "Delete a receipt")
    @DeleteMapping("/api/acts/{id}/receipts/{receiptId}")
    public ResponseEntity<Void> deleteReceipt(@PathVariable UUID id,
                                              @PathVariable UUID receiptId,
                                              @AuthenticationPrincipal UserPrincipal principal) {
        receiptService.delete(id, receiptId, principal.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Stream a receipt photo (authenticated owner)")
    @GetMapping("/api/acts/{id}/receipts/{receiptId}/file")
    public ResponseEntity<byte[]> receiptFile(@PathVariable UUID id,
                                              @PathVariable UUID receiptId,
                                              @AuthenticationPrincipal UserPrincipal principal) throws IOException {
        PhotoFile f = receiptService.readOwnedFile(id, receiptId, principal.id());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(f.bytes());
    }

    // ---- act share link (one link per act, prompt 5) ----------------------

    @Operation(summary = "State of this act's client share link (url + whether shared)")
    @GetMapping("/api/acts/{id}/share")
    public ActShareStateResponse shareState(@PathVariable UUID id,
                                            @AuthenticationPrincipal UserPrincipal principal) {
        return portalService.actState(id, principal.id());
    }

    @Operation(summary = "Publish this act to its client link (DRAFT → SENT, mints/reuses the link)")
    @PutMapping("/api/acts/{id}/share")
    public ActShareStateResponse share(@PathVariable UUID id,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        return portalService.updateAct(id, principal.id());
    }

    @Operation(summary = "Email the act's client link")
    @PostMapping("/api/acts/{id}/share/send-email")
    public ActShareStateResponse shareSendEmail(@PathVariable UUID id,
                                                @AuthenticationPrincipal UserPrincipal principal) {
        return portalService.sendActEmail(id, principal.id());
    }
}

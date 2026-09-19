package com.majstr.backend.controller;

import com.majstr.backend.dto.CashEntryKind;
import com.majstr.backend.dto.CashEntryRequest;
import com.majstr.backend.dto.CashFlowResponse;
import com.majstr.backend.dto.CashSummaryResponse;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.CashFlowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * «Мої гроші» (V135) — the master's own cash movement, not any one object's.
 *
 * <p>Owner-scoped throughout and ungated: it ships FREE alongside the rest of the economy. Nothing
 * here is object-addressed, which makes it the first surface in this API that is about the MASTER
 * rather than about a job.</p>
 */
@RestController
@RequestMapping("/api/cash")
@RequiredArgsConstructor
@Tag(name = "Cash flow", description = "The master's own income and spending across all objects")
@SecurityRequirement(name = "bearer-jwt")
public class CashController {

    private final CashFlowService cashService;

    /**
     * One period's movement. Both bounds are inclusive and optional — absent means the current
     * calendar month in {@code Europe/Kyiv}, so the app never has to agree with the server about
     * what «сьогодні» is.
     *
     * @param monthly the YEAR view: per-month totals instead of a flat list
     */
    @GetMapping
    @Operation(summary = "Income and spending for a period, across all objects and beside them")
    public CashFlowResponse flow(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean monthly,
            @AuthenticationPrincipal UserPrincipal principal) {
        return cashService.flow(principal.id(), from, to, monthly);
    }

    /** The home strip: this MONTH in three numbers — the window the tap then opens the screen on. */
    @GetMapping("/summary")
    @Operation(summary = "This month's totals for the home screen")
    public CashSummaryResponse summary(@AuthenticationPrincipal UserPrincipal principal) {
        return cashService.summary(principal.id());
    }

    /**
     * Add income or spending — always the master's OWN row.
     *
     * <p>It deliberately asks nothing about an object: money that belongs to one is already in that
     * object's journal and shows up on the read path by itself. What lands here is what no object
     * knows about.</p>
     *
     * <p>{@code X-Entity-Uuid} makes the create idempotent, like every other offline-capable create
     * here: a replayed queue entry must never bill the same money twice.</p>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add an income or an expense of the master's own")
    public CashFlowResponse.Entry create(
            @Valid @RequestBody CashEntryRequest req,
            @RequestHeader(value = "X-Entity-Uuid", required = false) UUID entityId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return cashService.create(principal.id(), req, entityId);
    }

    /**
     * Edit ANY row of the feed — his own, or an object's payment or expense.
     *
     * <p>{@code kind} in the body says which table the row lives in; the three id spaces are
     * separate, and the client always knows because the feed told it. An object row is written
     * through that object's own service, so its rules still apply — notably the refusal to edit an
     * expense a V129 till receipt owns (400, «правити його треба на чеку»).</p>
     */
    @PatchMapping("/{id}")
    @Operation(summary = "Edit a cash entry — personal, or an object's own payment/expense")
    public CashFlowResponse.Entry update(@PathVariable UUID id,
                                         @Valid @RequestBody CashEntryRequest req,
                                         @AuthenticationPrincipal UserPrincipal principal) {
        return cashService.update(principal.id(), id, req);
    }

    /**
     * Idempotent for every kind: a row already gone is a no-op, so a replayed queue op is harmless.
     *
     * <p>{@code kind} is a query parameter rather than a body field because a DELETE carries none;
     * absent it means PERSONAL, which is what an older client could only ever have meant.</p>
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a cash entry — personal, or an object's own payment/expense")
    public void delete(@PathVariable UUID id,
                       @RequestParam(required = false) CashEntryKind kind,
                       @AuthenticationPrincipal UserPrincipal principal) {
        cashService.delete(principal.id(), id, kind);
    }
}

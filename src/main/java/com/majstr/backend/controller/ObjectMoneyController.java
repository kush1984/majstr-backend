package com.majstr.backend.controller;

import com.majstr.backend.dto.ObjectMoneyResponse;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.CashFlowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * «Гроші обʼєкта» — the object's own rows of «Мої гроші», over its whole life. Owner-only and
 * ungated, like «Мої гроші» itself; nothing here reaches the client.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Cash flow", description = "The master's own income and spending across all objects")
@SecurityRequirement(name = "bearer-jwt")
public class ObjectMoneyController {

    private final CashFlowService cashService;

    @Operation(summary = "The object's money: what came in for the work, what the master spent, "
            + "what he earned, and the client's material balance")
    @GetMapping("/api/projects/{id}/money")
    public ObjectMoneyResponse money(@PathVariable UUID id,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        return cashService.objectMoney(principal.id(), id);
    }
}

package com.majstr.backend.dto;

import com.majstr.backend.entity.WorkActStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Owner-side act status move (review fix): SENT→DRAFT (recall), SENT→REJECTED (the client
 * declined), REJECTED→DRAFT (the client came around). Anything else — including touching a SIGNED
 * act — is a 409; the allowed set is enforced in {@code WorkActService#changeStatus}.
 */
public record WorkActStatusRequest(
        @NotNull WorkActStatus status
) {}

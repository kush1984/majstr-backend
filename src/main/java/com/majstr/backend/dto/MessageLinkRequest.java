package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What somebody sends through the master's message link.
 *
 * <p>Separate from {@link QuestionRequest}, and not by accident: there the name is optional, because a
 * client already known to the master is asking about their own estimate. Here the link can reach
 * anyone, so a message with no name is a message the master cannot act on — «Рахунок у вкладенні» from
 * nobody is not a lead. Hence {@code @NotBlank}, per the owner's ask.</p>
 *
 * <p>The phone stays optional; when it is given, the app offers a one-tap call.</p>
 */
public record MessageLinkRequest(
        @NotBlank @Size(max = 255) String authorName,
        @Size(max = 50) String authorPhone,
        @NotBlank @Size(max = 2000) String message
) {}

package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The client signs — a work act or an estimate, the portal shape is the same for both.
 *
 * <p>{@code version} is the {@code @Version} of the document the page RENDERED, and it is required
 * (review B-61). Both documents stay editable right up to the signature: the client opened «До
 * сплати» 20 000, the master added a 5 000 line while he read it, and the tap signed 25 000 with
 * the client's name, phone and IP on a document he never saw. A signature without a version is a
 * signature on «whatever it says now», which is precisely what may not be accepted — so the field
 * is {@code @NotNull} on a WRAPPER (a primitive would make an omitted value a 400 about malformed
 * JSON instead of a named, translatable one) and a mismatch answers 409 «перегляньте ще раз».</p>
 */
public record SignRequest(
        @NotBlank @Size(max = 255) String clientName,
        @NotBlank @Size(max = 50) String clientPhone,
        @NotNull Long version
) {}

package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * «Наступного разу, коли я скажу X, — це ось ця позиція мого каталогу.» Taught from the dictation
 * review after commit. The pair is unique per master: teaching a new target for an already-taught
 * wording overwrites the old one (delete + insert in the same tx), never inserts a second row.
 */
public record DictationSynonymRequest(
        @NotNull UUID catalogItemId,
        @NotBlank @Size(max = 200) String spokenText
) {}

package com.majstr.backend.dto;

import jakarta.validation.constraints.Size;

/** Move a photo between the Фото tab's folders (photo-folders): {@code RECEIPTS} = «Чеки»,
 *  null/blank = «Інше», anything else = a custom folder that exists by being named. */
public record PhotoFolderRequest(
        @Size(max = 100) String folder
) {}

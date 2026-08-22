package com.majstr.backend.dto;

import java.util.UUID;

/** One CUSTOM folder of the Фото tab (photo-folders). The defaults — «Чеки» (the reserved
 *  RECEIPTS value) and «Інше» (null) — are virtual and never listed here. */
public record ProjectPhotoFolderResponse(
        UUID id,
        String name
) {}

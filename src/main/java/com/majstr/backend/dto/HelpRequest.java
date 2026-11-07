package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * «Потрібна допомога з налаштуванням? Залиште телефон — передзвонимо» (V191). The phone is saved to the
 * profile (documents print it too) and the moment he asked is stamped for the admin's call list.
 */
public record HelpRequest(
        @NotBlank @Size(min = 5, max = 50) String phone
) {}

package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * «Увійти через Google»: the ID token Google handed the page, plus the same first-touch attribution a
 * registration carries (used only when this creates the account). Consent is the button's own line —
 * «Продовжуючи, ви погоджуєтесь з політикою конфіденційності» — and is stamped on a new account.
 */
public record GoogleSignInRequest(
        @NotBlank @Size(max = 4096) String credential,
        @Size(max = 40) String ref,
        @Size(max = 40) String promoCode,
        @Size(max = 60) String utmSource,
        @Size(max = 60) String utmMedium,
        @Size(max = 100) String utmCampaign,
        @Size(max = 100) String referrerHost,
        @Size(max = 100) String entryPath
) {}

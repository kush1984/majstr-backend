package com.majstr.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class VisitorAgentTest {

    @Test
    void anIphoneIsAMobileSafari() {
        assertThat(VisitorAgent.of("Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 "
                + "(KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1"))
                .isEqualTo(new VisitorAgent("mobile", "iOS", "Safari"));
    }

    @Test
    void anAndroidPhoneInSamsungsBrowserIsNotChrome() {
        assertThat(VisitorAgent.of("Mozilla/5.0 (Linux; Android 14; SM-A546B) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "SamsungBrowser/26.0 Chrome/122.0.0.0 Mobile Safari/537.36"))
                .isEqualTo(new VisitorAgent("mobile", "Android", "Samsung"));
    }

    @Test
    void tiktoksOwnBrowserIsNamed_itOftenSendsNoReferrer() {
        assertThat(VisitorAgent.of("Mozilla/5.0 (Linux; Android 13; RMX3630) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Version/4.0 Chrome/120.0 Mobile Safari/537.36 trill_340302 BytedanceWebview/d8a21c6"))
                .isEqualTo(new VisitorAgent("mobile", "Android", "TikTok"));
        assertThat(VisitorAgent.of("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
                + "(KHTML, like Gecko) Mobile/15E148 musical_ly_35.1.0 JsSdk/2.0").browser())
                .isEqualTo("TikTok");
    }

    @Test
    void instagramAndFacebookAreNamedToo() {
        assertThat(VisitorAgent.of("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
                + "(KHTML, like Gecko) Mobile/15E148 Instagram 345.0.0.32.93").browser()).isEqualTo("Instagram");
        assertThat(VisitorAgent.of("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Version/4.0 Chrome/124.0 Mobile Safari/537.36 [FB_IAB/FB4A;FBAV/460.0.0.48.109;]").browser())
                .isEqualTo("Facebook");
    }

    @Test
    void desktopsAndTablets() {
        assertThat(VisitorAgent.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"))
                .isEqualTo(new VisitorAgent("desktop", "Windows", "Edge"));
        assertThat(VisitorAgent.of("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
                + "(KHTML, like Gecko) Version/18.0 Safari/605.1.15"))
                .isEqualTo(new VisitorAgent("desktop", "macOS", "Safari"));
        assertThat(VisitorAgent.of("Mozilla/5.0 (Linux; Android 13; SM-X200) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/120.0.0.0 Safari/537.36").device()).isEqualTo("tablet");
    }

    @Test
    void aPhoneWhoseBrandEndsInBotIsStillAPerson() {
        assertThat(VisitorAgent.of("Mozilla/5.0 (Linux; Android 10; CUBOT X30) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/119.0 Mobile Safari/537.36")).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
            "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; bingbot/2.0)",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/130.0 Safari/537.36",
            "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
            "TelegramBot (like TwitterBot)",
            "WhatsApp/2.24.8.78 A",
            "curl/8.4.0",
            "python-requests/2.31",
            "Java/21.0.4",
            "Java-http-client/21.0.4",
            "node",
            "undici",
            " "
    })
    void crawlersPreviewsAndScriptsAreNotVisitors(String userAgent) {
        assertThat(VisitorAgent.of(userAgent)).isNull();
    }

    @Test
    void noBrowserStringIsNotAVisitor() {
        assertThat(VisitorAgent.of(null)).isNull();
    }
}

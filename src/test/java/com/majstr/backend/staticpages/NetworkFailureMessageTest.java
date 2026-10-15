package com.majstr.backend.staticpages;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A failed fetch on a client page is not always the client's internet — the server may be down or
 * redeploying. «Перевірте інтернет» is said only when the browser itself reports being offline;
 * otherwise «Сервіс тимчасово недоступний». Read as TEXT, like the other static-page guards.
 */
class NetworkFailureMessageTest {

    @ParameterizedTest
    @ValueSource(strings = {"portal", "message"})
    void checkYourInternetIsSaidOnlyWhenTheBrowserIsOffline(String page) throws IOException {
        String text = Files.readString(Path.of("src/main/resources/static", page, "index.html"),
                StandardCharsets.UTF_8);
        assertThat(text).contains("navigator.onLine").contains("Сервіс тимчасово недоступний");
        assertThat(text.split("Перевірте інтернет", -1)).as("one offline wording per page").hasSize(2);
    }
}

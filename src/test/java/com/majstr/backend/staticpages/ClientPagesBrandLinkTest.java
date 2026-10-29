package com.majstr.backend.staticpages;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every client who opens a master's link is someone who might know another master; the footer link
 * is how they find Majstr, and its utm_source is how the admin funnel tells that channel apart.
 */
class ClientPagesBrandLinkTest {

    private static final Path STATIC = Path.of("src/main/resources/static");

    @ParameterizedTest
    @ValueSource(strings = {"portal", "message"})
    void clientPageLinksToMajstrTaggedWithItsOwnSource(String name) throws IOException {
        String page = Files.readString(STATIC.resolve(name).resolve("index.html"), StandardCharsets.UTF_8)
                .replaceAll("(?s)<!--.*?-->", " ");
        assertThat(page).contains("href=\"https://majstr.pro/?utm_source=" + name + "&amp;utm_medium=referral");
    }
}

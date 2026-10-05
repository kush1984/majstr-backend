package com.majstr.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two message bundles must carry the SAME key set.
 *
 * <p>{@code messages.properties} is Ukrainian and doubles as the fallback, so a key missing from
 * {@code messages_en.properties} does not fail, log or even look wrong — an English-locale master is
 * simply shown Ukrainian. That is exactly how {@code error.work-act.addendum-line},
 * {@code over-estimate} and {@code percent-line} shipped in one round and the material-norm /
 * material-pref keys in another: nothing anywhere reads both files.</p>
 *
 * <p>Deliberately a SET comparison in both directions and nothing more. It says nothing about the
 * wording, the placeholders or the order — a translation is a human judgement, while a missing key
 * is a mechanical fact, and this test exists only for the mechanical half.</p>
 */
class LocalizationBundleParityTest {

    @Test
    void bothBundlesCarryTheSameKeys() throws IOException {
        Set<String> uk = keysOf("messages.properties");
        Set<String> en = keysOf("messages_en.properties");

        assertThat(new TreeSet<>(difference(uk, en)))
                .as("keys in the Ukrainian base bundle with no English translation — an en-locale "
                        + "master silently falls back to Ukrainian text")
                .isEmpty();
        assertThat(new TreeSet<>(difference(en, uk)))
                .as("keys only in the English bundle — the Ukrainian base is what every throw site "
                        + "is written against, so these are dead or the base is missing one")
                .isEmpty();
    }

    private static Set<String> difference(Set<String> from, Set<String> minus) {
        Set<String> out = new LinkedHashSet<>(from);
        out.removeAll(minus);
        return out;
    }

    private static Set<String> keysOf(String resource) throws IOException {
        Properties props = new Properties();
        try (InputStream in = LocalizationBundleParityTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(in).as(resource + " must be on the classpath").isNotNull();
            props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return new LinkedHashSet<>(props.stringPropertyNames());
    }
}

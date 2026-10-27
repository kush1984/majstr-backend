package com.majstr.backend.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The referrer host and entry page arrive from the visitor's browser (V178); only a plain host and
 * a plain path are kept — a query is where a token or a personal value would ride.
 */
class FirstTouchTest {

    @Test
    void aHostIsLowerCasedAndLosesItsWww() {
        assertThat(FirstTouch.host(" WWW.Google.com ")).isEqualTo("google.com");
        assertThat(FirstTouch.host("l.facebook.com")).isEqualTo("l.facebook.com");
    }

    @Test
    void anythingButAPlainHostIsDropped() {
        assertThat(FirstTouch.host("https://google.com/search?q=x")).isNull();
        assertThat(FirstTouch.host("localhost")).isNull();
        assertThat(FirstTouch.host("")).isNull();
        assertThat(FirstTouch.host(null)).isNull();
    }

    @Test
    void aPathLosesItsQueryAndFragment() {
        assertThat(FirstTouch.path("/kalkulyator-plytky?utm_source=x&token=abc#top"))
                .isEqualTo("/kalkulyator-plytky");
        assertThat(FirstTouch.path("/")).isEqualTo("/");
    }

    @Test
    void anythingButAPlainPathIsDropped() {
        assertThat(FirstTouch.path("kalkulyator-plytky")).isNull();
        assertThat(FirstTouch.path("/<script>")).isNull();
        assertThat(FirstTouch.path(null)).isNull();
    }
}

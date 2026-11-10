package com.majstr.backend.service;

import com.majstr.backend.config.JwtProperties;
import com.majstr.backend.security.JwtService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PortalPreviewTokensTest {

    private static final JwtProperties JWT =
            new JwtProperties("preview-test-secret-of-at-least-32-bytes", 15, 30, 30, "majstr");
    private static final Instant ISSUED = Instant.parse("2026-11-09T10:00:00Z");

    @Test
    void aPassNamesItsEstimate_forTenMinutes() {
        UUID estimate = UUID.randomUUID();
        String pass = at(ISSUED).issue(estimate);

        assertThat(at(ISSUED.plusSeconds(599)).estimateOf(pass)).contains(estimate);
        assertThat(at(ISSUED.plusSeconds(600)).estimateOf(pass)).isEmpty();
    }

    @Test
    void anythingTheServerDidNotSign_isNobody() {
        String pass = at(ISSUED).issue(UUID.randomUUID());
        PortalPreviewTokens tokens = at(ISSUED);

        assertThat(tokens.estimateOf(null)).isEmpty();
        assertThat(tokens.estimateOf("")).isEmpty();
        assertThat(tokens.estimateOf("abc")).isEmpty();
        assertThat(tokens.estimateOf(pass + ".x")).isEmpty();
        // Another estimate's head under this one's signature.
        String other = at(ISSUED).issue(UUID.randomUUID());
        assertThat(tokens.estimateOf(other.substring(0, other.indexOf('.')) + pass.substring(pass.indexOf('.'))))
                .isEmpty();
        // Another deployment's secret.
        PortalPreviewTokens elsewhere = new PortalPreviewTokens(
                new JwtProperties("another-secret-of-at-least-thirty-two-b", 15, 30, 30, "majstr"),
                Clock.fixed(ISSUED, ZoneOffset.UTC));
        assertThat(elsewhere.estimateOf(pass)).isEmpty();
    }

    @Test
    void anAccessTokenIsNeverAPass() {
        String access = new JwtService(JWT).generateAccessToken(UUID.randomUUID(), "m@majstr.test");
        assertThat(at(Instant.now()).estimateOf(access)).isEmpty();
    }

    @Test
    void aPortalPassNamesTheObjectAndTheTickedEstimates_inOrder_andIsNeverAnEstimatePass() {
        UUID project = UUID.randomUUID();
        List<UUID> ticked = List.of(UUID.randomUUID(), UUID.randomUUID());
        String pass = at(ISSUED).issuePortal(project, ticked);

        assertThat(at(ISSUED.plusSeconds(599)).portalOf(pass))
                .contains(new PortalPreviewTokens.PortalPass(project, ticked));
        assertThat(at(ISSUED.plusSeconds(600)).portalOf(pass)).isEmpty();
        // The two kinds never stand in for each other.
        assertThat(at(ISSUED).estimateOf(pass)).isEmpty();
        assertThat(at(ISSUED).portalOf(at(ISSUED).issue(UUID.randomUUID()))).isEmpty();
        // An empty selection is a legal preview: the portal with nothing on it.
        assertThat(at(ISSUED).portalOf(at(ISSUED).issuePortal(project, List.of())))
                .contains(new PortalPreviewTokens.PortalPass(project, List.of()));
    }

    private static PortalPreviewTokens at(Instant now) {
        return new PortalPreviewTokens(JWT, Clock.fixed(now, ZoneOffset.UTC));
    }
}

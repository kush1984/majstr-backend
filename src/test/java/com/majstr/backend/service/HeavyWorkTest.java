package com.majstr.backend.service;

import com.majstr.backend.exception.ServerBusyException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeavyWorkTest {

    private final HeavyWork heavyWork = new HeavyWork(1, 1, 0);

    @Test
    void aPdfWithAFreePermitRuns() throws Exception {
        assertThat(heavyWork.pdf(() -> new byte[]{7})).containsExactly(7);
    }

    @Test
    void aPdfWithNoPermitIsTurnedAwayWithARetryHint() throws Exception {
        heavyWork.pdf(() -> {
            assertThatThrownBy(() -> heavyWork.pdf(() -> new byte[0]))
                    .isInstanceOfSatisfying(ServerBusyException.class,
                            busy -> assertThat(busy.getRetryAfterSeconds()).isPositive());
            return new byte[0];
        });
    }

    @Test
    void aFailedPdfGivesItsPermitBack() throws Exception {
        assertThatThrownBy(() -> heavyWork.pdf(() -> {
            throw new IOException("render failed");
        })).isInstanceOf(IOException.class);

        assertThat(heavyWork.pdf(() -> new byte[]{1})).containsExactly(1);
    }

    @Test
    void anImportNeverWaitsForAPermit() {
        heavyWork.enterImport();

        assertThatThrownBy(heavyWork::enterImport).isInstanceOf(ServerBusyException.class);

        heavyWork.leaveImport();
        heavyWork.enterImport();
    }

    @Test
    void importsAndPdfsDoNotShareTheirPermits() throws Exception {
        heavyWork.enterImport();

        assertThat(heavyWork.pdf(() -> new byte[]{1})).containsExactly(1);
    }
}

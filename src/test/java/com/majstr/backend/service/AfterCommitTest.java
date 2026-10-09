package com.majstr.backend.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review B-96: «a rolled-back signature sends nothing» had no test. The sign-path tests use a
 * {@code @Spy AfterCommit} that runs the action at once, which is exactly the case where nothing
 * waits for a commit — so a push fired from inside a transaction that then rolls back would pass
 * every one of them.
 */
class AfterCommitTest {

    private final AfterCommit afterCommit = new AfterCommit();

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void aRolledBackTransactionSendsNothing() {
        AtomicInteger sent = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();

        afterCommit.run(sent::incrementAndGet);
        assertThat(sent).hasValue(0); // registered, not run
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(sent).hasValue(0);
    }

    @Test
    void aCommittedOneSendsOnce() {
        AtomicInteger sent = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();

        afterCommit.run(sent::incrementAndGet);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        assertThat(sent).hasValue(1);
    }
}

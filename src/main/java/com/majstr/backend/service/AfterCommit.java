package com.majstr.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Run an OUTWARD side effect only once the transaction that justifies it has committed
 * (review B-81).
 *
 * <p>Signing an act emails the client a stamped PDF and pushes the master a notification. Both
 * fired from inside the signing transaction, and a transaction can still lose: the optimistic lock
 * on the document (B-60/B-61), a constraint, the connection dropping on commit. The client then
 * holds a «signed» copy of a signature that does not exist — and unlike a database row, an email
 * cannot be rolled back. {@code @Async} does not help; it only makes the race non-deterministic.</p>
 *
 * <p>Whatever the effect needs must be read BEFORE it is handed over: by the time this runs the
 * entities are detached and no lazy association can be walked. That is the same discipline
 * {@link StorageCleanup} follows, and for the same reason — it is the one tidy thing about an
 * irreversible step.</p>
 *
 * <p>With no transaction in progress the action runs at once: there is nothing to wait for.</p>
 */
@Slf4j
@Component
public class AfterCommit {

    public void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            quietly(action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                quietly(action);
            }
        });
    }

    private void quietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // The commit already happened; throwing here would turn a successful write into a 500
            // over a notification. Every caller treats its own side effect as fail-soft anyway.
            log.warn("After-commit action failed: {}", e.getMessage());
        }
    }
}

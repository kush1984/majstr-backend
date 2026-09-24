package com.majstr.backend.service;

import com.majstr.backend.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * Deleting a stored file is the one step in a delete that cannot be rolled back (review B-25).
 *
 * <p>Every delete here used to run {@code storage.delete(key)} inside the transaction that removes
 * the row. The order is backwards: if anything after it fails — a constraint, an optimistic lock, a
 * connection dropping on commit — the row survives and the file behind it does not. The receipt
 * still claims a photo, the photo endpoint 404s, and the master is looking at a document whose
 * proof we destroyed on his behalf. The reverse leak is merely expensive: a file nobody references
 * costs storage and nothing else.</p>
 *
 * <p>So the blob is deleted <b>after the commit</b>, and it is deliberately fail-soft there — by
 * the time the synchronization runs the row is already gone, and throwing would only turn a
 * successful delete into a 500 the master cannot act on. With no transaction in progress (the
 * non-transactional create paths, which clean up a blob whose row never landed) the delete happens
 * at once, which is the same guarantee: nothing references it.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageCleanup {

    private final StorageService storage;

    /** Delete one stored object once the current transaction commits. Blank keys are ignored. */
    public void afterCommit(String key) {
        afterCommit(List.of(key == null ? "" : key));
    }

    /**
     * Delete these stored objects once the current transaction commits — the keys must be collected
     * BEFORE the rows disappear, since a cascade takes the only pointer to them with it (B-26).
     */
    public void afterCommit(Collection<String> keys) {
        List<String> live = keys.stream().filter(k -> k != null && !k.isBlank()).toList();
        if (live.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            live.forEach(this::deleteQuietly);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                live.forEach(StorageCleanup.this::deleteQuietly);
            }
        });
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (IOException | RuntimeException e) {
            // A leftover object is recoverable; a failed delete of a row already gone is not.
            log.warn("Could not delete stored object {}: {}", key, e.getMessage());
        }
    }
}

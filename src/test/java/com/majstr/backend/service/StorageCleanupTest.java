package com.majstr.backend.service;

import com.majstr.backend.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Review B-111: a blob stored inside a transaction that then rolls back must not outlive it.
 *
 * <p>The add paths used to call {@code afterCommit} from a catch block inside the failing
 * transaction — a hook that, by definition, never runs on a rollback. The unit test of the photo
 * service mocked {@code StorageCleanup}, so it could not see that.</p>
 */
class StorageCleanupTest {

    private final StorageService storage = mock(StorageService.class);
    private final StorageCleanup cleanup = new StorageCleanup(storage);

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void aRolledBackTransactionDropsTheBlobItStored() throws Exception {
        TransactionSynchronizationManager.initSynchronization();

        cleanup.onRollback("photos/a.jpg");
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(storage).delete("photos/a.jpg");
    }

    @Test
    void aCommittedTransactionKeepsIt() throws Exception {
        TransactionSynchronizationManager.initSynchronization();

        cleanup.onRollback("photos/a.jpg");
        complete(TransactionSynchronization.STATUS_COMMITTED);

        verify(storage, never()).delete("photos/a.jpg");
    }

    @Test
    void outsideATransactionItDoesNothing_theCallerCleansUpItself() {
        cleanup.onRollback("photos/a.jpg");

        verifyNoInteractions(storage);
    }

    private static void complete(int status) {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(status);
        }
    }
}

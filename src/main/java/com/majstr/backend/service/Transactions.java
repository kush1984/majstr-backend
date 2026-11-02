package com.majstr.backend.service;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * A transaction around the database part of a call only (review B-121). A method that also waits on
 * storage, or hashes a password, cannot carry {@code @Transactional}: the pool holds ten connections,
 * and the one it pins for that wait is one nobody else can have.
 */
final class Transactions {

    private Transactions() {
    }

    static <T> T read(PlatformTransactionManager manager, Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setReadOnly(true);
        return template.execute(status -> work.get());
    }

    static <T> T write(PlatformTransactionManager manager, Supplier<T> work) {
        return new TransactionTemplate(manager).execute(status -> work.get());
    }
}

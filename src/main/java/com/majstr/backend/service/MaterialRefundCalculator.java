package com.majstr.backend.service;

import com.majstr.backend.repository.PaymentReceiptRepository;
import com.majstr.backend.repository.ProjectReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The one place an object's refund split is read from the database (review B-65).
 *
 * <p>Three surfaces answer the same question — the master's payments summary, the FREE-visible
 * materials axis beside it, and the client's portal card — and the bug was precisely that each had
 * its own idea of what «Отримано» paid for. A shared {@link MaterialRefundSplit} is not enough on
 * its own: the two QUERIES that feed it must not drift either, or one screen's «повернення» would be
 * measured against a receivable another screen filters differently.</p>
 */
@Component
@RequiredArgsConstructor
public class MaterialRefundCalculator {

    private final PaymentReceiptRepository paymentReceiptRepository;
    private final ProjectReceiptRepository projectReceiptRepository;

    public MaterialRefundSplit forObject(UUID objectId) {
        return MaterialRefundSplit.of(
                paymentReceiptRepository.sumMaterialRefunds(objectId),
                projectReceiptRepository.sumReimbursable(objectId));
    }
}

package com.majstr.backend.service;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActReceipt;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.WorkActItemRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * The expense a signed act posts for each receipt is dated by the day the master ADDED the receipt,
 * not by the paper (master decision): a slip whose «14-11-25» was read as 2014 sent the spend eleven
 * years back, and «Мої гроші» seemed never to have received it.
 */
@ExtendWith(MockitoExtension.class)
class ActAddendumCreatorTest {

    @Mock WorkActItemRepository itemRepository;
    @Mock WorkActReceiptRepository receiptRepository;
    @Mock EstimateRepository estimateRepository;
    @Mock EstimateItemRepository estimateItemRepository;
    @Mock ObjectExpenseRepository expenseRepository;
    @InjectMocks ActAddendumCreator creator;

    @Test
    @SuppressWarnings("unchecked")
    void aReceiptExpenseIsDatedByTheDayItWasAdded_notByThePaper() {
        Instant added = Instant.now().minus(3, ChronoUnit.DAYS);
        WorkAct act = WorkAct.builder().id(UUID.randomUUID()).number("7")
                .project(Project.builder().id(UUID.randomUUID()).build())
                .receiptsToExpenses(true).build();
        WorkActReceipt slip = WorkActReceipt.builder().id(UUID.randomUUID()).label("ЕПІЦЕНТР")
                .amount(new BigDecimal("204.90")).issuedAt(LocalDate.of(2014, 11, 25))
                .createdAt(added).build();
        given(itemRepository.findByWorkActIdOrderBySortOrderAscIdAsc(act.getId())).willReturn(List.of());
        given(receiptRepository.findByWorkActIdNewestFirst(act.getId())).willReturn(List.of(slip));
        given(estimateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        creator.createIfNeeded(act);

        ArgumentCaptor<List<ObjectExpense>> posted = ArgumentCaptor.forClass(List.class);
        verify(expenseRepository).saveAll(posted.capture());
        assertThat(posted.getValue()).singleElement().satisfies(e -> {
            assertThat(e.getSpentAt()).isEqualTo(LocalDate.ofInstant(added, LocalizationConfig.ZONE));
            assertThat(e.getAmount()).isEqualByComparingTo("204.90");
        });
    }
}

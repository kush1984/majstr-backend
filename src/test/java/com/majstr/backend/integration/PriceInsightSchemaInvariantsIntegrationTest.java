package com.majstr.backend.integration;

import com.majstr.backend.entity.CatalogInsightDismissal;
import com.majstr.backend.entity.CatalogInsightKind;
import com.majstr.backend.entity.CatalogUpdateNotice;
import com.majstr.backend.entity.CatalogUpdateNoticeKind;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.CatalogInsightDismissalRepository;
import com.majstr.backend.repository.CatalogUpdateNoticeRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V94's two DB-level invariants — real CHECK constraints, so only a real Postgres proves they
 * actually reject what they claim to. A Mockito test cannot see a database refuse a row.
 */
class PriceInsightSchemaInvariantsIntegrationTest extends IntegrationTestBase {

    @Autowired UserRepository userRepository;
    @Autowired CatalogUpdateNoticeRepository noticeRepository;
    @Autowired CatalogInsightDismissalRepository dismissalRepository;

    private UUID userId() {
        String unique = UUID.randomUUID().toString();
        return userRepository.save(User.builder()
                .email(unique + "@majstr.test").emailCanonical(unique + "@majstr.test")
                .passwordHash("x").fullName("Майстер").phone("+380000000000")
                .companyName("ФОП").plan(Plan.PRO).referralCode(unique.substring(0, 10)).build())
                .getId();
    }

    @Test
    void aPriceDriftNoticeMissingItsPriceFieldsIsRejected() {
        CatalogUpdateNotice halfShaped = CatalogUpdateNotice.builder()
                .userId(userId()).kind(CatalogUpdateNoticeKind.PRICE_DRIFT)
                // positionName/oldPrice/newPrice left null — the CHECK must catch this, not just
                // application code, because a future writer could forget the same way this one
                // pretends to.
                .build();

        assertThatThrownBy(() -> noticeRepository.saveAndFlush(halfShaped))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aCountNoticeCarryingPriceFieldsIsRejected() {
        CatalogUpdateNotice wrongShape = CatalogUpdateNotice.builder()
                .userId(userId()).kind(CatalogUpdateNoticeKind.COUNT)
                .positionName("Штукатурка стін") // COUNT must never carry these
                .oldPrice(new BigDecimal("200")).newPrice(new BigDecimal("250"))
                .build();

        assertThatThrownBy(() -> noticeRepository.saveAndFlush(wrongShape))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aProperlyShapedPriceDriftNoticeIsAccepted() {
        CatalogUpdateNotice notice = CatalogUpdateNotice.builder()
                .userId(userId()).kind(CatalogUpdateNoticeKind.PRICE_DRIFT)
                .positionName("Штукатурка стін")
                .oldPrice(new BigDecimal("200")).newPrice(new BigDecimal("250"))
                .build();

        CatalogUpdateNotice saved = noticeRepository.saveAndFlush(notice);

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void twoPendingNoticesForTheSameMasterAreBothAllowed() {
        // The whole point of V94: no more UNIQUE(user_id) — a master can have a count notice
        // AND a price-drift notice pending at once.
        UUID user = userId();
        noticeRepository.saveAndFlush(CatalogUpdateNotice.builder()
                .userId(user).kind(CatalogUpdateNoticeKind.COUNT)
                .positionsAdded(3).positionsRemoved(0).build());
        noticeRepository.saveAndFlush(CatalogUpdateNotice.builder()
                .userId(user).kind(CatalogUpdateNoticeKind.PRICE_DRIFT)
                .positionName("Штукатурка стін")
                .oldPrice(new BigDecimal("200")).newPrice(new BigDecimal("250")).build());

        assertThat(noticeRepository.findByUserIdAndDismissedAtIsNullOrderByCreatedAtAsc(user)).hasSize(2);
    }

    @Test
    void priceDriftJoinsTheExistingDismissalMechanism() {
        CatalogInsightDismissal dismissal = CatalogInsightDismissal.builder()
                .kind(CatalogInsightKind.PRICE_DRIFT)
                .nameKey("штукатурка стін")
                .sampleName("Штукатурка стін")
                .build();

        CatalogInsightDismissal saved = dismissalRepository.saveAndFlush(dismissal);

        assertThat(saved.getId()).isNotNull();
    }
}

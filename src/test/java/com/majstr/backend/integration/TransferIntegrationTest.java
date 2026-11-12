package com.majstr.backend.integration;

import com.majstr.backend.dto.CatalogImportCommitRequest.DedupPolicy;
import com.majstr.backend.dto.TransferCommitRequest;
import com.majstr.backend.dto.TransferCommitRequest.CatalogLine;
import com.majstr.backend.dto.TransferCommitRequest.EstimateDoc;
import com.majstr.backend.dto.TransferCommitRequest.EstimateLine;
import com.majstr.backend.dto.TransferCommitResponse;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.TransferValidationException;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.importer.TradeFiler;
import com.majstr.backend.service.importer.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * «Перенести в Majstr» against Postgres: objects outside the FREE cap, one object per name,
 * positions filed under the library's trades, and the master's profile gaining those trades.
 */
@SpringBootTest
class TransferIntegrationTest extends IntegrationTestBase {

    @Autowired TransferService transfers;
    @Autowired TradeFiler tradeFiler;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private User master;

    @BeforeEach
    void seed() {
        String unique = UUID.randomUUID().toString();
        String email = unique + "@majstr.test";
        master = userRepository.save(User.builder()
                .email(email).emailCanonical(email).passwordHash("x").fullName("Перенос").emailVerified(true)
                .plan(Plan.FREE).role(Role.USER).referralCode(unique.substring(0, 10))
                .lifetimeProjectCount(2) // the FREE cap is already spent
                .build());
    }

    private static EstimateLine line(String name, Trade trade) {
        return new EstimateLine(name, Unit.M2, new BigDecimal("10"), new BigDecimal("150"), ItemType.WORK,
                "Кухня", trade, null);
    }

    @Test
    void movesEstimatesInOutsideTheFreeCap_oneObjectPerName_andFilesTheCatalog() {
        String fresh = "Позиція з перенесення " + UUID.randomUUID();
        TransferCommitRequest req = new TransferCommitRequest(
                List.of(new TransferCommitRequest.PriceList(List.of(
                        new CatalogLine(fresh, Unit.M2, new BigDecimal("420"), ItemType.WORK, Trade.TILING, "Підлога")))),
                List.of(
                        new EstimateDoc(null, "Квартира, Франка 12", "вул. Франка 12", "Олена Демченко",
                                "Кухня", new BigDecimal("5000"), null, List.of(line("Шпаклювання стін " + fresh, null))),
                        new EstimateDoc(null, "  квартира,  франка 12 ", null, "Олена Демченко",
                                "Ванна", null, false, List.of(line("Ванна " + fresh, null))),
                        new EstimateDoc(null, "Будинок у Дублянах", null, null,
                                null, null, null, List.of(line("Будинок " + fresh, null)))),
                DedupPolicy.SKIP);

        TransferCommitResponse res = transfers.commit(master.getId(), req);

        assertThat(res.objectsCreated()).isEqualTo(2);
        assertThat(res.estimatesCreated()).isEqualTo(3);
        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT (SELECT COUNT(*) FROM projects WHERE owner_id = ? AND transferred_at IS NOT NULL) AS objects,
                       (SELECT lifetime_project_count FROM users WHERE id = ?) AS lifetime,
                       (SELECT COUNT(*) FROM clients WHERE owner_id = ?) AS clients,
                       (SELECT COUNT(*) FROM payment_receipt r JOIN projects p ON p.id = r.project_id
                         WHERE p.owner_id = ?) AS receipts
                """, master.getId(), master.getId(), master.getId(), master.getId());
        assertThat(((Number) counts.get("objects")).intValue()).isEqualTo(2);
        assertThat(((Number) counts.get("lifetime")).intValue()).as("the FREE cap is untouched").isEqualTo(2);
        assertThat(((Number) counts.get("clients")).intValue()).as("one client, reused").isEqualTo(1);
        assertThat(((Number) counts.get("receipts")).intValue()).as("the deposit").isEqualTo(1);

        // The price list's position under its trade and category; the master now has that trade.
        assertThat(jdbc.queryForMap("SELECT trade, category FROM catalog_items WHERE owner_id = ? AND name = ?",
                master.getId(), fresh)).containsEntry("trade", "TILING").containsEntry("category", "Підлога");
        assertThat(jdbc.queryForList("SELECT trade FROM user_trades WHERE user_id = ?", String.class, master.getId()))
                .contains("TILING");
        // «Не в каталог» on the second estimate was honoured.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_items WHERE owner_id = ? AND name = ?",
                Long.class, master.getId(), "Ванна " + fresh)).isZero();
    }

    @Test
    void anEstimateWithNoObjectIsRefused() {
        TransferCommitRequest req = new TransferCommitRequest(List.of(),
                List.of(new EstimateDoc(null, " ", null, null, null, null, null, List.of(line("X", null)))),
                DedupPolicy.SKIP);

        assertThatThrownBy(() -> transfers.commit(master.getId(), req))
                .isInstanceOf(TransferValidationException.class)
                .hasMessage("error.transfer.no-object");
    }

    @Test
    void aLibraryPositionIsFiledUnderItsOwnTrade() {
        Map<String, Object> shipped = jdbc.queryForMap(
                "SELECT name, trade, type FROM catalog_templates WHERE trade <> 'OTHER' ORDER BY name LIMIT 1");

        List<TradeFiler.Filing> filings = tradeFiler.file(master.getId(), List.of(
                new TradeFiler.Position((String) shipped.get("name"), ItemType.valueOf((String) shipped.get("type"))),
                new TradeFiler.Position("Щось зовсім невідоме " + UUID.randomUUID(), ItemType.WORK)));

        assertThat(filings.get(0).trade()).isEqualTo(Trade.valueOf((String) shipped.get("trade")));
        assertThat(filings.get(1).trade()).isNull();
    }
}

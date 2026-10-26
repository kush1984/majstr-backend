package com.majstr.backend.integration;

import com.majstr.backend.dto.CatalogSyncRequest;
import com.majstr.backend.dto.CatalogSyncRequest.NewPosition;
import com.majstr.backend.dto.CatalogSyncRequest.PriceUpdate;
import com.majstr.backend.dto.CatalogSyncResponse;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.service.CatalogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * «Оновити каталог» from an estimate's card (inline editing, stage 4): one transaction that re-prices
 * the positions the master ticked and adds the ones he ticked, safe to replay from the offline queue.
 */
class CatalogSyncIntegrationTest extends IntegrationTestBase {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CatalogService catalogService;

    private UUID master;
    private UUID other;

    @BeforeEach
    void plantMasters() {
        master = user("CS1");
        other = user("CS2");
    }

    @AfterEach
    void removeMasters() {
        for (UUID u : List.of(master, other)) {
            jdbc.update("DELETE FROM catalog_update_notices WHERE user_id = ?", u);
            jdbc.update("DELETE FROM catalog_items WHERE owner_id = ?", u);
            jdbc.update("DELETE FROM users WHERE id = ?", u);
        }
    }

    private UUID user(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, last_synced_catalog_version)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, 1)
                """, id, id + "@test.ua", id + "@test.ua", code + id.toString().substring(0, 8));
        return id;
    }

    private UUID item(UUID owner, String name, String price) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO catalog_items (id, owner_id, name, category, type, unit, default_price,
                                           trade, source, sort_order)
                VALUES (?, ?, ?, 'Стяжка', 'WORK', 'M2', ?::numeric, 'FLOORING', 'LIBRARY', 0)
                """, id, owner, name, price);
        return id;
    }

    private BigDecimal price(UUID id) {
        return jdbc.queryForObject("SELECT default_price FROM catalog_items WHERE id = ?", BigDecimal.class, id);
    }

    private int count(UUID owner) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM catalog_items WHERE owner_id = ?", Integer.class, owner);
        return n == null ? 0 : n;
    }

    private static NewPosition position(UUID id, String name, String price) {
        return new NewPosition(id, name, "Стяжка", Trade.FLOORING, ItemType.WORK, Unit.M2, new BigDecimal(price));
    }

    @Test
    void repricesAndAddsInOneGo_andAReplayChangesNothing() {
        UUID screed = item(master, "Стяжка цементна", "650.00");
        UUID added = UUID.randomUUID();
        CatalogSyncRequest req = new CatalogSyncRequest(
                List.of(new PriceUpdate(screed, new BigDecimal("672.51"))),
                List.of(position(added, "Стяжка з пінополістиролбетону", "800")));

        CatalogSyncResponse first = catalogService.sync(req, master);
        CatalogSyncResponse replay = catalogService.sync(req, master);

        assertThat(first).isEqualTo(new CatalogSyncResponse(1, 1));
        assertThat(replay).isEqualTo(new CatalogSyncResponse(1, 1));
        assertThat(price(screed)).isEqualByComparingTo("672.51");
        assertThat(price(added)).isEqualByComparingTo("800");
        assertThat(count(master)).isEqualTo(2);
    }

    @Test
    void anAddedPositionTheCatalogAlreadyHasIsRepricedNotDuplicated_lastWriteWins() {
        UUID screed = item(master, "Стяжка цементна", "650.00");

        catalogService.sync(new CatalogSyncRequest(List.of(),
                List.of(position(UUID.randomUUID(), "  стяжка цементна ", "700"))), master);

        assertThat(count(master)).isEqualTo(1);
        assertThat(price(screed)).isEqualByComparingTo("700");
    }

    @Test
    void isAtomic_aFailingRowLeavesEveryOtherRowUntouched() {
        UUID screed = item(master, "Стяжка цементна", "650.00");
        // Past numeric(15,2): the database refuses it at flush, after the first row was written.
        CatalogSyncRequest req = new CatalogSyncRequest(
                List.of(new PriceUpdate(screed, new BigDecimal("700"))),
                List.of(position(UUID.randomUUID(), "Нова позиція", "1000000000000000")));

        assertThatThrownBy(() -> catalogService.sync(req, master)).isInstanceOf(RuntimeException.class);

        assertThat(price(screed)).isEqualByComparingTo("650");
        assertThat(count(master)).isEqualTo(1);
    }

    @Test
    void anotherMastersPositionIsSkipped_andADeletedOneDoesNotWedgeTheQueue() {
        UUID theirs = item(other, "Стяжка цементна", "650.00");

        CatalogSyncResponse res = catalogService.sync(new CatalogSyncRequest(
                List.of(new PriceUpdate(theirs, new BigDecimal("1")), new PriceUpdate(UUID.randomUUID(), new BigDecimal("1"))),
                List.of()), master);

        assertThat(res).isEqualTo(new CatalogSyncResponse(0, 0));
        assertThat(price(theirs)).isEqualByComparingTo("650");
    }

    @Test
    void aRepriceResolvesTheMastersOpenPriceDriftNotice() {
        UUID screed = item(master, "Стяжка цементна", "650.00");
        UUID notice = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed,
                                                    position_name, old_price, new_price)
                VALUES (?, ?, 'PRICE_DRIFT', 0, 0, 'Стяжка цементна', 650.00, 720.00)
                """, notice, master);

        catalogService.sync(new CatalogSyncRequest(List.of(new PriceUpdate(screed, new BigDecimal("700"))), List.of()), master);

        assertThat(jdbc.queryForObject("SELECT resolution FROM catalog_update_notices WHERE id = ?", String.class, notice))
                .isEqualTo("RESOLVED");
    }

    /** Only a client price can travel: there is no crew-price field anywhere in the request. */
    @Test
    void theRequestCarriesNoCrewPrice() {
        List<String> fields = Stream.of(CatalogSyncRequest.class, PriceUpdate.class, NewPosition.class)
                .flatMap(c -> Arrays.stream(c.getRecordComponents()))
                .map(RecordComponent::getName)
                .toList();
        assertThat(fields).noneMatch(f -> f.toLowerCase().contains("source"));
        assertThat(fields).noneMatch(f -> f.toLowerCase().contains("crew"));
    }
}

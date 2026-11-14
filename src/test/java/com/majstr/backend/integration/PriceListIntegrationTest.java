package com.majstr.backend.integration;

import com.majstr.backend.dto.PriceListRequest;
import com.majstr.backend.dto.PriceListResponse;
import com.majstr.backend.dto.PublicPriceListView;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.PriceListService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * «Поділитися прайсом» (V205) against Postgres: what is suggested, what can be ticked, what the client sees,
 * and that a closed link stops opening.
 */
@SpringBootTest
class PriceListIntegrationTest extends IntegrationTestBase {

    @Autowired PriceListService priceLists;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private User master;
    private UUID ownPriced;
    private UUID zeroPriced;
    private UUID material;
    private UUID libraryPriced;
    private UUID foreign;
    private String libraryName;

    @BeforeEach
    void seed() {
        master = user("Петро Прайс");
        User other = user("Чужий");
        ownPriced = item(master, "Укладання плитки " + UUID.randomUUID(), "WORK", "TILING", "Підлога", "650", "MANUAL");
        zeroPriced = item(master, "Продзвонка " + UUID.randomUUID(), "WORK", "ELECTRICAL", null, "0", "MANUAL");
        material = item(master, "Клей " + UUID.randomUUID(), "MATERIAL", "TILING", null, "300", "MANUAL");
        Map<String, Object> shipped = jdbc.queryForMap("""
                SELECT name, trade, category, unit, suggested_price FROM catalog_templates
                WHERE type = 'WORK' AND suggested_price > 0 ORDER BY name LIMIT 1""");
        libraryName = (String) shipped.get("name");
        libraryPriced = jdbc.queryForObject("""
                INSERT INTO catalog_items (id, owner_id, name, category, trade, type, unit, default_price, source,
                                           sort_order, created_at)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, 'WORK', ?, ?, 'LIBRARY', 3, now()) RETURNING id""",
                UUID.class, master.getId(), libraryName, shipped.get("category"), shipped.get("trade"),
                shipped.get("unit"), shipped.get("suggested_price"));
        foreign = item(other, "Чуже " + UUID.randomUUID(), "WORK", "TILING", null, "100", "MANUAL");
        // His trades: the library row's and tiling — a LIBRARY row of any other trade is hidden in his catalog.
        jdbc.update("INSERT INTO user_trades (user_id, trade) VALUES (?, 'TILING') ON CONFLICT DO NOTHING", master.getId());
        jdbc.update("INSERT INTO user_trades (user_id, trade) VALUES (?, ?) ON CONFLICT DO NOTHING",
                master.getId(), shipped.get("trade"));
    }

    @Test
    void beforeTheFirstShare_nothingIsTickedForHim() {
        PriceListResponse state = priceLists.state(master.getId());

        assertThat(state.configured()).isFalse();
        assertThat(state.url()).isNull();
        assertThat(state.itemIds()).isEmpty();
        assertThat(state.libraryPricedIds()).containsExactly(libraryPriced);
    }

    @Test
    void onlyTheMastersOwnPricedWorksCanBeTicked_andTheClientSeesThemGrouped() {
        PriceListResponse saved = priceLists.save(master.getId(), new PriceListRequest(
                List.of(ownPriced, zeroPriced, material, libraryPriced, foreign)));

        assertThat(saved.configured()).isTrue();
        assertThat(saved.url()).contains("/portal/index.html?l=");
        assertThat(saved.itemIds()).containsExactlyInAnyOrder(ownPriced, libraryPriced);
        assertThat(jdbc.queryForObject("SELECT in_price_list FROM catalog_items WHERE id = ?", Boolean.class, foreign))
                .as("a foreign id changes nothing").isFalse();

        PublicPriceListView view = priceLists.publicView(token(saved));
        assertThat(view.contractor().companyName()).isEqualTo("Петро Прайс");
        List<String> names = view.trades().stream().flatMap(t -> t.sections().stream())
                .flatMap(s -> s.items().stream()).map(PublicPriceListView.Item::name).toList();
        assertThat(names).hasSize(2).contains(libraryName);
        PublicPriceListView.Trade tiling = view.trades().stream().filter(t -> t.name().equals("Плитка"))
                .findFirst().orElseThrow();
        PublicPriceListView.Section floor = tiling.sections().stream()
                .filter(s -> "Підлога".equals(s.category())).findFirst().orElseThrow();
        assertThat(floor.items().getFirst().unit()).isEqualTo("м²");
        assertThat(floor.items().getFirst().price()).isEqualByComparingTo("650");

        // A ticked price cleared to 0 leaves the client's page by itself.
        jdbc.update("UPDATE catalog_items SET default_price = 0 WHERE id = ?", ownPriced);
        assertThat(priceLists.publicView(token(saved)).trades().stream().flatMap(t -> t.sections().stream())
                .flatMap(s -> s.items().stream()).map(PublicPriceListView.Item::name)).containsExactly(libraryName);
    }

    @Test
    void aClosedLinkStopsOpening_theTicksStay_andTheNextShareMintsANewOne() {
        String first = token(priceLists.save(master.getId(), new PriceListRequest(List.of(ownPriced))));

        PriceListResponse closed = priceLists.revoke(master.getId());

        assertThat(closed.url()).isNull();
        assertThat(closed.configured()).isTrue();
        assertThat(closed.itemIds()).containsExactly(ownPriced);
        assertThatThrownBy(() -> priceLists.publicView(first)).isInstanceOf(ResourceNotFoundException.class);
        String second = token(priceLists.save(master.getId(), new PriceListRequest(List.of(ownPriced))));
        assertThat(second).isNotEqualTo(first);
        assertThat(priceLists.save(master.getId(), new PriceListRequest(List.of())).url())
                .as("saving again keeps the live link").endsWith(second);
    }

    @Test
    void aTradeTakenOffTheProfileLeavesTheClientsPage() {
        UUID electrical = item(master, "Монтаж розетки " + UUID.randomUUID(), "WORK", "ELECTRICAL", null, "200", "LIBRARY");
        jdbc.update("DELETE FROM user_trades WHERE user_id = ? AND trade = 'ELECTRICAL'", master.getId());
        String token = token(priceLists.save(master.getId(), new PriceListRequest(List.of(ownPriced, electrical))));

        assertThat(names(priceLists.publicView(token))).doesNotContain(nameOf(electrical)).contains(nameOf(ownPriced));
    }

    @Test
    void aCustomTradeIsItsOwnBlockUnderItsName_besideInshe() {
        UUID other = item(master, "Інша робота " + UUID.randomUUID(), "WORK", "OTHER", null, "120", "MANUAL");
        UUID customTrade = jdbc.queryForObject(
                "INSERT INTO user_trade (id, user_id, name) VALUES (gen_random_uuid(), ?, 'Вітражі') RETURNING id",
                UUID.class, master.getId());
        UUID stained = item(master, "Вітраж " + UUID.randomUUID(), "WORK", "OTHER", null, "900", "MANUAL");
        jdbc.update("UPDATE catalog_items SET custom_trade_id = ? WHERE id = ?", customTrade, stained);

        PublicPriceListView view = priceLists.publicView(token(priceLists.save(master.getId(),
                new PriceListRequest(List.of(other, stained)))));

        assertThat(view.trades()).extracting(PublicPriceListView.Trade::name).containsExactlyInAnyOrder("Інше", "Вітражі");
    }

    @Test
    void twoSharesAtOnceMintOneLink() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<String> share = () -> {
                go.await();
                return priceLists.save(master.getId(), new PriceListRequest(List.of(ownPriced))).url();
            };
            var a = pool.submit(share);
            var b = pool.submit(share);
            go.countDown();
            assertThat(a.get()).isEqualTo(b.get());
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM price_list_link WHERE owner_id = ? AND NOT revoked",
                Integer.class, master.getId())).isEqualTo(1);
    }

    @Test
    void aPreviewSavesTheTicksButMintsNoLink_andShowsWhatTheClientWouldRead() {
        var preview = priceLists.preview(master.getId(), new PriceListRequest(List.of(ownPriced)));

        assertThat(preview.url()).contains("/portal/index.html?vl=");
        PriceListResponse state = priceLists.state(master.getId());
        assertThat(state.url()).as("looking is not sharing").isNull();
        assertThat(state.configured()).as("his ticks are his own from now on").isTrue();
        assertThat(state.itemIds()).containsExactly(ownPriced);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM price_list_link WHERE owner_id = ?", Integer.class,
                master.getId())).isZero();

        String pass = preview.url().substring(preview.url().indexOf("?vl=") + 4);
        assertThat(names(priceLists.previewView(pass))).containsExactly(nameOf(ownPriced));
        assertThatThrownBy(() -> priceLists.previewView(pass + "x")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> priceLists.publicView(pass)).as("a pass is no link")
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void thePdfRendersForTheMasterAndTheClient() throws Exception {
        String token = token(priceLists.save(master.getId(), new PriceListRequest(List.of(ownPriced))));

        assertThat(new String(priceLists.renderPdf(master.getId()), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(new String(priceLists.renderPublicPdf(token), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    private static List<String> names(PublicPriceListView view) {
        return view.trades().stream().flatMap(t -> t.sections().stream())
                .flatMap(s -> s.items().stream()).map(PublicPriceListView.Item::name).toList();
    }

    private String nameOf(UUID item) {
        return jdbc.queryForObject("SELECT name FROM catalog_items WHERE id = ?", String.class, item);
    }

    private static String token(PriceListResponse state) {
        return state.url().substring(state.url().indexOf("?l=") + 3);
    }

    private User user(String name) {
        String unique = UUID.randomUUID().toString();
        String email = unique + "@majstr.test";
        return userRepository.save(User.builder()
                .email(email).emailCanonical(email).passwordHash("x").fullName(name).emailVerified(true)
                .plan(Plan.FREE).role(Role.USER).referralCode(unique.substring(0, 10))
                .build());
    }

    private UUID item(User owner, String name, String type, String trade, String category, String price,
                      String source) {
        return jdbc.queryForObject("""
                INSERT INTO catalog_items (id, owner_id, name, category, trade, type, unit, default_price, source,
                                           sort_order, created_at)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, 'M2', ?, ?, 0, now()) RETURNING id""",
                UUID.class, owner.getId(), name, category, trade, type, new BigDecimal(price), source);
    }
}

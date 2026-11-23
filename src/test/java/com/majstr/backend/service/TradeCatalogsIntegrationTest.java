package com.majstr.backend.service;

import com.majstr.backend.dto.ApplyTemplatesRequest;
import com.majstr.backend.dto.CatalogItemRequest;
import com.majstr.backend.dto.CatalogItemResponse;
import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.EstimateItemFromCatalogRequest;
import com.majstr.backend.dto.EstimateItemRequest;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.PublicPriceListView;
import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.EstimateTemplate;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.integration.IntegrationTestBase;
import com.majstr.backend.repository.CatalogTemplateRepository;
import com.majstr.backend.repository.EstimateTemplateRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every trade holds its own positions (V220), against the library the product actually ships.
 *
 * <p>The owner's complaint (2026-11-23): an electrician's price list grew a «Плитка» section, because
 * a position the library ships under several trades was stored ONCE, under whichever trade claimed
 * it first, and every screen had to guess which trade the master meant. The promise this test holds
 * every trade to: a master of ONE trade sees that trade only — in his catalog, in every default
 * bundle he applies, on his price list — and every library position of his trade is there, in that
 * trade's own folder. A master of two trades holds a shared position once per trade, priced apart.</p>
 */
class TradeCatalogsIntegrationTest extends IntegrationTestBase {

    private static final String CLEANUP = "Прибирання приміщення після робіт";

    @Autowired CatalogTemplateService catalogTemplateService;
    @Autowired CatalogService catalogService;
    @Autowired EstimateTemplateService templateService;
    @Autowired EstimateService estimateService;
    @Autowired PriceListService priceListService;
    @Autowired CatalogTemplateRepository libraryRepository;
    @Autowired EstimateTemplateRepository bundleRepository;
    @Autowired UserRepository userRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aMasterOfOneTradeSeesThatTradeOnly_everywhere() {
        Set<Trade> shipped = EnumSet.noneOf(Trade.class);
        libraryRepository.findAll().stream().map(CatalogTemplate::getTrade).forEach(t -> {
            if (t != null) {
                shipped.add(t);
            }
        });
        assertThat(shipped).as("the library ships trades to check").hasSizeGreaterThan(5);

        List<String> problems = new ArrayList<>();
        int[] bundlesChecked = {0};
        for (Trade trade : shipped) {
            UUID ownerId = masterOf(trade);

            // 1. His catalog: only his trade, and every position of it in its own folder.
            List<CatalogItemResponse> visible = catalogService.listForOwner(ownerId, null).stream()
                    .filter(i -> !i.hidden()).toList();
            visible.stream().filter(i -> i.trade() != trade)
                    .forEach(i -> problems.add(trade + " catalog shows " + i.trade() + ": " + i.name()));
            Map<String, String> ownFolder = new HashMap<>();
            visible.forEach(i -> ownFolder.putIfAbsent(CatalogFiling.key(i.name(), i.type(), i.unit()),
                    String.valueOf(i.category())));
            Map<String, String> libraryFolder = new HashMap<>();
            libraryRepository.findByTradeInOrderBySortOrderAsc(List.of(trade)).forEach(t ->
                    libraryFolder.putIfAbsent(CatalogFiling.key(t.getName(), t.getType(), t.getUnit()),
                            String.valueOf(t.getCategory())));
            libraryFolder.forEach((key, folder) -> {
                if (!ownFolder.containsKey(key)) {
                    problems.add(trade + " catalog is missing " + key);
                } else if (!ownFolder.get(key).equals(folder)) {
                    problems.add(trade + " files " + key + " under «" + ownFolder.get(key) + "», not «" + folder + "»");
                }
            });

            // 2. Every default bundle of his trade: every line is his trade.
            // The schema is shared across the run, and other tests plant default bundles of their
            // own; the SHIPPED ones are those whose every line the library ships under the trade.
            List<UUID> bundles = bundleRepository.findDefaultsForTrades(List.of(trade)).stream()
                    .filter(t -> t.getTrade() == trade).map(EstimateTemplate::getId)
                    .filter(id -> isShipped(id, trade)).toList();
            bundlesChecked[0] += bundles.size();
            if (!bundles.isEmpty()) {
                UUID projectId = projectOf(ownerId);
                EstimateResponse estimate = templateService.applyToProject(projectId,
                        ApplyTemplatesRequest.wholeBundles(bundles, new EstimateCreateRequest(null, null, "Усі шаблони")),
                        ownerId);
                estimate.items().stream().filter(l -> l.trade() != trade)
                        .forEach(l -> problems.add(trade + " bundle line under " + l.trade() + ": " + l.name()));
            }

            // 3. His price list with every priced work ticked: one section, his trade.
            jdbc.update("UPDATE catalog_items SET in_price_list = (type = 'WORK' AND default_price > 0) "
                    + "WHERE owner_id = ?", ownerId);
            PublicPriceListView view = priceListOf(ownerId);
            view.trades().stream().map(PublicPriceListView.Trade::name)
                    .filter(name -> !name.equals(TradeLabel.ua(trade)))
                    .forEach(name -> problems.add(trade + " price list has a «" + name + "» section"));
        }
        assertThat(problems).isEmpty();
        assertThat(bundlesChecked[0]).as("shipped default bundles checked").isGreaterThan(20);
    }

    private boolean isShipped(UUID bundleId, Trade trade) {
        Integer foreign = jdbc.queryForObject("""
                SELECT count(*) FROM estimate_template_items i
                WHERE i.template_id = ?
                  AND NOT EXISTS (SELECT 1 FROM catalog_templates c
                                  WHERE c.trade = ? AND lower(trim(c.name)) = lower(trim(i.name)))
                """, Integer.class, bundleId, trade.name());
        return foreign != null && foreign == 0;
    }

    @Test
    void aMasterOfTwoTradesHoldsASharedPositionOncePerTrade_pricedApart() {
        UUID ownerId = masterOf(Trade.ELECTRICAL, Trade.TILING);

        List<CatalogItemResponse> cleanup = catalogService.listForOwner(ownerId, null).stream()
                .filter(i -> i.name().equals(CLEANUP)).toList();
        assertThat(cleanup).extracting(CatalogItemResponse::trade)
                .containsExactlyInAnyOrder(Trade.ELECTRICAL, Trade.TILING);
        assertThat(cleanup).extracting(CatalogItemResponse::category).containsOnly("Організаційні послуги");

        // Owner ruling 2026-11-23: a price changed under one trade does not move the other.
        CatalogItemResponse electrical = cleanup.stream().filter(i -> i.trade() == Trade.ELECTRICAL).findFirst().orElseThrow();
        CatalogItemResponse tiling = cleanup.stream().filter(i -> i.trade() == Trade.TILING).findFirst().orElseThrow();
        catalogService.update(electrical.id(), new CatalogItemRequest(electrical.name(), electrical.category(),
                Trade.ELECTRICAL, null, electrical.type(), electrical.unit(), new BigDecimal("77.00")), ownerId);
        assertThat(priceOf(tiling.id())).isEqualByComparingTo(tiling.defaultPrice());
        assertThat(priceOf(electrical.id())).isEqualByComparingTo("77.00");

        // Ticked under Електрика only: the price list shows Електрика only.
        jdbc.update("UPDATE catalog_items SET in_price_list = (id = ?) WHERE owner_id = ?", electrical.id(), ownerId);
        assertThat(priceListOf(ownerId).trades()).extracting(PublicPriceListView.Trade::name)
                .containsExactly(TradeLabel.ua(Trade.ELECTRICAL));
    }

    @Test
    void aTypedLineOfASharedName_followsTheEstimatesTrade_orNone() {
        UUID ownerId = masterOf(Trade.ELECTRICAL, Trade.TILING);
        UUID estimateId = estimateService.createForProject(projectOf(ownerId),
                new EstimateCreateRequest(null, null, "Кошторис"), ownerId).id();
        EstimateItemRequest typed = new EstimateItemRequest(ItemType.WORK, CLEANUP, null, Unit.M2,
                new BigDecimal("10"), new BigDecimal("40"), 0, null, false, null, null);

        // An empty estimate is in no trade: two trades hold the name, none is picked.
        assertThat(estimateService.addItem(estimateId, typed, ownerId).trade()).isNull();

        UUID socket = catalogService.listForOwner(ownerId, null).stream()
                .filter(i -> i.trade() == Trade.ELECTRICAL && i.type() == ItemType.WORK && i.unit() == Unit.PIECE)
                .findFirst().orElseThrow().id();
        estimateService.addItemFromCatalog(estimateId, socket,
                new EstimateItemFromCatalogRequest(BigDecimal.ONE, 1), ownerId);

        // Now the estimate is electrical: the typed line follows it.
        assertThat(estimateService.addItem(estimateId, typed, ownerId).trade()).isEqualTo(Trade.ELECTRICAL);
    }

    private UUID masterOf(Trade... trades) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?)
                """, ownerId, ownerId + "@t.ua", ownerId + "@t.ua", ownerId.toString().substring(0, 8));
        for (Trade trade : trades) {
            jdbc.update("INSERT INTO user_trades (user_id, trade) VALUES (?, ?)", ownerId, trade.name());
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                catalogTemplateService.seedForUser(userRepository.findWithTradesById(ownerId).orElseThrow()));
        return ownerId;
    }

    private UUID projectOf(UUID ownerId) {
        UUID projectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?, ?, 'Квартира', 'вул. Тестова 1', 'IN_PROGRESS')
                """, projectId, ownerId);
        return projectId;
    }

    private PublicPriceListView priceListOf(UUID ownerId) {
        return new TransactionTemplate(transactionManager).execute(tx -> {
            User owner = userRepository.findWithTradesById(ownerId).orElseThrow();
            return priceListService.viewOf(owner);
        });
    }

    private BigDecimal priceOf(UUID catalogItemId) {
        return jdbc.queryForObject("SELECT default_price FROM catalog_items WHERE id = ?", BigDecimal.class,
                catalogItemId);
    }
}

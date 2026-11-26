package com.majstr.backend.integration;

import com.majstr.backend.dto.RegionalPriceSummary;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Region;
import com.majstr.backend.entity.RegionalPrice;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.RegionalIndexRepository;
import com.majstr.backend.repository.RegionalPriceRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.AdminRegionalPriceService;
import com.majstr.backend.service.RegionalPriceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prices by oblast, step 1 (V222 + V223) on real Postgres: the oblast median needs 3 masters OF THAT
 * OBLAST; the country baseline is one vote per oblast (3+ oblasts, masters without one take no
 * part); the oblast factor compares its masters with that baseline; the admin summary reads all.
 */
class RegionalPriceIntegrationTest extends IntegrationTestBase {

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateItemRepository itemRepository;
    @Autowired RegionalPriceService regionalPriceService;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminRegionalPriceService adminRegionalPriceService;
    @Autowired RegionalPriceRepository priceRepository;
    @Autowired RegionalIndexRepository indexRepository;
    @Autowired com.majstr.backend.repository.RegionalBaselineRepository baselineRepository;

    /** Letters only: the name key folds digits and punctuation, a run must not meet another's rows. */
    private final String token = UUID.randomUUID().toString().replaceAll("[^a-f]", "");

    private void masterPricing(Region region, String name, String price) {
        String unique = UUID.randomUUID().toString();
        User master = userRepository.save(User.builder()
                .email("r-" + unique + "@majstr.test").emailCanonical("r-" + unique + "@majstr.test")
                .passwordHash("x").fullName("Майстер").phone("+380000000000").companyName("ФОП")
                .plan(Plan.PRO).referralCode(unique.substring(0, 10)).region(region)
                .build());
        Project object = projectRepository.save(Project.builder()
                .owner(master).name("Обʼєкт").address("вул. Тестова, 1").status(ProjectStatus.DRAFT).build());
        Estimate estimate = estimateRepository.save(Estimate.builder()
                .project(object).status(EstimateStatus.SENT).countInEconomy(true).build());
        itemRepository.save(EstimateItem.builder()
                .estimate(estimate).type(ItemType.WORK).name(name).unit(Unit.M2)
                .quantity(new BigDecimal("1.000")).unitPrice(new BigDecimal(price))
                .lineTotal(new BigDecimal(price).setScale(2)).sortOrder(0).build());
    }

    @Test
    void anOblastMedianNeedsThreeOfItsMasters_andTheFactorComparesThemWithTheCountry() {
        String work = "Укладання плитки " + token;
        masterPricing(Region.VOLYN, work, "400.00");
        masterPricing(Region.VOLYN, work, "420.00");
        masterPricing(Region.VOLYN, work, "440.00");
        masterPricing(Region.TERNOPIL, work, "300.00");
        masterPricing(Region.TERNOPIL, work, "320.00");
        masterPricing(Region.RIVNE, work, "360.00");
        masterPricing(null, work, "1000.00");         // no oblast, no vote — the baseline ignores him

        regionalPriceService.weeklyRefresh();

        List<RegionalPrice> rows = priceRepository.findAll().stream()
                .filter(r -> r.getSampleName().equals(work)).toList();
        assertThat(rows).as("Тернопільська has two masters — no oblast median").singleElement()
                .satisfies(r -> {
                    assertThat(r.getRegion()).isEqualTo(Region.VOLYN);
                    assertThat(r.getMedianPrice()).isEqualByComparingTo("420.00");
                    assertThat(r.getMastersCount()).isEqualTo(3);
                });

        // One vote per oblast: Волинь 420, Тернопіль 310, Рівне 360 → baseline 360 (Волинь's three
        // masters do not outvote the others, the master with no oblast is not there at all).
        assertThat(baselineRepository.findAll().stream().filter(b -> b.getSampleName().equals(work)))
                .singleElement().satisfies(b -> {
                    assertThat(b.getMedianPrice()).isEqualByComparingTo("360.00");
                    assertThat(b.getRegionsCount()).isEqualTo(3);
                });
        // Волинь against it: 111.11 %, 116.67 %, 122.22 % → 1.167.
        assertThat(indexRepository.findById(Region.VOLYN)).hasValueSatisfying(i -> {
            assertThat(i.getFactor()).isEqualByComparingTo("1.167");
            assertThat(i.getMastersCount()).isEqualTo(3);
            assertThat(i.getPositionsCount()).isEqualTo(1);
        });
        assertThat(indexRepository.findById(Region.TERNOPIL)).as("two masters are not a factor").isEmpty();

        RegionalPriceSummary summary = adminRegionalPriceService.summary();
        assertThat(summary.regions()).hasSize(Region.values().length);
        assertThat(summary.computedAt()).isNotNull();
        RegionalPriceSummary.Row volyn = summary.regions().stream()
                .filter(r -> r.region() == Region.VOLYN).findFirst().orElseThrow();
        assertThat(volyn.masters()).isGreaterThanOrEqualTo(3);
        assertThat(volyn.positionsWithMedian()).isEqualTo(1);
        assertThat(volyn.factor()).isEqualByComparingTo("1.167");
        assertThat(summary.baselinePositions()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void twoOblastsAreNotACountry_soNoBaselineAndNoFactor() {
        String work = "Монтаж плінтуса " + token;
        masterPricing(Region.KHERSON, work, "100.00");
        masterPricing(Region.KHERSON, work, "110.00");
        masterPricing(Region.KHERSON, work, "120.00");
        masterPricing(Region.CHERKASY, work, "90.00");

        regionalPriceService.weeklyRefresh();

        assertThat(priceRepository.findAll().stream().filter(r -> r.getSampleName().equals(work)))
                .as("Херсонська's own median stands").hasSize(1);
        assertThat(baselineRepository.findAll().stream().filter(b -> b.getSampleName().equals(work))).isEmpty();
        assertThat(indexRepository.findById(Region.KHERSON)).isEmpty();
    }

    @Test
    void aRerunReplacesTheRows_neverStacksThem() {
        // Its own oblast: the factor test above counts every Волинь master.
        String work = "Шпаклювання стелі " + token;
        masterPricing(Region.ZHYTOMYR, work, "200.00");
        masterPricing(Region.ZHYTOMYR, work, "210.00");
        masterPricing(Region.ZHYTOMYR, work, "220.00");

        regionalPriceService.weeklyRefresh();
        regionalPriceService.weeklyRefresh();

        assertThat(priceRepository.findAll().stream().filter(r -> r.getSampleName().equals(work))).hasSize(1);
    }

    @Test
    void anOldWordingCountsAsTheWordingItBecame_andTheNewNameIsShown() {
        String oldName = "Шліфування старе " + token;
        String newName = "Шліфування нове " + token;
        jdbc.update("""
                INSERT INTO catalog_renamed_position (old_name_key, new_name_key, unit, renamed_in)
                VALUES (lower(?), lower(?), 'M2', 'TEST')""", oldName, newName);
        try {
            masterPricing(Region.POLTAVA, oldName, "100.00");
            masterPricing(Region.POLTAVA, oldName, "110.00");
            masterPricing(Region.POLTAVA, newName, "120.00");

            regionalPriceService.weeklyRefresh();

            assertThat(priceRepository.findAll().stream()
                    .filter(r -> r.getRegion() == Region.POLTAVA && r.getSampleName().contains(token)))
                    .singleElement().satisfies(r -> {
                        assertThat(r.getMastersCount()).as("two old + one new = one work, three masters").isEqualTo(3);
                        assertThat(r.getMedianPrice()).isEqualByComparingTo("110");
                        assertThat(r.getSampleName()).isEqualTo(newName);
                    });
        } finally {
            jdbc.update("DELETE FROM catalog_renamed_position WHERE renamed_in = 'TEST'");
        }
    }
}

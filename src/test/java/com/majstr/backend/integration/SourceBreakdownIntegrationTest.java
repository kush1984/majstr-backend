package com.majstr.backend.integration;

import com.majstr.backend.dto.ActivationFunnelResponse;
import com.majstr.backend.dto.SourceBreakdownResponse;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateShareLink;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectShareLink;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.ShareLinkKind;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.ProjectShareLinkRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.MetricsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The by-source report as real SQL: seven grouped queries plus one cross-table union, against real
 * Postgres.
 *
 * <p><b>The invariant this file exists for:</b> for every funnel step, the sum over all sources must
 * equal the matching field of {@link ActivationFunnelResponse}. Two reports computing the same six
 * steps two different ways drift the moment one of them grows a filter the other does not have —
 * {@code role = USER} on one side only is enough. Note what the assertion can and cannot see: it
 * catches the two reports DISAGREEING, not both being wrong the same way.</p>
 *
 * <p>The Testcontainers database is shared across the whole run, so nothing here asserts an absolute
 * figure. Each test seeds masters under a source name unique to that test and reads its own row.</p>
 */
class SourceBreakdownIntegrationTest extends IntegrationTestBase {

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateShareLinkRepository estimateShareLinkRepository;
    @Autowired ProjectShareLinkRepository projectShareLinkRepository;
    @Autowired MetricsService metricsService;

    @Test
    void everyStepSumsToTheAggregateFunnel() {
        // A master at each depth, so the seeded data is not flat at any step.
        master("SUM-A").verified().build();
        master("SUM-B").verified().withObject().build();
        master("SUM-C").verified().withObject().withEstimate().build();
        master("SUM-D").verified().withObject().withEstimate().sharedFromObject().build();
        master("SUM-E").verified().withObject().withEstimate().sharedFromEstimate().signed().build();
        // The one that makes a sum-of-two-COUNTs implementation fail here: both kinds of link.
        master("SUM-F").verified().withObject().withEstimate()
                .sharedFromObject().sharedFromEstimate().signed().build();

        ActivationFunnelResponse funnel = metricsService.activationFunnel();
        List<SourceBreakdownResponse.SourceStat> rows = metricsService.bySource().sources();

        assertThat(sum(rows, SourceBreakdownResponse.SourceStat::registered)).isEqualTo(funnel.registered());
        assertThat(sum(rows, SourceBreakdownResponse.SourceStat::verifiedEmail)).isEqualTo(funnel.verifiedEmail());
        assertThat(sum(rows, SourceBreakdownResponse.SourceStat::activated)).isEqualTo(funnel.withProject());
        assertThat(sum(rows, SourceBreakdownResponse.SourceStat::withEstimate)).isEqualTo(funnel.withEstimate());
        assertThat(sum(rows, SourceBreakdownResponse.SourceStat::shared)).isEqualTo(funnel.shared());
        assertThat(sum(rows, SourceBreakdownResponse.SourceStat::withSigned)).isEqualTo(funnel.withSigned());
    }

    @Test
    void oneSourceCarriesTheWholeFunnel_andASharerWithBothLinksIsCountedOnce() {
        String source = "ROW-" + UUID.randomUUID().toString().substring(0, 8);
        master(source).build();                                   // registered only
        master(source).verified().build();
        master(source).verified().withObject().build();
        master(source).verified().withObject().withEstimate().sharedFromObject().build();
        master(source).verified().withObject().withEstimate()
                .sharedFromObject().sharedFromEstimate().signed().build();

        SourceBreakdownResponse.SourceStat row = rowFor(source);

        assertThat(row.registered()).isEqualTo(5L);
        assertThat(row.verifiedEmail()).isEqualTo(4L);
        assertThat(row.activated()).isEqualTo(3L);
        assertThat(row.withEstimate()).isEqualTo(2L);
        // 2, not 3: the last master holds an object link AND an estimate link. This is the step
        // that cannot be a GROUP BY — the ids are unioned in Java before anything is counted.
        assertThat(row.shared()).isEqualTo(2L);
        assertThat(row.withSigned()).isEqualTo(1L);
        assertThat(row.enoughData()).isTrue(); // exactly at the threshold, which is inclusive
    }

    @Test
    void anAdminIsInNoRowAtAll() {
        String source = "ADM-" + UUID.randomUUID().toString().substring(0, 8);
        master(source).role(Role.ADMIN).verified().withObject().withEstimate()
                .sharedFromObject().signed().build();

        // Not "the row is zero" — with no masters under that source there is no row, which is the
        // shape that keeps the sums above equal to the funnel (which also filters role = USER).
        assertThat(find(source)).isEmpty();
    }

    @Test
    void theUtmTableKeepsTheNullBucketAsARowOfItsOwn() {
        String tag = "utm-" + UUID.randomUUID().toString().substring(0, 8);
        master("UTM-TEST").utm(tag).verified().withObject().withEstimate().signed().build();
        master("UTM-TEST").verified().build(); // no tags at all → the NULL bucket

        List<SourceBreakdownResponse.UtmStat> utm = metricsService.bySource().utm();

        SourceBreakdownResponse.UtmStat tagged = utm.stream()
                .filter(u -> tag.equals(u.source())).findFirst().orElseThrow();
        assertThat(tagged.registered()).isEqualTo(1L);
        assertThat(tagged.withSigned()).isEqualTo(1L);
        assertThat(tagged.enoughData()).isFalse(); // one master is not a channel measurement

        // «Без UTM» is the largest bucket in reality, and it is a row, not a gap: dropped from the
        // fold, the table would total less than the registration count and look like a data loss.
        assertThat(utm).anyMatch(u -> u.source() == null && u.registered() > 0);
    }

    // ---- fixture ----------------------------------------------------------

    private static long sum(List<SourceBreakdownResponse.SourceStat> rows,
                            java.util.function.ToLongFunction<SourceBreakdownResponse.SourceStat> step) {
        return rows.stream().mapToLong(step).sum();
    }

    private SourceBreakdownResponse.SourceStat rowFor(String source) {
        return find(source).orElseThrow();
    }

    private Optional<SourceBreakdownResponse.SourceStat> find(String source) {
        return metricsService.bySource().sources().stream()
                .filter(s -> source.equals(s.source())).findFirst();
    }

    private MasterFixture master(String source) {
        return new MasterFixture(source);
    }

    /** One master built up to a chosen funnel depth — the steps read like the funnel itself. */
    private final class MasterFixture {
        private final String source;
        private String utmSource;
        private Role role = Role.USER;
        private boolean verified;
        private boolean object;
        private boolean estimate;
        private boolean signed;
        private boolean objectLink;
        private boolean estimateLink;

        private MasterFixture(String source) {
            this.source = source;
        }

        MasterFixture utm(String tag) { this.utmSource = tag; return this; }
        MasterFixture role(Role r) { this.role = r; return this; }
        MasterFixture verified() { this.verified = true; return this; }
        MasterFixture withObject() { this.object = true; return this; }
        MasterFixture withEstimate() { this.estimate = true; return this; }
        MasterFixture signed() { this.signed = true; return this; }
        MasterFixture sharedFromObject() { this.objectLink = true; return this; }
        MasterFixture sharedFromEstimate() { this.estimateLink = true; return this; }

        UUID build() {
            String unique = UUID.randomUUID().toString();
            User owner = userRepository.save(User.builder()
                    .email(unique + "@majstr.test")
                    .emailCanonical(unique + "@majstr.test")
                    .passwordHash("x")
                    .fullName("Майстер")
                    .phone("+380000000000")
                    .companyName("ФОП")
                    .plan(Plan.FREE)
                    .role(role)
                    .emailVerified(verified)
                    .referralSource(source)
                    .utmSource(utmSource)
                    .referralCode(unique.substring(0, 10)) // NOT NULL since V41, no entity default
                    .build());

            if (!object && !estimate && !objectLink && !estimateLink && !signed) {
                return owner.getId();
            }
            Project project = projectRepository.save(Project.builder()
                    .owner(owner)
                    .name("Обʼєкт")
                    .address("вул. Тестова, 1")
                    .status(ProjectStatus.DRAFT)
                    .build());
            if (estimate || signed || estimateLink) {
                Estimate est = estimateRepository.save(Estimate.builder()
                        .project(project)
                        .status(signed ? EstimateStatus.SIGNED : EstimateStatus.SENT)
                        .build());
                if (estimateLink) {
                    estimateShareLinkRepository.save(EstimateShareLink.builder()
                            .estimate(est)
                            .token(UUID.randomUUID().toString())
                            .build());
                }
            }
            if (objectLink) {
                projectShareLinkRepository.save(ProjectShareLink.builder()
                        .project(project)
                        .token(UUID.randomUUID().toString())
                        .kind(ShareLinkKind.PORTAL)
                        .build());
            }
            return owner.getId();
        }
    }
}

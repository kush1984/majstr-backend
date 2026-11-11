package com.majstr.backend.service;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.entity.AdminPlanGrant;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.PlanTermValidationException;
import com.majstr.backend.repository.AdminPlanGrantRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminPlanServiceTest {

    @Mock UserRepository userRepository;
    @Mock AdminPlanGrantRepository grantRepository;
    @InjectMocks AdminPlanService service;

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    private static User user(Plan plan, Instant expires) {
        return User.builder().id(UUID.randomUUID()).email("m@majstr.test").plan(plan).planExpiresAt(expires).build();
    }

    private static Instant plusMonths(Instant from, int months) {
        return ZonedDateTime.ofInstant(from, LocalizationConfig.ZONE).plusMonths(months).toInstant();
    }

    @Test
    void aTermOnFreeCountsFromToday() {
        assertThat(AdminPlanService.endOf(user(Plan.FREE, null), Plan.PRO, 2, NOW)).isEqualTo(plusMonths(NOW, 2));
    }

    @Test
    void monthsAreAddedToARunningTermOfTheSamePlan() {
        Instant runsUntil = NOW.plus(Duration.ofDays(10));
        assertThat(AdminPlanService.endOf(user(Plan.PRO, runsUntil), Plan.PRO, 1, NOW))
                .isEqualTo(plusMonths(runsUntil, 1));
    }

    @Test
    void aLapsedTermOrAnotherPlanCountsFromToday() {
        assertThat(AdminPlanService.endOf(user(Plan.PRO, NOW.minus(Duration.ofDays(1))), Plan.PRO, 1, NOW))
                .isEqualTo(plusMonths(NOW, 1));
        assertThat(AdminPlanService.endOf(user(Plan.TEAM, NOW.plus(Duration.ofDays(40))), Plan.PRO, 1, NOW))
                .isEqualTo(plusMonths(NOW, 1));
    }

    @Test
    void noTermMeansNoEnd_andTheAdminIsRecorded() {
        User u = user(Plan.PRO, NOW.plus(Duration.ofDays(5)));
        given(userRepository.findById(u.getId())).willReturn(Optional.of(u));

        service.change(u.getId(), Plan.PRO, null, null, "boss@majstr.test");

        assertThat(u.getPlanExpiresAt()).isNull();
        ArgumentCaptor<AdminPlanGrant> grant = ArgumentCaptor.forClass(AdminPlanGrant.class);
        verify(grantRepository).save(grant.capture());
        assertThat(grant.getValue().getActorEmail()).isEqualTo("boss@majstr.test");
        assertThat(grant.getValue().getMonths()).isNull();
        assertThat(grant.getValue().getExpiresAt()).isNull();
    }

    @Test
    void freeIgnoresATermAndLeavesTheBillingDateAlone() {
        Instant billed = NOW.plus(Duration.ofDays(5));
        User u = user(Plan.PRO, billed);
        given(userRepository.findById(u.getId())).willReturn(Optional.of(u));

        service.change(u.getId(), Plan.FREE, 3, null, "boss@majstr.test");

        assertThat(u.getPlan()).isEqualTo(Plan.FREE);
        assertThat(u.getPlanExpiresAt()).isEqualTo(billed);
        ArgumentCaptor<AdminPlanGrant> grant = ArgumentCaptor.forClass(AdminPlanGrant.class);
        verify(grantRepository).save(grant.capture());
        assertThat(grant.getValue().getMonths()).isNull();
    }

    @Test
    void aDateMeansThroughTheWholeOfThatDayInKyiv() {
        Instant end = AdminPlanService.endOfDay(LocalDate.of(2026, 12, 14), NOW);
        assertThat(end).isEqualTo(LocalDate.of(2026, 12, 15).atStartOfDay(LocalizationConfig.ZONE).toInstant());
    }

    @Test
    void aDateMustBeAfterToday() {
        assertThatThrownBy(() -> AdminPlanService.endOfDay(LocalDate.of(2026, 11, 11), NOW))
                .isInstanceOf(PlanTermValidationException.class);
        assertThatThrownBy(() -> AdminPlanService.endOfDay(LocalDate.of(2026, 10, 1), NOW))
                .isInstanceOf(PlanTermValidationException.class);
    }

    @Test
    void aDateReplacesARunningTermAndIsRecordedWithoutMonths() {
        User u = user(Plan.PRO, NOW.plus(Duration.ofDays(60)));
        given(userRepository.findById(u.getId())).willReturn(Optional.of(u));
        LocalDate until = LocalDate.now(LocalizationConfig.ZONE).plusDays(10);

        service.change(u.getId(), Plan.PRO, null, until, "boss@majstr.test");

        Instant expected = until.plusDays(1).atStartOfDay(LocalizationConfig.ZONE).toInstant();
        assertThat(u.getPlanExpiresAt()).isEqualTo(expected);
        ArgumentCaptor<AdminPlanGrant> grant = ArgumentCaptor.forClass(AdminPlanGrant.class);
        verify(grantRepository).save(grant.capture());
        assertThat(grant.getValue().getMonths()).isNull();
        assertThat(grant.getValue().getExpiresAt()).isEqualTo(expected);
    }

    @Test
    void monthsAndADateTogetherAreRefused() {
        assertThatThrownBy(() -> service.change(UUID.randomUUID(), Plan.PRO, 1,
                LocalDate.now(LocalizationConfig.ZONE).plusDays(5), "boss@majstr.test"))
                .isInstanceOf(PlanTermValidationException.class);
    }
}

package com.majstr.backend.repository;

import com.majstr.backend.entity.Plan;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the admin-user-search fix: the LIKE pattern is built in Java (typed
 * text), not via {@code LOWER(CONCAT('%', :search, '%'))} which made PostgreSQL
 * infer the bind parameter as {@code bytea} and fail with
 * "function lower(bytea) does not exist".
 *
 * <p>It also guards the id branch: a term that parses as a UUID is passed to the query as an
 * {@code id} bind alongside the text pattern, which is what lets a PostHog session replay be
 * traced back to a real master (PostHog knows the user by UUID and nothing else).</p>
 *
 * <p>Pure unit tests over {@link UserRepository#likePattern}, {@link UserRepository#idOrNull} and
 * the default {@code searchAdmin} delegation — the SQL execution itself belongs to the
 * (not-yet-wired) Testcontainers slice; see docs/open-questions.md.</p>
 */
class UserRepositorySearchTest {

    @Test
    void likePattern_blankOrNull_isNull_soSearchClauseIsSkipped() {
        assertThat(UserRepository.likePattern(null)).isNull();
        assertThat(UserRepository.likePattern("")).isNull();
        assertThat(UserRepository.likePattern("   ")).isNull();
    }

    @Test
    void likePattern_wrapsLoweredTrimmedTermInWildcards() {
        assertThat(UserRepository.likePattern("ACME")).isEqualTo("%acme%");
        assertThat(UserRepository.likePattern("  Acme Bud  ")).isEqualTo("%acme bud%");
        assertThat(UserRepository.likePattern("Іван")).isEqualTo("%іван%"); // Cyrillic lowercases
        assertThat(UserRepository.likePattern("a@B.com")).isEqualTo("%a@b.com%");
    }

    @Test
    void idOrNull_parsesAUuidTerm_andIsNullForEverythingElse() {
        UUID id = UUID.fromString("866feca8-dc5b-403f-993e-c6a1b2c3d4e5");
        assertThat(UserRepository.idOrNull("866feca8-dc5b-403f-993e-c6a1b2c3d4e5")).isEqualTo(id);
        assertThat(UserRepository.idOrNull("  866FECA8-DC5B-403F-993E-C6A1B2C3D4E5  ")).isEqualTo(id);

        assertThat(UserRepository.idOrNull(null)).isNull();
        assertThat(UserRepository.idOrNull("   ")).isNull();
        assertThat(UserRepository.idOrNull("acme")).isNull();
        assertThat(UserRepository.idOrNull("a@b.com")).isNull();
        // A TRUNCATED id is the realistic miss: PostHog and our own list both show a shortened
        // UUID, so half of one WILL get pasted. This is the case the plain `UUID.fromString` call
        // got WRONG: below 36 characters it stops validating and just splits on "-", so this string
        // parses into a perfectly valid but DIFFERENT uuid. It has to be rejected and fall through
        // to the text search instead.
        assertThat(UserRepository.idOrNull("866feca8-dc5b-403f-993e-c6")).isNull();
        assertThat(UserRepository.idOrNull("866feca8-dc5b-403f-993e-c6a1b2c3d4e5x")).isNull();
    }

    @Test
    void searchAdmin_withAUuid_passesItAsAnIdAsWellAsAPattern() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.searchAdmin(any(), any(), any(), any(), any())).thenCallRealMethod();
        when(repo.searchAdminByPattern(any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.<com.majstr.backend.entity.User>empty());
        Instant activeSince = Instant.now();
        String raw = "866feca8-dc5b-403f-993e-c6a1b2c3d4e5";

        repo.searchAdmin(null, null, raw, activeSince, Pageable.unpaged());

        // This is the PostHog replay → real person lookup: the `distinct_id` off a recording is the
        // user id, and pasting it must find that one master. The pattern still travels — a term is
        // both things at once, and the query ORs them.
        verify(repo).searchAdminByPattern(
                isNull(), isNull(), eq("%" + raw + "%"), eq(UUID.fromString(raw)),
                eq(activeSince), eq(Pageable.unpaged()));
    }

    @Test
    void searchAdmin_withText_delegatesWithLoweredPatternAndPlan() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.searchAdmin(any(), any(), any(), any(), any())).thenCallRealMethod();
        when(repo.searchAdminByPattern(any(), any(), any(), any(), any(), any())).thenReturn(Page.<com.majstr.backend.entity.User>empty());
        Instant activeSince = Instant.now();

        repo.searchAdmin(Plan.PRO, null, "ACME", activeSince, Pageable.unpaged());

        // text + plan together: lowered "%acme%" pattern, plan passed through, no source filter.
        verify(repo).searchAdminByPattern(eq(Plan.PRO), isNull(), eq("%acme%"), isNull(), eq(activeSince), eq(Pageable.unpaged()));
    }

    @Test
    void searchAdmin_blankText_passesNullPattern_soAllUsersReturn() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.searchAdmin(any(), any(), any(), any(), any())).thenCallRealMethod();
        when(repo.searchAdminByPattern(any(), any(), any(), any(), any(), any())).thenReturn(Page.<com.majstr.backend.entity.User>empty());
        Instant activeSince = Instant.now();

        repo.searchAdmin(null, null, "   ", activeSince, Pageable.unpaged());

        // empty search → null pattern (no filter); no plan/source filter either.
        verify(repo).searchAdminByPattern(isNull(), isNull(), isNull(), isNull(), eq(activeSince), eq(Pageable.unpaged()));
    }

    @Test
    void searchAdmin_trimsAndUppercasesTheSourceFilter() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.searchAdmin(any(), any(), any(), any(), any())).thenCallRealMethod();
        when(repo.searchAdminByPattern(any(), any(), any(), any(), any(), any())).thenReturn(Page.<com.majstr.backend.entity.User>empty());
        Instant activeSince = Instant.now();

        repo.searchAdmin(null, " liga ", null, activeSince, Pageable.unpaged());

        // source is trimmed + uppercased to match the stored referral_source.
        verify(repo).searchAdminByPattern(isNull(), eq("LIGA"), isNull(), isNull(), eq(activeSince), eq(Pageable.unpaged()));
    }

    @Test
    void searchAdmin_registrationDescending_delegatesToTheOriginalTwoLevelQuery() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.searchAdmin(any(), any(), any(), any(), anyBoolean(), any())).thenCallRealMethod();
        when(repo.searchAdmin(any(), any(), any(), any(), any())).thenCallRealMethod();
        when(repo.searchAdminByPattern(any(), any(), any(), any(), any(), any())).thenReturn(Page.<com.majstr.backend.entity.User>empty());
        Instant activeSince = Instant.now();

        repo.searchAdmin(Plan.PRO, null, "ACME", activeSince, false, Pageable.unpaged());

        // false (the default / DESC toggle state) is the same active-first, newest-registered-first
        // query as the 5-arg searchAdmin — no separate ORDER BY variant needed for it.
        verify(repo).searchAdminByPattern(eq(Plan.PRO), isNull(), eq("%acme%"), isNull(), eq(activeSince), eq(Pageable.unpaged()));
    }

    @Test
    void searchAdmin_registrationAscending_delegatesToTheAscendingQuery() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.searchAdmin(any(), any(), any(), any(), anyBoolean(), any())).thenCallRealMethod();
        when(repo.searchAdminByPatternRegistrationAscending(any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.<com.majstr.backend.entity.User>empty());
        Instant activeSince = Instant.now();

        repo.searchAdmin(Plan.PRO, " liga ", "ACME", activeSince, true, Pageable.unpaged());

        // Same plan/source/pattern handling as the descending path, just the ascending ORDER BY query.
        verify(repo).searchAdminByPatternRegistrationAscending(
                eq(Plan.PRO), eq("LIGA"), eq("%acme%"), isNull(), eq(activeSince), eq(Pageable.unpaged()));
    }
}

package com.majstr.backend.repository;

import com.majstr.backend.entity.Plan;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * <p>Pure unit tests over {@link UserRepository#likePattern} and the default
 * {@code searchAdmin} delegation — the SQL execution itself belongs to the
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
    void searchAdmin_withText_delegatesWithLoweredPatternAndPlan() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.searchAdmin(any(), any(), any())).thenCallRealMethod();
        when(repo.searchAdminByPattern(any(), any(), any())).thenReturn(Page.<com.majstr.backend.entity.User>empty());

        repo.searchAdmin(Plan.PRO, "ACME", Pageable.unpaged());

        // text + plan together: lowered "%acme%" pattern, plan passed through.
        verify(repo).searchAdminByPattern(eq(Plan.PRO), eq("%acme%"), eq(Pageable.unpaged()));
    }

    @Test
    void searchAdmin_blankText_passesNullPattern_soAllUsersReturn() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.searchAdmin(any(), any(), any())).thenCallRealMethod();
        when(repo.searchAdminByPattern(any(), any(), any())).thenReturn(Page.<com.majstr.backend.entity.User>empty());

        repo.searchAdmin(null, "   ", Pageable.unpaged());

        // empty search → null pattern (no filter); no plan filter either.
        verify(repo).searchAdminByPattern(isNull(), isNull(), eq(Pageable.unpaged()));
    }
}

package com.majstr.backend.controller;

import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the building block Spring Security relies on: the authority
 * names a {@link UserPrincipal} exposes. {@code SecurityConfig} pins
 * {@code /api/admin/**} to {@code hasRole("ADMIN")}, which matches the
 * {@code ROLE_ADMIN} authority generated here. End-to-end coverage
 * (full filter chain + URL matcher) is exercised manually in the
 * walkthrough until a SpringBootTest slice is added.
 */
class AdminAccessTest {

    @Test
    void plainUserPrincipalCarriesOnlyRoleUserAuthority() {
        UserPrincipal principal = UserPrincipal.from(userWithRole(Role.USER));
        assertThat(principal.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    void adminUserPrincipalCarriesRoleAdminAuthority() {
        UserPrincipal principal = UserPrincipal.from(userWithRole(Role.ADMIN));
        assertThat(principal.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void principalDoesNotCarryAnyRoleAdminAuthorityForNonAdmin() {
        UserPrincipal principal = UserPrincipal.from(userWithRole(Role.USER));
        assertThat(principal.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .doesNotContain("ROLE_ADMIN");
    }

    private User userWithRole(Role role) {
        return User.builder()
                .id(UUID.randomUUID())
                .role(role)
                .build();
    }
}

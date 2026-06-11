package com.majstr.backend.bootstrap;

import com.majstr.backend.config.AdminSeedProperties;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AdminSeederTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;

    private AdminSeeder seeder(String email, String password) {
        return new AdminSeeder(new AdminSeedProperties(email, password), userRepository, passwordEncoder);
    }

    @Test
    void createsAdminOnCleanDbWhenEnvProvided() {
        given(userRepository.existsByRole(Role.ADMIN)).willReturn(false);
        given(userRepository.existsByEmailIgnoreCase("admin@majstr.pro")).willReturn(false);
        given(passwordEncoder.encode("S3cret-Admin!")).willReturn("bcrypt-hash");

        seeder("Admin@Majstr.pro", "S3cret-Admin!").run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User admin = saved.getValue();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getEmail()).isEqualTo("admin@majstr.pro"); // normalized
        assertThat(admin.getPasswordHash()).isEqualTo("bcrypt-hash"); // BCrypt, never the raw value
        assertThat(admin.getPasswordHash()).isNotEqualTo("S3cret-Admin!");
        assertThat(admin.isEmailVerified()).isTrue();
        assertThat(admin.getPlan()).isEqualTo(Plan.TEAM);
    }

    @Test
    void doesNothingWhenEnvNotConfigured() {
        seeder("", "").run(null);

        // Blank env → returns before touching the DB or the encoder.
        verifyNoInteractions(userRepository);
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void doesNotDuplicateWhenAdminAlreadyExists() {
        given(userRepository.existsByRole(Role.ADMIN)).willReturn(true);

        seeder("admin@majstr.pro", "S3cret-Admin!").run(null);

        verify(userRepository, never()).save(any());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void skipsWhenEmailTakenByNonAdminUser() {
        given(userRepository.existsByRole(Role.ADMIN)).willReturn(false);
        given(userRepository.existsByEmailIgnoreCase("admin@majstr.pro")).willReturn(true);

        seeder("admin@majstr.pro", "S3cret-Admin!").run(null);

        // Don't hijack the existing account or trip the unique constraint.
        verify(userRepository, never()).save(any());
        verifyNoInteractions(passwordEncoder);
    }
}

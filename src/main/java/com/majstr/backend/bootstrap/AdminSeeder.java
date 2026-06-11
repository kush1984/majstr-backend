package com.majstr.backend.bootstrap;

import com.majstr.backend.config.AdminSeedProperties;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Creates the very first {@code ADMIN} on startup so a fresh production database
 * has an account that can reach the admin panel — without hand-editing the DB.
 *
 * <p>Runs in every profile but is a safe no-op unless <b>both</b> conditions hold:
 * {@code ADMIN_EMAIL} + {@code ADMIN_PASSWORD} are set, and no {@code ADMIN} exists
 * yet. Idempotent — once any admin exists (or on a restart) it does nothing. The
 * password is BCrypt-hashed and never logged. Email/password come from env only.</p>
 *
 * <p>Not {@code @Profile}-gated (unlike {@link com.majstr.backend.dev.DevDataSeeder}):
 * in dev the env vars are blank so it no-ops; in prod it bootstraps the admin.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSeeder implements ApplicationRunner {

    // Placeholder profile fields for a non-contractor system account — the admin
    // uses the admin panel, not the contractor flows. Email/password are env-driven.
    private static final String ADMIN_FULL_NAME = "Majstr Admin";
    private static final String ADMIN_COMPANY = "Majstr";
    private static final String ADMIN_PHONE = "-";

    private final AdminSeedProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isConfigured()) {
            return; // No ADMIN_EMAIL/ADMIN_PASSWORD — nothing to seed (dev default).
        }
        if (userRepository.existsByRole(Role.ADMIN)) {
            log.info("Admin auto-seed: an ADMIN already exists — skipping");
            return;
        }
        String email = properties.email().toLowerCase().trim();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            // The configured email is taken by a non-admin user — don't hijack it
            // or crash the unique constraint. Promote that account by hand if intended.
            log.warn("Admin auto-seed: ADMIN_EMAIL is already in use by a non-admin user — skipping");
            return;
        }
        User admin = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(properties.password()))
                .fullName(ADMIN_FULL_NAME)
                .companyName(ADMIN_COMPANY)
                .phone(ADMIN_PHONE)
                .trades(new LinkedHashSet<>(Set.of(Trade.GENERAL)))
                .plan(Plan.TEAM)
                .role(Role.ADMIN)
                .emailVerified(true)
                .build();
        try {
            userRepository.save(admin);
            log.info("Admin auto-seed: created initial ADMIN account (password from env, not logged)");
        } catch (DataIntegrityViolationException e) {
            // Another instance won the race (multi-node start) — the admin now exists.
            log.info("Admin auto-seed: ADMIN created concurrently by another instance — skipping");
        }
    }
}

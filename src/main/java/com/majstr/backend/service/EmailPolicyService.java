package com.majstr.backend.service;

import com.majstr.backend.exception.EmailDomainNotAllowedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Set;

/**
 * Anti-abuse email policy applied at registration and unverified-email change.
 * Three guards, cheapest first:
 *
 * <ol>
 *   <li><b>Disposable-domain blocklist</b> — reject known throwaway providers
 *       (loaded from {@code anti-abuse/disposable-email-domains.txt}).</li>
 *   <li><b>Mail-exchanger (MX/A) check</b> — reject a domain that can't receive
 *       mail (a made-up domain). <b>Fail-open:</b> a DNS hiccup/timeout must never
 *       block a legitimate registration, so lookup errors are treated as "ok".</li>
 *   <li><b>Canonicalization</b> — collapse address aliases (gmail dots + plus tags,
 *       plus tags on other plus-aware providers) to one canonical form used for
 *       duplicate-account detection, so {@code j.o.hn+2@gmail.com} can't sidestep
 *       the existing account {@code john@gmail.com}.</li>
 * </ol>
 *
 * The blocklist is deliberately small and curated (not exhaustive) — extend it as
 * abuse is observed. Mirrors the fail-soft, env-free style of the rest of the app.
 */
@Slf4j
@Service
public class EmailPolicyService {

    private static final String BLOCKLIST_RESOURCE = "anti-abuse/disposable-email-domains.txt";

    /** Providers that support "+tag" aliasing — the tag is stripped for canonical dedup. */
    private static final Set<String> PLUS_ALIAS_PROVIDERS = Set.of(
            "gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "live.com",
            "yahoo.com", "protonmail.com", "proton.me", "icloud.com", "fastmail.com");

    /** Google domains additionally ignore dots in the local part. */
    private static final Set<String> DOT_INSENSITIVE_PROVIDERS = Set.of("gmail.com", "googlemail.com");

    private final Set<String> disposableDomains;

    public EmailPolicyService() {
        this.disposableDomains = loadBlocklist();
        log.info("Loaded {} disposable email domains for the registration blocklist", disposableDomains.size());
    }

    /**
     * The canonical form used for duplicate detection — NOT the login address (the
     * user still logs in with what they typed). Lowercased+trimmed; for plus-aware
     * providers the {@code +tag} is dropped; for Google the dots are removed and the
     * domain normalized to {@code gmail.com}.
     */
    public String canonicalize(String rawEmail) {
        String email = rawEmail == null ? "" : rawEmail.toLowerCase().trim();
        int at = email.lastIndexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return email; // not a well-formed address — leave as-is (format is validated elsewhere)
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);

        if (PLUS_ALIAS_PROVIDERS.contains(domain)) {
            int plus = local.indexOf('+');
            if (plus >= 0) {
                local = local.substring(0, plus);
            }
        }
        if (DOT_INSENSITIVE_PROVIDERS.contains(domain)) {
            local = local.replace(".", "");
            domain = "gmail.com";
        }
        return local + "@" + domain;
    }

    /**
     * Rejects a disposable-domain or non-mail-receiving address. Format is assumed
     * already valid (jakarta {@code @Email} on the request). Safe to call inside a
     * transaction — the MX lookup is bounded (2s) and fail-open.
     */
    public void assertAcceptable(String rawEmail) {
        String email = rawEmail == null ? "" : rawEmail.toLowerCase().trim();
        int at = email.lastIndexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return; // malformed — let the format validator own it
        }
        String domain = email.substring(at + 1);

        if (disposableDomains.contains(domain)) {
            throw new EmailDomainNotAllowedException("error.email.domain-not-allowed");
        }
        if (!hasMailExchanger(domain)) {
            throw new EmailDomainNotAllowedException("error.email.domain-not-allowed");
        }
    }

    /**
     * True if {@code domain} has an MX (or fallback A) record — i.e. it can receive
     * mail. Fail-open: any lookup failure (SERVFAIL, timeout, no DNS) returns true so
     * a transient DNS problem never blocks a real user.
     */
    private boolean hasMailExchanger(String domain) {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "2000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            DirContext ctx = new InitialDirContext(env);
            try {
                Attributes attrs = ctx.getAttributes(domain, new String[]{"MX", "A"});
                Attribute mx = attrs.get("MX");
                Attribute a = attrs.get("A");
                return (mx != null && mx.size() > 0) || (a != null && a.size() > 0);
            } finally {
                ctx.close();
            }
        } catch (Exception e) {
            log.debug("MX lookup failed for {} — allowing (fail-open): {}", domain, e.toString());
            return true;
        }
    }

    private static Set<String> loadBlocklist() {
        Set<String> domains = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(BLOCKLIST_RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String d = line.trim().toLowerCase();
                if (!d.isEmpty() && !d.startsWith("#")) {
                    domains.add(d);
                }
            }
        } catch (IOException e) {
            // Non-fatal: an unreadable blocklist must not break startup — the MX check
            // and canonical dedup still apply. Log loudly so it's noticed.
            log.error("Failed to load disposable-email blocklist {} — continuing with none: {}",
                    BLOCKLIST_RESOURCE, e.toString());
        }
        return domains;
    }
}

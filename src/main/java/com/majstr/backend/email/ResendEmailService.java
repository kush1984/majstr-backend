package com.majstr.backend.email;

import com.majstr.backend.config.EmailProperties;
import com.majstr.backend.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sends transactional email via the Resend HTTP API
 * (https://api.resend.com/emails). The API key comes from
 * {@link EmailProperties} (env only). Each send runs on a background thread
 * ({@link Async}) so it never blocks the request, and swallows+logs any
 * failure so a flaky email provider can't break the calling flow.
 */
@Slf4j
@Service
public class ResendEmailService implements EmailService {

    private static final String RESEND_URL = "https://api.resend.com/emails";

    private final EmailProperties props;
    private final RestClient restClient;

    public ResendEmailService(EmailProperties props) {
        this.props = props;
        this.restClient = RestClient.create();
    }

    @Override
    @Async
    public void sendVerificationEmail(User user, String token) {
        String link = props.appUrl() + "/verify-email?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        if (!props.isConfigured()) {
            log.warn("RESEND_API_KEY not set — skipping verification email to {}. Verify link: {}",
                    user.getEmail(), link);
            return;
        }
        send(user.getEmail(), "Підтвердіть email — Majstr", verificationHtml(user.getFullName(), link));
    }

    @Override
    @Async
    public void sendPasswordResetEmail(User user, String token) {
        String link = props.appUrl() + "/reset-password?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        if (!props.isConfigured()) {
            log.warn("RESEND_API_KEY not set — skipping password-reset email to {}. Reset link: {}",
                    user.getEmail(), link);
            return;
        }
        send(user.getEmail(), "Скидання пароля — Majstr", passwordResetHtml(user.getFullName(), link));
    }

    @Override
    @Async
    public void sendEstimateShareEmail(String toEmail, String clientName, String contractorName,
                                       String projectName, String shareUrl) {
        if (!props.isConfigured()) {
            log.warn("RESEND_API_KEY not set — skipping estimate email to {}. Portal link: {}", toEmail, shareUrl);
            return;
        }
        send(toEmail, "Кошторис від " + contractorName,
                estimateShareHtml(clientName, contractorName, projectName, shareUrl));
    }

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy").withLocale(Locale.forLanguageTag("uk"));

    @Override
    @Async
    public void sendRenewReminderEmail(User user, Instant chargeDate, BigDecimal amount) {
        String manageUrl = props.appUrl() + "/profile";
        if (!props.isConfigured()) {
            log.warn("RESEND_API_KEY not set — skipping renew reminder to {} (charge {})", user.getEmail(), fmt(chargeDate));
            return;
        }
        send(user.getEmail(), "Автопродовження Majstr PRO — " + fmt(chargeDate),
                renewReminderHtml(money(amount), fmt(chargeDate), manageUrl));
    }

    @Override
    @Async
    public void sendRenewReceiptEmail(User user, Instant until, BigDecimal amount) {
        if (!props.isConfigured()) {
            log.warn("RESEND_API_KEY not set — skipping renew receipt to {} (until {})", user.getEmail(), fmt(until));
            return;
        }
        send(user.getEmail(), "Оплату Majstr PRO прийнято",
                renewReceiptHtml(money(amount), fmt(until)));
    }

    @Override
    @Async
    public void sendRenewFailedEmail(User user, Instant accessUntil, BigDecimal amount) {
        String updateUrl = props.appUrl() + "/profile";
        if (!props.isConfigured()) {
            log.warn("RESEND_API_KEY not set — skipping renew-failed email to {}", user.getEmail());
            return;
        }
        send(user.getEmail(), "Не вдалося продовжити Majstr PRO",
                renewFailedHtml(money(amount), fmt(accessUntil), updateUrl));
    }

    private static String fmt(Instant instant) {
        return instant == null ? "—" : DATE.format(instant.atZone(ZoneId.of("Europe/Kyiv")).toLocalDate());
    }

    private static String money(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString() + " ₴";
    }

    /** Actual HTTP call — fail-soft: never throws, logs the outcome. Callers ensure the key is configured. */
    private void send(String toEmail, String subject, String html) {
        try {
            restClient.post()
                    .uri(RESEND_URL)
                    .header("Authorization", "Bearer " + props.resendApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "from", props.fromAddress(),
                            "to", List.of(toEmail),
                            "subject", subject,
                            "html", html))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Email sent to {} (subject: {})", toEmail, subject);
        } catch (Exception e) {
            log.error("Failed to send email to {} (subject: {}): {}", toEmail, subject, e.getMessage());
        }
    }

    private static String passwordResetHtml(String fullName, String link) {
        return """
                <div style="font-family:Arial,sans-serif;max-width:480px;margin:0 auto;color:#1a1a1a">
                  <h2 style="color:#F26B1F">Скидання пароля%s</h2>
                  <p>Ви (або хтось) попросили скинути пароль до вашого акаунту Majstr.
                     Натисніть кнопку, щоб задати новий пароль.</p>
                  <p style="margin:28px 0">
                    <a href="%s" style="background:#F26B1F;color:#fff;text-decoration:none;
                       padding:12px 24px;border-radius:8px;display:inline-block;font-weight:bold">
                       Змінити пароль</a>
                  </p>
                  <p style="font-size:13px;color:#666">Якщо кнопка не працює, відкрийте посилання:<br>
                     <a href="%s">%s</a></p>
                  <p style="font-size:13px;color:#666">Посилання дійсне 45 хвилин. Якщо це не ви —
                     просто проігноруйте цей лист, пароль лишиться той самий.</p>
                </div>
                """.formatted(
                        fullName == null || fullName.isBlank() ? "" : ", " + fullName,
                        link, link, link);
    }

    private static String verificationHtml(String fullName, String link) {
        return """
                <div style="font-family:Arial,sans-serif;max-width:480px;margin:0 auto;color:#1a1a1a">
                  <h2 style="color:#F26B1F">Вітаємо в Majstr%s!</h2>
                  <p>Дякуємо за реєстрацію. Підтвердіть свою електронну пошту, щоб
                     надсилати кошториси клієнтам.</p>
                  <p style="margin:28px 0">
                    <a href="%s" style="background:#F26B1F;color:#fff;text-decoration:none;
                       padding:12px 24px;border-radius:8px;display:inline-block;font-weight:bold">
                       Підтвердити email</a>
                  </p>
                  <p style="font-size:13px;color:#666">Якщо кнопка не працює, відкрийте посилання:<br>
                     <a href="%s">%s</a></p>
                  <p style="font-size:13px;color:#666">Посилання дійсне 24 години. Якщо ви не
                     реєструвались у Majstr — просто проігноруйте цей лист.</p>
                </div>
                """.formatted(
                        fullName == null || fullName.isBlank() ? "" : ", " + fullName,
                        link, link, link);
    }

    private static String estimateShareHtml(String clientName, String contractorName, String projectName, String shareUrl) {
        return """
                <div style="font-family:Arial,sans-serif;max-width:480px;margin:0 auto;color:#1a1a1a">
                  <h2 style="color:#F26B1F">Вітаємо%s!</h2>
                  <p><b>%s</b> надіслав(ла) вам кошторис на об'єкт «<b>%s</b>».</p>
                  <p>Ви можете переглянути його, поставити запитання або підписати онлайн:</p>
                  <p style="margin:28px 0">
                    <a href="%s" style="background:#F26B1F;color:#fff;text-decoration:none;
                       padding:12px 24px;border-radius:8px;display:inline-block;font-weight:bold">
                       Переглянути кошторис</a>
                  </p>
                  <p style="font-size:13px;color:#666">Якщо кнопка не працює, відкрийте посилання:<br>
                     <a href="%s">%s</a></p>
                </div>
                """.formatted(
                        clientName == null || clientName.isBlank() ? "" : ", " + clientName,
                        contractorName, projectName, shareUrl, shareUrl, shareUrl);
    }

    private static String renewReminderHtml(String amount, String chargeDate, String manageUrl) {
        return """
                <div style="font-family:Arial,sans-serif;max-width:480px;margin:0 auto;color:#1a1a1a">
                  <h2 style="color:#F26B1F">Автопродовження PRO</h2>
                  <p><b>%s</b> ми автоматично спишемо <b>%s</b> за наступний місяць Majstr PRO
                     з вашої збереженої картки.</p>
                  <p>Нічого робити не треба — доступ продовжиться сам. Якщо не хочете продовжувати,
                     вимкніть автопродовження у профілі (в один дотик):</p>
                  <p style="margin:24px 0">
                    <a href="%s" style="background:#F26B1F;color:#fff;text-decoration:none;
                       padding:12px 24px;border-radius:8px;display:inline-block;font-weight:bold">Керувати підпискою</a>
                  </p>
                  <p style="font-size:13px;color:#666">Посилання: <a href="%s">%s</a></p>
                </div>
                """.formatted(chargeDate, amount, manageUrl, manageUrl, manageUrl);
    }

    private static String renewReceiptHtml(String amount, String until) {
        return """
                <div style="font-family:Arial,sans-serif;max-width:480px;margin:0 auto;color:#1a1a1a">
                  <h2 style="color:#F26B1F">Оплату прийнято 🎉</h2>
                  <p>Ми списали <b>%s</b> — Majstr PRO активний до <b>%s</b>. Дякуємо, що з нами!</p>
                  <p style="font-size:13px;color:#666">Керувати підпискою або вимкнути автопродовження
                     можна будь-коли у профілі.</p>
                </div>
                """.formatted(amount, until);
    }

    private static String renewFailedHtml(String amount, String accessUntil, String updateUrl) {
        return """
                <div style="font-family:Arial,sans-serif;max-width:480px;margin:0 auto;color:#1a1a1a">
                  <h2 style="color:#F26B1F">Не вдалося списати оплату</h2>
                  <p>Ми не змогли списати <b>%s</b> за Majstr PRO — можливо, недостатньо коштів або
                     картка застаріла. <b>Доступ збережено до %s</b>, ми спробуємо ще раз.</p>
                  <p>Щоб не втратити PRO — онови картку (нова оплата з галочкою автопродовження):</p>
                  <p style="margin:24px 0">
                    <a href="%s" style="background:#F26B1F;color:#fff;text-decoration:none;
                       padding:12px 24px;border-radius:8px;display:inline-block;font-weight:bold">Оновити картку</a>
                  </p>
                  <p style="font-size:13px;color:#666">Посилання: <a href="%s">%s</a></p>
                </div>
                """.formatted(amount, accessUntil, updateUrl, updateUrl, updateUrl);
    }
}

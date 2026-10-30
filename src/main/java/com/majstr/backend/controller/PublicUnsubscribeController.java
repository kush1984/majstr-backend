package com.majstr.backend.controller;

import com.majstr.backend.service.broadcast.UnsubscribeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * The unsubscribe link in every broadcast letter.
 *
 * <p><b>GET never unsubscribes.</b> Mail scanners and link previews open every link in a letter; if
 * opening the link were the action, masters would be unsubscribed by their own antivirus. GET shows
 * one button; the POST behind it is the action — and is also exactly what Gmail's one-click
 * «Відписатися» sends (RFC 8058, {@code List-Unsubscribe-Post}), so both paths land here.</p>
 */
@RestController
@RequestMapping("/api/public/unsubscribe")
@RequiredArgsConstructor
@Tag(name = "Public unsubscribe", description = "Opt out of admin broadcasts from a letter's link")
public class PublicUnsubscribeController {

    private final UnsubscribeService unsubscribeService;

    @Operation(summary = "Confirmation page with one button")
    @GetMapping(value = "/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String token) {
        if (!unsubscribeService.isKnown(token)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(page("Посилання недійсне",
                    "<p>Це посилання не знайдено. Якщо лист був тестовим — так і має бути.</p>"));
        }
        String action = HtmlUtils.htmlEscape(token);
        return ResponseEntity.ok(page("Відписатися від листів Майстра",
                """
                <p>Ми більше не надсилатимемо вам листів-нагадувань і новин про Майстер.
                   Листи про ваш акаунт (підтвердження пошти, пароль, оплата) приходитимуть як і раніше.</p>
                <form method="post" action="%s"><button type="submit">Не надсилати мені такі листи</button></form>
                """.formatted(action)));
    }

    @Operation(summary = "Opt out (the page's button and the mail client's one-click POST)")
    @PostMapping(value = "/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> optOut(@PathVariable String token) {
        if (!unsubscribeService.optOut(token)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(page("Посилання недійсне",
                    "<p>Це посилання не знайдено.</p>"));
        }
        return ResponseEntity.ok(page("Готово",
                "<p>Ви відписались. Більше таких листів не буде.</p>"));
    }

    private static String page(String title, String body) {
        return """
                <!DOCTYPE html>
                <html lang="uk"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <meta name="robots" content="noindex, nofollow">
                <title>%s</title>
                <style>
                  body { margin: 0; padding: 32px 16px; background: #f7f7f8; color: #1f2937;
                         font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial, sans-serif; line-height: 1.5; }
                  main { max-width: 440px; margin: 0 auto; background: #fff; border: 1px solid #e5e7eb;
                         border-radius: 12px; padding: 20px; }
                  h1 { font-size: 1.25rem; margin: 0 0 12px; }
                  button { width: 100%%; min-height: 48px; font: inherit; font-weight: 600; color: #fff;
                           background: #F26B1F; border: 0; border-radius: 10px; cursor: pointer;
                           touch-action: manipulation; -webkit-user-select: none; user-select: none;
                           -webkit-touch-callout: none; }
                  button:active { opacity: 0.85; }
                </style></head>
                <body><main><h1>%s</h1>%s</main></body></html>
                """.formatted(title, title, body);
    }
}

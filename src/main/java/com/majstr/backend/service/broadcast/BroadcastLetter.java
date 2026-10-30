package com.majstr.backend.service.broadcast;

import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the admin's plain text into one master's letter: the name substituted, paragraphs on blank
 * lines, {@code **bold**}, one button, and the unsubscribe footer — which is appended here, never
 * left to the text, so no send can go out without it.
 *
 * <p>Everything the admin typed is HTML-escaped first: the text is plain, the formatting is ours.</p>
 */
public final class BroadcastLetter {

    /** {@code {{імʼя}}} with any of the apostrophes a Ukrainian keyboard produces, or {@code {{name}}}. */
    private static final String NAME = "\\{\\{\\s*(?:ім[ʼ'’`]?я|name)\\s*}}";
    private static final Pattern NAME_TOKEN = Pattern.compile(NAME, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** With no name, «Вітаю, {{імʼя}}!» must read «Вітаю!» — the comma goes with the token. */
    private static final Pattern NAME_WITH_LEAD = Pattern.compile(",?\\s*" + NAME, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final String ACCENT = "#F26B1F";

    private BroadcastLetter() {}

    public record Rendered(String subject, String html, String text) {}

    public static Rendered render(String subject, String body, String fullName,
                                  String buttonLabel, String buttonUrl, String unsubscribeUrl) {
        String name = fullName == null ? "" : fullName.trim();
        String personalSubject = personalize(subject, name).trim();
        String personalBody = personalize(body.replace("\r\n", "\n"), name).strip();
        return new Rendered(personalSubject,
                html(personalBody, buttonLabel, buttonUrl, unsubscribeUrl),
                text(personalBody, buttonLabel, buttonUrl, unsubscribeUrl));
    }

    static String personalize(String s, String name) {
        if (name.isEmpty()) {
            return NAME_WITH_LEAD.matcher(s).replaceAll("");
        }
        return NAME_TOKEN.matcher(s).replaceAll(Matcher.quoteReplacement(name));
    }

    private static String html(String body, String buttonLabel, String buttonUrl, String unsubscribeUrl) {
        StringBuilder out = new StringBuilder(
                "<div style=\"font-family:Arial,sans-serif;max-width:520px;margin:0 auto;color:#1a1a1a;line-height:1.5\">\n");
        for (String paragraph : paragraphs(body)) {
            String escaped = HtmlUtils.htmlEscape(paragraph);
            escaped = BOLD.matcher(escaped).replaceAll("<b>$1</b>").replace("\n", "<br>\n");
            out.append("  <p>").append(escaped).append("</p>\n");
        }
        String url = HtmlUtils.htmlEscape(buttonUrl);
        out.append("""
                  <p style="margin:24px 0">
                    <a href="%s" style="background:%s;color:#fff;text-decoration:none;
                       padding:12px 24px;border-radius:8px;display:inline-block;font-weight:bold">%s</a>
                  </p>
                  <p style="font-size:12px;color:#888;border-top:1px solid #eee;padding-top:12px;margin-top:32px">
                     Ви отримали цей лист, бо зареєстровані в Майстрі.
                     <a href="%s" style="color:#888">Не надсилати мені такі листи</a></p>
                </div>
                """.formatted(url, ACCENT, HtmlUtils.htmlEscape(buttonLabel), HtmlUtils.htmlEscape(unsubscribeUrl)));
        return out.toString();
    }

    private static String text(String body, String buttonLabel, String buttonUrl, String unsubscribeUrl) {
        return body.replace("**", "")
                + "\n\n" + buttonLabel + ": " + buttonUrl
                + "\n\n--\nВи отримали цей лист, бо зареєстровані в Майстрі.\nНе надсилати мені такі листи: "
                + unsubscribeUrl + "\n";
    }

    private static List<String> paragraphs(String body) {
        List<String> out = new ArrayList<>();
        for (String p : body.split("\\n\\s*\\n")) {
            String trimmed = p.strip();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}

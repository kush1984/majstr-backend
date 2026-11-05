package com.majstr.backend.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a visit report keeps of the browser string: three coarse words, never the string itself.
 *
 * <p>The in-app browsers come first on purpose. TikTok and Instagram usually open a link with no
 * referrer, so their own browser is often the only sign of where a visitor came from.</p>
 */
record VisitorAgent(String device, String os, String browser) {

    /** Crawlers, link previews and scripts. A real visitor's browser names none of these. */
    private static final Pattern NOT_A_PERSON = Pattern.compile(
            "bot/|bot-|\\bbot\\b|googlebot|bingbot|yandex(?:bot|images|metrika)|baiduspider|duckduckbot|applebot|petalbot"
                    + "|bytespider|gptbot|claudebot|telegrambot|twitterbot|linkedinbot|slackbot|discordbot"
                    + "|whatsapp|facebookexternalhit|crawler|spider|headless|lighthouse|python|curl/|wget"
                    + "|java/|go-http|okhttp|axios|node-fetch|^node$|undici|httpclient|http-client|phantomjs|pingdom"
                    + "|uptimerobot");

    /** {@code null} for a browser string that is missing or not a person's. */
    static VisitorAgent of(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (NOT_A_PERSON.matcher(ua).find()) {
            return null;
        }
        return new VisitorAgent(device(ua), os(ua), browser(ua));
    }

    private static String device(String ua) {
        if (ua.contains("ipad") || ua.contains("tablet") || (ua.contains("android") && !ua.contains("mobile"))) {
            return "tablet";
        }
        if (ua.contains("mobi") || ua.contains("iphone") || ua.contains("ipod") || ua.contains("android")) {
            return "mobile";
        }
        return "desktop";
    }

    private static String os(String ua) {
        if (ua.contains("android")) return "Android";
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod")) return "iOS";
        if (ua.contains("windows")) return "Windows";
        if (ua.contains("cros")) return "ChromeOS";
        if (ua.contains("mac os x") || ua.contains("macintosh")) return "macOS";
        if (ua.contains("linux")) return "Linux";
        return "other";
    }

    private static String browser(String ua) {
        if (ua.contains("musical_ly") || ua.contains("bytedancewebview") || ua.contains("tiktok")) return "TikTok";
        if (ua.contains("instagram")) return "Instagram";
        if (ua.contains("fban") || ua.contains("fbav") || ua.contains("fb_iab")) return "Facebook";
        if (ua.contains("samsungbrowser")) return "Samsung";
        if (ua.contains("edg/") || ua.contains("edga/") || ua.contains("edgios")) return "Edge";
        if (ua.contains("opr/") || ua.contains("opt/") || ua.contains("opera")) return "Opera";
        if (ua.contains("yabrowser")) return "Yandex";
        if (ua.contains("firefox/") || ua.contains("fxios")) return "Firefox";
        if (ua.contains("chrome/") || ua.contains("crios")) return "Chrome";
        if (ua.contains("safari/")) return "Safari";
        return "other";
    }
}

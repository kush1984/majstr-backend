package com.majstr.backend.security;

import java.util.Locale;

/**
 * Coarse device classification from a User-Agent string — phone vs computer
 * and the OS — for admin visibility into how masters reach the product.
 * Browser is deliberately NOT extracted (not useful for product decisions).
 *
 * <p>Heuristic, not exhaustive. Known limitation: iPadOS 13+ Safari sends a
 * desktop ("Macintosh") UA, so such iPads read as DESKTOP/macOS. Good enough
 * for a phone-vs-computer split.</p>
 */
public final class DeviceInfo {

    public enum DeviceType { MOBILE, TABLET, DESKTOP, UNKNOWN }

    public record Parsed(DeviceType deviceType, String os) {
        /** True when we learned anything real — used to avoid overwriting a
         *  known device with a blank/unrecognized UA (API tools, curl, …). */
        public boolean isKnown() {
            return deviceType != DeviceType.UNKNOWN || os != null;
        }
    }

    private DeviceInfo() {
    }

    public static Parsed parse(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return new Parsed(DeviceType.UNKNOWN, null);
        }
        String s = userAgent.toLowerCase(Locale.ROOT);
        return new Parsed(deviceType(s), os(s));
    }

    private static DeviceType deviceType(String s) {
        // Tablet before mobile: an Android tablet UA has no "mobile" token.
        if (s.contains("ipad") || s.contains("tablet") || (s.contains("android") && !s.contains("mobile"))) {
            return DeviceType.TABLET;
        }
        if (s.contains("iphone") || s.contains("ipod") || s.contains("mobile")) {
            return DeviceType.MOBILE;
        }
        if (s.contains("windows") || s.contains("macintosh") || s.contains("mac os x")
                || s.contains("cros") || s.contains("x11") || s.contains("linux")) {
            return DeviceType.DESKTOP;
        }
        return DeviceType.UNKNOWN;
    }

    private static String os(String s) {
        if (s.contains("iphone") || s.contains("ipad") || s.contains("ipod")) {
            return "iOS";
        }
        if (s.contains("android")) {
            return "Android";
        }
        if (s.contains("windows")) {
            return "Windows";
        }
        if (s.contains("mac os x") || s.contains("macintosh")) {
            return "macOS";
        }
        if (s.contains("cros")) {
            return "ChromeOS";
        }
        if (s.contains("linux")) {
            return "Linux";
        }
        return null;
    }
}

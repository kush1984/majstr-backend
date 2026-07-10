package com.majstr.backend.security;

import com.majstr.backend.security.DeviceInfo.DeviceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceInfoTest {

    @Test
    void iphone_isMobileIos() {
        DeviceInfo.Parsed p = DeviceInfo.parse(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                        + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1");
        assertThat(p.deviceType()).isEqualTo(DeviceType.MOBILE);
        assertThat(p.os()).isEqualTo("iOS");
        assertThat(p.isKnown()).isTrue();
    }

    @Test
    void androidPhone_isMobileAndroid() {
        DeviceInfo.Parsed p = DeviceInfo.parse(
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/120.0 Mobile Safari/537.36");
        assertThat(p.deviceType()).isEqualTo(DeviceType.MOBILE);
        assertThat(p.os()).isEqualTo("Android");
    }

    @Test
    void androidTablet_isTablet() {
        DeviceInfo.Parsed p = DeviceInfo.parse(
                "Mozilla/5.0 (Linux; Android 13; SM-T500) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/120.0 Safari/537.36");
        assertThat(p.deviceType()).isEqualTo(DeviceType.TABLET);
        assertThat(p.os()).isEqualTo("Android");
    }

    @Test
    void ipad_isTabletIos() {
        DeviceInfo.Parsed p = DeviceInfo.parse(
                "Mozilla/5.0 (iPad; CPU OS 15_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1");
        assertThat(p.deviceType()).isEqualTo(DeviceType.TABLET);
        assertThat(p.os()).isEqualTo("iOS");
    }

    @Test
    void windows_isDesktopWindows() {
        DeviceInfo.Parsed p = DeviceInfo.parse(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        assertThat(p.deviceType()).isEqualTo(DeviceType.DESKTOP);
        assertThat(p.os()).isEqualTo("Windows");
    }

    @Test
    void mac_isDesktopMacOs() {
        DeviceInfo.Parsed p = DeviceInfo.parse(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15");
        assertThat(p.deviceType()).isEqualTo(DeviceType.DESKTOP);
        assertThat(p.os()).isEqualTo("macOS");
    }

    @Test
    void linux_isDesktopLinux() {
        DeviceInfo.Parsed p = DeviceInfo.parse(
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        assertThat(p.deviceType()).isEqualTo(DeviceType.DESKTOP);
        assertThat(p.os()).isEqualTo("Linux");
    }

    @Test
    void blankOrTool_isUnknownAndNotKnown() {
        assertThat(DeviceInfo.parse(null).isKnown()).isFalse();
        assertThat(DeviceInfo.parse("  ").isKnown()).isFalse();
        DeviceInfo.Parsed curl = DeviceInfo.parse("curl/8.4.0");
        assertThat(curl.deviceType()).isEqualTo(DeviceType.UNKNOWN);
        assertThat(curl.os()).isNull();
        assertThat(curl.isKnown()).isFalse();
    }
}

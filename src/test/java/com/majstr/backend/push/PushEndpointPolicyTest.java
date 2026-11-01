package com.majstr.backend.push;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PushEndpointPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://fcm.googleapis.com/fcm/send/abc",
            "https://android.googleapis.com/gcm/send/abc",
            "https://web.push.apple.com/QKx",
            "https://wns2-par02p.notify.windows.com/w/?token=abc",
            "https://updates.push.services.mozilla.com/wpush/v2/abc",
            "https://FCM.googleapis.com:443/fcm/send/abc",
    })
    void realPushServicesAreAllowed(String endpoint) {
        assertThat(PushEndpointPolicy.allows(endpoint)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://fcm.googleapis.com/fcm/send/abc",
            "https://evil.example/fcm/send/abc",
            "https://fcm.googleapis.com.evil.example/x",
            "https://notify.windows.com.evil.example/x",
            "https://evilpush.apple.com/x",
            "https://user@fcm.googleapis.com/x",
            "https://fcm.googleapis.com:8443/x",
            "http://127.0.0.1:8080/internal",
            "https://169.254.169.254/latest/meta-data",
            "http://postgres.railway.internal:5432/",
            "file:///etc/passwd",
            "not a url",
            "",
    })
    void anythingElseIsRefused(String endpoint) {
        assertThat(PushEndpointPolicy.allows(endpoint)).isFalse();
    }
}

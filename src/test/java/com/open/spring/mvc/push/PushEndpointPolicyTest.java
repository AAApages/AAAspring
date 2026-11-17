package com.open.spring.mvc.push;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Only the browser vendors' push services may be registered as an endpoint (no SSRF). */
class PushEndpointPolicyTest {

    @Test
    void acceptsTheRealPushServices() {
        assertTrue(PushEndpointPolicy.isAllowed("https://fcm.googleapis.com/fcm/send/dXk2:APA91b"));
        assertTrue(PushEndpointPolicy.isAllowed("https://updates.push.services.mozilla.com/wpush/v2/gAAAA"));
        assertTrue(PushEndpointPolicy.isAllowed("https://web.push.apple.com/QGrU2Xx"));
        assertTrue(PushEndpointPolicy.isAllowed("https://wns2-by3p.notify.windows.com/w/?token=abc"));
        assertTrue(PushEndpointPolicy.isAllowed("https://fcm.googleapis.com:443/fcm/send/x"));
    }

    @Test
    void rejectsEverythingElse() {
        assertFalse(PushEndpointPolicy.isAllowed(null));
        assertFalse(PushEndpointPolicy.isAllowed("http://fcm.googleapis.com/fcm/send/x"));
        assertFalse(PushEndpointPolicy.isAllowed("https://fcm.googleapis.com.evil.example/x"));
        assertFalse(PushEndpointPolicy.isAllowed("https://evilpush.apple.com.example/x"));
        assertFalse(PushEndpointPolicy.isAllowed("https://notapple-push.apple.co/x"));
        assertFalse(PushEndpointPolicy.isAllowed("https://user:pw@fcm.googleapis.com/x"));
        assertFalse(PushEndpointPolicy.isAllowed("https://fcm.googleapis.com:8443/x"));
        assertFalse(PushEndpointPolicy.isAllowed("https://169.254.169.254/latest/meta-data"));
        assertFalse(PushEndpointPolicy.isAllowed("https://localhost:8585/api/person"));
        assertFalse(PushEndpointPolicy.isAllowed("https://spring.opencodingsociety.com/api/x"));
        assertFalse(PushEndpointPolicy.isAllowed("https://fcm.googleapis.com/" + "a".repeat(800)));
    }
}

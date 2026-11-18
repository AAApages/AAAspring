package com.open.spring.mvc.push;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Which push endpoints Spring will send to. A browser hands us its endpoint, so without this a
 * signed-in user could register any URL and have Spring POST to it on every message they get.
 * Only the browser vendors' push services are allowed: https on the default port, no user info,
 * and a host that is one of them or a subdomain of one.
 */
final class PushEndpointPolicy {

    /** Chrome and Android (FCM), Firefox (autopush), Safari and iPhone (Apple), Edge (WNS). */
    private static final List<String> HOSTS = List.of(
            "fcm.googleapis.com",
            "updates.push.services.mozilla.com",
            "push.apple.com",
            "notify.windows.com");

    private PushEndpointPolicy() {
    }

    static boolean isAllowed(String endpoint) {
        if (endpoint == null || endpoint.isBlank() || endpoint.length() > PushSubscriptionEntity.MAX_ENDPOINT) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(endpoint.trim());
        } catch (Exception e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getHost() == null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return HOSTS.stream().anyMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed));
    }
}

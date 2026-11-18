package com.open.spring.mvc.push;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

/**
 * Lets a signed-in browser register itself for Web Push (the "Notify my phone" button
 * on /dm). Every endpoint resolves the person from the JWT principal, and a subscription
 * can only be removed by the person it belongs to. The Pages side is
 * {@code assets/js/api/push-notifications.js}.
 */
@RestController
@RequestMapping("/api/push")
@RequiredArgsConstructor
public class PushApiRestController {

    private final PushNotificationService pushNotificationService;
    private final PersonJpaRepository personRepository;

    /** The shape {@code PushSubscriptionEntity.toJSON()} gives in the browser. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubscriptionRequest {
        private String endpoint;
        private Keys keys;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Keys {
            private String p256dh;
            private String auth;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EndpointRequest {
        private String endpoint;
    }

    /** Whether push is set up on this server, and the key the browser subscribes with. */
    @GetMapping("/config")
    public ResponseEntity<Object> config() {
        return ResponseEntity.ok(Map.of(
                "enabled", pushNotificationService.isConfigured(),
                "publicKey", pushNotificationService.getPublicKey()));
    }

    /** The caller's registered browsers, so a page can show "on for 2 devices". */
    @GetMapping("/subscriptions")
    public ResponseEntity<Object> list(@AuthenticationPrincipal UserDetails userDetails) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return unauthenticated();
        }
        List<Map<String, Object>> devices = pushNotificationService.subscriptionsOf(person).stream()
                .map(subscription -> Map.<String, Object>of(
                        "endpoint", subscription.getEndpoint(),
                        "userAgent", subscription.getUserAgent() == null ? "" : subscription.getUserAgent(),
                        "createdAt", subscription.getCreatedAt() == null ? "" : subscription.getCreatedAt().toString()))
                .toList();
        return ResponseEntity.ok(devices);
    }

    @PostMapping("/subscriptions")
    public ResponseEntity<Object> subscribe(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            @RequestBody SubscriptionRequest request) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return unauthenticated();
        }
        if (!pushNotificationService.isConfigured()) {
            return new ResponseEntity<>(Map.of("error", "Push notifications are not set up on this server"),
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (request == null || isBlank(request.getEndpoint()) || request.getKeys() == null
                || isBlank(request.getKeys().getP256dh()) || isBlank(request.getKeys().getAuth())) {
            return new ResponseEntity<>(Map.of("error", "endpoint, keys.p256dh and keys.auth are required"),
                    HttpStatus.BAD_REQUEST);
        }
        if (!PushEndpointPolicy.isAllowed(request.getEndpoint())) {
            return new ResponseEntity<>(Map.of("error", "endpoint must be a browser push service URL"),
                    HttpStatus.BAD_REQUEST);
        }
        String agent = userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 255));
        boolean saved = pushNotificationService.saveSubscription(person, request.getEndpoint().trim(),
                request.getKeys().getP256dh().trim(), request.getKeys().getAuth().trim(), agent);
        if (!saved) {
            return new ResponseEntity<>(Map.of("error", "This browser is registered to another account"),
                    HttpStatus.CONFLICT);
        }
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @DeleteMapping("/subscriptions")
    public ResponseEntity<Object> unsubscribe(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody EndpointRequest request) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return unauthenticated();
        }
        if (request == null || isBlank(request.getEndpoint())) {
            return new ResponseEntity<>(Map.of("error", "endpoint is required"), HttpStatus.BAD_REQUEST);
        }
        pushNotificationService.removeSubscription(person, request.getEndpoint().trim());
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    /** Sends the caller a test notification, so "Notify my phone" can prove it worked. */
    @PostMapping("/test")
    public ResponseEntity<Object> test(@AuthenticationPrincipal UserDetails userDetails) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return unauthenticated();
        }
        if (!pushNotificationService.isConfigured()) {
            return new ResponseEntity<>(Map.of("error", "Push notifications are not set up on this server"),
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        pushNotificationService.notifyPerson(person, "Open Coding Society",
                "Phone notifications are on. You'll hear about new direct messages here.", "/dm", "ocs-test");
        return new ResponseEntity<>(HttpStatus.ACCEPTED);
    }

    private Person currentPerson(UserDetails userDetails) {
        return userDetails == null ? null : personRepository.findByUid(userDetails.getUsername());
    }

    private static ResponseEntity<Object> unauthenticated() {
        return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

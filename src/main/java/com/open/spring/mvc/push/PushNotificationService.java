package com.open.spring.mvc.push;

import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.person.Person;

import jakarta.annotation.PreDestroy;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;

/**
 * Sends Web Push notifications (the ones a phone shows on its lock screen) to every
 * browser a person has registered through {@link PushApiRestController}.
 *
 * It is off until VAPID keys are configured: {@code PUSH_VAPID_PUBLIC_KEY},
 * {@code PUSH_VAPID_PRIVATE_KEY} and {@code PUSH_VAPID_SUBJECT} in {@code .env}
 * (see {@code scripts/push_vapid_keys.py}). Without them {@link #isConfigured()} is
 * false, the page offers no "notify my phone" button, and {@link #notifyPerson} is a no-op,
 * so direct messages keep working exactly as before.
 *
 * Sending goes through one background thread: the HTTP call to the push service
 * (FCM, Mozilla, Apple) must never slow down or fail the request that posted the message.
 * A subscription the push service reports as gone (404 or 410) is deleted.
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);
    private static final int TTL_SECONDS = 60 * 60 * 24; // keep undelivered pushes for a day
    private static final int MAX_BODY_CHARS = 140;
    /** How long an unchanged subscription goes before a re-send refreshes last_used_at. */
    private static final Duration RESEEN_AFTER = Duration.ofDays(1);

    private final PushSubscriptionJpaRepository subscriptionRepository;
    private final ObjectMapper objectMapper;
    private final String publicKey;
    private final PushService pushService;
    private Predicate<String> endpointAllowed = PushEndpointPolicy::isAllowed;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "web-push");
        thread.setDaemon(true);
        return thread;
    });

    public PushNotificationService(
            PushSubscriptionJpaRepository subscriptionRepository,
            ObjectMapper objectMapper,
            @Value("${push.vapid.public-key:}") String publicKey,
            @Value("${push.vapid.private-key:}") String privateKey,
            @Value("${push.vapid.subject:}") String subject) {
        this.subscriptionRepository = subscriptionRepository;
        this.objectMapper = objectMapper;
        this.publicKey = publicKey == null ? "" : publicKey.trim();
        this.pushService = buildPushService(this.publicKey, privateKey, subject);
    }

    private static PushService buildPushService(String publicKey, String privateKey, String subject) {
        if (publicKey.isBlank() || privateKey == null || privateKey.isBlank()) {
            log.info("Web push is off: PUSH_VAPID_PUBLIC_KEY / PUSH_VAPID_PRIVATE_KEY are not set");
            return null;
        }
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            String vapidSubject = subject == null || subject.isBlank() ? "mailto:admin@opencodingsociety.com" : subject.trim();
            return new PushService(publicKey, privateKey.trim(), vapidSubject);
        } catch (Exception ex) {
            log.warn("Web push is off: the VAPID keys could not be loaded ({})", ex.getMessage());
            return null;
        }
    }

    /** Tests point the service at a local stand-in for the push service. */
    void allowEndpointsForTest(Predicate<String> allowed) {
        this.endpointAllowed = allowed;
    }

    public boolean isConfigured() {
        return pushService != null;
    }

    /** The VAPID public key the browser needs to subscribe (applicationServerKey). */
    public String getPublicKey() {
        return publicKey;
    }

    @Transactional(readOnly = true)
    public List<PushSubscriptionEntity> subscriptionsOf(Person person) {
        return subscriptionRepository.findByPerson(person);
    }

    /**
     * Saves a browser's subscription for {@code person}. The endpoint is unique per browser,
     * so re-registering (new keys, or the same phone after signing in as someone else) updates
     * the row in place instead of adding a second one.
     *
     * The page re-sends its subscription regularly, so an unchanged one seen within the last
     * day is not written at all, and every write is a single statement (see the repository):
     * this must not add write traffic to a SQLite database that already contends for its lock.
     * Not transactional on purpose, for the same reason.
     */
    public void saveSubscription(Person person, String endpoint, String p256dh, String auth, String userAgent) {
        Instant now = Instant.now();
        PushSubscriptionEntity existing = subscriptionRepository.findByEndpoint(endpoint).orElse(null);
        if (existing != null && existing.getPerson() != null && existing.getPerson().getId().equals(person.getId())
                && p256dh.equals(existing.getP256dh()) && auth.equals(existing.getAuth())
                && existing.getLastUsedAt() != null && existing.getLastUsedAt().isAfter(now.minus(RESEEN_AFTER))) {
            return;
        }
        if (subscriptionRepository.updateByEndpoint(endpoint, person, p256dh, auth, userAgent, now) > 0) {
            return;
        }
        try {
            subscriptionRepository.save(new PushSubscriptionEntity(person, endpoint, p256dh, auth, userAgent));
        } catch (DataIntegrityViolationException raced) {
            // The same browser registered twice at once; the other insert won, so update that row.
            subscriptionRepository.updateByEndpoint(endpoint, person, p256dh, auth, userAgent, now);
        }
    }

    /** Removes the subscription only if it belongs to {@code person}; returns whether it did. */
    public boolean removeSubscription(Person person, String endpoint) {
        return subscriptionRepository.deleteByEndpointAndPersonId(endpoint, person.getId()) > 0;
    }

    /**
     * Tells every browser {@code person} registered about something, after the current
     * transaction commits (so a notification never points at a message that was rolled
     * back). Outside a transaction it is queued right away. Never throws.
     *
     * @param url  where tapping the notification goes, relative to the Pages site (e.g. {@code /dm?dm=12})
     * @param tag  notifications with the same tag replace each other on the phone
     */
    public void notifyPerson(Person person, String title, String body, String url, String tag) {
        if (!isConfigured() || person == null) {
            return;
        }
        Map<String, Object> payload = Map.of(
                "title", title == null ? "New message" : title,
                "body", snippet(body),
                "url", url == null ? "/dm" : url,
                "tag", tag == null ? "ocs" : tag);
        Long personId = person.getId();
        Runnable queue = () -> sender.execute(() -> sendToPerson(personId, payload));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    queue.run();
                }
            });
        } else {
            queue.run();
        }
    }

    /** Runs on the sender thread: loads the person's subscriptions and pushes to each. */
    private void sendToPerson(Long personId, Map<String, Object> payload) {
        List<PushSubscriptionEntity> subscriptions;
        String json;
        try {
            subscriptions = subscriptionRepository.findByPersonId(personId);
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            log.warn("Web push: could not prepare the notification ({})", ex.getMessage());
            return;
        }
        for (PushSubscriptionEntity subscription : subscriptions) {
            send(subscription, json);
        }
    }

    private void send(PushSubscriptionEntity subscription, String json) {
        if (!endpointAllowed.test(subscription.getEndpoint())) {
            // Checked again here, not only on registration: never POST to a host that is not a push service.
            log.warn("Web push: skipped a subscription whose endpoint is not a known push service");
            return;
        }
        try {
            Notification notification = new Notification(
                    subscription.getEndpoint(),
                    subscription.getP256dh(),
                    subscription.getAuth(),
                    json.getBytes(StandardCharsets.UTF_8),
                    TTL_SECONDS);
            // aes128gcm (RFC 8291) explicitly: the library defaults to the legacy aesgcm draft,
            // which Apple's push service rejects, so iPhones would never get a notification.
            HttpResponse response = pushService.send(notification, Encoding.AES128GCM);
            int status = response.getStatusLine().getStatusCode();
            if (status == 404 || status == 410) {
                // The browser unsubscribed, or the user cleared site data: forget it.
                subscriptionRepository.deleteDirectlyById(subscription.getId());
                log.info("Web push: dropped a subscription the push service no longer knows");
            } else if (status >= 300) {
                log.warn("Web push: push service answered HTTP {}", status);
            }
        } catch (Exception ex) {
            log.warn("Web push: send failed ({})", ex.getMessage());
        }
    }

    static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= MAX_BODY_CHARS ? oneLine : oneLine.substring(0, MAX_BODY_CHARS - 1) + "…";
    }

    @PreDestroy
    void shutdown() {
        sender.shutdown();
    }
}

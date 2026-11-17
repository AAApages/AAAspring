package com.open.spring.mvc.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.person.Person;

/** Which statements saving and removing a subscription run (no Spring context, no database). */
class PushNotificationServiceTest {

    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/abc";

    private final List<String> calls = new ArrayList<>();
    private PushSubscriptionEntity stored;
    private int updatedRows;
    private int deletedRows;

    private PushNotificationService service() {
        PushSubscriptionJpaRepository repository = (PushSubscriptionJpaRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { PushSubscriptionJpaRepository.class },
                (proxy, method, args) -> {
                    calls.add(method.getName());
                    return switch (method.getName()) {
                        case "findByEndpoint" -> Optional.ofNullable(stored);
                        case "updateByEndpoint" -> updatedRows;
                        case "save" -> args[0];
                        case "deleteByEndpointAndPersonId" -> deletedRows;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        return new PushNotificationService(repository, new ObjectMapper(), "", "", "");
    }

    private static Person person(long id) {
        Person person = new Person();
        ReflectionTestUtils.setField(person, "id", id);
        return person;
    }

    private PushSubscriptionEntity row(Person owner, Instant lastUsed) {
        PushSubscriptionEntity row = new PushSubscriptionEntity(owner, ENDPOINT, "key", "secret", "ua");
        row.setLastUsedAt(lastUsed);
        return row;
    }

    @Test
    void anUnchangedResendWritesNothing() {
        stored = row(person(1), Instant.now().minus(Duration.ofHours(2)));

        service().saveSubscription(person(1), ENDPOINT, "key", "secret", "ua");

        assertEquals(List.of("findByEndpoint"), calls);
    }

    @Test
    void aStaleResendRefreshesWithOneUpdate() {
        stored = row(person(1), Instant.now().minus(Duration.ofDays(3)));
        updatedRows = 1;

        service().saveSubscription(person(1), ENDPOINT, "key", "secret", "ua");

        assertEquals(List.of("findByEndpoint", "updateByEndpoint"), calls);
    }

    @Test
    void aPhoneSignedInAsSomeoneElseMovesToThem() {
        stored = row(person(1), Instant.now());
        updatedRows = 1;

        service().saveSubscription(person(2), ENDPOINT, "key", "secret", "ua");

        assertEquals(List.of("findByEndpoint", "updateByEndpoint"), calls);
    }

    @Test
    void aNewBrowserIsInsertedOnce() {
        stored = null;
        updatedRows = 0;

        service().saveSubscription(person(1), ENDPOINT, "key", "secret", "ua");

        assertEquals(List.of("findByEndpoint", "updateByEndpoint", "save"), calls);
    }

    @Test
    void onlyTheOwnerCanRemoveADevice() {
        deletedRows = 0;
        assertFalse(service().removeSubscription(person(9), ENDPOINT));
        deletedRows = 1;
        assertTrue(service().removeSubscription(person(1), ENDPOINT));
        assertEquals(List.of("deleteByEndpointAndPersonId", "deleteByEndpointAndPersonId"), calls);
    }
}

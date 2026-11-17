package com.open.spring.mvc.push;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.person.Person;

/**
 * Push subscriptions. Every write is one UPDATE or DELETE statement with no read before it in the
 * same transaction: on SQLite a transaction that reads and then writes fails with SQLITE_BUSY when
 * another connection wrote in between, and the browser re-sends its subscription regularly.
 */
public interface PushSubscriptionJpaRepository extends JpaRepository<PushSubscriptionEntity, Long> {
    List<PushSubscriptionEntity> findByPerson(Person person);

    /** By id, for the sender thread, which holds no Person entity. */
    List<PushSubscriptionEntity> findByPersonId(Long personId);

    Optional<PushSubscriptionEntity> findByEndpoint(String endpoint);

    /**
     * Points an existing browser's row at this person and keys, but only when the row is already
     * theirs or the caller presents the row's own keys (see PushNotificationService.saveSubscription).
     * Returns the rows changed (0 or 1).
     */
    @Modifying
    @Transactional
    @Query("UPDATE PushSubscriptionEntity s SET s.person = :person, s.p256dh = :p256dh, s.auth = :auth, "
            + "s.userAgent = :userAgent, s.lastUsedAt = :now WHERE s.endpoint = :endpoint "
            + "AND (s.person.id = :personId OR (s.p256dh = :p256dh AND s.auth = :auth))")
    int updateByEndpoint(@Param("endpoint") String endpoint, @Param("person") Person person,
            @Param("personId") Long personId, @Param("p256dh") String p256dh, @Param("auth") String auth,
            @Param("userAgent") String userAgent, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("DELETE FROM PushSubscriptionEntity s WHERE s.endpoint = :endpoint AND s.person.id = :personId")
    int deleteByEndpointAndPersonId(@Param("endpoint") String endpoint, @Param("personId") Long personId);

    @Modifying
    @Transactional
    @Query("DELETE FROM PushSubscriptionEntity s WHERE s.id = :id")
    int deleteDirectlyById(@Param("id") Long id);
}

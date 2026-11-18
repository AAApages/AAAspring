package com.open.spring.mvc.push;

import java.time.Instant;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.open.spring.mvc.person.Person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One browser (phone, laptop tab, installed PWA) that asked to be told about new
 * direct messages. It is the Web Push subscription the browser handed us:
 * the push service URL plus the two keys that let only us encrypt for it.
 * A person can have many; a subscription is tied to exactly one person, and is
 * dropped the moment the push service says it is gone (404/410).
 */
@Entity
@Table(name = "push_subscription")
@Getter
@Setter
@NoArgsConstructor
public class PushSubscriptionEntity {

    /** Longest endpoint stored; MySQL cannot put a unique index on a longer utf8mb4 column. */
    public static final int MAX_ENDPOINT = 700;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "person_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JsonIgnore
    private Person person;

    /** Push service URL (FCM, Mozilla autopush, Apple). Unique: one row per browser. */
    @Column(length = MAX_ENDPOINT, unique = true, nullable = false)
    private String endpoint;

    /** Client public key, base64url. */
    @Column(length = 255, nullable = false)
    private String p256dh;

    /** Client auth secret, base64url. */
    @Column(length = 255, nullable = false)
    private String auth;

    /** Who registered it, for the user's own "your devices" view. Never used for auth. */
    @Column(length = 255)
    private String userAgent;

    private Instant createdAt;

    private Instant lastUsedAt;

    public PushSubscriptionEntity(Person person, String endpoint, String p256dh, String auth, String userAgent) {
        this.person = person;
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
        this.userAgent = userAgent;
        this.createdAt = Instant.now();
        this.lastUsedAt = this.createdAt;
    }
}

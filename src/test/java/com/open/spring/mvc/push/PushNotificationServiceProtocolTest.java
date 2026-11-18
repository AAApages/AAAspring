package com.open.spring.mvc.push;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.person.Person;
import com.sun.net.httpserver.HttpServer;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Proves the server half of Web Push against the specs, without a phone: the real
 * PushNotificationService sends to a local stand-in for the push service (FCM, Apple, Mozilla),
 * and this test then checks what those services and the phone would check.
 *
 * - RFC 8291 / RFC 8188: the body is aes128gcm (the only encoding Apple accepts), and the
 *   browser's private key plus auth secret decrypt it to the JSON the service worker reads.
 * - RFC 8292 (VAPID): the Authorization header is "vapid t=JWT, k=key"; the JWT is ES256,
 *   signed by our private key, for the push service's origin, expiring within 24 hours.
 * - A 410 from the push service (phone unsubscribed) deletes the subscription.
 */
class PushNotificationServiceProtocolTest {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    record Captured(Map<String, List<String>> headers, byte[] body) {
    }

    private HttpServer server;
    private final BlockingQueue<Captured> received = new LinkedBlockingQueue<>();
    private volatile int status = 201;

    private KeyPair vapid;
    private KeyPair browser;
    private byte[] authSecret;
    private final List<Long> deleted = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new Captured(Map.copyOf(exchange.getRequestHeaders()), body));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();

        vapid = p256();
        browser = p256();
        authSecret = new byte[16];
        new SecureRandom().nextBytes(authSecret);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static KeyPair p256() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /** 0x04 || X || Y, the form browsers and scripts/push_vapid_keys.py use. */
    private static byte[] uncompressed(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 4;
        System.arraycopy(fixed32(key.getW().getAffineX()), 0, out, 1, 32);
        System.arraycopy(fixed32(key.getW().getAffineY()), 0, out, 33, 32);
        return out;
    }

    private static byte[] fixed32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private ECPublicKey decodePoint(byte[] point) throws Exception {
        ECPublicKey reference = (ECPublicKey) browser.getPublic();
        ECPoint w = new ECPoint(new BigInteger(1, Arrays.copyOfRange(point, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(point, 33, 65)));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, reference.getParams()));
    }

    private PushNotificationService service(PushSubscriptionEntity subscription) {
        PushSubscriptionJpaRepository repository = (PushSubscriptionJpaRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { PushSubscriptionJpaRepository.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByPersonId" -> List.of(subscription);
                    case "deleteDirectlyById" -> {
                        deleted.add((Long) args[0]);
                        yield null;
                    }
                    case "toString" -> "stub";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        String publicKey = B64.encodeToString(uncompressed((ECPublicKey) vapid.getPublic()));
        String privateKey = B64.encodeToString(fixed32(((ECPrivateKey) vapid.getPrivate()).getS()));
        PushNotificationService service = new PushNotificationService(repository, new ObjectMapper(), publicKey,
                privateKey, "mailto:admin@opencodingsociety.com");
        service.allowEndpointsForTest(endpoint -> endpoint.startsWith("http://127.0.0.1:"));
        return service;
    }

    private PushSubscriptionEntity subscription() {
        PushSubscriptionEntity subscription = new PushSubscriptionEntity(null,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/push/abc123",
                B64.encodeToString(uncompressed((ECPublicKey) browser.getPublic())),
                B64.encodeToString(authSecret), "test");
        subscription.setId(7L);
        return subscription;
    }

    private static Person person() {
        Person person = new Person();
        ReflectionTestUtils.setField(person, "id", 42L);
        return person;
    }

    private static String header(Captured captured, String name) {
        return captured.headers().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(e -> e.getValue().get(0)).findFirst().orElse(null);
    }

    @Test
    void sendsAnAes128gcmPushThePhoneCanDecryptWithAValidVapidSignature() throws Exception {
        PushNotificationService service = service(subscription());
        assertTrue(service.isConfigured());

        service.notifyPerson(person(), "Ada Lovelace", "  are you coming\n to the review? ", "/dm?dm=12", "dm-12");

        Captured captured = received.poll(15, TimeUnit.SECONDS);
        assertNotNull(captured, "nothing reached the push service");

        // What the push service checks.
        assertEquals("aes128gcm", header(captured, "Content-Encoding"));
        assertEquals("86400", header(captured, "TTL"));
        String authorization = header(captured, "Authorization");
        assertNotNull(authorization);
        assertTrue(authorization.startsWith("vapid t="), authorization);
        String jwt = authorization.substring("vapid t=".length(), authorization.indexOf(','));
        String k = authorization.substring(authorization.indexOf("k=") + 2).trim();
        assertArrayEquals(uncompressed((ECPublicKey) vapid.getPublic()), B64D.decode(k));

        String[] parts = jwt.split("\\.");
        ObjectMapper json = new ObjectMapper();
        assertEquals("ES256", json.readTree(B64D.decode(parts[0])).get("alg").asText());
        JsonNode claims = json.readTree(B64D.decode(parts[1]));
        // The library builds aud from scheme and host only. Real push services (fcm.googleapis.com,
        // web.push.apple.com, updates.push.services.mozilla.com) are on the default https port, where
        // that is the exact origin; this stand-in's random port is the only reason it differs here.
        assertEquals("http://127.0.0.1", claims.get("aud").asText());
        assertEquals("mailto:admin@opencodingsociety.com", claims.get("sub").asText());
        long secondsLeft = claims.get("exp").asLong() - System.currentTimeMillis() / 1000;
        assertTrue(secondsLeft > 0 && secondsLeft <= 24 * 3600, "exp must be within 24h, was " + secondsLeft);
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(vapid.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(B64D.decode(parts[2])), "VAPID JWT signature does not verify");

        // What the phone does: RFC 8291 decryption with its private key and auth secret.
        JsonNode payload = json.readTree(decrypt(captured.body()));
        assertEquals("Ada Lovelace", payload.get("title").asText());
        assertEquals("are you coming to the review?", payload.get("body").asText());
        assertEquals("/dm?dm=12", payload.get("url").asText());
        assertEquals("dm-12", payload.get("tag").asText());
    }

    @Test
    void forgetsASubscriptionThePushServiceSaysIsGone() throws Exception {
        status = 410;
        service(subscription()).notifyPerson(person(), "x", "y", "/dm", "t");

        assertNotNull(received.poll(15, TimeUnit.SECONDS));
        for (int i = 0; i < 50 && deleted.isEmpty(); i++) {
            Thread.sleep(100);
        }
        assertEquals(List.of(7L), deleted);
    }

    @Test
    void staysOffWithoutKeys() {
        PushNotificationService off = new PushNotificationService(null, new ObjectMapper(), "", "", "");
        off.notifyPerson(person(), "x", "y", "/dm", "t");
        assertEquals(false, off.isConfigured());
        assertTrue(received.isEmpty());
    }

    /** RFC 8291 section 3.4 + RFC 8188, from the receiving browser's side. */
    private byte[] decrypt(byte[] body) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        in.get(salt);
        int recordSize = in.getInt();
        byte[] senderKey = new byte[in.get() & 0xff];
        in.get(senderKey);
        byte[] ciphertext = new byte[in.remaining()];
        in.get(ciphertext);
        assertEquals(65, senderKey.length, "keyid must be the sender's P-256 public key");
        assertTrue(ciphertext.length <= recordSize, "single record expected");

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(browser.getPrivate());
        agreement.doPhase(decodePoint(senderKey), true);
        byte[] ecdhSecret = agreement.generateSecret();

        ByteArrayOutputStream keyInfo = new ByteArrayOutputStream();
        keyInfo.write("WebPush: info".getBytes(StandardCharsets.US_ASCII));
        keyInfo.write(0);
        keyInfo.write(uncompressed((ECPublicKey) browser.getPublic()));
        keyInfo.write(senderKey);
        byte[] ikm = expand(extract(authSecret, ecdhSecret), keyInfo.toByteArray(), 32);
        byte[] prk = extract(salt, ikm);
        byte[] cek = expand(prk, label("Content-Encoding: aes128gcm"), 16);
        byte[] nonce = expand(prk, label("Content-Encoding: nonce"), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = cipher.doFinal(ciphertext);
        int end = padded.length - 1;
        while (end >= 0 && padded[end] == 0) {
            end--;
        }
        assertEquals(2, padded[end], "last record must end with the 0x02 delimiter");
        return Arrays.copyOf(padded, end);
    }

    private static byte[] label(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        return Arrays.copyOf(bytes, bytes.length + 1);
    }

    private static byte[] extract(byte[] salt, byte[] ikm) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        return mac.doFinal(ikm);
    }

    private static byte[] expand(byte[] prk, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1);
        return Arrays.copyOf(mac.doFinal(), length);
    }
}

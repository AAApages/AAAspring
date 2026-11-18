package com.open.spring.mvc.aiconnections;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AiKeyCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AiKeyCipher(@Value("${ai.key.encryption-key:${AI_KEY_ENCRYPTION_KEY:}}") String encoded) {
        SecretKeySpec parsed = null;
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length == 32) parsed = new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // Disable personal storage rather than generating a replacement deployment key.
        }
        key = parsed;
    }
    public boolean isAvailable() { return key != null; }
    public void requireAvailable() { if (!isAvailable()) throw AiConnectionException.unavailable(); }

    public String encrypt(long personId, AiProvider provider, String plaintext) {
        return encrypt(personId + ":" + provider.name(), plaintext);
    }
    /** Encrypts a system secret; the context (for example "house-model") is bound as associated data. */
    public String encrypt(String context, String plaintext) {
        requireAvailable();
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, context, nonce)
                .doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException e) { throw AiConnectionException.unavailable(); }
    }
    public String decrypt(long personId, AiProvider provider, String envelope) {
        return decrypt(personId + ":" + provider.name(), envelope);
    }
    public String decrypt(String context, String envelope) {
        requireAvailable();
        try {
            if (envelope == null || !envelope.startsWith("v1:")) throw new IllegalArgumentException();
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(envelope.substring(3)));
            if (buffer.remaining() < 28) throw new IllegalArgumentException();
            byte[] nonce = new byte[12];
            buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            return new String(cipher(Cipher.DECRYPT_MODE, context, nonce).doFinal(encrypted),
                StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw AiConnectionException.unavailable();
        }
    }
    private Cipher cipher(int mode, String context, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("ocs-ai:" + context).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}

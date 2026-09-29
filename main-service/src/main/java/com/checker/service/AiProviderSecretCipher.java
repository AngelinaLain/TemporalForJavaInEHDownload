package com.checker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class AiProviderSecretCipher {
    private static final int NONCE_LENGTH = 12;
    private final String masterKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AiProviderSecretCipher(@Value("${ai.config.master-key:}") String masterKey) {
        this.masterKey = masterKey == null ? "" : masterKey;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        requireMasterKey();
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce).put(ciphertext).array());
        } catch (Exception exception) {
            throw new IllegalStateException("AI API Key 加密失败", exception);
        }
    }

    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) return null;
        requireMasterKey();
        try {
            byte[] payload = Base64.getDecoder().decode(encrypted);
            if (payload.length <= NONCE_LENGTH) throw new IllegalArgumentException("密文格式不正确");
            byte[] nonce = new byte[NONCE_LENGTH];
            byte[] ciphertext = new byte[payload.length - NONCE_LENGTH];
            System.arraycopy(payload, 0, nonce, 0, nonce.length);
            System.arraycopy(payload, nonce.length, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("AI API Key 解密失败，请检查 AI_CONFIG_MASTER_KEY", exception);
        }
    }

    private SecretKeySpec key() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(masterKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    private void requireMasterKey() {
        if (masterKey.length() < 16) {
            throw new IllegalStateException("保存 API Key 前必须配置至少 16 个字符的 AI_CONFIG_MASTER_KEY");
        }
    }
}

package com.checker.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiProviderSecretCipherTest {
    @Test
    void encryptsWithRandomNonceAndDecrypts() {
        AiProviderSecretCipher cipher = new AiProviderSecretCipher("test-master-key-with-enough-entropy");
        String first = cipher.encrypt("secret-key");
        String second = cipher.encrypt("secret-key");
        assertNotEquals(first, second);
        assertEquals("secret-key", cipher.decrypt(first));
        assertEquals("secret-key", cipher.decrypt(second));
    }

    @Test
    void rejectsMissingOrWrongMasterKey() {
        assertThrows(IllegalStateException.class,
                () -> new AiProviderSecretCipher("short").encrypt("secret"));
        String encrypted = new AiProviderSecretCipher("first-master-key-123456").encrypt("secret");
        assertThrows(IllegalStateException.class,
                () -> new AiProviderSecretCipher("other-master-key-123456").decrypt(encrypted));
    }
}

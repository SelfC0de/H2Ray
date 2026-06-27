package com.h2ray.app.data;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureStorage {
    private static final String ALIAS = "h2ray_profiles_aes_v1";
    private static final String PREFIX = "enc:v1:";

    String encrypt(String value) {
        if (value == null || value.isEmpty() || value.startsWith(PREFIX)) {
            return value == null ? "" : value;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            ByteBuffer result = ByteBuffer.allocate(1 + iv.length + encrypted.length);
            result.put((byte) iv.length).put(iv).put(encrypted);
            return PREFIX + Base64.encodeToString(result.array(), Base64.NO_WRAP);
        } catch (Exception error) {
            throw new IllegalStateException("Не удалось зашифровать профиль", error);
        }
    }

    String decrypt(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            return value == null ? "" : value;
        }
        try {
            byte[] packed = Base64.decode(value.substring(PREFIX.length()), Base64.DEFAULT);
            ByteBuffer buffer = ByteBuffer.wrap(packed);
            int ivLength = buffer.get() & 0xff;
            if (ivLength < 12 || ivLength > 16 || buffer.remaining() <= ivLength) {
                throw new IllegalStateException("Повреждён зашифрованный профиль");
            }
            byte[] iv = new byte[ivLength];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("Не удалось расшифровать профиль", error);
        }
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        KeyStore.Entry entry = store.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        );
        generator.init(new KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return generator.generateKey();
    }
}

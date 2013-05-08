package com.englishtown.stash.hook;

import com.atlassian.sal.api.pluginsettings.PluginSettings;
import org.apache.commons.codec.binary.Base64;

import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Service to encrypt/decrypt git user passwords
 */
public class DefaultPasswordEncryptor implements PasswordEncryptor {

    private SecretKey secretKey;

    public static final String ENCRYPTED_PREFIX = "encrypted:";
    public static final String SETTINGS_CRYPTO_KEY = "crypto.key";

    @Override
    public void init(PluginSettings pluginSettings) {

        try {
            String keyBase64;
            Object value = pluginSettings.get(SETTINGS_CRYPTO_KEY);

            if (value == null || value.toString().isEmpty()) {
                KeyGenerator gen = KeyGenerator.getInstance("AES");
                secretKey = gen.generateKey();
                keyBase64 = Base64.encodeBase64String(secretKey.getEncoded());
                pluginSettings.put(SETTINGS_CRYPTO_KEY, keyBase64);
            } else {
                keyBase64 = value.toString();
                byte[] data = Base64.decodeBase64(keyBase64);
                secretKey = new SecretKeySpec(data, 0, data.length, "AES");
            }

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

    }

    protected byte[] runCipher(byte[] data, boolean encrypt) {

        try {
            int mode = encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE;
            Cipher cipher = getCipher(mode);
            return cipher.doFinal(data);

        } catch (IllegalBlockSizeException e) {
            throw new RuntimeException(e);
        } catch (BadPaddingException e) {
            throw new RuntimeException(e);
        }
    }

    private Cipher getCipher(int mode) {

        try {
            // Create Cipher
            Cipher cipher = Cipher.getInstance("AES");

            // Initialize Cipher with key
            cipher.init(mode, secretKey);
            return cipher;

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        } catch (NoSuchPaddingException e) {
            throw new RuntimeException(e);
        } catch (InvalidKeyException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public boolean isEncrypted(String password) {
        if (password == null || password.isEmpty()) {
            return false;
        }
        return password.startsWith(ENCRYPTED_PREFIX);
    }

    @Override
    public String encrypt(String password) {
        if (isEncrypted(password)) {
            return password;
        }
        try {
            byte[] encryptedData = runCipher(password.getBytes("UTF-8"), true);
            return ENCRYPTED_PREFIX + Base64.encodeBase64String(encryptedData);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String decrypt(String password) {
        if (!isEncrypted(password)) {
            return password;
        }
        try {
            byte[] encryptedData = Base64.decodeBase64(password.substring(ENCRYPTED_PREFIX.length()));
            byte[] clearData = runCipher(encryptedData, false);
            return new String(clearData, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

}

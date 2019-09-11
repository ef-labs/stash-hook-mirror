package com.englishtown.bitbucket.hook;

import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;

import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Service to encrypt/decrypt git user passwords
 */
public class DefaultPasswordEncryptor implements PasswordEncryptor {

    private SecretKey secretKey;

    static final String PLUGIN_SETTINGS_KEY = "com.englishtown.stash.hook.mirror";
    static final String ENCRYPTED_PREFIX = "encrypted:";
    static final String SETTINGS_CRYPTO_KEY = "crypto.key";

    public DefaultPasswordEncryptor(PluginSettingsFactory settingsFactory) {
        PluginSettings pluginSettings = settingsFactory.createSettingsForKey(PLUGIN_SETTINGS_KEY);

        try {
            String keyBase64;
            Object value = pluginSettings.get(SETTINGS_CRYPTO_KEY);

            if (value == null || value.toString().isEmpty()) {
                KeyGenerator gen = KeyGenerator.getInstance("AES");
                secretKey = gen.generateKey();
                keyBase64 = Base64.getEncoder().encodeToString(secretKey.getEncoded());
                pluginSettings.put(SETTINGS_CRYPTO_KEY, keyBase64);
            } else {
                keyBase64 = value.toString();
                byte[] data = Base64.getDecoder().decode(keyBase64);
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

        } catch (IllegalBlockSizeException | BadPaddingException e) {
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

        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException e) {
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
        byte[] encryptedData = runCipher(password.getBytes(StandardCharsets.UTF_8), true);
        return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(encryptedData);
    }

    @Override
    public String decrypt(String password) {
        if (!isEncrypted(password)) {
            return password;
        }
        byte[] encryptedData = Base64.getDecoder().decode(password.substring(ENCRYPTED_PREFIX.length()));
        byte[] clearData = runCipher(encryptedData, false);
        return new String(clearData, StandardCharsets.UTF_8);
    }

}

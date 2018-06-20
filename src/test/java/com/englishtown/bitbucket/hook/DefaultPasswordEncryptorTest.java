package com.englishtown.bitbucket.hook;

import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

/**
 * DefaultPasswordEncryptor unit tests
 */
public class DefaultPasswordEncryptorTest {

    private final static String CRYPTO_KEY = "m3ys5YexQc7irRlmJeCwAw==";

    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private PluginSettings pluginSettings;
    @Mock
    private PluginSettingsFactory pluginSettingsFactory;

    private DefaultPasswordEncryptor encryptor;

    @Before
    public void setUp() {
        when(pluginSettingsFactory.createSettingsForKey(DefaultPasswordEncryptor.PLUGIN_SETTINGS_KEY))
                .thenReturn(pluginSettings);
        when(pluginSettings.get(DefaultPasswordEncryptor.SETTINGS_CRYPTO_KEY)).thenReturn(CRYPTO_KEY);

        encryptor = new DefaultPasswordEncryptor(pluginSettingsFactory);
    }

    @Test
    public void testRunCipher() {
        String clearText = "clear text";
        byte[] clearData = clearText.getBytes();
        byte[] encryptedData;
        byte[] resultData;
        String resultText;

        encryptedData = encryptor.runCipher(clearData, true);

        resultData = encryptor.runCipher(encryptedData, false);
        resultText = new String(resultData);

        assertArrayEquals(clearData, resultData);
        assertEquals(clearText, resultText);
    }

    @Test
    public void testIsEncrypted() {
        assertFalse(encryptor.isEncrypted("clear-text-key"));
        assertFalse(encryptor.isEncrypted(null));
        assertFalse(encryptor.isEncrypted(""));

        assertTrue(encryptor.isEncrypted(DefaultPasswordEncryptor.ENCRYPTED_PREFIX + "encrypted-key"));
    }

    @Test
    public void testEncrypt() {
        assertFalse(encryptor.isEncrypted("test"));

        String encrypted = encryptor.encrypt("test");
        assertTrue(encryptor.isEncrypted(encrypted));

        String reencrypted = encryptor.encrypt(encrypted);
        assertEquals(encrypted, reencrypted);

        String decrypted = encryptor.decrypt(encrypted);
        assertEquals("test", decrypted);

        assertFalse(encryptor.isEncrypted(decrypted));

        String redecrypted = encryptor.decrypt(decrypted);
        assertEquals(decrypted, redecrypted);
    }
}

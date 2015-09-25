package com.englishtown.bitbucket.hook;

import com.atlassian.sal.api.pluginsettings.PluginSettings;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * DefaultPasswordEncryptor unit tests
 */
@RunWith(MockitoJUnitRunner.class)
public class DefaultPasswordEncryptorTest {

    private final static String CRYPTO_KEY = "m3ys5YexQc7irRlmJeCwAw==";

    @Mock
    private PluginSettings pluginSettings;

    private DefaultPasswordEncryptor encryptor;

    @Before
    public void setUp() throws Exception {

        when(pluginSettings.get(DefaultPasswordEncryptor.SETTINGS_CRYPTO_KEY)).thenReturn(CRYPTO_KEY);

        encryptor = new DefaultPasswordEncryptor();
        encryptor.init(pluginSettings);

    }

    @Test
    public void testInit() throws Exception {

        DefaultPasswordEncryptor encryptor = new DefaultPasswordEncryptor();
        PluginSettings pluginSettings = mock(PluginSettings.class);
        encryptor.init(pluginSettings);

        verify(pluginSettings).put(eq(DefaultPasswordEncryptor.SETTINGS_CRYPTO_KEY), anyString());

        when(pluginSettings.get(DefaultPasswordEncryptor.SETTINGS_CRYPTO_KEY)).thenReturn(CRYPTO_KEY);

        encryptor.init(pluginSettings);

        // Verify put hasn't been called again
        verify(pluginSettings).put(eq(DefaultPasswordEncryptor.SETTINGS_CRYPTO_KEY), anyString());

    }

    @Test
    public void testRunCipher() throws Exception {

        DefaultPasswordEncryptor encryptor = new DefaultPasswordEncryptor();
        encryptor.init(pluginSettings);

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
    public void testIsEncrypted() throws Exception {

        DefaultPasswordEncryptor encryptor = new DefaultPasswordEncryptor();
        String password = "clear-text-key";
        boolean result;

        result = encryptor.isEncrypted(password);
        assertFalse(result);

        password = null;

        result = encryptor.isEncrypted(password);
        assertFalse(result);

        password = "";

        result = encryptor.isEncrypted(password);
        assertFalse(result);

        password = DefaultPasswordEncryptor.ENCRYPTED_PREFIX + "encrypted-key";

        result = encryptor.isEncrypted(password);
        assertTrue(result);

    }

    @Test
    public void testEncrypt() throws Exception {

        String password = "test";
        String encrypted;
        String clear;
        String result;

        assertFalse(encryptor.isEncrypted(password));

        encrypted = encryptor.encrypt(password);
        assertTrue(encryptor.isEncrypted(encrypted));

        result = encryptor.encrypt(encrypted);
        assertEquals(encrypted, result);

        clear = encryptor.decrypt(encrypted);
        assertEquals(password, clear);

        assertFalse(encryptor.isEncrypted(clear));

        result = encryptor.decrypt(clear);
        assertEquals(clear, result);

    }

}

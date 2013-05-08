package com.englishtown.stash.hook;

import com.atlassian.sal.api.pluginsettings.PluginSettings;

/**
 * Service to encrypt/decrypt git user passwords
 */
public interface PasswordEncryptor {

    void init(PluginSettings pluginSettings);

    boolean isEncrypted(String password);

    String encrypt(String password);

    String decrypt(String password);

}

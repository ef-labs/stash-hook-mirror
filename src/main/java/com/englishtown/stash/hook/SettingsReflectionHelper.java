package com.englishtown.stash.hook;

import com.atlassian.stash.setting.Settings;

import java.util.Map;

/**
 * Helper service to set {@link Settings}
 */
public interface SettingsReflectionHelper {

    /**
     * Set the values field of the {@link Settings} via reflection
     *
     * @param values   the values to set
     * @param settings the settings to set the values field on
     */
    void set(Map<String, Object> values, Settings settings);

}

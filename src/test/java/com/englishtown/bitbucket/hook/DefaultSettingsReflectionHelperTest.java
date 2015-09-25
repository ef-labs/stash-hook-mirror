package com.englishtown.bitbucket.hook;

import com.atlassian.bitbucket.setting.Settings;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for {@link DefaultSettingsReflectionHelper}
 */
public class DefaultSettingsReflectionHelperTest {

    @Test
    public void testSet() throws Exception {

        DefaultSettingsReflectionHelper helper = new DefaultSettingsReflectionHelper();
        Map<String, Object> original = new HashMap<>();
        original.put("old", "old");
        TestSettings settings = new TestSettings(original);
        Map<String, Object> values = new HashMap<>();

        values.put("new", "new");

        helper.set(values, settings);

        assertNull(settings.getString("old"));
        assertEquals("new", settings.getString("new"));

    }

    private static class TestSettings implements Settings {
        private final Map<String, Object> values;

        public TestSettings(Map<String, Object> values) {
            this.values = ImmutableMap.copyOf(values);
        }

        @Nullable
        @Override
        public String getString(@Nonnull String key) {
            return (String) values.get(key);
        }

        @Nonnull
        @Override
        public String getString(@Nonnull String key, @Nonnull String defaultValue) {
            return null;
        }

        @Nullable
        @Override
        public Boolean getBoolean(@Nonnull String key) {
            return null;
        }

        @Override
        public boolean getBoolean(@Nonnull String key, boolean defaultValue) {
            return false;
        }

        @Nullable
        @Override
        public Integer getInt(@Nonnull String key) {
            return null;
        }

        @Override
        public int getInt(@Nonnull String key, int defaultValue) {
            return 0;
        }

        @Nullable
        @Override
        public Long getLong(@Nonnull String key) {
            return null;
        }

        @Override
        public long getLong(@Nonnull String key, long defaultValue) {
            return 0;
        }

        @Nullable
        @Override
        public Double getDouble(@Nonnull String key) {
            return null;
        }

        @Override
        public double getDouble(@Nonnull String key, double defaultValue) {
            return 0;
        }

        @Override
        public Map<String, Object> asMap() {
            return values;
        }
    }

}

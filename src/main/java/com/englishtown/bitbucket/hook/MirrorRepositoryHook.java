package com.englishtown.bitbucket.hook;

import com.atlassian.bitbucket.concurrent.BucketedExecutor;
import com.atlassian.bitbucket.concurrent.BucketedExecutorSettings;
import com.atlassian.bitbucket.concurrent.ConcurrencyPolicy;
import com.atlassian.bitbucket.concurrent.ConcurrencyService;
import com.atlassian.bitbucket.hook.repository.*;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.scm.git.GitScm;
import com.atlassian.bitbucket.scope.RepositoryScope;
import com.atlassian.bitbucket.scope.Scope;
import com.atlassian.bitbucket.scope.ScopeVisitor;
import com.atlassian.bitbucket.server.ApplicationPropertiesService;
import com.atlassian.bitbucket.setting.Settings;
import com.atlassian.bitbucket.setting.SettingsValidationErrors;
import com.atlassian.bitbucket.setting.SettingsValidator;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class MirrorRepositoryHook implements PostRepositoryHook<RepositoryHookRequest>, SettingsValidator {

    static final String PROP_PREFIX = "plugin.com.englishtown.stash-hook-mirror.push.";
    static final String PROP_ATTEMPTS = PROP_PREFIX + "attempts";
    static final String PROP_THREADS = PROP_PREFIX + "threads";
    static final String SETTING_MIRROR_REPO_URL = "mirrorRepoUrl";
    static final String SETTING_USERNAME = "username";
    static final String SETTING_PASSWORD = "password";
    static final String SETTING_REFSPEC = "refspec";
    static final String SETTING_TAGS = "tags";
    static final String SETTING_NOTES = "notes";
    static final String SETTING_ATOMIC = "atomic";

    /**
     * Trigger types that don't cause a mirror to happen
     */
    private static Set<RepositoryHookTrigger> TRIGGERS_TO_IGNORE =
            ImmutableSet.of(
                    StandardRepositoryHookTrigger.UNKNOWN
            );

    private final PasswordEncryptor passwordEncryptor;
    private final SettingsReflectionHelper settingsReflectionHelper;
    private final BucketedExecutor<MirrorRequest> pushExecutor;

    private static final Logger logger = LoggerFactory.getLogger(MirrorRepositoryHook.class);

    public MirrorRepositoryHook(ConcurrencyService concurrencyService,
                                PasswordEncryptor passwordEncryptor,
                                ApplicationPropertiesService propertiesService,
                                MirrorBucketProcessor pushProcessor,
                                SettingsReflectionHelper settingsReflectionHelper) {
        logger.debug("MirrorRepositoryHook: init started");

        this.passwordEncryptor = passwordEncryptor;
        this.settingsReflectionHelper = settingsReflectionHelper;

        int attempts = propertiesService.getPluginProperty(PROP_ATTEMPTS, 5);
        int threads = propertiesService.getPluginProperty(PROP_THREADS, 3);

        pushExecutor = concurrencyService.getBucketedExecutor(getClass().getSimpleName(),
                new BucketedExecutorSettings.Builder<>(MirrorRequest::toString, pushProcessor)
                        .batchSize(Integer.MAX_VALUE) // Coalesce all requests into a single push
                        .maxAttempts(attempts)
                        .maxConcurrency(threads, ConcurrencyPolicy.PER_NODE)
                        .build());

        logger.debug("MirrorRepositoryHook: init completed");
    }

    /**
     * Schedules pushes to apply the latest changes to any configured mirrors.
     *
     * @param context provides hook settings and a way to obtain the commits added/removed
     * @param request provides details about the refs that have been updated
     */
    @Override
    public void postUpdate(@Nonnull PostRepositoryHookContext context, @Nonnull RepositoryHookRequest request) {
        if (TRIGGERS_TO_IGNORE.contains(request.getTrigger())) {
            logger.trace("MirrorRepositoryHook: skipping trigger {}", request.getTrigger());
            return;
        }

        Repository repository = request.getRepository();
        if (!GitScm.ID.equalsIgnoreCase(repository.getScmId())) {
            return;
        }

        List<MirrorSettings> mirrorSettings = getMirrorSettings(context.getSettings());
        if (mirrorSettings.isEmpty()) {
            logger.debug("{}: Mirroring is not configured", repository);
        } else {
            logger.debug("{}: Scheduling pushes for {} remote(s) after {}",
                    repository, mirrorSettings.size(), request.getTrigger());
            schedulePushes(repository, mirrorSettings);
        }
    }

    /**
     * Validate the given {@code settings} before they are persisted., and encrypts any user-supplied password.
     *
     * @param settings to be validated
     * @param errors   callback for reporting validation errors.
     * @param scope    the context {@code Repository} the settings will be associated with
     */
    @Override
    public void validate(@Nonnull Settings settings, @Nonnull SettingsValidationErrors errors, @Nonnull Scope scope) {
        Repository repository = scope.accept(new ScopeVisitor<Repository>() {

            @Override
            public Repository visit(@Nonnull RepositoryScope scope) {
                return scope.getRepository();
            }
        });
        if (repository == null) {
            return;
        }

        try {
            boolean ok = true;
            logger.debug("MirrorRepositoryHook: validate started.");

            List<MirrorSettings> mirrorSettings = getMirrorSettings(settings, false, false, false);
            for (MirrorSettings ms : mirrorSettings) {
                if (!validate(ms, errors)) {
                    ok = false;
                }
            }

            // If no errors, run the mirror command
            if (ok) {
                updateSettings(mirrorSettings, settings);
                schedulePushes(repository, mirrorSettings);
            }
        } catch (Exception e) {
            logger.error("Error running MirrorRepositoryHook validate.", e);
            errors.addFormError(e.getMessage());
        }
    }

    private List<MirrorSettings> getMirrorSettings(Settings settings) {
        return getMirrorSettings(settings, true, true, true);
    }

    private List<MirrorSettings> getMirrorSettings(Settings settings, boolean defTags, boolean defNotes, boolean defAtomic) {
        Map<String, Object> allSettings = settings.asMap();
        int count = 0;

        List<MirrorSettings> results = new ArrayList<>();
        for (String key : allSettings.keySet()) {
            if (key.startsWith(SETTING_MIRROR_REPO_URL)) {
                String suffix = key.substring(SETTING_MIRROR_REPO_URL.length());

                MirrorSettings ms = new MirrorSettings();
                ms.mirrorRepoUrl = settings.getString(SETTING_MIRROR_REPO_URL + suffix, "");
                ms.username = settings.getString(SETTING_USERNAME + suffix, "");
                ms.password = settings.getString(SETTING_PASSWORD + suffix, "");
                ms.refspec = (settings.getString(SETTING_REFSPEC + suffix, ""));
                ms.tags = (settings.getBoolean(SETTING_TAGS + suffix, defTags));
                ms.notes = (settings.getBoolean(SETTING_NOTES + suffix, defNotes));
                ms.atomic = (settings.getBoolean(SETTING_ATOMIC + suffix, defAtomic));
                ms.suffix = String.valueOf(count++);

                results.add(ms);
            }
        }

        return results;
    }

    private void schedulePushes(Repository repository, List<MirrorSettings> list) {
        list.forEach(settings -> pushExecutor.schedule(new MirrorRequest(repository, settings), 5L, TimeUnit.SECONDS));
    }

    private boolean validate(MirrorSettings ms, SettingsValidationErrors errors) {
        boolean result = true;
        boolean isHttp = false;

        if (ms.mirrorRepoUrl.isEmpty()) {
            result = false;
            errors.addFieldError(SETTING_MIRROR_REPO_URL + ms.suffix, "The mirror repo url is required.");
        } else {
            try {
                URI uri = URI.create(ms.mirrorRepoUrl);
                String scheme = uri.getScheme().toLowerCase();

                if (scheme.startsWith("http")) {
                    isHttp = true;
                    if (ms.mirrorRepoUrl.contains("@")) {
                        result = false;
                        errors.addFieldError(SETTING_MIRROR_REPO_URL + ms.suffix,
                                "The username and password should not be included.");
                    }
                }
            } catch (Exception ex) {
                // Not a valid url, assume it is something git can read

            }
        }

        // HTTP must have username and password
        if (isHttp) {
            if (ms.username.isEmpty()) {
                result = false;
                errors.addFieldError(SETTING_USERNAME + ms.suffix, "The username is required when using http(s).");
            }

            if (ms.password.isEmpty()) {
                result = false;
                errors.addFieldError(SETTING_PASSWORD + ms.suffix, "The password is required when using http(s).");
            }
        } else {
            // Only http should have username or password
            ms.password = ms.username = "";
        }

        if (!ms.refspec.isEmpty()) {
            if (!ms.refspec.contains(":")) {
                result = false;
                errors.addFieldError(SETTING_REFSPEC + ms.suffix, "A refspec should be in the form <src>:<dest>.");
            }
        }

        return result;
    }

    private void updateSettings(List<MirrorSettings> mirrorSettings, Settings settings) {
        Map<String, Object> values = new HashMap<>();
        for (MirrorSettings ms : mirrorSettings) {
            values.put(SETTING_MIRROR_REPO_URL + ms.suffix, ms.mirrorRepoUrl);
            values.put(SETTING_USERNAME + ms.suffix, ms.username);
            values.put(SETTING_PASSWORD + ms.suffix, (ms.password.isEmpty() ? ms.password : passwordEncryptor.encrypt(ms.password)));
            values.put(SETTING_REFSPEC + ms.suffix, ms.refspec);
            values.put(SETTING_TAGS + ms.suffix, ms.tags);
            values.put(SETTING_NOTES + ms.suffix, ms.notes);
            values.put(SETTING_ATOMIC + ms.suffix, ms.atomic);
        }

        // Unfortunately the settings are stored in an immutable map, so need to cheat with reflection
        settingsReflectionHelper.set(values, settings);
    }
}

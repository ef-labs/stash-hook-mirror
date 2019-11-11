package com.englishtown.bitbucket.hook;

import com.atlassian.bitbucket.concurrent.BucketedExecutor;
import com.atlassian.bitbucket.concurrent.BucketedExecutorSettings;
import com.atlassian.bitbucket.concurrent.ConcurrencyPolicy;
import com.atlassian.bitbucket.concurrent.ConcurrencyService;
import com.atlassian.bitbucket.event.repository.RepositoryDeletedEvent;
import com.atlassian.bitbucket.event.repository.RepositoryModifiedEvent;
import com.atlassian.bitbucket.hook.repository.*;
import com.atlassian.bitbucket.i18n.I18nService;
import com.atlassian.bitbucket.project.Project;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.scm.git.GitScm;
import com.atlassian.bitbucket.scope.ProjectScope;
import com.atlassian.bitbucket.scope.RepositoryScope;
import com.atlassian.bitbucket.scope.Scope;
import com.atlassian.bitbucket.scope.ScopeVisitor;
import com.atlassian.bitbucket.server.ApplicationPropertiesService;
import com.atlassian.bitbucket.setting.Settings;
import com.atlassian.bitbucket.setting.SettingsValidationErrors;
import com.atlassian.bitbucket.setting.SettingsValidator;
import com.atlassian.event.api.EventListener;
import com.atlassian.utils.process.StringOutputHandler;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    static final String SETTING_REST_API_URL = "restApiURL";
    static final String SETTING_PRIVATE_TOKEN = "privateToken";
    static final Pattern PLACEHOLDER_REGEX = Pattern.compile("\\$\\{([^}]+)\\}");
    static final Pattern OBJECT_REGEX = Pattern.compile("^([\\w]+)");
    static final Pattern METHOD_REGEX = Pattern.compile("\\.([\\w]+)\\(\\)");

    /**
     * Trigger types that don't cause a mirror to happen
     */
    private static Set<RepositoryHookTrigger> TRIGGERS_TO_IGNORE =
            ImmutableSet.of(
                    StandardRepositoryHookTrigger.UNKNOWN
            );

    private final ConcurrencyService concurrencyService;
    private final I18nService i18nService;
    private final PasswordEncryptor passwordEncryptor;
    private final ApplicationPropertiesService propertiesService;
    private final MirrorBucketProcessor pushProcessor;
    private final MirrorRemoteAdmin mirrorRemoteAdmin;
    private final RepositoryHookService repositoryHookService;
    private final SettingsReflectionHelper settingsReflectionHelper;
    private BucketedExecutor<MirrorRequest> pushExecutor;

    private static final Logger logger = LoggerFactory.getLogger(MirrorRepositoryHook.class);

    public MirrorRepositoryHook(ConcurrencyService concurrencyService,
                                I18nService i18nService,
                                PasswordEncryptor passwordEncryptor,
                                ApplicationPropertiesService propertiesService,
                                MirrorBucketProcessor pushProcessor,
                                MirrorRemoteAdmin mirrorRemoteAdmin,
                                RepositoryHookService repositoryHookService,
                                SettingsReflectionHelper settingsReflectionHelper) {
        logger.debug("MirrorRepositoryHook: init started");

        this.concurrencyService = concurrencyService;
        this.i18nService = i18nService;
        this.passwordEncryptor = passwordEncryptor;
        this.propertiesService = propertiesService;
        this.pushProcessor = pushProcessor;
        this.mirrorRemoteAdmin = mirrorRemoteAdmin;
        this.repositoryHookService = repositoryHookService;
        this.settingsReflectionHelper = settingsReflectionHelper;

        pushExecutor = createPushExecutor();
        logger.debug("MirrorRepositoryHook: init completed");
    }

    private BucketedExecutor<MirrorRequest> createPushExecutor() {
        logger.debug("MirrorRepositoryHook: initialize pushExecutor");
        int attempts = propertiesService.getPluginProperty(PROP_ATTEMPTS, 5);
        logger.debug(PROP_ATTEMPTS + ": " + attempts);
        int threads = propertiesService.getPluginProperty(PROP_THREADS, 3);
        logger.debug(PROP_THREADS + ": " + threads);
        return concurrencyService.getBucketedExecutor(getClass().getSimpleName(),
                new BucketedExecutorSettings.Builder<>(MirrorRequest::toString, pushProcessor)
                        .batchSize(Integer.MAX_VALUE) // Coalesce all requests into a single push
                        .maxAttempts(attempts)
                        .maxConcurrency(threads, ConcurrencyPolicy.PER_NODE)
                        .build());

    }

    /**
     * Schedules pushes to apply the latest changes to any configured mirrors.
     *
     * @param context provides hook settings and a way to obtain the commits added/removed
     * @param request provides details about the refs that have been updated
     */
    @Override
    public void postUpdate(@Nonnull PostRepositoryHookContext context, @Nonnull RepositoryHookRequest request) {
        logger.debug("postUpdate " + request.getRepository().getName());
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
        Project project = scope.accept(new ScopeVisitor<Project>() {

            @Override
            public Project visit(@Nonnull ProjectScope scope) {
                return scope.getProject();
            }
        });
        if (repository == null && project == null) {
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
                logger.error("update settings");
                updateSettings(mirrorSettings, settings);
                if(repository != null) {
                    schedulePushes(repository, mirrorSettings);
                }
            }
        } catch (Exception e) {
            logger.error("Error running MirrorRepositoryHook validate.", e);
            errors.addFormError(e.getMessage());
        }
    }

    @EventListener
    public void repositoryDeleted(RepositoryDeletedEvent repositoryDeletedEvent) {
        logger.debug("Delete " + repositoryDeletedEvent.getRepository().getName());
        deleteRepository(repositoryDeletedEvent.getRepository());
    }

    @EventListener
    public void repositoryModified(RepositoryModifiedEvent repositoryModifiedEvent) {
        logger.debug("Modify " + repositoryModifiedEvent.getRepository().getName());
        if (repositoryModifiedEvent.getOldValue().getName().equals(repositoryModifiedEvent.getNewValue().getName())
                && repositoryModifiedEvent.getOldValue().getProject().getKey().equals(repositoryModifiedEvent.getNewValue().getProject().getKey())) {
            logger.debug("Repository project and name not changed. Nothing to mirror");
            return;
        }

        List<MirrorSettings> newMirrorSettingsList = getMirrorSettings(getPluginSettings(repositoryModifiedEvent.getNewValue()));
        for (MirrorSettings mirrorSettings : newMirrorSettingsList) {
            interpolateMirrorRepoUrl(repositoryModifiedEvent.getNewValue(), mirrorSettings);
            }

        deleteRepository(repositoryModifiedEvent.getOldValue());
        createRepositoryMirrors(repositoryModifiedEvent.getNewValue(),newMirrorSettingsList);
    }

    private void deleteRepository(Repository repository) {
        Settings settings = getPluginSettings(repository);
        List<MirrorSettings> oldMirrorSettingsList = getMirrorSettings(settings);
        for (MirrorSettings mirrorSettings : oldMirrorSettingsList) {
            if (!mirrorSettings.restApiURL.isEmpty()) {
                // Delete old repository
                StringOutputHandler outputHandler=new StringOutputHandler();
                try {
                    logger.debug("Delete mirror for " + mirrorSettings.restApiURL);
                    mirrorRemoteAdmin.delete(mirrorSettings, repository, outputHandler);
                } catch (Exception e) {
                    logger.debug("Deleting Mirroring failed with " + outputHandler.getOutput() + e );
                }
            }
        }
    }

    private void createRepositoryMirrors(Repository repository,List<MirrorSettings> newMirrorSettingsList) {
        for (MirrorSettings mirrorSettings : newMirrorSettingsList) {
            // Create new repository
            StringOutputHandler outputHandler=new StringOutputHandler();
            try {
                logger.debug("Trigger mirror for " + mirrorSettings.mirrorRepoUrl);
                pushProcessor.runMirrorCommand(mirrorSettings, repository, outputHandler);
            } catch (Exception e) {
                logger.debug("Mirroring failed with " + outputHandler.getOutput() + e);
            }
        }
    }

    public Settings getPluginSettings(Repository repository) {
        RepositoryScope repositoryScope = new RepositoryScope(repository);
        RepositoryHookSettings repositoryHookSettings = repositoryHookService.getSettings(new GetRepositoryHookSettingsRequest.Builder(repositoryScope, "com.englishtown.stash-hook-mirror:mirror-repository-hook").build());
        if (repositoryHookSettings != null) {
            return repositoryHookSettings.getSettings();
        }
        return repositoryHookService.createSettingsBuilder().build();
    }

    public List<MirrorSettings> getMirrorSettings(Settings settings) {
        return getMirrorSettings(settings, true, true, true);
    }

    public List<MirrorSettings> getMirrorSettings(Settings settings, boolean defTags, boolean defNotes, boolean defAtomic) {
        if(settings == null){
            return null;
        }
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
                ms.restApiURL = (settings.getString(SETTING_REST_API_URL + suffix, ""));
                ms.privateToken = (settings.getString(SETTING_PRIVATE_TOKEN + suffix, ""));
                ms.suffix = String.valueOf(count++);

                results.add(ms);
            }
        }
        return results;
    }

    private MirrorSettings interpolateMirrorRepoUrl(Repository repository, MirrorSettings settings) {
        settings.mirrorRepoUrl = interpolateMirrorRepoUrl(repository, settings.mirrorRepoUrl);
        return settings;
    }

    public String interpolateMirrorRepoUrl(Repository repository, String mirrorRepoUrl) {
        Matcher p = PLACEHOLDER_REGEX.matcher(mirrorRepoUrl);
        StringBuffer stringBuffer = new StringBuffer();
        while (p.find()) {
            Matcher o = OBJECT_REGEX.matcher(p.group(1));
            Matcher m = METHOD_REGEX.matcher(p.group(1));
            try {
                if (!o.find()) {
                    throw new NoSuchFieldException("Object not referenced");
                }
                Object instance = null;
                /* Cannot get method argument from reflection. Assign well known names */
                switch (o.group(1)) {
                    case "repository":
                        instance = repository;
                        break;
                    default:
                        throw new NoSuchFieldException("Unknown object " + o.group(1));
                }
                while (m.find()) {
                    instance = instance.getClass().getMethod(m.group(1)).invoke(instance);
                }
                if(instance.equals(repository)) {
                    throw new NoSuchMethodException("Not repository method specified");
                }
                p.appendReplacement(stringBuffer, Matcher.quoteReplacement(instance.toString()));
            } catch (NoSuchFieldException | NoSuchMethodException e) {
                logger.error("Failed to interpolate expression " + p.group(0) + " " + e);
                throw new RuntimeException("Failed to interpolate expression " + p.group(0) + " " + e);
                //p.appendReplacement(stringBuffer, Matcher.quoteReplacement(p.group(0)));
            } catch (IllegalAccessException | InvocationTargetException e) {
                p.appendReplacement(stringBuffer, Matcher.quoteReplacement(p.group(0)));
            }
        }
        p.appendTail(stringBuffer);
        return stringBuffer.toString();
    }

    public void schedulePushes(Repository repository, List<MirrorSettings> list) {
        for (MirrorSettings settings : list) {
            MirrorRequest request = new MirrorRequest(repository, interpolateMirrorRepoUrl(repository, settings));
            try {
                pushExecutor.schedule(request, 5L, TimeUnit.SECONDS);
            } catch (ClassCastException e) {
                /* Quick and dirty way to re-initialize pushExecutor after plugin update */
                pushExecutor.shutdown();
                pushExecutor = createPushExecutor();
                pushExecutor.schedule(request, 5L, TimeUnit.SECONDS);
            }
        }
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
            values.put(SETTING_REST_API_URL + ms.suffix, ms.restApiURL);
            values.put(SETTING_PRIVATE_TOKEN + ms.suffix, (ms.privateToken.isEmpty() ? ms.privateToken : passwordEncryptor.encrypt(ms.privateToken)));
        }

        // Unfortunately the settings are stored in an immutable map, so need to cheat with reflection
        settingsReflectionHelper.set(values, settings);
    }
}

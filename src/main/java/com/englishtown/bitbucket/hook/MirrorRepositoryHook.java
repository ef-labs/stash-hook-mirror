package com.englishtown.bitbucket.hook;

import com.atlassian.bitbucket.hook.repository.AsyncPostReceiveRepositoryHook;
import com.atlassian.bitbucket.hook.repository.RepositoryHookContext;
import com.atlassian.bitbucket.i18n.I18nService;
import com.atlassian.bitbucket.repository.RefChange;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.bitbucket.scm.CommandExitHandler;
import com.atlassian.bitbucket.scm.DefaultCommandExitHandler;
import com.atlassian.bitbucket.scm.ScmCommandBuilder;
import com.atlassian.bitbucket.scm.ScmService;
import com.atlassian.bitbucket.scm.git.command.GitScmCommandBuilder;
import com.atlassian.bitbucket.setting.RepositorySettingsValidator;
import com.atlassian.bitbucket.setting.Settings;
import com.atlassian.bitbucket.setting.SettingsValidationErrors;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MirrorRepositoryHook implements AsyncPostReceiveRepositoryHook, RepositorySettingsValidator {

    protected static class MirrorSettings {
        String mirrorRepoUrl;
        String username;
        String password;
        String suffix;
        String refspec;
        boolean tags;
        boolean notes;
        boolean atomic;
    }

    public static final String PLUGIN_SETTINGS_KEY = "com.englishtown.stash.hook.mirror";
    static final String SETTING_MIRROR_REPO_URL = "mirrorRepoUrl";
    static final String SETTING_USERNAME = "username";
    static final String SETTING_PASSWORD = "password";
    static final String SETTING_REFSPEC = "refspec";
    static final String SETTING_TAGS = "tags";
    static final String SETTING_NOTES = "notes";
    static final String SETTING_ATOMIC = "atomic";
    static final int MAX_ATTEMPTS = 5;
    static final String DEFAULT_REFSPEC = "+refs/heads/*:refs/heads/*";

    private final ScmService scmService;
    private final I18nService i18nService;
    private final ScheduledExecutorService executor;
    private final PasswordEncryptor passwordEncryptor;
    private final SettingsReflectionHelper settingsReflectionHelper;
    private final RepositoryService repositoryService;

    private static final Logger logger = LoggerFactory.getLogger(MirrorRepositoryHook.class);

    public MirrorRepositoryHook(
            ScmService scmService,
            I18nService i18nService,
            ScheduledExecutorService executor,
            PasswordEncryptor passwordEncryptor,
            SettingsReflectionHelper settingsReflectionHelper,
            PluginSettingsFactory pluginSettingsFactory,
            RepositoryService repositoryService
    ) {
        logger.debug("MirrorRepositoryHook: init started");

        // Set fields
        this.scmService = scmService;
        this.i18nService = i18nService;
        this.executor = executor;
        this.passwordEncryptor = passwordEncryptor;
        this.settingsReflectionHelper = settingsReflectionHelper;
        this.repositoryService = repositoryService;

        // Init password encryptor
        PluginSettings pluginSettings = pluginSettingsFactory.createSettingsForKey(PLUGIN_SETTINGS_KEY);
        passwordEncryptor.init(pluginSettings);

        logger.debug("MirrorRepositoryHook: init completed");
    }

    /**
     * Calls the remote bitbucket instance(s) to push the latest changes
     * <p>
     * Callback method that is called just after a push is completed (or a pull request accepted).
     * This hook executes <i>after</i> the processing of a push and will not block the user client.
     * <p>
     * Despite being asynchronous, the user who initiated this change is still available from
     *
     * @param context    the context which the hook is being run with
     * @param refChanges the refs that have just been updated
     */
    @Override
    public void postReceive(
            @Nonnull RepositoryHookContext context,
            @Nonnull Collection<RefChange> refChanges) {

        logger.debug("MirrorRepositoryHook: postReceive started.");

        List<MirrorSettings> mirrorSettings = getMirrorSettings(context.getSettings());

        for (MirrorSettings settings : mirrorSettings) {
            runMirrorCommand(settings, context.getRepository());
        }

    }

    void runMirrorCommand(MirrorSettings settings, final Repository repository) {
        if (repositoryService.isEmpty(repository)) {
            return;
        }

        try {
            final String password = passwordEncryptor.decrypt(settings.password);
            final String authenticatedUrl = getAuthenticatedUrl(settings.mirrorRepoUrl, settings.username, password);

            executor.submit(new Runnable() {

                int attempts = 0;

                @Override
                public void run() {
                    try {
                        ScmCommandBuilder obj = scmService.createBuilder(repository);
                        if (!(obj instanceof GitScmCommandBuilder)) {
                            logger.warn("Repository " + repository.getName() + " is not a git repo, cannot mirror");
                            return;
                        }
                        GitScmCommandBuilder builder = (GitScmCommandBuilder) obj;
                        PasswordHandler passwordHandler = getPasswordHandler(builder, password);

                        // Call push command with the prune flag and refspecs for heads and tags
                        // Do not use the mirror flag as pull-request refs are included
                        builder.command("push")
                                .argument("--prune") // this deletes locally deleted branches
                                .argument(authenticatedUrl)
                                .argument("--force");

                        // Use an atomic transaction to have a consistent state
                        if (settings.atomic) {
                            builder.argument("--atomic");
                        }

                        // Add refspec args
                        String refspecs = Strings.isNullOrEmpty(settings.refspec) ? DEFAULT_REFSPEC : settings.refspec;
                        for (String refspec : refspecs.split("\\s|\\n")) {
                            if (!Strings.isNullOrEmpty(refspec)) {
                                builder.argument(refspec);
                            }
                        }

                        // Add tags refspec
                        if (settings.tags) {
                            builder.argument("+refs/tags/*:refs/tags/*");
                        }
                        // Add notes refspec
                        if (settings.notes) {
                            builder.argument("+refs/notes/*:refs/notes/*");
                        }

                        builder.errorHandler(passwordHandler)
                                .exitHandler(passwordHandler);

                        String result = builder.build(passwordHandler).call();

                        logger.debug("MirrorRepositoryHook: postReceive completed with result '{}'.", result);

                    } catch (Exception e) {
                        if (++attempts >= MAX_ATTEMPTS) {
                            logger.error("Failed to mirror repository " + repository.getName() + " after " + attempts
                                    + " attempts.", e);
                        } else {
                            logger.warn("Failed to mirror repository " + repository.getName() + ", " +
                                    "retrying in 1 minute (attempt {} of {}).", attempts, MAX_ATTEMPTS);
                            executor.schedule(this, 1, TimeUnit.MINUTES);
                        }
                    }

                }
            });

        } catch (Exception e) {
            logger.error("MirrorRepositoryHook: Error running mirror hook", e);
        }
    }

    protected String getAuthenticatedUrl(String mirrorRepoUrl, String username, String password) throws URISyntaxException {

        // Only http(s) has username/password
        if (!mirrorRepoUrl.toLowerCase().startsWith("http")) {
            return mirrorRepoUrl;
        }

        URI uri = URI.create(mirrorRepoUrl);
        String userInfo = username + ":" + password;

        return new URI(uri.getScheme(), userInfo, uri.getHost(), uri.getPort(),
                uri.getPath(), uri.getQuery(), uri.getFragment()).toString();

    }

    /**
     * Validate the given {@code settings} before they are persisted.
     *
     * @param settings   to be validated
     * @param errors     callback for reporting validation errors.
     * @param repository the context {@code Repository} the settings will be associated with
     */
    @Override
    public void validate(
            @Nonnull Settings settings,
            @Nonnull SettingsValidationErrors errors,
            @Nonnull Repository repository) {

        try {
            boolean ok = true;
            logger.debug("MirrorRepositoryHook: validate started.");

            List<MirrorSettings> mirrorSettings = getMirrorSettings(settings, false, false, false);

            for (MirrorSettings ms : mirrorSettings) {
                if (!validate(ms, settings, errors)) {
                    ok = false;
                }
            }

            // If no errors, run the mirror command
            if (ok) {
                updateSettings(mirrorSettings, settings);
                for (MirrorSettings ms : mirrorSettings) {
                    runMirrorCommand(ms, repository);
                }
            }

        } catch (Exception e) {
            logger.error("Error running MirrorRepositoryHook validate.", e);
            errors.addFormError(e.getMessage());
        }

    }

    protected List<MirrorSettings> getMirrorSettings(Settings settings) {
        return getMirrorSettings(settings, true, true, true);
    }

    protected List<MirrorSettings> getMirrorSettings(Settings settings, boolean defTags, boolean defNotes, boolean defAtomic) {

        List<MirrorSettings> results = new ArrayList<>();
        Map<String, Object> allSettings = settings.asMap();
        int count = 0;

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

    protected boolean validate(MirrorSettings ms, Settings settings, SettingsValidationErrors errors) {

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

    protected void updateSettings(List<MirrorSettings> mirrorSettings, Settings settings) {

        Map<String, Object> values = new HashMap<String, Object>();

        // Store each mirror setting
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

    protected PasswordHandler getPasswordHandler(GitScmCommandBuilder builder, String password) {

        try {
            Method method = builder.getClass().getDeclaredMethod("createExitHandler");
            method.setAccessible(true);
            CommandExitHandler exitHandler = (CommandExitHandler) method.invoke(builder);

            return new PasswordHandler(password, exitHandler);

        } catch (Throwable t) {
            logger.warn("Unable to create exit handler", t);
        }

        return new PasswordHandler(password, new DefaultCommandExitHandler(i18nService));
    }

}

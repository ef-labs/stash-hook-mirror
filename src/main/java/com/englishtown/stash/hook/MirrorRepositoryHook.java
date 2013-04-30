package com.englishtown.stash.hook;

import com.atlassian.stash.hook.repository.AsyncPostReceiveRepositoryHook;
import com.atlassian.stash.hook.repository.RepositoryHookContext;
import com.atlassian.stash.i18n.I18nService;
import com.atlassian.stash.internal.scm.git.GitCommandExitHandler;
import com.atlassian.stash.repository.RefChange;
import com.atlassian.stash.repository.Repository;
import com.atlassian.stash.scm.CommandExitHandler;
import com.atlassian.stash.scm.git.GitScm;
import com.atlassian.stash.scm.git.GitScmCommandBuilder;
import com.atlassian.stash.setting.RepositorySettingsValidator;
import com.atlassian.stash.setting.Settings;
import com.atlassian.stash.setting.SettingsValidationErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MirrorRepositoryHook implements AsyncPostReceiveRepositoryHook, RepositorySettingsValidator {

    static final String SETTING_MIRROR_REPO_URL = "mirrorRepoUrl";
    static final String SETTING_USERNAME = "username";
    static final String SETTING_PASSWORD = "password";
    static final int MAX_ATTEMPTS = 5;

    private final GitScm gitScm;
    private final I18nService i18nService;
    private final ScheduledExecutorService executor;
    private static final Logger logger = LoggerFactory.getLogger(MirrorRepositoryHook.class);

    public MirrorRepositoryHook(
            GitScm gitScm,
            I18nService i18nService,
            ScheduledExecutorService executor) {
        this.gitScm = gitScm;
        this.i18nService = i18nService;
        this.executor = executor;
    }

    /**
     * Calls the remote stash instance(s) to push the latest changes
     * <p/>
     * Callback method that is called just after a push is completed (or a pull request accepted).
     * This hook executes <i>after</i> the processing of a push and will not block the user client.
     * <p/>
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

        Settings settings = context.getSettings();
        String mirrorRepoUrl = settings.getString(SETTING_MIRROR_REPO_URL);
        String username = settings.getString(SETTING_USERNAME);
        String password = settings.getString(SETTING_PASSWORD);

        runMirrorCommand(mirrorRepoUrl, username, password, context.getRepository());

    }

    void runMirrorCommand(String mirrorRepoUrl, String username, final String password, final Repository repository) {

        try {
            final URI authenticatedUrl = getAuthenticatedUrl(mirrorRepoUrl, username, password);

            executor.submit(new Callable<Void>() {

                int attempts = 0;

                @Override
                public Void call() throws Exception {
                    try {
                        GitScmCommandBuilder builder = gitScm.getCommandBuilderFactory().builder(repository);
                        CommandExitHandler exitHandler = new GitCommandExitHandler(i18nService, repository);
                        PasswordHandler passwordHandler = new PasswordHandler(password, exitHandler);

                        // Call push command with the mirror flag set
                        String result = builder
                                .command("push")
                                .argument("--mirror")
                                .argument(authenticatedUrl.toString())
                                .errorHandler(passwordHandler)
                                .exitHandler(passwordHandler)
                                .build(passwordHandler)
                                .call();

                        logger.debug("MirrorRepositoryHook: postReceive completed with result '{}'.", result);

                    } catch (Exception e) {
                        if (++attempts > MAX_ATTEMPTS) {
                            logger.error("Failed to mirror repository " + repository.getName() + " after " + --attempts
                                    + " attempts.", e);
                        } else {
                            logger.warn("Failed to mirror repository " + repository.getName() + ", " +
                                    "retrying in 1 minute.");
                            executor.schedule(this, 1, TimeUnit.MINUTES);
                        }
                    }

                    return null;
                }
            });

        } catch (Exception e) {
            logger.error("MirrorRepositoryHook: Error running mirror hook", e);
        }
    }

    URI getAuthenticatedUrl(String mirrorRepoUrl, String username, String password) throws URISyntaxException {

        URI uri = URI.create(mirrorRepoUrl);
        String userInfo = username + ":" + password;

        return new URI(uri.getScheme(), userInfo, uri.getHost(), uri.getPort(),
                uri.getPath(), uri.getQuery(), uri.getFragment());

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
            int count = 0;
            logger.debug("MirrorRepositoryHook: validate started.");

            String mirrorRepoUrl = settings.getString(SETTING_MIRROR_REPO_URL, "");
            if (mirrorRepoUrl.isEmpty()) {
                count++;
                errors.addFieldError(SETTING_MIRROR_REPO_URL, "The mirror repo url is required.");
            } else {
                URI uri;
                try {
                    uri = URI.create(mirrorRepoUrl);
                    if (!uri.getScheme().toLowerCase().startsWith("http") || mirrorRepoUrl.contains("@")) {
                        count++;
                        errors.addFieldError(SETTING_MIRROR_REPO_URL, "The mirror repo url must be a valid http(s) " +
                                "URI and the user should be specified separately.");
                    }
                } catch (Exception ex) {
                    count++;
                    errors.addFieldError(SETTING_MIRROR_REPO_URL, "The mirror repo url must be a valid http(s) URI.");
                }
            }

            String username = settings.getString(SETTING_USERNAME, "");
            if (username.isEmpty()) {
                count++;
                errors.addFieldError(SETTING_USERNAME, "The username is required.");
            }

            String password = settings.getString(SETTING_PASSWORD, "");
            if (password.isEmpty()) {
                count++;
                errors.addFieldError(SETTING_PASSWORD, "The password is required.");
            }

            // If no errors, run the mirror command
            if (count == 0) {
                runMirrorCommand(mirrorRepoUrl, username, password, repository);
            }
            logger.debug("MirrorRepositoryHook: validate completed with {} error(s).", count);

        } catch (Exception e) {
            logger.error("Error running MirrorRepositoryHook validate.", e);
            errors.addFormError(e.getMessage());
        }

    }

}
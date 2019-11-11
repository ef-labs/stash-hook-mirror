package com.englishtown.bitbucket.hook;

import com.atlassian.bitbucket.concurrent.BucketProcessor;
import com.atlassian.bitbucket.i18n.I18nService;
import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.bitbucket.scm.*;
import com.atlassian.bitbucket.scm.git.command.GitCommandExitHandler;
import com.atlassian.bitbucket.server.ApplicationPropertiesService;
import com.atlassian.bitbucket.user.SecurityService;
import com.atlassian.utils.process.ProcessException;
import com.atlassian.utils.process.StringOutputHandler;
import com.atlassian.utils.process.Watchdog;
import com.google.common.base.Strings;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static com.englishtown.bitbucket.hook.MirrorRepositoryHook.PROP_PREFIX;

public class MirrorBucketProcessor implements BucketProcessor<MirrorRequest> {

    static final String PROP_TIMEOUT = PROP_PREFIX + "timeout";

    private static final String DEFAULT_REFSPEC = "+refs/heads/*:refs/heads/*";

    private static final Logger log = LoggerFactory.getLogger(MirrorBucketProcessor.class);

    private final I18nService i18nService;
    private final PasswordEncryptor passwordEncryptor;
    private final RepositoryService repositoryService;
    private final ScmService scmService;
    private final SecurityService securityService;
    private final Duration timeout;

    public MirrorBucketProcessor(I18nService i18nService, PasswordEncryptor passwordEncryptor,
                                 ApplicationPropertiesService propertiesService, RepositoryService repositoryService,
                                 ScmService scmService, SecurityService securityService) {
        this.i18nService = i18nService;
        this.passwordEncryptor = passwordEncryptor;
        this.repositoryService = repositoryService;
        this.scmService = scmService;
        this.securityService = securityService;

        timeout = Duration.ofSeconds(propertiesService.getPluginProperty(PROP_TIMEOUT, 120L));
        log.debug(PROP_TIMEOUT+": "+timeout.getSeconds());
    }

    @Override
    public void process(@Nonnull String key, @Nonnull List<MirrorRequest> requests) {
        if (requests.isEmpty()) {
            return;
        }
        // Every request is for the same mirror URL, and the same repository ID. In case the
        // settings (e.g. username/password) have been changed since the first request was
        // queued, we process the _last_ request in the list. Since mirroring pushes all of
        // the configured refspecs, any single request should roll up changes from any number
        // of requests
        MirrorRequest request = requests.get(requests.size() - 1);

        securityService.withPermission(Permission.REPO_READ, "Mirror changes")
                .call(() -> {
                    Repository repository = repositoryService.getById(request.getRepositoryId());
                    if (repository == null) {
                        log.debug("{}: Repository has been deleted", request.getRepositoryId());
                        return null;
                    }
                    if (repositoryService.isEmpty(repository)) {
                        log.debug("{}: The repository is empty", repository);
                        return null;
                    }
                    runMirrorCommand(request.getSettings(), repository);
                    return null;
                });
    }

    public void runMirrorCommand(MirrorSettings settings, Repository repository) {
        runMirrorCommand(settings, repository, null);
    }
    public void runMirrorCommand(MirrorSettings settings, Repository repository, StringOutputHandler outputHandler) {
        log.debug("{}: Preparing to push changes to mirror", repository);

        String plainPassword = passwordEncryptor.decrypt(settings.password);
        String plainPrivateToken = passwordEncryptor.decrypt(settings.privateToken);
        PasswordHandler passwordHandler = new PasswordHandler(plainPassword,plainPrivateToken,
                new GitCommandExitHandler(i18nService, repository));
        String authenticatedUrl = getAuthenticatedUrl(settings.mirrorRepoUrl, settings.username, plainPassword);

        // Call push command with the prune flag and refspecs for heads and tags
        // Do not use the mirror flag as pull-request refs are included
        ScmCommandBuilder<?> builder = scmService.createBuilder(repository)
                .command("push")
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


        Command<String> command = builder.errorHandler(passwordHandler)
                .exitHandler(passwordHandler)
                .build(passwordHandler);
        command.setTimeout(timeout);
        Object result=null;
        try {
            result = command.call();
            if(result != null) {
                passwordHandler.cleanText(result.toString());
            }
        } catch (Exception e) {
            if (outputHandler != null) {
                try {
                    outputHandler.process(new ByteArrayInputStream(passwordHandler.getOutput().getBytes()));
                } catch (ProcessException s) {
                    log.error("Failed to process response: " + s.getMessage());
                }
            }
            log.info("{}: Push failed with the following output:\n{}", repository, passwordHandler.getOutput());
            throw e;
        }
        if (outputHandler != null) {
            try {
                outputHandler.process(new ByteArrayInputStream(passwordHandler.getOutput().getBytes()));
            } catch (ProcessException s) {
                log.error("Failed to process response: " + s.getMessage());
            }
        }
        log.debug("{}: Push completed with the following output:\n{}", repository, passwordHandler.getOutput());
    }

    String getAuthenticatedUrl(String mirrorRepoUrl, String username, String password) {
        // Only http(s) has username/password
        if (!mirrorRepoUrl.toLowerCase(Locale.ROOT).startsWith("http")) {
            return mirrorRepoUrl;
        }

        URI uri = URI.create(mirrorRepoUrl);
        String userInfo = username + ":" + password;

        try {
            return new URI(uri.getScheme(), userInfo, uri.getHost(), uri.getPort(),
                    uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("The configured mirror URL (" + mirrorRepoUrl + ") is invalid", e);
        }
    }
}

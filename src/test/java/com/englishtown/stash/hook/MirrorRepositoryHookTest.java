package com.englishtown.stash.hook;

import com.atlassian.stash.hook.repository.RepositoryHookContext;
import com.atlassian.stash.i18n.I18nService;
import com.atlassian.stash.repository.RefChange;
import com.atlassian.stash.repository.Repository;
import com.atlassian.stash.scm.CommandErrorHandler;
import com.atlassian.stash.scm.CommandExitHandler;
import com.atlassian.stash.scm.CommandOutputHandler;
import com.atlassian.stash.scm.git.GitCommand;
import com.atlassian.stash.scm.git.GitCommandBuilderFactory;
import com.atlassian.stash.scm.git.GitScm;
import com.atlassian.stash.scm.git.GitScmCommandBuilder;
import com.atlassian.stash.setting.Settings;
import com.atlassian.stash.setting.SettingsValidationErrors;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MirrorRepositoryHook}
 */
@RunWith(MockitoJUnitRunner.class)
public class MirrorRepositoryHookTest {

    private MirrorRepositoryHook hook;
    private GitScmCommandBuilder builder;
    @Mock
    private GitCommand<String> cmd;
    @Mock
    private ScheduledExecutorService executor;

    private final String mirrorRepoUrl = "https://stash-mirror.englishtown.com/scm/test/test.git";
    private final String username = "test-user";
    private final String password = "test-password";
    private final String repository = "https://test-user:test-password@stash-mirror.englishtown.com/scm/test/test.git";

    @SuppressWarnings("UnusedDeclaration")
    @Captor
    ArgumentCaptor<Callable<Void>> argumentCaptor;

    @Before
    public void setup() {

        builder = mock(GitScmCommandBuilder.class);
        when(builder.command(anyString())).thenReturn(builder);
        when(builder.argument(anyString())).thenReturn(builder);
        when(builder.errorHandler(any(CommandErrorHandler.class))).thenReturn(builder);
        when(builder.exitHandler(any(CommandExitHandler.class))).thenReturn(builder);
        when(builder.build(any(CommandOutputHandler.class))).thenReturn(cmd);

        GitCommandBuilderFactory builderFactory = mock(GitCommandBuilderFactory.class);
        when(builderFactory.builder(any(Repository.class))).thenReturn(builder);

        GitScm gitScm = mock(GitScm.class);
        when(gitScm.getCommandBuilderFactory()).thenReturn(builderFactory);

        hook = new MirrorRepositoryHook(gitScm, mock(I18nService.class), executor);

    }

    @Test
    public void testPostReceive() throws Exception {

        Settings settings = mock(Settings.class);
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL))).thenReturn(mirrorRepoUrl);
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_USERNAME))).thenReturn(username);
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_PASSWORD))).thenReturn(password);

        Repository repo = mock(Repository.class);
        when(repo.getName()).thenReturn("test");

        RepositoryHookContext context = mock(RepositoryHookContext.class);
        when(context.getSettings()).thenReturn(settings);
        when(context.getRepository()).thenReturn(repo);

        Collection<RefChange> refChanges = new ArrayList<RefChange>();

        hook.postReceive(context, refChanges);
        verifyExecutor();
    }

    @Test
    public void testRunMirrorCommand_Retries() throws Exception {

        GitScm gitScm = mock(GitScm.class);
        when(gitScm.getCommandBuilderFactory()).thenThrow(new RuntimeException("Intentional unit test exception"));
        MirrorRepositoryHook hook = new MirrorRepositoryHook(gitScm, mock(I18nService.class), executor);
        hook.runMirrorCommand(mirrorRepoUrl, username, password, mock(Repository.class));

        verify(executor).submit(argumentCaptor.capture());
        Callable<Void> callable = argumentCaptor.getValue();
        callable.call();

        verify(executor, times(1)).schedule(argumentCaptor.capture(), anyInt(), any(TimeUnit.class));
        callable = argumentCaptor.getValue();
        callable.call();

        verify(executor, times(2)).schedule(argumentCaptor.capture(), anyInt(), any(TimeUnit.class));
        callable = argumentCaptor.getValue();
        callable.call();

        verify(executor, times(3)).schedule(argumentCaptor.capture(), anyInt(), any(TimeUnit.class));
        callable = argumentCaptor.getValue();
        callable.call();

        verify(executor, times(4)).schedule(argumentCaptor.capture(), anyInt(), any(TimeUnit.class));
        callable = argumentCaptor.getValue();
        callable.call();

        verify(executor, times(5)).schedule(argumentCaptor.capture(), anyInt(), any(TimeUnit.class));
        callable = argumentCaptor.getValue();
        callable.call();

        // Make sure it is only called 5 times
        verify(executor, times(5)).schedule(argumentCaptor.capture(), anyInt(), any(TimeUnit.class));

    }

    private void verifyExecutor() throws Exception {

        verify(executor).submit(argumentCaptor.capture());
        Callable<Void> callable = argumentCaptor.getValue();
        callable.call();

        verify(builder, times(1)).command(eq("push"));
        verify(builder, times(1)).argument(eq("--mirror"));
        verify(builder, times(1)).argument(eq(repository));
        verify(cmd, times(1)).call();

    }

    @Test
    public void testGetAuthenticatedUrl() throws Exception {

        URI result;

        result = hook.getAuthenticatedUrl(mirrorRepoUrl, username, password);
        assertEquals(repository, result.toString());

    }

    @Test
    public void testValidate() throws Exception {

        Settings settings = mock(Settings.class);

        when(settings.getString(eq(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL), eq("")))
                .thenThrow(new RuntimeException("Intentional unit test exception"))
                .thenReturn("")
                .thenReturn("invalid uri")
                .thenReturn("http://should-not:have-user@stash-mirror.englishtown.com/scm/test/test.git")
                .thenReturn(mirrorRepoUrl);

        when(settings.getString(eq(MirrorRepositoryHook.SETTING_USERNAME), eq("")))
                .thenReturn("")
                .thenReturn(username);

        when(settings.getString(eq(MirrorRepositoryHook.SETTING_PASSWORD), eq("")))
                .thenReturn("")
                .thenReturn(password);

        Repository repo = mock(Repository.class);
        SettingsValidationErrors errors;

        errors = mock(SettingsValidationErrors.class);
        hook.validate(settings, errors, repo);
        verify(errors, times(1)).addFormError(anyString());

        errors = mock(SettingsValidationErrors.class);
        hook.validate(settings, errors, repo);
        verify(errors, never()).addFormError(anyString());
        verify(errors).addFieldError(eq(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL), anyString());
        verify(errors).addFieldError(eq(MirrorRepositoryHook.SETTING_USERNAME), anyString());
        verify(errors).addFieldError(eq(MirrorRepositoryHook.SETTING_PASSWORD), anyString());

        errors = mock(SettingsValidationErrors.class);
        hook.validate(settings, errors, repo);
        verify(errors, never()).addFormError(anyString());
        verify(errors).addFieldError(eq(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL), anyString());
        verify(errors).addFieldError(anyString(), anyString());

        errors = mock(SettingsValidationErrors.class);
        hook.validate(settings, errors, repo);
        verify(errors, never()).addFormError(anyString());
        verify(errors).addFieldError(eq(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL), anyString());
        verify(errors).addFieldError(anyString(), anyString());

        errors = mock(SettingsValidationErrors.class);
        hook.validate(settings, errors, repo);
        verify(errors, never()).addFormError(anyString());
        verify(errors, never()).addFieldError(anyString(), anyString());

    }

}

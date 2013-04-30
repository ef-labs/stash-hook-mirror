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

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MirrorRepositoryHook}
 */
public class MirrorRepositoryHookTest {

    private MirrorRepositoryHook hook;
    private GitScmCommandBuilder builder;
    private GitCommand<String> cmd;

    private final String mirrorRepoUrl = "https://stash-mirror.englishtown.com/scm/test/test.git";
    private final String username = "test-user";
    private final String password = "test-password";
    private final String repository = "https://test-user:test-password@stash-mirror.englishtown.com/scm/test/test.git";

    @SuppressWarnings("unchecked")
    @Before
    public void setup() {

        cmd = mock(GitCommand.class);
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

        hook = new MirrorRepositoryHook(gitScm, mock(I18nService.class));

    }

    @Test
    public void testPostReceive() throws Exception {

        Settings settings = mock(Settings.class);
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL))).thenReturn(mirrorRepoUrl);
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_USERNAME))).thenReturn(username);
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_PASSWORD))).thenReturn(password);

        RepositoryHookContext context = mock(RepositoryHookContext.class);
        when(context.getSettings()).thenReturn(settings);

        Collection<RefChange> refChanges = new ArrayList<RefChange>();

        hook.postReceive(context, refChanges);
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
                .thenThrow(new RuntimeException())
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

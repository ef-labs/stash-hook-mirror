package com.englishtown.bitbucket.hook;

import com.atlassian.bitbucket.hook.repository.PostRepositoryHookContext;
import com.atlassian.bitbucket.hook.repository.RepositoryHookRequest;
import com.atlassian.bitbucket.hook.repository.RepositoryHookTrigger;
import com.atlassian.bitbucket.hook.repository.StandardRepositoryHookTrigger;
import com.atlassian.bitbucket.i18n.I18nService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.bitbucket.scm.CommandErrorHandler;
import com.atlassian.bitbucket.scm.CommandExitHandler;
import com.atlassian.bitbucket.scm.CommandOutputHandler;
import com.atlassian.bitbucket.scm.ScmService;
import com.atlassian.bitbucket.scm.git.command.GitCommand;
import com.atlassian.bitbucket.scm.git.command.GitScmCommandBuilder;
import com.atlassian.bitbucket.scope.Scope;
import com.atlassian.bitbucket.setting.Settings;
import com.atlassian.bitbucket.setting.SettingsValidationErrors;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MirrorRepositoryHook}
 */
public class MirrorRepositoryHookTest {

    private MirrorRepositoryHook hook;
    private GitScmCommandBuilder builder;

    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private ScmService scmService;
    @Mock
    private GitCommand<String> cmd;
    @Mock
    private ScheduledExecutorService executor;
    @Mock
    private PasswordEncryptor passwordEncryptor;
    @Mock
    private SettingsReflectionHelper settingsReflectionHelper;
    @Mock
    private PluginSettingsFactory pluginSettingsFactory;
    @Mock
    private PluginSettings pluginSettings;
    @Mock
    private RepositoryService repositoryService;

    private final String mirrorRepoUrlHttp = "https://bitbucket-mirror.englishtown.com/scm/test/test.git";
    private final String mirrorRepoUrlSsh = "ssh://git@bitbucket-mirror.englishtown.com/scm/test/test.git";
    private final String username = "test-user";
    private final String password = "test-password";
    private final String repository = "https://test-user:test-password@bitbucket-mirror.englishtown.com/scm/test/test.git";
    private final String refspec = "+refs/heads/master:refs/heads/master +refs/heads/develop:refs/heads/develop";

    @Captor
    ArgumentCaptor<Runnable> argumentCaptor;

    @Before
    public void setup() {

        builder = mock(GitScmCommandBuilder.class);
        when(builder.command(anyString())).thenReturn(builder);
        when(builder.argument(anyString())).thenReturn(builder);
        when(builder.errorHandler(any(CommandErrorHandler.class))).thenReturn(builder);
        when(builder.exitHandler(any(CommandExitHandler.class))).thenReturn(builder);
        when(builder.<String>build(any(CommandOutputHandler.class))).thenReturn(cmd);

        doReturn(builder).when(scmService).createBuilder(any());

        when(pluginSettingsFactory.createSettingsForKey(anyString())).thenReturn(pluginSettings);

        hook = new MirrorRepositoryHook(scmService, mock(I18nService.class), executor, passwordEncryptor
                , settingsReflectionHelper, pluginSettingsFactory, repositoryService);

    }

    @Test
    public void testPostReceive() throws Exception {
        when(passwordEncryptor.decrypt(anyString())).thenReturn(password);

        Repository repo = mock(Repository.class);

        hook.postUpdate(buildContext(), buildRequest(StandardRepositoryHookTrigger.REPO_PUSH, repo));
        verifyExecutor();
    }

    @Test
    public void testUnwantedEventsIgnored() {
        Repository repo = mock(Repository.class);

        hook.postUpdate(buildContext(), buildRequest(StandardRepositoryHookTrigger.UNKNOWN, repo));

        verify(executor, never()).submit(ArgumentMatchers.<Runnable>any());
    }

    @Test
    public void testEmptyRepositoriesNotMirrored() {
        Repository repo = mock(Repository.class);
        when(repositoryService.isEmpty(repo)).thenReturn(true);

        hook.postUpdate(buildContext(), buildRequest(StandardRepositoryHookTrigger.REPO_PUSH, repo));

        verify(executor, never()).submit(ArgumentMatchers.<Runnable>any());
    }

    @Test
    public void testRunMirrorCommand_Retries() throws Exception {

        when(scmService.createBuilder(any())).thenThrow(new RuntimeException("Intentional unit test exception"));
        MirrorRepositoryHook hook = new MirrorRepositoryHook(scmService, mock(I18nService.class), executor,
                passwordEncryptor, settingsReflectionHelper, pluginSettingsFactory, repositoryService);
        MirrorRepositoryHook.MirrorSettings ms = new MirrorRepositoryHook.MirrorSettings();
        ms.mirrorRepoUrl = mirrorRepoUrlHttp;
        ms.username = username;
        ms.password = password;
        hook.runMirrorCommand(ms, mock(Repository.class));

        verify(executor).submit(argumentCaptor.capture());
        Runnable runnable = argumentCaptor.getValue();
        runnable.run();

        verify(executor, times(1)).schedule(argumentCaptor.capture(), anyLong(), any(TimeUnit.class));
        runnable = argumentCaptor.getValue();
        runnable.run();

        verify(executor, times(2)).schedule(argumentCaptor.capture(), anyLong(), any(TimeUnit.class));
        runnable = argumentCaptor.getValue();
        runnable.run();

        verify(executor, times(3)).schedule(argumentCaptor.capture(), anyLong(), any(TimeUnit.class));
        runnable = argumentCaptor.getValue();
        runnable.run();

        verify(executor, times(4)).schedule(argumentCaptor.capture(), anyLong(), any(TimeUnit.class));
        runnable = argumentCaptor.getValue();
        runnable.run();

        // Make sure it is only called 5 times
        runnable.run();
        runnable.run();
        runnable.run();
        verify(executor, times(4)).schedule(argumentCaptor.capture(), anyLong(), any(TimeUnit.class));

    }

    private void verifyExecutor() throws Exception {

        verify(executor).submit(argumentCaptor.capture());
        Runnable runnable = argumentCaptor.getValue();
        runnable.run();

        verify(builder, times(1)).command(eq("push"));
        verify(builder, times(1)).argument(eq("--prune"));
        verify(builder, times(1)).argument(eq("--atomic"));
        verify(builder, times(1)).argument(eq(repository));
        verify(builder, times(1)).argument(eq("--force"));
        verify(builder, times(1)).argument(eq("+refs/heads/master:refs/heads/master"));
        verify(builder, times(1)).argument(eq("+refs/heads/develop:refs/heads/develop"));
        verify(builder, times(1)).argument(eq("+refs/tags/*:refs/tags/*"));
        verify(builder, times(1)).argument(eq("+refs/notes/*:refs/notes/*"));
        verify(cmd, times(1)).call();

    }

    @Test
    public void testGetAuthenticatedUrl() throws Exception {

        String result = hook.getAuthenticatedUrl(mirrorRepoUrlHttp, username, password);
        assertEquals(repository, result);

    }

    @Test
    public void testValidate() throws Exception {

        Settings settings = mock(Settings.class);

        Map<String, Object> map = new HashMap<String, Object>();
        map.put(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL + "0", "");

        when(settings.asMap()).thenReturn(map);

        when(settings.getString(eq(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL + "0"), eq("")))
                .thenThrow(new RuntimeException("Intentional unit test exception"))
                .thenReturn("")
                .thenReturn(mirrorRepoUrlHttp)
                .thenReturn("invalid uri")
                .thenReturn("http://should-not:have-user@bitbucket-mirror.englishtown.com/scm/test/test.git")
                .thenReturn("ssh://user@bitbucket-mirror.englishtown.com/scm/test/test.git")
                .thenReturn(mirrorRepoUrlSsh)
                .thenReturn(mirrorRepoUrlHttp);

        when(settings.getString(eq(MirrorRepositoryHook.SETTING_USERNAME + "0"), eq("")))
                .thenReturn("")
                .thenReturn("")
                .thenReturn("")
                .thenReturn(username);

        when(settings.getString(eq(MirrorRepositoryHook.SETTING_PASSWORD + "0"), eq("")))
                .thenReturn("")
                .thenReturn("")
                .thenReturn("")
                .thenReturn(password);

        when(settings.getString(eq(MirrorRepositoryHook.SETTING_REFSPEC + "0"), eq("")))
                .thenReturn("??")
                .thenReturn("+refs/heads/master:refs/heads/master")
                .thenReturn("");

        Scope scope = mock(Scope.class);

        SettingsValidationErrors errors;

        errors = mock(SettingsValidationErrors.class);
        hook.validate(settings, errors, scope);
        verify(errors, times(1)).addFormError(anyString());
        verify(errors, never()).addFieldError(anyString(), anyString());

        errors = mock(SettingsValidationErrors.class);
        hook.validate(settings, errors, scope);
        verify(errors, never()).addFormError(anyString());
        verify(errors).addFieldError(eq(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL + "0"), anyString());
        verify(errors, never()).addFieldError(eq(MirrorRepositoryHook.SETTING_USERNAME + "0"), anyString());
        verify(errors, never()).addFieldError(eq(MirrorRepositoryHook.SETTING_PASSWORD + "0"), anyString());
        verify(errors).addFieldError(eq(MirrorRepositoryHook.SETTING_REFSPEC + "0"), anyString());

        errors = mock(SettingsValidationErrors.class);
        hook.validate(settings, errors, scope);
        verify(errors, never()).addFormError(anyString());
        verify(errors, never()).addFieldError(eq(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL + "0"), anyString());
        verify(errors).addFieldError(eq(MirrorRepositoryHook.SETTING_USERNAME + "0"), anyString());
        verify(errors).addFieldError(eq(MirrorRepositoryHook.SETTING_PASSWORD + "0"), anyString());

        errors = mock(SettingsValidationErrors.class);
        hook.validate(settings, errors, scope);
        verify(errors, never()).addFormError(anyString());
        verify(errors, never()).addFieldError(eq(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL + "0"), anyString());
        verify(errors, never()).addFieldError(anyString(), anyString());

        errors = mock(SettingsValidationErrors.class);
        hook.validate(settings, errors, scope);
        verify(errors, never()).addFormError(anyString());
        verify(errors).addFieldError(eq(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL + "0"), anyString());
        verify(errors, never()).addFieldError(eq(MirrorRepositoryHook.SETTING_USERNAME + "0"), anyString());
        verify(errors, never()).addFieldError(eq(MirrorRepositoryHook.SETTING_PASSWORD + "0"), anyString());

        errors = mock(SettingsValidationErrors.class);
        hook.validate(settings, errors, scope);
        verify(errors, never()).addFormError(anyString());
        verify(errors, never()).addFieldError(eq(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL + "0"), anyString());
        verify(errors, never()).addFieldError(eq(MirrorRepositoryHook.SETTING_USERNAME + "0"), anyString());
        verify(errors, never()).addFieldError(eq(MirrorRepositoryHook.SETTING_PASSWORD + "0"), anyString());

        errors = mock(SettingsValidationErrors.class);
        hook.validate(settings, errors, scope);
        verify(errors, never()).addFormError(anyString());
        verify(errors, never()).addFieldError(anyString(), anyString());

        errors = mock(SettingsValidationErrors.class);
        hook.validate(settings, errors, scope);
        verify(errors, never()).addFormError(anyString());
        verify(errors, never()).addFieldError(anyString(), anyString());

    }

    private PostRepositoryHookContext buildContext() {
        PostRepositoryHookContext context = mock(PostRepositoryHookContext.class);
        Settings settings = defaultSettings();
        when(context.getSettings()).thenReturn(settings);
        return context;
    }

    private RepositoryHookRequest buildRequest(RepositoryHookTrigger trigger, Repository repo) {
        RepositoryHookRequest request = mock(RepositoryHookRequest.class);
        when(request.getTrigger()).thenReturn(trigger);
        when(request.getRepository()).thenReturn(repo);
        return request;
    }

    private Settings defaultSettings() {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL, "");

        Settings settings = mock(Settings.class);
        when(settings.asMap()).thenReturn(map);
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL), eq(""))).thenReturn(mirrorRepoUrlHttp);
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_USERNAME), eq(""))).thenReturn(username);
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_PASSWORD), eq(""))).thenReturn(password);
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_REFSPEC), eq(""))).thenReturn(refspec);
        when(settings.getBoolean(eq(MirrorRepositoryHook.SETTING_TAGS), eq(true))).thenReturn(true);
        when(settings.getBoolean(eq(MirrorRepositoryHook.SETTING_NOTES), eq(true))).thenReturn(true);
        when(settings.getBoolean(eq(MirrorRepositoryHook.SETTING_ATOMIC), eq(true))).thenReturn(true);
        return settings;
    }
}

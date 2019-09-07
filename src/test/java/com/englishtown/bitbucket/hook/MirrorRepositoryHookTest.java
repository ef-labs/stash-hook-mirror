package com.englishtown.bitbucket.hook;

import com.atlassian.bitbucket.concurrent.BucketedExecutor;
import com.atlassian.bitbucket.concurrent.ConcurrencyService;
import com.atlassian.bitbucket.hook.repository.*;
import com.atlassian.bitbucket.project.Project;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.scm.git.GitScm;
import com.atlassian.bitbucket.scope.Scope;
import com.atlassian.bitbucket.scope.Scopes;
import com.atlassian.bitbucket.server.ApplicationPropertiesService;
import com.atlassian.bitbucket.setting.Settings;
import com.atlassian.bitbucket.setting.SettingsValidationErrors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.atlassian.bitbucket.mockito.MockitoUtils.returnArg;
import static com.englishtown.bitbucket.hook.MirrorRepositoryHook.PROP_ATTEMPTS;
import static com.englishtown.bitbucket.hook.MirrorRepositoryHook.PROP_THREADS;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MirrorRepositoryHook}.
 */
public class MirrorRepositoryHookTest {

    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule().silent();

    private final String mirrorRepoUrlHttp = "https://bitbucket-mirror.englishtown.com/scm/test/test.git";
    private final String mirrorRepoUrlSsh = "ssh://git@bitbucket-mirror.englishtown.com/scm/test/test.git";
    private final String password = "test-password";
    private final String refspec = "+refs/heads/master:refs/heads/master +refs/heads/develop:refs/heads/develop";
    private final String username = "test-user";

    @Mock
    private BucketedExecutor<MirrorRequest> bucketedExecutor;
    @Mock
    private MirrorBucketProcessor bucketProcessor;
    @Mock
    private ConcurrencyService concurrencyService;
    private MirrorRepositoryHook hook;
    @Mock
    private PasswordEncryptor passwordEncryptor;
    @Mock
    private ApplicationPropertiesService propertiesService;
    @Captor
    private ArgumentCaptor<MirrorRequest> requestCaptor;
    @Mock
    private SettingsReflectionHelper settingsReflectionHelper;

    @Before
    public void setup() {
        doReturn(bucketedExecutor).when(concurrencyService).getBucketedExecutor(anyString(), any());

        when(propertiesService.getPluginProperty(eq(PROP_ATTEMPTS), anyInt())).thenAnswer(returnArg(1));
        when(propertiesService.getPluginProperty(eq(PROP_THREADS), anyInt())).thenAnswer(returnArg(1));

        hook = new MirrorRepositoryHook(concurrencyService, passwordEncryptor,
                propertiesService, bucketProcessor, settingsReflectionHelper);
    }

    @Test
    public void testPostUpdate() {
        when(passwordEncryptor.decrypt(anyString())).thenReturn(password);

        Repository repo = mock(Repository.class);
        when(repo.getId()).thenReturn(1);
        when(repo.getScmId()).thenReturn(GitScm.ID);

        hook.postUpdate(buildContext(), new RepositoryPushHookRequest.Builder(repo).build());

        verify(repo).getId();
        verify(repo).getScmId();
        verify(bucketedExecutor).schedule(requestCaptor.capture(), eq(5L), same(TimeUnit.SECONDS));

        MirrorRequest request = requestCaptor.getValue();
        assertEquals(1, request.getRepositoryId());
    }

    @Test
    public void testPostUpdateForHgRepository() {
        Repository repo = mock(Repository.class);
        when(repo.getScmId()).thenReturn("hg");

        hook.postUpdate(buildContext(), new RepositoryPushHookRequest.Builder(repo).build());

        verifyZeroInteractions(bucketedExecutor);
    }

    @Test
    public void testPostUpdateUnconfigured() {
        Repository repo = mock(Repository.class);
        when(repo.getScmId()).thenReturn(GitScm.ID);

        Settings settings = mock(Settings.class);
        when(settings.asMap()).thenReturn(Collections.emptyMap());

        PostRepositoryHookContext context = mock(PostRepositoryHookContext.class);
        when(context.getSettings()).thenReturn(settings);

        hook.postUpdate(context, new RepositoryPushHookRequest.Builder(repo).build());

        verifyZeroInteractions(bucketedExecutor);
    }

    @Test
    public void testUnwantedEventsIgnored() {
        Repository repo = mock(Repository.class);

        hook.postUpdate(buildContext(), buildRequest(StandardRepositoryHookTrigger.UNKNOWN, repo));

        verify(bucketedExecutor, never()).submit(any());
    }

    @Test
    public void testValidate() {
        Settings settings = mock(Settings.class);

        Map<String, Object> map = new HashMap<>();
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

        Repository repo = mock(Repository.class);
        Scope scope = Scopes.repository(repo);
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

    @Test
    public void testValidateForGlobal() {
        SettingsValidationErrors errors = mock(SettingsValidationErrors.class);
        Settings settings = mock(Settings.class);

        hook.validate(settings, errors, Scopes.global());

        verifyZeroInteractions(bucketedExecutor, errors, settings);
    }

    @Test
    public void testValidateForProject() {
        SettingsValidationErrors errors = mock(SettingsValidationErrors.class);
        Project project = mock(Project.class);
        Settings settings = mock(Settings.class);

        hook.validate(settings, errors, Scopes.project(project));

        verifyZeroInteractions(bucketedExecutor, errors, settings);
    }

    private PostRepositoryHookContext buildContext() {
        Settings settings = defaultSettings();

        PostRepositoryHookContext context = mock(PostRepositoryHookContext.class);
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
        Map<String, Object> map = new HashMap<>();
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

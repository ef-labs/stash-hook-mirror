package com.englishtown.bitbucket.hook;

import com.atlassian.bitbucket.concurrent.BucketedExecutor;
import com.atlassian.bitbucket.concurrent.ConcurrencyService;
import com.atlassian.bitbucket.event.repository.RepositoryDeletedEvent;
import com.atlassian.bitbucket.event.repository.RepositoryModifiedEvent;
import com.atlassian.bitbucket.hook.repository.*;
import com.atlassian.bitbucket.i18n.I18nService;
import com.atlassian.bitbucket.project.Project;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.scm.git.GitScm;
import com.atlassian.bitbucket.scope.Scope;
import com.atlassian.bitbucket.scope.Scopes;
import com.atlassian.bitbucket.server.ApplicationPropertiesService;
import com.atlassian.bitbucket.setting.Settings;
import com.atlassian.bitbucket.setting.SettingsValidationErrors;
import com.atlassian.utils.process.StringOutputHandler;
import org.junit.Assert;
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
    private final String restApiURL = "http://bitbucket-mirror.englishtown.com/api";
    private final String privateToken = "123ASD";

    @Mock
    private BucketedExecutor<MirrorRequest> bucketedExecutor;
    @Mock
    private MirrorBucketProcessor bucketProcessor;
    @Mock
    private ConcurrencyService concurrencyService;
    private MirrorRepositoryHook hook;
    @Mock
    private MirrorRemoteAdmin mirrorRemoteAdmin;
    @Mock
    private I18nService i18nService;
    @Mock
    private PasswordEncryptor passwordEncryptor;
    @Mock
    private ApplicationPropertiesService propertiesService;
    @Captor
    private ArgumentCaptor<MirrorRequest> requestCaptor;
    @Mock
    private RepositoryHookService repositoryHookService;
    @Mock
    private SettingsReflectionHelper settingsReflectionHelper;

    @Before
    public void setup() {
        doReturn(bucketedExecutor).when(concurrencyService).getBucketedExecutor(anyString(), any());

        when(propertiesService.getPluginProperty(eq(PROP_ATTEMPTS), anyInt())).thenAnswer(returnArg(1));
        when(propertiesService.getPluginProperty(eq(PROP_THREADS), anyInt())).thenAnswer(returnArg(1));

        hook = new MirrorRepositoryHook(concurrencyService, i18nService, passwordEncryptor,
                propertiesService, bucketProcessor, mirrorRemoteAdmin, repositoryHookService, settingsReflectionHelper);
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
    public void testValidateRepository() {
        Repository repo = mock(Repository.class);
        Scope scope = Scopes.repository(repo);
        testValidate(scope);
    }
    @Test
    public void testValidateProject() {
        Project project = mock(Project.class);
        Scope scope = Scopes.project(project);
        testValidate(scope);
    }
    public void testValidate(Scope scope) {
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

        verifyZeroInteractions(bucketedExecutor);
    }

    @Test
    public void testRepositoryDeleted() {
        Repository repo = mock(Repository.class);
        when(repo.getName()).thenReturn("test");

//        RepositoryDeletedEvent deletedEvent = mock(RepositoryDeletedEvent.class, withSettings().verboseLogging());
        RepositoryDeletedEvent deletedEvent = mock(RepositoryDeletedEvent.class);
        when(deletedEvent.getRepository()).thenReturn(repo);

        RepositoryHookSettings repositoryHookSettings = mock(RepositoryHookSettings.class);

        Settings settings = mock(Settings.class);
        when(repositoryHookSettings.getSettings()).thenReturn(settings);

        when(repositoryHookService.getSettings(any(GetRepositoryHookSettingsRequest.class))).thenReturn(repositoryHookSettings);
        doThrow(new RuntimeException("Repository not found")).when(mirrorRemoteAdmin).delete(any(MirrorSettings.class), any(Repository.class), any(StringOutputHandler.class));
        hook.repositoryDeleted(deletedEvent);
        verify(mirrorRemoteAdmin, times(0)).delete(any(MirrorSettings.class), any(Repository.class), any(StringOutputHandler.class));

        settings = defaultSettings();
        when(repositoryHookSettings.getSettings()).thenReturn(settings);

        hook.repositoryDeleted(deletedEvent);
        verify(mirrorRemoteAdmin, times(1)).delete(any(MirrorSettings.class), any(Repository.class), any(StringOutputHandler.class));
    }

    @Test
    public void testRepositoryModified() {
        Repository oldRepo = mock(Repository.class);
        when(oldRepo.getName()).thenReturn("test");
        Project oldProject = mock(Project.class);
        when(oldProject.getKey()).thenReturn("PROJECT");
        when(oldRepo.getProject()).thenReturn(oldProject);

        Repository newRepo = mock(Repository.class);
        when(newRepo.getName()).thenReturn("test");
        Project newProject = mock(Project.class);
        when(newProject.getKey()).thenReturn("PROJECT");
        when(newRepo.getProject()).thenReturn(newProject);

        RepositoryModifiedEvent modifiedEvent = mock(RepositoryModifiedEvent.class);
        when(modifiedEvent.getRepository()).thenReturn(newRepo);
        when(modifiedEvent.getOldValue()).thenReturn(oldRepo);
        when(modifiedEvent.getNewValue()).thenReturn(newRepo);

        RepositoryHookSettings repositoryHookSettings = mock(RepositoryHookSettings.class);

        Settings settings = defaultSettings();
        when(repositoryHookSettings.getSettings()).thenReturn(settings);

        when(repositoryHookService.getSettings(any(GetRepositoryHookSettingsRequest.class))).thenReturn(repositoryHookSettings);
        doThrow(new RuntimeException("Repository not found")).when(mirrorRemoteAdmin).delete(any(MirrorSettings.class), any(Repository.class), any(StringOutputHandler.class));
        doThrow(new RuntimeException("Repository no created")).when(bucketProcessor).runMirrorCommand(any(MirrorSettings.class), any(Repository.class), any(StringOutputHandler.class));
        hook.repositoryModified(modifiedEvent);
        verify(mirrorRemoteAdmin, times(0)).delete(any(MirrorSettings.class), any(Repository.class), any(StringOutputHandler.class));
        verify(bucketProcessor, times(0)).runMirrorCommand(any(MirrorSettings.class), any(Repository.class), any(StringOutputHandler.class));

        when(newRepo.getName()).thenReturn("test");
        when(newProject.getKey()).thenReturn("NEW_PROJECT");
        hook.repositoryModified(modifiedEvent);
        verify(mirrorRemoteAdmin, times(1)).delete(any(MirrorSettings.class), any(Repository.class), any(StringOutputHandler.class));
        verify(bucketProcessor, times(1)).runMirrorCommand(any(MirrorSettings.class), any(Repository.class), any(StringOutputHandler.class));

        when(newRepo.getName()).thenReturn("newtest");
        when(newProject.getKey()).thenReturn("PROJECT");
        hook.repositoryModified(modifiedEvent);
        verify(mirrorRemoteAdmin, times(2)).delete(any(MirrorSettings.class), any(Repository.class), any(StringOutputHandler.class));
        verify(bucketProcessor, times(2)).runMirrorCommand(any(MirrorSettings.class), any(Repository.class), any(StringOutputHandler.class));

        when(newRepo.getName()).thenReturn("newtest");
        when(newProject.getKey()).thenReturn("NEWPROJECT");
        hook.repositoryModified(modifiedEvent);
        verify(mirrorRemoteAdmin, times(3)).delete(any(MirrorSettings.class), any(Repository.class), any(StringOutputHandler.class));
        verify(bucketProcessor, times(3)).runMirrorCommand(any(MirrorSettings.class), any(Repository.class), any(StringOutputHandler.class));

        //TODO test with different settings
    }

    @Test
    public void testInterpolateMirrorRepoUrl() {
        Repository repo = mock(Repository.class);
        when(repo.getName()).thenReturn("test");
        Project project = mock(Project.class);
        when(project.getKey()).thenReturn("PROJECT");
        when(repo.getProject()).thenReturn(project);
        assertEquals("http://host/PROJECT/test.git", hook.interpolateMirrorRepoUrl(repo, "http://host/${repository.getProject().getKey()}/${repository.getName()}.git"));
        try {
            hook.interpolateMirrorRepoUrl(repo, "http://host/${.something()}/${repository.getName()}.git");
            Assert.fail("Exception not thrown");
        } catch (RuntimeException e) {
            assertEquals("Failed to interpolate expression ${.something()} java.lang.NoSuchFieldException: Object not referenced",e.getMessage() );
        }
        try {
            hook.interpolateMirrorRepoUrl(repo, "http://host/${getProject().getKey()}/${repository.getName()}.git");
            Assert.fail("Exception not thrown");
        } catch (RuntimeException e) {
            assertEquals("Failed to interpolate expression ${getProject().getKey()} java.lang.NoSuchFieldException: Unknown object getProject",e.getMessage());
        }
        try {
            hook.interpolateMirrorRepoUrl(repo, "http://host/${norepo.getProject().getKey()}/${repository.getName()}.git");
            Assert.fail("Exception not thrown");
        } catch (RuntimeException e) {
            assertEquals("Failed to interpolate expression ${norepo.getProject().getKey()} java.lang.NoSuchFieldException: Unknown object norepo",e.getMessage());
        }
        try {
            hook.interpolateMirrorRepoUrl(repo, "http://host/${repository.getProject().getKey()}/${repository.getSomething()}.git");
        } catch (RuntimeException e) {
            Assert.assertTrue(e.getMessage().startsWith("Failed to interpolate expression ${repository.getSomething()} java.lang.NoSuchMethodException:"));
        }
        try {
            hook.interpolateMirrorRepoUrl(repo, "http://host/${repository.field}.git");
            Assert.fail("Exception not thrown");
        } catch (RuntimeException e) {
            assertEquals("Failed to interpolate expression ${repository.field} java.lang.NoSuchMethodException: Not repository method specified",e.getMessage());
        }
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
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_REST_API_URL), eq(""))).thenReturn(restApiURL);
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_PRIVATE_TOKEN), eq(""))).thenReturn(privateToken);

        return settings;
    }
}

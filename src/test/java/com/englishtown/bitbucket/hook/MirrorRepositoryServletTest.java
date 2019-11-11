package com.englishtown.bitbucket.hook;

import com.atlassian.bitbucket.hook.repository.RepositoryHookService;
import com.atlassian.bitbucket.i18n.I18nService;
import com.atlassian.bitbucket.project.Project;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.bitbucket.setting.Settings;
import com.atlassian.soy.renderer.SoyException;
import com.atlassian.soy.renderer.SoyTemplateRenderer;
import com.atlassian.utils.process.StringOutputHandler;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

//import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
/**
 * Unit tests for {@link MirrorRepositoryServlet}.
 */

public class MirrorRepositoryServletTest {
    private final String mirrorRepoUrlHttp = "https://bitbucket-mirror.englishtown.com/scm/test/test.git";
    //private final String mirrorRepoUrlSsh = "ssh://git@bitbucket-mirror.englishtown.com/scm/test/test.git";
    private final String password = "test-password";
    //private final String refspec = "+refs/heads/master:refs/heads/master +refs/heads/develop:refs/heads/develop";
    //private final String username = "test-user";
    private final String restApiURL = "http://bitbucket-mirror.englishtown.com/api";
    private final String privateToken = "123ASD";

    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule().silent();

    @Mock
    private I18nService i18nService;
    @Mock
    private MirrorBucketProcessor pushProcessor;
    @Mock
    private MirrorRemoteAdmin mirrorRemoteAdmin;
    @Mock
    private MirrorRepositoryHook mirrorRepositoryHook;
    @Mock
    private RepositoryHookService repositoryHookService;
    @Mock
    private RepositoryService repositoryService;
    @Mock
    private SoyTemplateRenderer soyTemplateRenderer;
    @Captor
    private ArgumentCaptor<Map<String,Object>> templateData;
    @Mock
    private HttpServletRequest req;
    @Mock
    private HttpServletResponse resp;
    @Mock
    private Repository repo;

    private MirrorRepositoryServlet mirrorRepositoryServlet;

    @Before
    public void setup() {

        mirrorRepositoryServlet = new MirrorRepositoryServlet(
                i18nService,
                pushProcessor,
                mirrorRemoteAdmin,
                mirrorRepositoryHook,
                repositoryService,
                soyTemplateRenderer);

        when(repo.getName()).thenReturn("test");
        Project project = mock(Project.class);
        when(project.getKey()).thenReturn("PROJECT");
        when(repo.getProject()).thenReturn(project);
        when(repositoryService.getBySlug(eq("PROJECT"),eq("test"))).thenReturn(repo);

    }

    @Test
    public void testDoGetWrongConfig() throws IOException, ServletException, SoyException {
        doNothing().when(resp).sendError(eq(HttpServletResponse.SC_NOT_FOUND));

        when(req.getPathInfo()).thenReturn("wrongPath");
        mirrorRepositoryServlet.doGet(req,resp);
        verifyZeroInteractions(repositoryService);

        when(req.getPathInfo()).thenReturn("projects/PROJECT/repos/unknown_repo");
        mirrorRepositoryServlet.doGet(req,resp);
        verify(repositoryService,times(1)).getBySlug(ArgumentMatchers.any(String.class),ArgumentMatchers.any(String.class));
        verifyZeroInteractions(repositoryHookService);
        }

    @SuppressWarnings("unchecked")
    @Test
    public void testDoGet() throws IOException, ServletException, SoyException {
        Settings settings=defaultSettings();
        when(mirrorRepositoryHook.getPluginSettings(repo)).thenReturn(settings);
        when(mirrorRepositoryHook.interpolateMirrorRepoUrl(ArgumentMatchers.any(Repository.class),eq(mirrorRepoUrlHttp))).thenReturn(mirrorRepoUrlHttp);
        doThrow(new RuntimeException("Object not referenced")).when(mirrorRepositoryHook).interpolateMirrorRepoUrl(ArgumentMatchers.any(Repository.class),eq("wrongUrl"));

        when(req.getMethod()).thenReturn("GET");
        when(req.getPathInfo()).thenReturn("projects/PROJECT/repos/test");
        mirrorRepositoryServlet.doGet(req,resp);
        verify(soyTemplateRenderer, times(1)).render(eq(null),
                eq("com.englishtown.stash-hook-mirror:mirror-hook-action-form"),
                eq("com.englishtown.bitbucket.hook.action"), templateData.capture());

        templateData.getValue().forEach((k, v) -> System.out.println("data " + k + " : " + v));
        System.out.println(templateData.getValue().keySet());
        Assert.assertTrue(templateData.getValue().containsKey("config"));
        Assert.assertTrue(templateData.getValue().containsKey("errors"));
        Assert.assertTrue(templateData.getValue().containsKey("repository"));
        Assert.assertEquals(templateData.getValue().get("repository"),repo);
        Assert.assertTrue(((Map<String,String>)templateData.getValue().get("config")).containsKey("mirrorRepoUrl0"));
        Assert.assertTrue(((Map<String,String>)templateData.getValue().get("config")).containsKey("mirrorRepoUrl1"));
        Assert.assertTrue(((Map<String,String>)templateData.getValue().get("errors")).containsKey("mirrorRepoUrl1"));
    }
    @Test
    public void testDoPost() throws IOException, ServletException {
        Settings settings=defaultSettings();
        when(mirrorRepositoryHook.getPluginSettings(repo)).thenReturn(settings);
        when(mirrorRepositoryHook.interpolateMirrorRepoUrl(ArgumentMatchers.any(Repository.class),eq(mirrorRepoUrlHttp))).thenReturn(mirrorRepoUrlHttp);
        doThrow(new RuntimeException("Object not referenced")).when(mirrorRepositoryHook).interpolateMirrorRepoUrl(ArgumentMatchers.any(Repository.class),eq("wrongUrl"));
        MirrorSettings ms0=new MirrorSettings();
        MirrorSettings ms1=new MirrorSettings();
        List<MirrorSettings> mirrorSettingsList=new ArrayList<>(Arrays.asList(ms0,ms1));
        ms0.mirrorRepoUrl=mirrorRepoUrlHttp;
        ms0.restApiURL=restApiURL;
        ms0.password=password;
        ms0.privateToken=privateToken;
        ms0.suffix="0";
        ms1.mirrorRepoUrl="wrongUrl";
        ms1.restApiURL=restApiURL;
        ms1.password=password;
        ms1.privateToken=privateToken;
        ms1.suffix="1";
        when(mirrorRepositoryHook.getMirrorSettings(any(Settings.class))).thenReturn(mirrorSettingsList);

        when(req.getMethod()).thenReturn("POST");
        when(req.getPathInfo()).thenReturn("projects/PROJECT/repos/test");
        mirrorRepositoryServlet.doPost(req,resp);
        verify(mirrorRemoteAdmin,times(0)).delete(any(MirrorSettings.class),any(Repository.class),any(StringOutputHandler.class));
        verify(pushProcessor,times(0)).runMirrorCommand(any(MirrorSettings.class),any(Repository.class),any(PasswordHandler.class));

        doThrow(new RuntimeException("Cannot delete")).when(mirrorRemoteAdmin).delete(eq (ms0),any(Repository.class),any(StringOutputHandler.class));
        when(req.getParameter( eq("delete0" ))).thenReturn("Anything");
        when(req.getParameter( eq("delete1" ))).thenReturn("Anything");
        when(req.getParameter( eq("trigger0" ))).thenReturn("Anything");
        when(req.getParameter( eq("trigger1" ))).thenReturn("Anything");
        mirrorRepositoryServlet.doPost(req,resp);
        verify(mirrorRemoteAdmin,times(2)).delete(any(MirrorSettings.class),any(Repository.class),any(StringOutputHandler.class));
        verify(pushProcessor,times(1)).runMirrorCommand(any(MirrorSettings.class),any(Repository.class),any(StringOutputHandler.class));
    }
    private Settings defaultSettings() {
        Map<String, Object> map = new HashMap<>();
        map.put(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL+"0", mirrorRepoUrlHttp);
        map.put(MirrorRepositoryHook.SETTING_PASSWORD+"0", password);
        map.put(MirrorRepositoryHook.SETTING_REST_API_URL+"0", restApiURL);
        map.put(MirrorRepositoryHook.SETTING_PRIVATE_TOKEN+"0", privateToken);
        map.put(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL+"1", "wrongUrl");
        map.put(MirrorRepositoryHook.SETTING_PASSWORD+"1", password);
        map.put(MirrorRepositoryHook.SETTING_REST_API_URL+"1", restApiURL);
        map.put(MirrorRepositoryHook.SETTING_PRIVATE_TOKEN+"1", privateToken);

        Settings settings = mock(Settings.class);
        when(settings.asMap()).thenReturn(map);
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL+"0"), eq(""))).thenReturn(mirrorRepoUrlHttp);
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL+"0"))).thenReturn(mirrorRepoUrlHttp);
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_PASSWORD+"0"), eq(""))).thenReturn(password);
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_PASSWORD+"0"))).thenReturn(password);
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_REST_API_URL+"0"), eq(""))).thenReturn(restApiURL);
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_REST_API_URL+"0"))).thenReturn(restApiURL);
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_PRIVATE_TOKEN+"0"), eq(""))).thenReturn(privateToken);
        when(settings.getString(eq(MirrorRepositoryHook.SETTING_PRIVATE_TOKEN+"0"))).thenReturn(privateToken);

        return settings;
    }

}

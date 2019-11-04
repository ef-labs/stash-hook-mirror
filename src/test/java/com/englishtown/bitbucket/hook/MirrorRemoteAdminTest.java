package com.englishtown.bitbucket.hook;

import com.atlassian.bitbucket.i18n.I18nService;
import com.atlassian.bitbucket.project.Project;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.scm.CommandExitHandler;
import com.atlassian.utils.process.ProcessException;
import com.atlassian.utils.process.StringOutputHandler;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import javax.ws.rs.core.MediaType;

import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MirrorRemoteAdmin}.
 */
public class MirrorRemoteAdminTest {
    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule().silent();

    private MirrorRemoteAdmin mirrorRemoteAdmin;
    private CommandExitHandler exitHandler;
    //private PasswordHandler handler;
    private Repository repo;
    private Project project;
    @Mock
    private PasswordEncryptor passwordEncryptor;
    @Mock
    private I18nService i18nService;
    @Mock
    private StringOutputHandler handler;

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(wireMockConfig().dynamicPort().dynamicHttpsPort());

    @Before
    public void setup() {
        mirrorRemoteAdmin=new MirrorRemoteAdmin(passwordEncryptor,i18nService);
        exitHandler = mock(CommandExitHandler.class);
        //handler = new PasswordHandler("password", "privateToken", exitHandler);

        repo = mock(Repository.class);
        when(repo.getName()).thenReturn("test");
        project = mock(Project.class);
        when(project.getKey()).thenReturn("PROJECT");
        when(repo.getProject()).thenReturn(project);
        when(passwordEncryptor.decrypt(anyString())).then(AdditionalAnswers.returnsFirstArg());
    }
    @Test
    public void testDeleteNotConfigured() {
        MirrorSettings mirrorSettings = emptySettings();
        mirrorRemoteAdmin.delete(mirrorSettings, repo, handler);
    }

    @Test
    public void testDelete404() {
        MirrorSettings mirrorSettings = emptySettings();
        mirrorSettings.restApiURL = "http://localhost:" + wireMockRule.port();
        mirrorSettings.privateToken = "PRIVATETOKEN";

        stubFor(get(urlMatching(".*"))
                .willReturn(aResponse()
                        .withStatus(404)));

        try {
            mirrorRemoteAdmin.delete(mirrorSettings, repo, handler);
            Assert.fail("Exception not thrown");
        } catch (RuntimeException e) {
            assertEquals("Failed : HTTP error code : 404", e.getMessage());
        }
    }
    @Test
    public void testDeleteBrokenInputStream() throws ProcessException {

        MirrorSettings mirrorSettings = emptySettings();
        mirrorSettings.restApiURL = "http://localhost:" + wireMockRule.port();
        mirrorSettings.privateToken = "PRIVATETOKEN";

        stubFor(get(urlMatching(".*"))
                .willReturn(aResponse()
                        .withStatus(404)));

        PasswordHandler brokenOutput = mock(PasswordHandler.class, withSettings().verboseLogging());
        doThrow(new ProcessException("Output porcessing exception")).when(brokenOutput).process(any(InputStream.class));

        try {
            mirrorRemoteAdmin.delete(mirrorSettings, repo, brokenOutput);
            Assert.fail("Exception not thrown");
        } catch (RuntimeException e) {
            assertEquals("Failed : HTTP error code : 404", e.getMessage());
        }
    }

    @Test
    public void testDeleteInvalidResponseData() {
        MirrorSettings mirrorSettings = emptySettings();
        mirrorSettings.restApiURL = "http://localhost:" + wireMockRule.port();
        mirrorSettings.privateToken = "PRIVATETOKEN";

        stubFor(get(urlEqualTo("/api/v4/projects?search=test&private_token=PRIVATETOKEN"))
                .withHeader("Accept", equalTo(MediaType.APPLICATION_JSON))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON)
                        .withBody("Not JSON data")));
        try {
            mirrorRemoteAdmin.delete(mirrorSettings, repo, handler);
            Assert.fail("Exception not thrown");
        } catch (RuntimeException e) {
            Assert.assertTrue(e.getMessage().startsWith("Failed : Invalid response data from"));
        }
    }
    @Test
    public void testDeleteNoRepo() {
        MirrorSettings mirrorSettings = emptySettings();
        mirrorSettings.restApiURL = "http://localhost:" + wireMockRule.port();
        mirrorSettings.privateToken = "PRIVATETOKEN";

        stubFor(get(urlEqualTo("/api/v4/projects?search=test&private_token=PRIVATETOKEN"))
                .withHeader("Accept", equalTo(MediaType.APPLICATION_JSON))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON)
                        .withBody("[{\"id\":1,\"path_with_namespace\":\"PROJECT/testing\"},{\"id\":3,\"path_with_namespace\":\"OTHER/test\"}]")));
        try {
            mirrorRemoteAdmin.delete(mirrorSettings, repo, handler);
            Assert.fail("Exception not thrown");
        } catch (RuntimeException e) {
            Assert.assertTrue(e.getMessage().startsWith("Remote repository not found"));
        }
    }
    @Test
    public void testDeleteNoPermissions() {
        MirrorSettings mirrorSettings = emptySettings();
        mirrorSettings.restApiURL = "http://localhost:" + wireMockRule.port();
        mirrorSettings.privateToken = "PRIVATETOKEN";

        stubFor(get(urlEqualTo("/api/v4/projects?search=test&private_token=PRIVATETOKEN"))
                .withHeader("Accept", equalTo(MediaType.APPLICATION_JSON))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON)
                        .withBody("[{\"id\":1,\"path_with_namespace\":\"PROJECT/testing\"},{\"id\":2,\"path_with_namespace\":\"PROJECT/test\"},{\"id\":3,\"path_with_namespace\":\"OTHER/test\"}]")));

        stubFor(delete(urlEqualTo("/api/v4/projects/2?private_token=PRIVATETOKEN"))
                .withHeader("Accept", equalTo(MediaType.APPLICATION_JSON))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withHeader("Content-Type", MediaType.TEXT_PLAIN)
                        .withBody("No permissions")));
        try {
            mirrorRemoteAdmin.delete(mirrorSettings,repo,handler);
            Assert.fail("Exception not thrown");
        } catch (RuntimeException e) {
            assertEquals("Failed : HTTP error code : 403", e.getMessage());
        }
    }

    @Test
    public void testDeleteSuccess() {
        MirrorSettings mirrorSettings = emptySettings();
        mirrorSettings.restApiURL = "http://localhost:" + wireMockRule.port();
        mirrorSettings.privateToken = "PRIVATETOKEN";

        stubFor(get(urlEqualTo("/api/v4/projects?search=test&private_token=PRIVATETOKEN"))
            .withHeader("Accept", equalTo(MediaType.APPLICATION_JSON))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", MediaType.APPLICATION_JSON)
                .withBody("[{\"id\":1,\"path_with_namespace\":\"PROJECT/testing\"},{\"id\":2,\"path_with_namespace\":\"PROJECT/test\"},{\"id\":3,\"path_with_namespace\":\"OTHER/test\"}]")));

        stubFor(delete(urlEqualTo("/api/v4/projects/2?private_token=PRIVATETOKEN"))
                .withHeader("Accept", equalTo(MediaType.APPLICATION_JSON))
                .willReturn(aResponse()
                        .withStatus(202)));

        mirrorRemoteAdmin.delete(mirrorSettings,repo,handler);
    }

    private MirrorSettings emptySettings() {
        MirrorSettings ms=new MirrorSettings();
        ms.mirrorRepoUrl="";
        ms.username="";
        ms.password="";
        ms.suffix="0";
        ms.refspec="";
        ms.tags=false;
        ms.notes=false;
        ms.atomic=false;
        ms.restApiURL="";
        ms.privateToken="";
        return ms;
    }
}
package com.englishtown.bitbucket.hook;

import com.atlassian.bitbucket.i18n.I18nService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.bitbucket.scm.git.command.GitCommandExitHandler;
import com.atlassian.bitbucket.setting.Settings;
import com.atlassian.soy.renderer.SoyException;
import com.atlassian.soy.renderer.SoyTemplateRenderer;
import com.atlassian.utils.process.StringOutputHandler;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

public class MirrorRepositoryServlet extends HttpServlet {
    private static final Logger log = LoggerFactory.getLogger(MirrorRepositoryServlet.class);

    private final I18nService i18nService;
    private final MirrorBucketProcessor pushProcessor;
    private final MirrorRemoteAdmin mirrorRemoteAdmin;
    private final MirrorRepositoryHook mirrorRepositoryHook;
    private final RepositoryService repositoryService;
    private final SoyTemplateRenderer soyTemplateRenderer;

    @SuppressWarnings("WeakerAccess")
    public MirrorRepositoryServlet(I18nService i18nService,
                                   MirrorBucketProcessor pushProcessor,
                                   MirrorRemoteAdmin mirrorRemoteAdmin,
                                   MirrorRepositoryHook mirrorRepositoryHook,
                                   RepositoryService repositoryService,
                                   SoyTemplateRenderer soyTemplateRenderer
    ) {
        this.i18nService = i18nService;
        this.pushProcessor = pushProcessor;
        this.mirrorRemoteAdmin = mirrorRemoteAdmin;
        this.mirrorRepositoryHook = mirrorRepositoryHook;
        this.repositoryService = repositoryService;
        this.soyTemplateRenderer = soyTemplateRenderer;
    }

    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        doContinue(req,resp);
    }

    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        doContinue(req,resp);
    }

    private void doContinue(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        Repository repository = getRepositoryFromRequest(req);
        if (repository == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        Settings settings = mirrorRepositoryHook.getPluginSettings(repository);

        Map<String, Object> allSettings = Maps.newHashMap(settings.asMap());
        Map<String, Iterable<String>> configErrors = new HashMap<>();
        for (String key : allSettings.keySet()) {
            if (key.startsWith(MirrorRepositoryHook.SETTING_MIRROR_REPO_URL)) {
                try {
                    allSettings.replace(key, mirrorRepositoryHook.interpolateMirrorRepoUrl(repository, (String) allSettings.get(key)));
                } catch (Exception e) {
                    configErrors.put(key, new ArrayList<>(Collections.singletonList(e.getMessage())));
                }
            }
        }

        if(req.getMethod().equals("POST")) {
            Map<String, String[]> map = req.getParameterMap();
            map.forEach((k, v) -> Arrays.stream(v).forEach((s) -> log.debug(k + " : " + s)));

            List<MirrorSettings> mirrorSettingsList = mirrorRepositoryHook.getMirrorSettings(settings);
            for (MirrorSettings mirrorSettings : mirrorSettingsList) {

                if (req.getParameter("delete" + mirrorSettings.suffix) != null && !mirrorSettings.restApiURL.isEmpty()) {
                    StringOutputHandler outputHandler=new StringOutputHandler();
                    try {
                        log.debug("Delete mirror for " + mirrorSettings.restApiURL);
                        mirrorRemoteAdmin.delete(mirrorSettings, repository, outputHandler);
                        allSettings.put("stdout" + mirrorSettings.suffix, outputHandler.getOutput());
                    } catch (Exception e) {
                        log.debug("Deleting Mirroring failed with " + e.getMessage());
                        allSettings.put("stderr" + mirrorSettings.suffix, e.getMessage() + "\n" + outputHandler.getOutput());
                    }
                }
                if (req.getParameter("trigger" + mirrorSettings.suffix) != null) {
                    StringOutputHandler outputHandler=new StringOutputHandler();
                    try {
                        mirrorSettings.mirrorRepoUrl = mirrorRepositoryHook.interpolateMirrorRepoUrl(repository, mirrorSettings.mirrorRepoUrl);
                        log.debug("Trigger mirror for " + mirrorSettings.mirrorRepoUrl);
                        pushProcessor.runMirrorCommand(mirrorSettings, repository, outputHandler);
                        allSettings.put("stdout" + mirrorSettings.suffix, outputHandler.getOutput());
                    } catch (Exception e) {
                        log.debug("Mirroring failed with " + e);
                        allSettings.put("stderr" + mirrorSettings.suffix, e + "\n" + outputHandler.getOutput());
                    }
                }
            }
        }

        resp.setContentType("text/html;charset=UTF-8");
        try {
            soyTemplateRenderer.render(resp.getWriter(), "com.englishtown.stash-hook-mirror:mirror-hook-action-form",
                    "com.englishtown.bitbucket.hook.action",
                    ImmutableMap
                            .<String, Object>builder()
                            .put("config", allSettings)
                            .put("errors", configErrors)
                            .put("repository", repository)
                            .build()
            );
        } catch (
                SoyException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new ServletException(e);
        }
    }

    private Repository getRepositoryFromRequest(HttpServletRequest req) {
        String pathInfo = req.getPathInfo();
        String[] pathParts = pathInfo.substring(1).split("/");
        if (pathParts.length != 4) {
            return null;
        }
        String projectKey = pathParts[1];
        String repoSlug = pathParts[3];

        return repositoryService.getBySlug(projectKey, repoSlug);
    }
}
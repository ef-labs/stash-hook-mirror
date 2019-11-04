package com.englishtown.bitbucket.hook;

import com.atlassian.bitbucket.hook.repository.RepositoryHook;
import com.atlassian.bitbucket.hook.repository.RepositoryHookService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.scope.Scopes;
import com.atlassian.plugin.PluginParseException;
import com.atlassian.plugin.web.Condition;

import java.util.Map;

public class MirrorRepositoryHasConfigurationCondition implements Condition {

    public static final String REPOSITORY = "repository";
    private final RepositoryHookService repositoryHookService;

    public MirrorRepositoryHasConfigurationCondition(RepositoryHookService repositoryHookService) {
        this.repositoryHookService = repositoryHookService;
    }

    @Override
    public void init(Map<String, String> map) throws PluginParseException {
    }

    @Override
    public boolean shouldDisplay(Map<String, Object> context) {
        Repository repository = (Repository) context.get(REPOSITORY);
        RepositoryHook repositoryHook = repositoryHookService.getByKey(Scopes.repository(repository), "com.englishtown.stash-hook-mirror:mirror-repository-hook");
        if (repositoryHook == null) {
            /* Hook not installed. How do I get here :)? */
            return false;
        }
        return repositoryHook.isConfigured() && repositoryHook.isEnabled();
    }
}

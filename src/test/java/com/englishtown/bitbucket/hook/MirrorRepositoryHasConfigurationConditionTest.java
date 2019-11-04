package com.englishtown.bitbucket.hook;

import com.atlassian.bitbucket.hook.repository.RepositoryHook;
import com.atlassian.bitbucket.hook.repository.RepositoryHookService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.scope.RepositoryScope;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class MirrorRepositoryHasConfigurationConditionTest {
    @Test
    public void testConditionConfigured() {
        RepositoryHookService repositoryHookService = mock(RepositoryHookService.class);
        RepositoryHook repositoryHook = mock(RepositoryHook.class);
        MirrorRepositoryHasConfigurationCondition condition = new MirrorRepositoryHasConfigurationCondition(repositoryHookService);
        condition.init(Collections.EMPTY_MAP);
        Repository repo = mock(Repository.class);
        Map<String, Object> context = Collections.singletonMap("repository", repo);

        Assert.assertFalse(condition.shouldDisplay(context));

        when(repositoryHookService.getByKey(any(RepositoryScope.class), eq("com.englishtown.stash-hook-mirror:mirror-repository-hook"))).thenReturn(repositoryHook);
        Assert.assertFalse(condition.shouldDisplay(context));

        when(repositoryHook.isConfigured()).thenReturn(true);
        when(repositoryHook.isEnabled()).thenReturn(false);
        Assert.assertFalse(condition.shouldDisplay(context));

        when(repositoryHook.isConfigured()).thenReturn(true);
        when(repositoryHook.isEnabled()).thenReturn(true);
        Assert.assertTrue(condition.shouldDisplay(context));
    }
}
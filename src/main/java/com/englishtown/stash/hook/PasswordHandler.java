package com.englishtown.stash.hook;

import com.atlassian.stash.scm.CommandErrorHandler;
import com.atlassian.stash.scm.CommandExitHandler;
import com.atlassian.stash.scm.CommandOutputHandler;
import com.atlassian.utils.process.StringOutputHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Handles removing passwords from output text
 */
class PasswordHandler extends StringOutputHandler
        implements CommandOutputHandler<String>, CommandErrorHandler, CommandExitHandler {

    private final String target;
    private final CommandExitHandler exitHandler;

    private static final String PASSWORD_REPLACEMENT = ":*****@";

    public PasswordHandler(String password, CommandExitHandler exitHandler) {
        this.exitHandler = exitHandler;
        this.target = ":" + password + "@";
    }

    public String cleanText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replace(target, PASSWORD_REPLACEMENT);
    }

    @Override
    public String getOutput() {
        return cleanText(super.getOutput());
    }

    @Override
    public void onCancel(@Nonnull String command, int exitCode, @Nullable String stdErr, @Nullable Throwable thrown) {
        exitHandler.onCancel(cleanText(command), exitCode, cleanText(stdErr), thrown);
    }

    @Override
    public void onExit(@Nonnull String command, int exitCode, @Nullable String stdErr, @Nullable Throwable thrown) {
        exitHandler.onExit(cleanText(command), exitCode, cleanText(stdErr), thrown);
    }

}


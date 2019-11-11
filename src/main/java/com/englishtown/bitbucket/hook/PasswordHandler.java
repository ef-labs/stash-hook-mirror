package com.englishtown.bitbucket.hook;

import com.atlassian.bitbucket.scm.CommandErrorHandler;
import com.atlassian.bitbucket.scm.CommandExitHandler;
import com.atlassian.bitbucket.scm.CommandOutputHandler;
import com.atlassian.utils.process.ProcessException;
import com.atlassian.utils.process.StringOutputHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;

/**
 * Handles removing passwords from output text
 */
class PasswordHandler extends StringOutputHandler
        implements CommandOutputHandler<String>, CommandErrorHandler, CommandExitHandler {

    private final String passwordText;
    private final String privateTokenText;
    private final CommandExitHandler exitHandler;

    private static final String PASSWORD_REPLACEMENT = ":*****@";
    private static final String TOKEN_REPLACEMENT = "*****";

    public PasswordHandler(String password, String privateToken, CommandExitHandler exitHandler) {
        this.exitHandler = exitHandler;
        this.passwordText = ":" + password + "@";
        this.privateTokenText = privateToken;
    }

    public String cleanText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String truncatedText=text.replace(passwordText, PASSWORD_REPLACEMENT);
        if(!privateTokenText.isEmpty()) {
            truncatedText=truncatedText.replace(privateTokenText,TOKEN_REPLACEMENT);
        }
        return truncatedText;
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


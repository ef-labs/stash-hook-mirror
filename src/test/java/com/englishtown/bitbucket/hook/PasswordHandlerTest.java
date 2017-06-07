package com.englishtown.bitbucket.hook;

import com.atlassian.bitbucket.scm.CommandExitHandler;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PasswordHandler}
 */
public class PasswordHandlerTest {

    @SuppressWarnings("FieldCanBeLocal")
    private final String password = "pwd@123";
    private final String secretText = "https://test.user:pwd@123@test.englishtown.com/scm/test/test.git";
    private final String cleanedText = "https://test.user:*****@test.englishtown.com/scm/test/test.git";
    private CommandExitHandler exitHandler;
    private PasswordHandler handler;

    @Before
    public void setup() throws Exception {
        exitHandler = mock(CommandExitHandler.class);
        handler = new PasswordHandler(password, exitHandler);
    }

    @Test
    public void testCleanText() throws Exception {

        String result = handler.cleanText(secretText);
        assertEquals(cleanedText, result);

    }

    @Test
    public void testGetOutput() throws Exception {

        String result = handler.getOutput();
        assertEquals("", result);

    }

    @Test
    public void testOnCancel() throws Exception {

        handler.onCancel(secretText, 0, secretText, new RuntimeException());
        verify(exitHandler).onCancel(eq(cleanedText), eq(0), eq(cleanedText), any(Throwable.class));

    }

    @Test
    public void testOnExit() throws Exception {

        handler.onExit(secretText, 0, secretText, new RuntimeException());
        verify(exitHandler).onExit(eq(cleanedText), eq(0), eq(cleanedText), any(Throwable.class));

    }
}

package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class SetupCredentialVerifierTest {
    private static final DefaultApplicationArguments ARGUMENTS = new DefaultApplicationArguments();
    private final AuthMapper users = mock(AuthMapper.class);

    @Test
    void generatedCredentialWorksOnceLoggedAndChangesAfterRestart(CapturedOutput output) {
        var verifier = new SetupCredentialVerifier("", users);
        verifier.run(ARGUMENTS);
        verifier.run(ARGUMENTS);
        var firstCredentials = generatedCredentials(output);
        assertEquals(1, firstCredentials.size());
        String first = firstCredentials.getFirst();
        assertDoesNotThrow(() -> verifier.verify(first));
        assertThrows(ApiException.class, () -> verifier.verify("wrong-setup-credential"));

        var restarted = new SetupCredentialVerifier("", users);
        restarted.run(ARGUMENTS);
        var credentials = generatedCredentials(output);
        assertEquals(2, credentials.size());
        assertNotEquals(first, credentials.getLast());
        assertDoesNotThrow(() -> restarted.verify(credentials.getLast()));
        assertThrows(ApiException.class, () -> restarted.verify(first));
    }

    @Test
    void explicitPropertyTakesPrecedenceWithoutLoggingIt(CapturedOutput output) {
        String configured = "explicit-configured-setup-credential";
        String environment = "environment-configured-setup-credential";
        context().withPropertyValues("agenteam.auth.setup-credential=" + configured, "AGENTEAM_SETUP_CREDENTIAL=" + environment)
            .run(application -> {
                assertNull(application.getStartupFailure());
                var verifier = application.getBean(SetupCredentialVerifier.class);
                verifier.run(ARGUMENTS);
                assertDoesNotThrow(() -> verifier.verify(configured));
                assertThrows(ApiException.class, () -> verifier.verify(environment));
            });
        verifyNoInteractions(users);
        assertFalse(output.getAll().contains(configured));
        assertFalse(output.getAll().contains(environment));
        assertEquals(List.of(), generatedCredentials(output));
    }

    @Test
    void environmentCredentialWorksWithoutAProfileOrExplicitProperty(CapturedOutput output) {
        String environment = "environment-configured-setup-credential";
        context().withPropertyValues("AGENTEAM_SETUP_CREDENTIAL=" + environment).run(application -> {
            assertNull(application.getStartupFailure());
            var verifier = application.getBean(SetupCredentialVerifier.class);
            verifier.run(ARGUMENTS);
            assertDoesNotThrow(() -> verifier.verify(environment));
        });
        verifyNoInteractions(users);
        assertFalse(output.getAll().contains(environment));
        assertEquals(List.of(), generatedCredentials(output));
    }

    @Test
    void initializedSystemDoesNotGenerateOrLogACredential(CapturedOutput output) {
        when(users.superAdminInitialized()).thenReturn(true);
        var verifier = new SetupCredentialVerifier("", users);
        verifier.run(ARGUMENTS);
        assertEquals(List.of(), generatedCredentials(output));
        assertFalse(output.getAll().contains("系统尚未初始化"));
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withBean(AuthMapper.class, () -> users)
            .withUserConfiguration(SetupCredentialVerifier.class);
    }

    private List<String> generatedCredentials(CapturedOutput output) {
        return Pattern.compile("本次启动的初始化凭据：([A-Za-z0-9_-]{43})").matcher(output.getAll())
            .results().map(match -> match.group(1)).toList();
    }
}

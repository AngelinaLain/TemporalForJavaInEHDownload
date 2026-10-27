package com.checker.controllers;

import com.checker.config.JwtTokenProvider;
import com.checker.service.AdminCredentialService;
import com.checker.service.LoginAttemptService;
import com.checker.service.TokenBlacklistService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthControllerTest {
    private final JwtTokenProvider tokens = new JwtTokenProvider("a-signing-secret-for-controller-tests-123456", 60_000);
    private final AdminCredentialService credentials = mock(AdminCredentialService.class);
    private final LoginAttemptService attempts = mock(LoginAttemptService.class);
    private final AuthController controller = new AuthController(tokens, credentials, attempts, mock(TokenBlacklistService.class));

    @Test
    void loginIssuesOnlyTheCurrentCredentialGeneration() {
        when(credentials.authenticate("admin", "new-password")).thenReturn(OptionalLong.of(4));
        AuthController.LoginRequest body = new AuthController.LoginRequest();
        body.setUsername("admin"); body.setPassword("new-password");
        var response = controller.login(body, request());
        assertEquals(200, response.getCode());
        assertEquals(4, tokens.getCredentialVersionFromToken(response.getData().get("token")));
        assertEquals("admin", response.getData().get("username"));
        assertEquals(2, response.getData().size());
    }

    @Test
    void changePasswordRequiresAuthenticationAndReturnsOnlyReloginMessage() {
        assertEquals(401, controller.changePassword(change(), request(), null).getCode());
        verifyNoInteractions(credentials);
        var response = controller.changePassword(change(), request(), principal());
        assertEquals(200, response.getCode());
        assertEquals(true, response.getData().get("relogin"));
        assertFalse(response.getData().toString().contains("new-password"));
        verify(credentials).changePassword("admin", "old-password", "new-password", "new-password");
    }

    @Test
    void wrongCurrentPasswordUsesSeparateRateLimitWithoutLoggingUserOut() {
        doThrow(new AdminCredentialService.InvalidCurrentPasswordException()).when(credentials)
                .changePassword("admin", "old-password", "new-password", "new-password");
        assertEquals(400, controller.changePassword(change(), request(), principal()).getCode());
        verify(attempts).recordFailure("password-change:admin", "password-change:127.0.0.1");
        when(attempts.isBlocked("password-change:admin", "password-change:127.0.0.1")).thenReturn(true);
        assertEquals(429, controller.changePassword(change(), request(), principal()).getCode());
        verify(credentials, times(1)).changePassword(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void anonymousCallsAreBlockedByMethodSecurityBeforePasswordService() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(MethodAuthorization.class);
            context.registerBean(AuthController.class, () -> controller);
            context.refresh();
            assertThrows(AuthenticationCredentialsNotFoundException.class,
                    () -> context.getBean(AuthController.class).changePassword(change(), request(), principal()));
            verifyNoInteractions(credentials);
        }
    }

    @Configuration
    @EnableMethodSecurity
    static class MethodAuthorization {}

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
    private static UsernamePasswordAuthenticationToken principal() {
        return new UsernamePasswordAuthenticationToken("admin", null, java.util.List.of());
    }
    private static AuthController.ChangePasswordRequest change() {
        AuthController.ChangePasswordRequest request = new AuthController.ChangePasswordRequest();
        request.setCurrentPassword("old-password"); request.setNewPassword("new-password"); request.setConfirmPassword("new-password");
        return request;
    }
}

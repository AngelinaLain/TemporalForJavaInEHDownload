package com.checker.controllers;

import com.checker.service.KomgaIdRefreshService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KomgaIdRefreshControllerTest {
    @Test void startStatusAndCancelUseTheSameStateEnvelopeAndTranslateValidationErrors() {
        KomgaIdRefreshService service = mock(KomgaIdRefreshService.class);
        KomgaIdRefreshController controller = new KomgaIdRefreshController(service);
        Map<String, Object> state = Map.of("running", true, "jobId", "job");
        when(service.start(List.of(42L))).thenReturn(state);
        when(service.status()).thenReturn(state); when(service.cancel(null)).thenReturn(state);
        assertSame(state, controller.start(new KomgaIdRefreshController.RefreshRequest(List.of(42L))).getData());
        assertSame(state, controller.status().getData()); assertSame(state, controller.cancel(null).getData());
        when(service.start(null)).thenThrow(new IllegalStateException("running"));
        assertEquals(409, controller.start(null).getCode());
        when(service.start(List.of(0L))).thenThrow(new IllegalArgumentException("bad gid"));
        assertEquals(400, controller.start(new KomgaIdRefreshController.RefreshRequest(List.of(0L))).getCode());
    }

    @Test void anonymousCallsAreDeniedByMethodSecurityBeforeStartingOrReadingAJob() {
        KomgaIdRefreshService service = mock(KomgaIdRefreshService.class);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(MethodSecurity.class);
            context.registerBean(KomgaIdRefreshController.class, () -> new KomgaIdRefreshController(service));
            context.refresh();
            KomgaIdRefreshController secured = context.getBean(KomgaIdRefreshController.class);
            assertThrows(AuthenticationCredentialsNotFoundException.class, () -> secured.start(null));
            assertThrows(AuthenticationCredentialsNotFoundException.class, secured::status);
            assertThrows(AuthenticationCredentialsNotFoundException.class, () -> secured.cancel(null));
            verifyNoInteractions(service);
        }
    }

    @Test void jsonScopeNeverCoercesFractionalOrStringIdsIntoDifferentGalleries() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertEquals(List.of(42L), json.readValue("{\"gids\":[42]}", KomgaIdRefreshController.RefreshRequest.class).gids());
        assertThrows(java.io.IOException.class, () -> json.readValue("{\"gids\":[42.9]}", KomgaIdRefreshController.RefreshRequest.class));
        assertThrows(java.io.IOException.class, () -> json.readValue("{\"gids\":[\"42\"]}", KomgaIdRefreshController.RefreshRequest.class));
        assertThrows(java.io.IOException.class, () -> json.readValue("{\"gids\":[9223372036854775808]}", KomgaIdRefreshController.RefreshRequest.class));
    }

    @Test void cancellationUsesTheDisplayedJobIdAndReturnsConflictWhenItChanged() {
        KomgaIdRefreshService service = mock(KomgaIdRefreshService.class);
        KomgaIdRefreshController controller = new KomgaIdRefreshController(service);
        when(service.cancel("previous-job")).thenThrow(new IllegalStateException("changed job"));
        assertEquals(409, controller.cancel(new KomgaIdRefreshController.CancelRequest("previous-job")).getCode());
        verify(service).cancel("previous-job");
        verify(service, never()).cancel(null);
    }

    @Configuration @EnableMethodSecurity static class MethodSecurity {}
}

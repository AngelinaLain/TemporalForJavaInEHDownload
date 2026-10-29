package com.checker.controllers;

import com.checker.common.DedupeSettings;
import com.checker.service.DedupeSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DedupeSettingsControllerTest {
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test void readingAndSavingReturnCurrentSettingsAndDefaultsInTheSameEnvelope() {
        DedupeSettingsService service = mock(DedupeSettingsService.class);
        DedupeSettings changed = new DedupeSettings(70, 92, 90, 12, 5, 85, 8);
        when(service.current()).thenReturn(changed);
        when(service.save(changed)).thenReturn(changed);
        DedupeSettingsController controller = new DedupeSettingsController(service);
        assertEquals(changed, controller.get().getData().get("settings"));
        assertEquals(DedupeSettings.defaults(), controller.get().getData().get("defaults"));
        var response = controller.save(json.valueToTree(changed));
        assertEquals(200, response.getCode());
        assertEquals(changed, response.getData().get("settings"));
        assertEquals(DedupeSettings.defaults(), response.getData().get("defaults"));
        verify(service).save(changed);
    }

    @Test void rejectsIncompleteUnknownAndCoercedNumbersBeforeAnyWrite() throws Exception {
        DedupeSettingsService service = mock(DedupeSettingsService.class);
        DedupeSettingsController controller = new DedupeSettingsController(service);
        for (String replacement : new String[]{"85.9", "\"85\"", "true", "null", "2147483648"}) {
            ObjectNode request = validRequest();
            request.set("autoMatchScore", json.readTree(replacement));
            assertEquals(400, controller.save(request).getCode(), replacement);
        }
        ObjectNode missing = validRequest();
        missing.remove("visualMinCoverage");
        assertEquals(400, controller.save(missing).getCode());
        assertEquals(400, controller.save(validRequest().put("sampleCount", 16)).getCode());
        assertEquals(400, controller.save(json.createArrayNode()).getCode());
        assertEquals(400, controller.save(null).getCode());
        verifyNoInteractions(service);
    }

    @Test void invalidRangesOrThresholdOrderDoNotWritePartialSettings() {
        DedupeSettingsService service = mock(DedupeSettingsService.class);
        DedupeSettingsController controller = new DedupeSettingsController(service);
        String[] fields = {"reviewScore", "autoMatchScore", "titleSimilarityPercent", "visualPageDistance",
                "visualMinMatchedPages", "visualMinCoverage", "visualQualityDelta"};
        int[] invalidValues = {39, 101, 69, 25, 1, 29, 31};
        for (int i = 0; i < fields.length; i++) {
            assertEquals(400, controller.save(validRequest().put(fields[i], invalidValues[i])).getCode(), fields[i]);
        }
        assertEquals(400, controller.save(validRequest().put("reviewScore", 85)).getCode());
        verifyNoInteractions(service);
    }

    @Test void bothEndpointsRequireAnAdministrator() {
        DedupeSettingsService service = mock(DedupeSettingsService.class);
        when(service.current()).thenReturn(DedupeSettings.defaults());
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(MethodSecurity.class);
            context.registerBean(DedupeSettingsController.class, () -> new DedupeSettingsController(service));
            context.refresh();
            DedupeSettingsController controller = context.getBean(DedupeSettingsController.class);
            assertThrows(AuthenticationCredentialsNotFoundException.class, controller::get);
            assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.save(validRequest()));
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("reader", "", "ROLE_USER"));
            assertThrows(AccessDeniedException.class, controller::get);
            assertThrows(AccessDeniedException.class, () -> controller.save(validRequest()));
            verifyNoInteractions(service);
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin", "", "ROLE_ADMIN"));
            assertEquals(200, controller.get().getCode());
            verify(service).current();
        }
    }

    private ObjectNode validRequest() {
        return json.valueToTree(DedupeSettings.defaults());
    }

    @Configuration @EnableMethodSecurity static class MethodSecurity {}
}

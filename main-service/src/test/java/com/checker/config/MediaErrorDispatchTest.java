package com.checker.config;

import com.checker.controllers.GalleryMediaController;
import com.checker.service.AdminCredentialService;
import com.checker.service.GalleryMediaService;
import com.checker.service.TokenBlacklistService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.ErrorPage;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Real servlet error dispatches are not simulated by standalone MockMvc tests. */
class MediaErrorDispatchTest {
    private static AnnotationConfigServletWebServerApplicationContext context;
    private static final String SECRET = "media-error-dispatch-test-signing-key-1234567890";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static String base;
    private static String token;

    @BeforeAll static void start() {
        context = new AnnotationConfigServletWebServerApplicationContext(WebFixture.class);
        base = "http://127.0.0.1:" + context.getWebServer().getPort();
        token = context.getBean(JwtTokenProvider.class).generateToken("admin");
    }

    @AfterAll static void stop() { if (context != null) context.close(); }

    @Test void mediaErrorsKeepTheirOriginalStatusInsteadOfBecomingAuthenticationFailures() throws Exception {
        for (int status : new int[]{400, 404, 429, 502}) {
            HttpResponse<String> response = get("/api/dashboard/gallery-media/" + status + "/cover", token);
            assertEquals(status, response.statusCode(), "media failure " + status + " must survive ERROR dispatch");
            assertFalse(response.body().contains("未登录"));
        }
        assertEquals(200, get("/api/dashboard/probe", token).statusCode(), "media errors do not revoke a valid JWT");
    }

    @Test void directRequestsStillRequireAValidSessionIncludingDirectErrorRoute() throws Exception {
        assertEquals(401, get("/api/dashboard/gallery-media/404/cover", null).statusCode());
        String expired = new JwtTokenProvider(SECRET, -60_000).generateToken("admin");
        assertEquals(401, get("/api/dashboard/gallery-media/404/cover", expired).statusCode());
        assertEquals(401, get("/api/dashboard/probe", null).statusCode());
        assertEquals(401, get("/error", null).statusCode(), "only container ERROR dispatch is public");
    }

    private static HttpResponse<String> get(String path, String jwt) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path));
        if (jwt != null) request.header("Authorization", "Bearer " + jwt);
        return HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class WebFixture {
        @Bean ServletWebServerFactory webServerFactory() {
            var factory = new TomcatServletWebServerFactory(0);
            factory.setAddress(java.net.InetAddress.getLoopbackAddress());
            factory.addErrorPages(new ErrorPage("/error"));
            return factory;
        }
        @Bean DispatcherServlet dispatcherServlet() { return new DispatcherServlet(); }
        @Bean DispatcherServletRegistrationBean dispatcherRegistration(DispatcherServlet servlet) {
            return new DispatcherServletRegistrationBean(servlet, "/");
        }
        @Bean DelegatingFilterProxyRegistrationBean securityRegistration() {
            var registration = new DelegatingFilterProxyRegistrationBean("springSecurityFilterChain");
            registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
            return registration;
        }
        @Bean FilterRegistrationBean<JwtAuthenticationFilter> jwtRegistration(JwtAuthenticationFilter filter) {
            var registration = new FilterRegistrationBean<>(filter);
            registration.setEnabled(false);
            return registration;
        }
        @Bean SecurityProperties securityProperties() { return new SecurityProperties(); }
        @Bean JwtTokenProvider tokens() { return new JwtTokenProvider(SECRET, 60_000); }
        @Bean AdminCredentialService credentials() {
            var credentials = mock(AdminCredentialService.class);
            when(credentials.isTokenCurrent(anyString(), eq(0L))).thenReturn(true);
            return credentials;
        }
        @Bean TokenBlacklistService blacklist() { return mock(TokenBlacklistService.class); }
        @Bean JwtAuthenticationFilter jwtFilter(JwtTokenProvider tokens, TokenBlacklistService blacklist,
                                                AdminCredentialService credentials) {
            return new JwtAuthenticationFilter(tokens, blacklist, credentials);
        }
        @Bean GalleryMediaService media() {
            var service = mock(GalleryMediaService.class);
            when(service.cover(anyLong())).thenAnswer(call -> {
                int code = ((Long) call.getArgument(0)).intValue();
                throw new ResponseStatusException(HttpStatus.valueOf(code), "preview unavailable");
            });
            return service;
        }
        @Bean GalleryMediaController controller(GalleryMediaService media) { return new GalleryMediaController(media); }
        @Bean ErrorFixture errorFixture() { return new ErrorFixture(); }
    }

    @RestController
    static class ErrorFixture {
        @GetMapping("/error") ResponseEntity<Map<String, Object>> error(HttpServletRequest request) {
            int status = (Integer) request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
            return ResponseEntity.status(status).body(Map.of("status", status, "message", "preview unavailable"));
        }
        @GetMapping("/api/dashboard/probe") Map<String, Object> probe() { return Map.of("ok", true); }
    }
}

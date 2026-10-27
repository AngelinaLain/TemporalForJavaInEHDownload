package com.checker.config;

import com.checker.service.AdminCredentialService;
import com.checker.service.TokenBlacklistService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtCredentialFilterTest {
    private final JwtTokenProvider tokens = new JwtTokenProvider("a-signing-secret-for-filter-tests-1234567890", 60_000);
    private final AdminCredentialService credentials = mock(AdminCredentialService.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokens, mock(TokenBlacklistService.class), credentials);

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void currentTokenAuthenticatesButEarlierCredentialGenerationDoesNot() throws Exception {
        when(credentials.isTokenCurrent("admin", 2)).thenReturn(true);
        when(credentials.isTokenCurrent("admin", 1)).thenReturn(false);
        filter.doFilter(request(tokens.generateToken("admin", 2)), new MockHttpServletResponse(),
                (request, response) -> assertEquals("admin", SecurityContextHolder.getContext().getAuthentication().getName()));
        // The stale-token branch must clear even a pre-existing context.
        filter.doFilter(request(tokens.generateToken("admin", 1)), new MockHttpServletResponse(),
                (request, response) -> assertNull(SecurityContextHolder.getContext().getAuthentication()));
    }

    @Test
    void credentialStoreFailureNeverAuthenticatesOrContinuesTheRequest() throws Exception {
        when(credentials.isTokenCurrent("admin", 0)).thenThrow(new DataAccessResourceFailureException("unavailable"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request(tokens.generateToken("admin")), response, chain);
        assertEquals(503, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(chain);
        assertFalse(response.getContentAsString().contains("unavailable"));
    }

    @Test
    void legacyTokensWithoutGenerationClaimAreBootstrapOnly() {
        String token = Jwts.builder().subject("admin").issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor("a-signing-secret-for-filter-tests-1234567890".getBytes(StandardCharsets.UTF_8)))
                .compact();
        assertTrue(tokens.validateToken(token));
        assertEquals(0, tokens.getCredentialVersionFromToken(token));
    }

    private static MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dashboard/stats");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}

package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the pure parts of the OAuth 2.0 Authorization Code + PKCE flow — challenge derivation,
 * authorization-URL construction, redirect parsing, and token-response parsing — without any network.
 */
class OAuth2AuthorizationCodeTest {

    @Test
    void s256ChallengeMatchesRfc7636TestVector() {
        // RFC 7636 Appendix B known-answer vector.
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
        assertEquals(expected, OAuth2AuthorizationCode.codeChallengeS256(verifier));
    }

    @Test
    void createPkceProducesUrlSafeUnpaddedVerifierAndMatchingChallenge() {
        OAuth2AuthorizationCode.Pkce pkce = OAuth2AuthorizationCode.createPkce();
        assertEquals("S256", pkce.method());
        // base64url(32 bytes) = 43 chars, no padding, URL-safe alphabet only.
        assertEquals(43, pkce.verifier().length());
        assertTrue(pkce.verifier().matches("[A-Za-z0-9_-]+"));
        assertFalse(pkce.verifier().contains("="));
        assertEquals(OAuth2AuthorizationCode.codeChallengeS256(pkce.verifier()), pkce.challenge());
    }

    @Test
    void twoPkcePairsDiffer() {
        assertNotEquals(OAuth2AuthorizationCode.createPkce().verifier(),
                OAuth2AuthorizationCode.createPkce().verifier());
    }

    @Test
    void authorizationUrlIncludesAllParamsAndEncodes() {
        OAuth2AuthorizationCode.Pkce pkce =
                new OAuth2AuthorizationCode.Pkce("verifier", "challengeXYZ", "S256");
        String url = OAuth2AuthorizationCode.buildAuthorizationUrl(
                "https://auth.example.com/authorize", "my-client",
                "https://app/callback", "openid profile", "xyz-state", pkce);

        assertTrue(url.startsWith("https://auth.example.com/authorize?"));
        assertTrue(url.contains("response_type=code"));
        assertTrue(url.contains("client_id=my-client"));
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fapp%2Fcallback"));
        assertTrue(url.contains("scope=openid+profile") || url.contains("scope=openid%20profile"));
        assertTrue(url.contains("state=xyz-state"));
        assertTrue(url.contains("code_challenge=challengeXYZ"));
        assertTrue(url.contains("code_challenge_method=S256"));
    }

    @Test
    void authorizationUrlPreservesExistingQuery() {
        String url = OAuth2AuthorizationCode.buildAuthorizationUrl(
                "https://auth.example.com/authorize?audience=api", "c", "", "", "", null);
        assertTrue(url.startsWith("https://auth.example.com/authorize?audience=api&"));
        assertFalse(url.contains("code_challenge"));   // pkce omitted
    }

    @Test
    void parseRedirectExtractsCodeAndState() {
        OAuth2AuthorizationCode.AuthCodeResult r = OAuth2AuthorizationCode.parseRedirect(
                "https://app/callback?code=abc123&state=xyz-state");
        assertFalse(r.isError());
        assertEquals("abc123", r.code());
        assertEquals("xyz-state", r.state());
    }

    @Test
    void parseRedirectSurfacesError() {
        OAuth2AuthorizationCode.AuthCodeResult r = OAuth2AuthorizationCode.parseRedirect(
                "https://app/callback?error=access_denied&error_description=User%20said%20no");
        assertTrue(r.isError());
        assertEquals("access_denied", r.error());
        assertEquals("User said no", r.errorDescription());
    }

    @Test
    void parseTokenResponseReadsTokensAndDefaults() throws Exception {
        OAuth2AuthorizationCode.TokenResponse t = OAuth2AuthorizationCode.parseTokenResponse(
                "{\"access_token\":\"AT\",\"refresh_token\":\"RT\",\"expires_in\":7200,\"scope\":\"read\"}");
        assertEquals("AT", t.accessToken());
        assertEquals("RT", t.refreshToken());
        assertEquals("Bearer", t.tokenType());   // defaulted
        assertEquals(7200, t.expiresIn());
        assertEquals("read", t.scope());
    }
}

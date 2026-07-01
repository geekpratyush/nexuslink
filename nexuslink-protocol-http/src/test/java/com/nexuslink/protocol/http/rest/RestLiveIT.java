package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live REST tests against the local {@code test-env} httpbin (go-httpbin) at localhost:8088.
 * <pre>docker compose -f test-env/docker-compose.yml up -d httpbin</pre>
 * Run with {@code -Dnexuslink.it=true}.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class RestLiveIT {

    private static final String BASE = "http://localhost:8088";

    @Test
    void getReturns200WithEchoedQuery() {
        RestExecutionService exec = new RestExecutionService();
        RestRequest req = new RestRequest();
        req.setMethod("GET");
        req.setUrl(BASE + "/get");
        req.getQueryParams().add(new RestRequest.KeyValue("nexus", "link"));

        RestResponse res = exec.execute(req);
        assertFalse(res.failed(), res.errorMessage());
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("\"nexus\""));
        assertTrue(res.body().contains("link"));
    }

    @Test
    void postJsonBodyIsEchoed() {
        RestExecutionService exec = new RestExecutionService();
        RestRequest req = new RestRequest();
        req.setMethod("POST");
        req.setUrl(BASE + "/post");
        req.setBodyType(RestRequest.BodyType.JSON);
        req.setBody("{\"hello\":\"nexus\"}");

        RestResponse res = exec.execute(req);
        assertFalse(res.failed(), res.errorMessage());
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("nexus"));
    }

    @Test
    void basicAuthSucceeds() {
        RestExecutionService exec = new RestExecutionService();
        RestRequest req = new RestRequest();
        req.setMethod("GET");
        req.setUrl(BASE + "/basic-auth/nexus/secret");
        req.setAuthType(RestRequest.AuthType.BASIC);
        req.setAuthUsername("nexus");
        req.setAuthPassword("secret");

        RestResponse res = exec.execute(req);
        assertFalse(res.failed(), res.errorMessage());
        assertEquals(200, res.statusCode());
    }
}

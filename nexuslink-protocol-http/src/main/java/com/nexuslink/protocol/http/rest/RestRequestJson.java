package com.nexuslink.protocol.http.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Rebuilds a {@link RestRequest} from the JSON a collection stores, applying {@code ${var}}
 * substitution as it goes.
 *
 * <p>The editor already knew how to write that JSON, but only the editor knew how to read it back —
 * which is fine for "open this request in the tab" and useless for anything that needs to
 * <em>send</em> a stored request without displaying it first. The collection runner needs exactly
 * that, so the reading half lives here, free of JavaFX and therefore testable.
 *
 * <p>Substitution is applied to the URL, parameter and header values, and the body, because those
 * are where a chained value ({@code ${token}}) is actually used.
 */
public final class RestRequestJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestRequestJson() {}

    /**
     * Parses stored request JSON.
     *
     * @param substitute applied to every user-supplied string; pass {@link UnaryOperator#identity()}
     *                   to keep {@code ${var}} references as they are
     * @return the request, or {@code null} when the JSON cannot be read
     */
    public static RestRequest parse(String json, UnaryOperator<String> substitute) {
        if (json == null || json.isBlank()) return null;
        UnaryOperator<String> sub = substitute == null ? UnaryOperator.identity() : substitute;
        try {
            JsonNode root = MAPPER.readTree(json);
            RestRequest request = new RestRequest();
            request.setMethod(root.path("method").asText("GET"));
            request.setUrl(sub.apply(root.path("url").asText("")));
            request.setBodyType(enumOrDefault(root.path("bodyType").asText("NONE"),
                    RestRequest.BodyType.class, RestRequest.BodyType.NONE));
            request.setBody(sub.apply(root.path("body").asText("")));

            readKeyValues(root.path("params"), request.getQueryParams(), sub);
            readKeyValues(root.path("headers"), request.getHeaders(), sub);

            request.setAuthType(enumOrDefault(root.path("authType").asText("NONE"),
                    RestRequest.AuthType.class, RestRequest.AuthType.NONE));
            request.setAuthUsername(sub.apply(root.path("authUsername").asText("")));
            request.setAuthToken(sub.apply(root.path("authToken").asText("")));
            request.setApiKeyName(sub.apply(root.path("apiKeyName").asText("")));
            request.setApiKeyValue(sub.apply(root.path("apiKeyValue").asText("")));
            request.setApiKeyLocation(enumOrDefault(root.path("apiKeyLocation").asText("HEADER"),
                    RestRequest.ApiKeyLocation.class, RestRequest.ApiKeyLocation.HEADER));

            for (JsonNode a : root.path("assertions")) {
                AssertionSpec spec = new AssertionSpec(
                        enumOrDefault(a.path("type").asText("STATUS_EQUALS"),
                                ResponseAssertions.Type.class, ResponseAssertions.Type.STATUS_EQUALS),
                        a.path("name").asText(""), sub.apply(a.path("target").asText("")),
                        a.path("max").asText(""));
                spec.setEnabled(a.path("enabled").asBoolean(true));
                request.getAssertions().add(spec);
            }
            return request;
        } catch (Exception e) {
            return null;
        }
    }

    /** The extraction rules stored with a request, or an empty list. */
    public static List<ResponseExtraction> extractionsOf(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return ResponseExtraction.parse(MAPPER.readTree(json).path("extractions").asText(""));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** The method and URL of a stored request, for a report line that names it before it is sent. */
    public static String describe(String json) {
        RestRequest request = parse(json, UnaryOperator.identity());
        return request == null ? "(unreadable request)" : request.getMethod() + " " + request.getUrl();
    }

    private static void readKeyValues(JsonNode array, List<RestRequest.KeyValue> target,
                                      UnaryOperator<String> sub) {
        for (JsonNode node : array) {
            RestRequest.KeyValue kv = new RestRequest.KeyValue(
                    node.path("key").asText(""), sub.apply(node.path("value").asText("")));
            kv.setEnabled(node.path("enabled").asBoolean(true));
            target.add(kv);
        }
    }

    private static <E extends Enum<E>> E enumOrDefault(String name, Class<E> type, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}

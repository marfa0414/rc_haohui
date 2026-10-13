package com.rc.notification.support;

import static com.rc.notification.support.NotificationApiFixture.*;
import static com.rc.notification.support.ConfigurationFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** CP06 separate Token/business network journals; Token request bytes and fingerprints are never evidence artifacts. */
public final class OAuthFixture implements AutoCloseable {
    public final HttpMock tokens;
    public final WorkerFixture worker;
    public final JsonNode template;
    public final JsonNode auth;

    public OAuthFixture(Map<String, Object> overrides, String method, Class<?>... sources) throws Exception {
        assertEquals("cp06 fake:+密钥", System.getenv("CP06_CLIENT_SECRET"));
        assertEquals("fixture-oauth-v1", System.getenv("CP06_CLIENT_SECRET_VERSION"));
        tokens = new HttpMock();
        worker = new WorkerFixture(overrides, List.of(tokens.uri("/token").getPort()), sources);
        var definition = template("tenant-a", "json", JSON.readTree("{\"event\":{\"from\":\"eventId\",\"type\":\"string\",\"required\":true}}"))
                .put("providerId", "cp06-provider").put("url", worker.mock.uri("/business").toString());
        definition.set("headers", JSON.readTree("{\"X-Event-Id\":{\"from\":\"eventId\",\"type\":\"string\"}}"));
        var authentication = (ObjectNode) JSON.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("examples/configuration/"
                + (method.equals("client_secret_basic") ? "crm-oauth-basic-auth.json" : "crm-oauth-post-auth.json"))));
        authentication.put("tokenUrl", tokens.uri("/token").toString()).put("clientId", "client: +中").put("clientSecretRef", "env:CP06_CLIENT_SECRET")
                .put("clientAuthenticationMethod", method);
        authentication.putArray("scopes").add("write").add("read").add("read");
        var installed = worker.configuration.install(definition, authentication, "oauth.notify");
        template = installed.template(); auth = installed.auth();
    }

    public UUID submit(String event) throws Exception {
        var response = worker.api.post(payload("tenant-a", "oauth.notify", event, "{}"), ADMIN);
        assertEquals(202, response.statusCode(), response.body());
        return UUID.fromString(worker.api.json(response).path("notificationId").asText());
    }

    public static String response(String token, long seconds) {
        return JSON.createObjectNode().put("access_token", token).put("token_type", "Bearer").put("expires_in", seconds).toString();
    }

    public void evidence(String scenario, UUID id) throws Exception {
        worker.evidence(scenario, id, null);
        var document = JSON.createObjectNode().put("scenario", scenario + "-authentication").put("observedAt", Instant.now().toString());
        var state = worker.task(id);
        document.put("taskId", id.toString()).put("runNumber", state.path("run_number").asInt())
                .put("recoveryUsed", state.path("auth_recovery_used").asBoolean())
                .put("recoveryRequired", state.path("auth_recovery_required").asBoolean());
        var requests = document.putArray("tokenCalls");
        for (var call : tokens.journal()) requests.addObject().put("receivedAt", call.receivedAt().toString())
                .put("method", call.method()).put("path", call.uri().getPath());
        var directory = java.nio.file.Path.of("target", "evidence");
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve(scenario + "-auth-" + id + ".json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n");
    }

    @Override public void close() throws Exception { try { worker.close(); } finally { tokens.close(); } }
}

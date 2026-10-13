package com.rc.notification.api;

import static com.rc.notification.support.ConfigurationFixture.oauth;
import static com.rc.notification.support.ConfigurationFixture.scalarTemplate;
import static com.rc.notification.support.NotificationApiFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.support.ConfigurationFixture;
import com.rc.notification.support.HttpMock;
import com.rc.notification.support.NotificationApiFixture;
import com.rc.notification.support.RealPostgres;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** TC-MAP-07: the entire inbound JSON envelope, not just data, is bounded in UTF-8 bytes. */
@Timeout(60)
class InboundExactSizeTest {
    private static final String EVENT = "inbound-size-000001";
    private static final String OPERATION = "inbound.exact-size";

    @ParameterizedTest(name = "inbound UTF-8 bytes = {0}")
    @ValueSource(ints = {262144, 262145})
    void enforcesTheExactByteBoundaryBeforePersistenceOrAnyOutboundRequest(int byteCount) throws Exception {
        try (var mock = new HttpMock()) {
            mock.route("/notify", exchange -> HttpMock.respond(exchange, 200, "unexpected-business-request"));
            mock.route("/token", exchange -> HttpMock.respond(exchange, 200,
                    "{\"access_token\":\"unexpected-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}"));
            try (var api = new NotificationApiFixture(new RealPostgres(), "api", Map.of(
                    "app.environment", "test",
                    "app.outbound.allowed-hosts", "127.0.0.1,supplier.invalid",
                    "app.outbound.development-http-hosts", "127.0.0.1",
                    "app.outbound.development-allowed-cidrs", "127.0.0.1/32",
                    "app.outbound.development-http-ports", Integer.toString(mock.uri("/notify").getPort())))) {
                var configuration = new ConfigurationFixture(api);
                var template = scalarTemplate("tenant-a", "small-output");
                template.put("url", mock.uri("/notify").toString());
                configuration.install(template, oauth("tenant-a", mock.uri("/token").toString()), OPERATION);

                String emptyEnvelope = payload("tenant-a", OPERATION, EVENT, "{\"padding\":\"\"}");
                int paddingBytes = byteCount - emptyEnvelope.getBytes(StandardCharsets.UTF_8).length;
                String padding = "中".repeat(paddingBytes / 3) + "x".repeat(paddingBytes % 3);
                String request = payload("tenant-a", OPERATION, EVENT, "{\"padding\":\"" + padding + "\"}");
                assertEquals(19, EVENT.length(), "The event identifier stays the same length in both cases");
                assertEquals(byteCount, request.getBytes(StandardCharsets.UTF_8).length,
                        "The bound applies to the complete serialized JSON request");
                assertTrue(request.length() < byteCount, "Multibyte Chinese padding detects character-count bugs");
                assertEquals(0, api.taskCount());
                assertTrue(mock.journal().isEmpty(), "Configuration preparation is offline");

                var response = api.post(request, ADMIN);
                if (byteCount == 262144) {
                    assertEquals(202, response.statusCode(), response.body());
                    UUID id = UUID.fromString(api.json(response).path("notificationId").asText());
                    assertEquals(1, api.taskCount());
                    assertArrayEquals("{\"marker\":\"small-output\"}".getBytes(StandardCharsets.UTF_8),
                            configuration.bodyBytes(id.toString()), "A small constant output isolates the inbound limit");
                    try (var connection = api.database.connection(); var query = connection.prepareStatement(
                            "SELECT event_id,status,run_attempt_count,total_attempt_count FROM "
                                    + api.database.schema() + ".notification_task WHERE id=?")) {
                        query.setObject(1, id);
                        try (var row = query.executeQuery()) {
                            assertTrue(row.next(), "The 202 refers to an independently visible committed task");
                            assertEquals(EVENT, row.getString("event_id"));
                            assertEquals("pending", row.getString("status"));
                            assertEquals(0, row.getInt("run_attempt_count"));
                            assertEquals(0, row.getLong("total_attempt_count"));
                            assertFalse(row.next());
                        }
                    }
                } else {
                    assertEquals(413, response.statusCode(), response.body());
                    assertEquals("PAYLOAD_TOO_LARGE", api.json(response).path("code").asText());
                    assertEquals(0, api.taskCount(), "The byte above the limit must not create any task");
                }
                try (var connection = api.database.connection(); var query = connection.createStatement();
                        var rows = query.executeQuery("SELECT count(*) FROM " + api.database.schema()
                                + ".notification_attempt")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getLong(1));
                }
                assertEquals(0, mock.count("/token"), "Submission must not obtain a token synchronously");
                assertEquals(0, mock.count("/notify"), "Submission must not contact the supplier synchronously");
            }
            assertTrue(mock.journal().isEmpty(), "API shutdown must not schedule a delayed outbound request");
        }
    }
}

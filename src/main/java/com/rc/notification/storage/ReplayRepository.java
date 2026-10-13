package com.rc.notification.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.notification.application.DeliveryProperties;
import com.rc.notification.application.ReplayService;
import com.rc.notification.application.TaskView;
import com.rc.notification.configuration.StoredConfigurationJson;
import com.rc.notification.security.CallerPrincipal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Called only inside ReplayService's task-lock transaction. */
@Repository
public class ReplayRepository {
    private static final ObjectMapper JSON = StoredConfigurationJson.newMapper();
    private final JdbcTemplate jdbc;
    public ReplayRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Saved(byte[] digest, ReplayService.Response response) { }

    public Optional<Saved> find(UUID id, String key) {
        return jdbc.query("SELECT request_digest,response_snapshot FROM replay_request WHERE task_id=? AND replay_request_id=?", (row, n) -> {
            try { return new Saved(row.getBytes("request_digest"), JSON.readValue(row.getString("response_snapshot"), ReplayService.Response.class)); }
            catch (JsonProcessingException invalid) { throw new IllegalStateException("Invalid saved replay response"); }
        }, id, key).stream().findFirst();
    }

    public int restart(UUID id, int expectedRun, DeliveryProperties defaults) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                WITH restart AS MATERIALIZED (SELECT clock_timestamp() AS at)
                UPDATE notification_task SET status='pending',run_number=run_number+1,run_attempt_count=0,
                    max_attempts=?,run_started_at=restart.at,deadline_at=restart.at + ? * interval '1 millisecond',
                    next_attempt_at=restart.at,defer_reason=NULL,lease_token=NULL,lease_owner=NULL,lease_until=NULL,
                    auth_recovery_used=false,auth_recovery_required=false,rejected_token_digest=NULL,updated_at=restart.at
                FROM restart WHERE id=? AND status='failed' AND run_number=? RETURNING run_number
                """, Integer.class, defaults.maxAttempts(), defaults.runDuration().toMillis(), id, expectedRun));
    }

    public void save(TaskView previous, String key, int expectedRun, String reason, byte[] digest,
                     ReplayService.Response response, CallerPrincipal caller) {
        String saved;
        try { saved = JSON.writeValueAsString(response); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Replay response could not be saved"); }
        int inserted = jdbc.update("""
                INSERT INTO replay_request(task_id,replay_request_id,request_digest,expected_run_number,result_run_number,
                    actor_id,reason,response_snapshot) VALUES(?,?,?,?,?,?,?,?::jsonb)
                """, previous.notificationId(), key, digest, expectedRun, response.runNumber(), caller.id(), reason, saved);
        if (inserted != 1) throw new IllegalStateException("Replay operation could not be persisted");
        String before = JSON.createObjectNode().put("runNumber", previous.runNumber()).put("status", previous.status()).toString();
        String after = JSON.createObjectNode().put("runNumber", response.runNumber()).put("status", response.status()).toString();
        int audited = jdbc.update("""
                INSERT INTO audit_event(id,actor_id,tenant_id,object_type,object_id,action,reason,before_summary,after_summary,request_key)
                VALUES(?,?,?,'notification',?,'REPLAY',?,?::jsonb,?::jsonb,?)
                """, UUID.randomUUID(), caller.id(), previous.tenantId(), previous.notificationId().toString(), reason, before, after, key);
        if (audited != 1) throw new IllegalStateException("Replay operation could not be audited");
    }
}

package com.rc.notification.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.notification.application.RequestSnapshot;
import com.rc.notification.configuration.StoredConfigurationJson;
import com.rc.notification.application.AttemptCursor;
import com.rc.notification.retry.RetryPolicy;
import com.rc.notification.worker.Attempt;
import com.rc.notification.worker.AttemptView;
import com.rc.notification.worker.Completion;
import com.rc.notification.worker.Lease;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** All ownership changes are short independent transactions; callers must never perform HTTP inside them. */
@Repository
public class DeliveryRepository {
    private static final Logger LOG = LoggerFactory.getLogger(DeliveryRepository.class);
    private static final ObjectMapper JSON = StoredConfigurationJson.newMapper();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RetryPolicy retryPolicy;
    private final MeterRegistry metrics;

    public DeliveryRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, RetryPolicy retryPolicy, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.retryPolicy = retryPolicy;
        this.metrics = metrics;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    public List<String> dueProviders(String after, int limit) {
        checkLimit(limit);
        return jdbc.queryForList("""
                SELECT DISTINCT provider_id FROM notification_task
                WHERE status='pending' AND next_attempt_at<=clock_timestamp() AND deadline_at>clock_timestamp()
                  AND run_attempt_count<max_attempts AND (CAST(? AS varchar) IS NULL OR provider_id>?)
                ORDER BY provider_id LIMIT ?
                """, String.class, after, after, limit);
    }

    /** T1: acquire ownership without consuming an attempt. */
    public Optional<Lease> claim(String providerId, String owner, Duration leaseDuration) {
        positive(leaseDuration);
        if (owner == null || owner.isBlank() || owner.length() > 128) throw new IllegalArgumentException("Invalid lease owner");
        return Objects.requireNonNull(transaction.execute(ignored -> {
            List<UUID> candidates = jdbc.query("""
                    SELECT id FROM notification_task WHERE provider_id=? AND status='pending'
                      AND next_attempt_at<=clock_timestamp() AND deadline_at>clock_timestamp()
                      AND run_attempt_count<max_attempts
                    ORDER BY next_attempt_at,created_at,id FOR UPDATE SKIP LOCKED LIMIT 1
                    """, (row, n) -> row.getObject(1, UUID.class), providerId);
            if (candidates.isEmpty()) return Optional.empty();
            Instant now = now();
            UUID token = UUID.randomUUID();
            // Recheck the deadline after obtaining the row lock, using a fresh database clock.
            return jdbc.query("""
                    UPDATE notification_task SET status='processing',lease_token=?,lease_owner=?,lease_until=?,
                        next_attempt_at=NULL,defer_reason=NULL,updated_at=?
                    WHERE id=? AND status='pending' AND deadline_at>clock_timestamp() AND run_attempt_count<max_attempts
                    RETURNING *
                    """, (row, n) -> lease(row), token, owner, ts(now.plus(leaseDuration)), ts(now), candidates.get(0))
                    .stream().findFirst();
        }));
    }

    /** T2: the counter and RESERVED history row either both commit or neither does. */
    public Optional<Attempt> reserve(Lease lease, Duration attemptTimeout) {
        positive(attemptTimeout);
        return Objects.requireNonNull(transaction.execute(ignored -> {
            TaskState state = lock(lease.taskId());
            Instant now = now();
            if (!owns(state, lease, now) || !state.deadline.isAfter(now) || state.count >= state.maximum) return Optional.empty();
            Integer open = jdbc.queryForObject("SELECT count(*) FROM notification_attempt WHERE task_id=? AND run_number=? AND ended_at IS NULL",
                    Integer.class, lease.taskId(), lease.runNumber());
            if (open != null && open != 0) return Optional.empty();
            int number = state.count + 1;
            Instant deadline = now.plus(attemptTimeout);
            int reserved = jdbc.update("""
                    UPDATE notification_task SET run_attempt_count=run_attempt_count+1,
                        total_attempt_count=total_attempt_count+1,updated_at=?
                    WHERE id=? AND run_number=? AND lease_token=? AND status='processing'
                        AND lease_until>clock_timestamp() AND deadline_at>clock_timestamp() AND run_attempt_count<max_attempts
                    """, ts(now), lease.taskId(), lease.runNumber(), lease.leaseToken());
            if (reserved == 0) return Optional.empty();
            one(reserved);
            one(jdbc.update("""
                    INSERT INTO notification_attempt(task_id,run_number,attempt_no,lease_token,started_at,
                        phase,outcome,execution_deadline) VALUES(?,?,?,?,?,'RESERVED','NOT_SENT',?)
                    """, lease.taskId(), lease.runNumber(), number, lease.leaseToken(), ts(now), ts(deadline)));
            Instant committedAt = now();
            if (!state.leaseUntil.isAfter(committedAt) || !state.deadline.isAfter(committedAt) || !deadline.isAfter(committedAt)) {
                ignored.setRollbackOnly();
                return Optional.empty();
            }
            return Optional.of(new Attempt(lease.taskId(), lease.runNumber(), number, lease.leaseToken(), now, deadline));
        }));
    }

    /** T3: a durable send intent, not proof that any request reached the network. */
    public boolean markRequestStarted(Lease lease, Attempt attempt, String credentialVersion) {
        if (credentialVersion == null || credentialVersion.isBlank() || credentialVersion.length() > 128) {
            throw new IllegalArgumentException("Invalid credential version");
        }
        return Boolean.TRUE.equals(transaction.execute(ignored -> {
            TaskState state = lock(lease.taskId());
            Instant now = now();
            if (!owns(state, lease, now) || !same(lease, attempt)) return false;
            int changed = jdbc.update("""
                    UPDATE notification_attempt SET phase='REQUEST_STARTED',outcome='UNKNOWN',request_started_at=?,credential_version=?
                    WHERE task_id=? AND run_number=? AND attempt_no=? AND lease_token=?
                        AND phase='RESERVED' AND ended_at IS NULL AND execution_deadline>clock_timestamp()
                        AND EXISTS (SELECT 1 FROM notification_task t WHERE t.id=notification_attempt.task_id
                            AND t.run_number=notification_attempt.run_number AND t.lease_token=notification_attempt.lease_token
                            AND t.status='processing' AND t.lease_until>clock_timestamp())
                    """, ts(now), credentialVersion, lease.taskId(), lease.runNumber(), attempt.attemptNo(), lease.leaseToken());
            if (changed == 0) return false;
            if (!stillExecutable(lease, attempt)) { ignored.setRollbackOnly(); return false; }
            return true;
        }));
    }

    /** T4: an expired or replaced owner cannot change either the task or its history. */
    public boolean complete(Lease lease, Attempt attempt, Completion completion) {
        return Boolean.TRUE.equals(transaction.execute(ignored -> {
            TaskState state = lock(lease.taskId());
            Instant now = now();
            if (!owns(state, lease, now) || !same(lease, attempt)) return false;
            int updated = jdbc.update("""
                    UPDATE notification_attempt SET phase='COMPLETED',outcome=?,http_status=?,error_code=?,
                        http_send_started_at=?,ended_at=?
                    WHERE task_id=? AND run_number=? AND attempt_no=? AND lease_token=? AND ended_at IS NULL
                        AND execution_deadline>clock_timestamp()
                        AND EXISTS (SELECT 1 FROM notification_task t WHERE t.id=notification_attempt.task_id
                            AND t.run_number=notification_attempt.run_number AND t.lease_token=notification_attempt.lease_token
                            AND t.status='processing' AND t.lease_until>clock_timestamp())
                    """, completion.outcome(), completion.httpStatus(), completion.errorCode(), ts(completion.httpSendStartedAt()),
                    ts(now), lease.taskId(), lease.runNumber(), attempt.attemptNo(), lease.leaseToken());
            if (updated == 0) return false;
            one(updated);
            String status = completion.outcome().equals("HTTP_ACKNOWLEDGED") ? "succeeded" : "failed";
            String reason = completion.errorCode();
            Instant nextAttempt = null;
            if (!status.equals("succeeded") && completion.retryable()) {
                Instant scheduleNow = now();
                if (state.count >= state.maximum) reason = "ATTEMPTS_EXHAUSTED";
                else if (!state.deadline.isAfter(scheduleNow)) reason = "DEADLINE_EXCEEDED";
                else {
                    Instant proposed = retryPolicy.nextAttemptAt(scheduleNow, attempt.attemptNo(), Map.of("retry-after", completion.retryAfterValues()));
                    if (!proposed.isBefore(state.deadline)) reason = "DEADLINE_EXCEEDED";
                    else { status = "pending"; nextAttempt = proposed; reason = "RETRY_BACKOFF"; }
                }
            }
            int taskUpdated = jdbc.update("""
                    UPDATE notification_task SET status=?,last_outcome=?,last_error_code=?,last_http_status=?,
                        lease_token=NULL,lease_owner=NULL,lease_until=NULL,next_attempt_at=?,defer_reason=?,updated_at=?
                    WHERE id=? AND run_number=? AND lease_token=? AND status='processing'
                        AND lease_until>clock_timestamp()
                        AND EXISTS (SELECT 1 FROM notification_attempt a WHERE a.task_id=notification_task.id
                            AND a.run_number=notification_task.run_number AND a.attempt_no=? AND a.execution_deadline>clock_timestamp())
                    """, status, completion.outcome(), completion.errorCode(), completion.httpStatus(), ts(nextAttempt), reason, ts(now),
                    lease.taskId(), lease.runNumber(), lease.leaseToken(), attempt.attemptNo());
            if (taskUpdated == 0) { ignored.setRollbackOnly(); return false; }
            one(taskUpdated);
            Instant completedAt = now();
            if (!state.leaseUntil.isAfter(completedAt) || !attemptDeadline(lease, attempt).isAfter(completedAt)) {
                ignored.setRollbackOnly();
                return false;
            }
            String decision = status.equals("succeeded") ? "SUCCESS" : status.equals("pending") ? "RETRY" : "FAIL";
            afterCommit(() -> metrics.counter("notification.worker.decision", "decision", decision).increment());
            return true;
        }));
    }

    /** Reclaim only expired database leases. Unfinished reserved work is conservatively UNKNOWN. */
    public int recoverExpired(int limit) {
        checkLimit(limit);
        return Objects.requireNonNull(transaction.execute(ignored -> {
            List<TaskState> expired = jdbc.query("""
                    SELECT * FROM notification_task WHERE status='processing' AND lease_until<=clock_timestamp()
                    ORDER BY lease_until,id FOR UPDATE SKIP LOCKED LIMIT ?
                    """, (row, n) -> state(row), limit);
            int recovered = 0;
            for (TaskState state : expired) {
                Instant now = now();
                int unfinished = jdbc.update("""
                        UPDATE notification_attempt SET phase='COMPLETED',outcome='UNKNOWN',error_code='LEASE_EXPIRED',ended_at=?
                        WHERE task_id=? AND run_number=? AND lease_token=? AND ended_at IS NULL
                        """, ts(now), state.id, state.run, state.token);
                if (unfinished > 1) throw new IllegalStateException("Multiple unfinished attempts for one lease");
                boolean retry = state.count < state.maximum && state.deadline.isAfter(now);
                String reason = !state.deadline.isAfter(now) ? "DEADLINE_EXCEEDED" : state.count >= state.maximum ? "ATTEMPTS_EXHAUSTED" : "LEASE_EXPIRED";
                one(jdbc.update("""
                        UPDATE notification_task SET status=?,next_attempt_at=?,lease_token=NULL,lease_owner=NULL,lease_until=NULL,
                            last_outcome=CASE WHEN ? THEN 'UNKNOWN' ELSE last_outcome END,
                            last_error_code=CASE WHEN ? THEN 'LEASE_EXPIRED' ELSE last_error_code END,
                            last_http_status=CASE WHEN ? THEN NULL ELSE last_http_status END,defer_reason=?,updated_at=?
                        WHERE id=? AND run_number=? AND lease_token=? AND status='processing'
                        """, retry ? "pending" : "failed", retry ? ts(now) : null, unfinished == 1, unfinished == 1, unfinished == 1,
                        reason, ts(now), state.id, state.run, state.token));
                recovered++;
            }
            return recovered;
        }));
    }

    /** Deadline cleanup never depends on next_attempt_at; it does not invent a new delivery result. */
    public int expirePending(int limit) {
        checkLimit(limit);
        return Objects.requireNonNull(transaction.execute(ignored -> {
            List<TaskState> deadlines = jdbc.query("""
                    SELECT * FROM notification_task WHERE status='pending' AND deadline_at<=clock_timestamp()
                    ORDER BY deadline_at,id FOR UPDATE SKIP LOCKED LIMIT ?
                    """, (row, n) -> state(row), limit);
            List<ExhaustedBudget> violations = new ArrayList<>();
            for (TaskState task : deadlines) {
                if (task.count >= task.maximum) violations.add(new ExhaustedBudget(task.id, task.run));
                one(jdbc.update("""
                    UPDATE notification_task SET status='failed',next_attempt_at=NULL,defer_reason='DEADLINE_EXCEEDED',updated_at=clock_timestamp()
                    WHERE id=? AND status='pending' AND deadline_at<=clock_timestamp()
                    """, task.id));
            }
            int room = limit - deadlines.size();
            List<ExhaustedBudget> exhausted = room == 0 ? List.of() : jdbc.query("""
                    SELECT id,run_number FROM notification_task WHERE status='pending' AND run_attempt_count>=max_attempts
                    ORDER BY id FOR UPDATE SKIP LOCKED LIMIT ?
                    """, (row, n) -> new ExhaustedBudget(row.getObject("id", UUID.class), row.getInt("run_number")), room);
            for (ExhaustedBudget task : exhausted) one(jdbc.update("""
                    UPDATE notification_task SET status='failed',next_attempt_at=NULL,defer_reason='ATTEMPTS_EXHAUSTED',updated_at=clock_timestamp()
                    WHERE id=? AND status='pending' AND run_attempt_count>=max_attempts
                    """, task.id()));
            violations.addAll(exhausted);
            if (!violations.isEmpty()) afterCommit(() -> {
                metrics.counter("notification.worker.budget_invariant_violation").increment(violations.size());
                for (ExhaustedBudget task : violations) LOG.warn("delivery_budget_invariant_violated taskId={} run={}", task.id(), task.run());
            });
            return deadlines.size() + exhausted.size();
        }));
    }

    private record ExhaustedBudget(UUID id, int run) { }

    private static void afterCommit(Runnable event) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { event.run(); }
                catch (RuntimeException telemetryFailure) { LOG.warn("delivery_telemetry_failed"); }
            }
        });
    }

    public List<AttemptView> attempts(UUID taskId) {
        return jdbc.query("SELECT * FROM notification_attempt WHERE task_id=? ORDER BY run_number,attempt_no", (row, n) -> attemptView(row), taskId);
    }

    public List<AttemptView> attemptsPage(UUID taskId, AttemptCursor cursor, int count) {
        if (cursor == null) return jdbc.query("SELECT * FROM notification_attempt WHERE task_id=? ORDER BY run_number DESC,attempt_no DESC LIMIT ?",
                (row, n) -> attemptView(row), taskId, count);
        return jdbc.query("""
                SELECT * FROM notification_attempt WHERE task_id=? AND (run_number,attempt_no)<(?,?)
                ORDER BY run_number DESC,attempt_no DESC LIMIT ?
                """, (row, n) -> attemptView(row), taskId, cursor.runNumber(), cursor.attemptNo(), count);
    }

    private static AttemptView attemptView(ResultSet row) throws SQLException {
        return new AttemptView(row.getObject("task_id", UUID.class), row.getInt("run_number"), row.getInt("attempt_no"),
                        row.getString("phase"), row.getString("outcome"), row.getObject("http_status", Integer.class), row.getString("error_code"),
                        instant(row, "started_at"), instant(row, "ended_at"), instant(row, "request_started_at"),
                        instant(row, "http_send_started_at"), instant(row, "execution_deadline"), row.getString("credential_version"));
    }

    private TaskState lock(UUID id) {
        return jdbc.query("SELECT * FROM notification_task WHERE id=? FOR UPDATE", (row, n) -> state(row), id).stream().findFirst().orElse(null);
    }
    private Instant now() { return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class)).toInstant(); }
    private boolean stillExecutable(Lease lease, Attempt attempt) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM notification_task t JOIN notification_attempt a ON a.task_id=t.id
                    WHERE t.id=? AND t.run_number=? AND t.lease_token=? AND t.status='processing'
                      AND t.lease_until>clock_timestamp() AND a.run_number=t.run_number AND a.attempt_no=?
                      AND a.lease_token=t.lease_token AND a.execution_deadline>clock_timestamp() AND a.ended_at IS NULL)
                """, Boolean.class, lease.taskId(), lease.runNumber(), lease.leaseToken(), attempt.attemptNo()));
    }
    private Instant attemptDeadline(Lease lease, Attempt attempt) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT execution_deadline FROM notification_attempt WHERE task_id=? AND run_number=? AND attempt_no=? AND lease_token=?
                """, Timestamp.class, lease.taskId(), lease.runNumber(), attempt.attemptNo(), lease.leaseToken())).toInstant();
    }
    private static boolean owns(TaskState state, Lease lease, Instant now) {
        return state != null && state.status.equals("processing") && state.run == lease.runNumber()
                && Objects.equals(state.token, lease.leaseToken()) && state.leaseUntil.isAfter(now);
    }
    private static boolean same(Lease lease, Attempt attempt) {
        return lease.taskId().equals(attempt.taskId()) && lease.runNumber() == attempt.runNumber() && lease.leaseToken().equals(attempt.leaseToken());
    }
    private record TaskState(UUID id, String status, int run, UUID token, Instant leaseUntil, Instant deadline, int count, int maximum) { }
    private static TaskState state(ResultSet row) throws SQLException {
        return new TaskState(row.getObject("id", UUID.class), row.getString("status"), row.getInt("run_number"),
                row.getObject("lease_token", UUID.class), instant(row, "lease_until"), instant(row, "deadline_at"),
                row.getInt("run_attempt_count"), row.getInt("max_attempts"));
    }
    private static Lease lease(ResultSet row) throws SQLException {
        Map<String, String> headers = new LinkedHashMap<>();
        try { JSON.readTree(row.getString("ordinary_headers")).fields().forEachRemaining(entry -> headers.put(entry.getKey(), entry.getValue().textValue())); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Invalid persisted request headers"); }
        var snapshot = new RequestSnapshot(row.getString("request_url"), row.getString("request_method"), headers,
                row.getString("content_type"), row.getBytes("body_bytes"));
        return new Lease(row.getObject("id", UUID.class), row.getInt("run_number"), row.getObject("lease_token", UUID.class),
                row.getString("lease_owner"), instant(row, "lease_until"), row.getString("tenant_id"), row.getString("operation"),
                row.getString("event_id"), row.getString("provider_id"), row.getString("endpoint_group"), row.getObject("auth_profile_id", UUID.class), snapshot);
    }
    private static Instant instant(ResultSet row, String column) throws SQLException { Timestamp value = row.getTimestamp(column); return value == null ? null : value.toInstant(); }
    private static Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private static void positive(Duration duration) { if (duration == null || duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("Duration must be positive"); }
    private static void checkLimit(int limit) { if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid batch size"); }
    private static void one(int changed) { if (changed != 1) throw new IllegalStateException("Delivery state transition lost ownership"); }
}

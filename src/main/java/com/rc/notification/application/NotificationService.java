package com.rc.notification.application;

import com.rc.notification.api.ApiException;
import com.rc.notification.security.CallerPrincipal;
import com.rc.notification.storage.NotificationRepository;
import com.rc.notification.storage.DeliveryRepository;
import com.rc.notification.worker.AttemptView;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationService {
    private final NotificationRepository repository;
    private final RequestSnapshotFactory snapshots;
    private final DeliveryRepository deliveries;
    private final TransactionTemplate transaction;
    public NotificationService(NotificationRepository repository, RequestSnapshotFactory snapshots,
                               PlatformTransactionManager manager, DeliveryRepository deliveries) {
        this.repository = repository;
        this.snapshots = snapshots;
        this.deliveries = deliveries;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    public record Accepted(UUID notificationId, String status, int runNumber, boolean deduplicated) { }
    public record Page(List<TaskView> items, String nextCursor) { }
    public record AttemptPage(List<AttemptView> items, String nextCursor) { }

    public AttemptPage attempts(UUID id, String encodedCursor, int limit, CallerPrincipal caller) {
        get(id, caller);
        if (limit < 1 || limit > 100) throw ApiException.invalid();
        AttemptCursor cursor = AttemptCursor.parse(encodedCursor);
        List<AttemptView> rows = deliveries.attemptsPage(id, cursor, limit + 1);
        boolean more = rows.size() > limit;
        List<AttemptView> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        AttemptView last = items.isEmpty() ? null : items.get(items.size() - 1);
        return new AttemptPage(items, more ? new AttemptCursor(last.runNumber(), last.attemptNo()).encode() : null);
    }

    public Accepted submit(Submission submission, CallerPrincipal caller) {
        caller.requireSubmission(submission.tenantId(), submission.operation());
        // TransactionTemplate only returns after commit; a commit failure cannot produce HTTP 202.
        return Objects.requireNonNull(transaction.execute(ignored -> {
            var existing = repository.findEvent(submission);
            if (existing.isPresent()) return duplicate(existing.get(), submission);
            var route = repository.route(submission);
            var snapshot = snapshots.create(route.configuration(), submission);
            var inserted = repository.insert(submission, route, snapshot);
            if (inserted.isPresent()) return new Accepted(inserted.get(), "pending", 1, false);
            // This must be a separate READ COMMITTED statement after the conflicting insert waits.
            return duplicate(repository.findEvent(submission).orElseThrow(() ->
                    new IllegalStateException("The concurrent accepted task is missing")), submission);
        }));
    }

    private Accepted duplicate(NotificationRepository.Existing existing, Submission submission) {
        if (!MessageDigest.isEqual(existing.digest(), submission.digest())) {
            throw new ApiException(409, "EVENT_CONFLICT", "Event identity already belongs to different data");
        }
        TaskView task = existing.view();
        return new Accepted(task.notificationId(), task.status(), task.runNumber(), true);
    }

    public TaskView get(UUID id, CallerPrincipal caller) {
        if (!(caller.hasRole("BUSINESS") || caller.hasRole("OPERATOR"))) throw ApiException.notFound();
        return repository.find(id, caller).orElseThrow(ApiException::notFound);
    }

    public Page list(String tenant, String status, String encodedCursor, int limit, CallerPrincipal caller) {
        SubmissionParser.identifier(tenant, 64);
        caller.requireQuery(tenant);
        if (limit < 1 || limit > 100 || status != null && !Set.of("pending", "processing", "succeeded", "failed").contains(status)) {
            throw ApiException.invalid();
        }
        TaskCursor cursor = TaskCursor.parse(encodedCursor);
        List<TaskView> rows = repository.list(tenant, status, cursor, limit + 1, caller);
        boolean hasNext = rows.size() > limit;
        List<TaskView> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        TaskView last = items.isEmpty() ? null : items.get(items.size() - 1);
        return new Page(items, hasNext ? new TaskCursor(last.createdAt(), last.notificationId()).encode() : null);
    }
}

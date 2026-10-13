package com.rc.notification.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rc.notification.api.ApiException;
import com.rc.notification.security.CallerPrincipal;
import com.rc.notification.storage.NotificationRepository;
import com.rc.notification.storage.ReplayRepository;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReplayService {
    private static final CanonicalDigest DIGEST = new CanonicalDigest();
    private final NotificationRepository tasks;
    private final ReplayRepository replays;
    private final DeliveryProperties defaults;
    private final TransactionTemplate transaction;

    public ReplayService(NotificationRepository tasks, ReplayRepository replays, DeliveryProperties defaults, PlatformTransactionManager manager) {
        this.tasks = tasks;
        this.replays = replays;
        this.defaults = defaults;
        this.transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    public record Response(UUID notificationId, String status, int runNumber, String replayRequestId) { }

    public Response replay(UUID id, ObjectNode input, CallerPrincipal caller) {
        if (!caller.hasRole("OPERATOR")) throw new ApiException(403, "FORBIDDEN_OPERATION", "Replay requires an operator role");
        Request request = parse(input);
        return Objects.requireNonNull(transaction.execute(ignored -> {
            // Reauthorize every retry before exposing even the saved result of a prior operation.
            tasks.find(id, caller).orElseThrow(ApiException::notFound);
            var saved = replays.find(id, request.key());
            if (saved.isPresent()) return duplicate(saved.get(), request);
            TaskView current = tasks.findForUpdate(id, caller).orElseThrow(ApiException::notFound);
            // READ COMMITTED needs a second statement after waiting for the competing task lock.
            saved = replays.find(id, request.key());
            if (saved.isPresent()) return duplicate(saved.get(), request);
            if (!current.status().equals("failed") || current.runNumber() != request.expectedRunNumber()
                    || current.runNumber() == Integer.MAX_VALUE) throw conflict();
            int run = replays.restart(id, request.expectedRunNumber(), defaults);
            Response response = new Response(id, "pending", run, request.key());
            replays.save(current, request.key(), request.expectedRunNumber(), request.reason(), request.digest(), response, caller);
            return response;
        }));
    }

    private static Response duplicate(ReplayRepository.Saved saved, Request request) {
        if (!MessageDigest.isEqual(saved.digest(), request.digest())) throw conflict();
        return saved.response();
    }
    private record Request(String key, int expectedRunNumber, String reason, byte[] digest) { }
    private static Request parse(ObjectNode node) {
        Set<String> keys = Set.of("replayRequestId", "expectedRunNumber", "reason");
        if (node.size() != 3) throw ApiException.invalid();
        node.fieldNames().forEachRemaining(key -> { if (!keys.contains(key)) throw ApiException.invalid(); });
        if (!node.path("replayRequestId").isTextual() || !node.path("reason").isTextual()
                || !node.path("expectedRunNumber").isIntegralNumber() || !node.path("expectedRunNumber").canConvertToInt()) throw ApiException.invalid();
        String key = node.path("replayRequestId").textValue();
        SubmissionParser.identifier(key, 128);
        String reason = node.path("reason").textValue();
        int expected = node.path("expectedRunNumber").intValue();
        if (expected < 1 || reason.isBlank() || reason.codePointCount(0, reason.length()) > 512 || reason.indexOf(0) >= 0) throw ApiException.invalid();
        ObjectNode digestInput = node.objectNode().put("expectedRunNumber", expected).put("reason", reason);
        return new Request(key, expected, reason, DIGEST.digest(digestInput));
    }
    private static ApiException conflict() { return new ApiException(409, "REPLAY_CONFLICT", "Replay identity, task status or run number conflicts"); }
}

#!/usr/bin/env python3
"""Fail-closed CP10 analysis. Missing sends/unknown acceptance never disappear from percentiles."""
import argparse
import collections
import hashlib
import json
import math
import pathlib
import re
import sys

from common import epoch, read_jsonl, write_json


def percentile(values, percent):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * percent / 100) - 1)] if ordered else None


def measurement(metrics, name):
    candidates = metrics.get(name, [])
    return next((item['value'] for item in candidates if item['statistic'] == 'VALUE'), None)


def seconds(value):
    match = re.fullmatch(r'([0-9.]+)(ms|s|m|h)', value or '')
    return float(match[1]) * {'ms':.001,'s':1,'m':60,'h':3600}[match[2]] if match else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('directory', type=pathlib.Path)
    args = parser.parse_args()
    directory = args.directory
    issues = []
    def check(condition, code):
        if not condition:
            issues.append(code)
    def load(name):
        return json.loads((directory / name).read_text())
    manifest, workload = load('manifest.json'), load('workload.json')
    mode = manifest['mode']
    requests, tasks, attempts, calls, resources = [read_jsonl(directory / name) for name in
                ('requests.jsonl','tasks.jsonl','attempts.jsonl','journal.jsonl','resources.jsonl')]
    mock = load('mock-final.json')
    check(manifest.get('executionComplete') is True, 'EXECUTION_INCOMPLETE')
    expected = dict(baseline=12000, pressure=9000, burst=200, recovery=200)[mode]
    check(workload['planned'] == expected and len(requests) == expected, 'PLANNED_INPUT_COUNT')
    check(len({r['sequence'] for r in requests}) == expected, 'INPUT_SEQUENCE_DUPLICATE_OR_MISSING')
    check(len({r['eventId'] for r in requests}) == expected, 'INPUT_EVENT_DUPLICATE_OR_MISSING')
    statuses = collections.Counter(r['status'] for r in requests)
    check(statuses[0] == 0, 'UNKNOWN_ACCEPTANCE_OR_INJECTOR_FAILURE')
    check(not (set(statuses) - {202,429,503}), 'UNEXPECTED_HTTP_ACCEPTANCE_STATUS')
    if mode != 'pressure':
        check(statuses[202] == expected, 'EVERY_PLANNED_EVENT_MUST_BE_ACCEPTED')
    else:
        check(statuses[429] > 0 and statuses[503] > 0, 'PRESSURE_MUST_OBSERVE_BOTH_429_AND_503')
        check(all(r.get('errorCode') == ({429:'ADMISSION_RATE_LIMITED',503:'BACKLOG_CAPACITY_EXCEEDED'}.get(r['status'])) for r in requests if r['status'] in (429,503)), 'PRESSURE_REJECTION_NOT_EXPECTED_PROTECTION')
    event_to_task = {(t['tenant_id'], t['event_id']): t for t in tasks}
    task_by_id = {t['id']: t for t in tasks}
    check(len(task_by_id) == len(tasks) == len(event_to_task), 'DATABASE_TASK_NOT_UNIQUE')
    accepted = [r for r in requests if r['status'] == 202]
    check({r['notificationId'] for r in accepted} == set(task_by_id), 'ACCEPTED_TASK_SET_MISMATCH')
    check(len(accepted) == len(tasks), 'ACCEPTED_TASK_COUNT_MISMATCH')
    mismatches = []
    for item in requests:
        task = event_to_task.get((item['tenantId'], item['eventId']))
        if item['status'] == 202:
            if not task or task['id'] != item['notificationId']:
                mismatches.append(item['eventId'])
        elif task:
            mismatches.append(item['eventId'])
    check(not mismatches, 'ACCEPTANCE_ID_OR_REJECTED_PERSISTENCE_MISMATCH')
    check(workload['drain']['complete'], 'DRAIN_DEADLINE_EXCEEDED')
    check(all(t['status'] == 'succeeded' for t in tasks), 'TASKS_NOT_SUCCESSFULLY_TERMINAL')
    attempts_by_task = collections.defaultdict(list)
    for attempt in attempts:
        attempts_by_task[attempt['task_id']].append(attempt)
    check(set(attempts_by_task) == set(task_by_id), 'TASK_ATTEMPT_SET_MISMATCH')
    check(all(len(attempts_by_task[t['id']]) == t['total_attempt_count'] for t in tasks), 'ATTEMPT_COUNTER_MISMATCH')
    check(all(a['phase'] == 'COMPLETED' and a['ended_at'] for a in attempts), 'OPEN_ATTEMPT_AFTER_DRAIN')
    business = [c for c in calls if c['kind'] == 'business']
    tokens = [c for c in calls if c['kind'] == 'token']
    by_event = collections.defaultdict(list)
    for call in business:
        by_event[(call.get('tenantId'), call.get('eventId'))].append(call)
    check(set(by_event) == set(event_to_task), 'MOCK_TASK_EVENT_SET_MISMATCH')
    check(mock['businessCalls'] == len(business), 'MOCK_JOURNAL_COUNT_MISMATCH')
    check(mock['delaySeconds'] == (1 if mode == 'recovery' else .1), 'MOCK_FIXED_LATENCY_CHANGED')
    check(mock['sideEffects'] == len(tasks), 'ACTUAL_DEDUP_SIDE_EFFECT_COUNT_MISMATCH')
    check(mock['capacityRejections'] == mock['journalOverflows'] == mock['authRejections'] == 0, 'MOCK_SATURATION_OVERFLOW_OR_AUTH_FAILURE')
    check(all(c.get('bodyBytes') == 1024 for c in business), 'BODY_NOT_EXACTLY_1KIB')
    if mode in ('baseline','pressure'):
        check(all(len(by_event[key]) == 1 for key in event_to_task), 'UNEXPECTED_BASELINE_OR_PRESSURE_DUPLICATE_SEND')
    # Real OAuth is checked in separately retained warmup plus all main requests' Bearer acceptance.
    warm_calls = read_jsonl(directory / 'warmup-journal.jsonl')
    check(sum(c['kind'] == 'token' and c['status'] == 200 for c in warm_calls) >= 10, 'REAL_OAUTH_WARMUP_MISSING')
    check(all(c['status'] == 200 for c in tokens), 'TOKEN_EXCHANGE_FAILURE')
    if mode in ('baseline','pressure'):
        check(len(tokens) >= 10, 'TOKEN_REFRESH_WAIT_NOT_EXERCISED_DURING_MEASUREMENT')
    api_delays = [r['latencyMs'] for r in accepted]
    scheduled_delays = [(r['finishedAt'] - r['plannedAt']) * 1000 for r in accepted]
    send_delays, reserve_delays, missing_sends, negative_delays = [], [], [], []
    reconciliation = []
    for task in tasks:
        rows = attempts_by_task[task['id']]
        sends = [epoch(a['http_send_started_at']) for a in rows if a['http_send_started_at'] is not None]
        reservations = [epoch(a['started_at']) for a in rows]
        send_delay = min(sends) - epoch(task['created_at']) if sends else None
        reserve_delay = min(reservations) - epoch(task['created_at']) if reservations else None
        if send_delay is None:
            missing_sends.append(task['id'])
        else:
            send_delays.append(send_delay)
            if send_delay < 0:
                negative_delays.append({'taskId': task['id'], 'seconds': send_delay})
        if reserve_delay is not None:
            reserve_delays.append(reserve_delay)
        reconciliation.append(dict(notificationId=task['id'], tenantId=task['tenant_id'],eventId=task['event_id'],
                    status=task['status'], attemptCount=len(rows), mockCalls=len(by_event[(task['tenant_id'],task['event_id'])]),
                    firstBusinessSendDelaySeconds=send_delay, firstReservationDelaySeconds=reserve_delay,
                    completedFromInputSeconds=epoch(task['updated_at']) - workload['startAt']))
    check(not missing_sends and len(send_delays) == len(accepted), 'MISSING_FIRST_BUSINESS_SEND')
    check(len(reserve_delays) == len(accepted), 'MISSING_FIRST_RESERVATION')
    # Clock sampling includes round-trip uncertainty; uncalibrated JVM time is never treated as DB time.
    clock_summary = []
    for file in ('clock-before.json','clock-after.json'):
        grouped = collections.defaultdict(list)
        for sample in load(file):
            grouped[sample['source']].append(sample)
        for source, samples in grouped.items():
            valid = [s for s in samples if s.get('remoteEpoch') is not None]
            check(bool(valid), 'CLOCK_UNOBSERVABLE_' + source)
            if valid:
                best = min(valid, key=lambda s: s['uncertaintySeconds'])
                bound = abs(best['offsetEstimate']) + best['uncertaintySeconds']
                check(bound <= .25, 'CLOCK_OFFSET_OR_UNCERTAINTY_TOO_LARGE_' + source)
                clock_summary.append(dict(phase=file,source=source,offsetSeconds=best['offsetEstimate'],uncertaintySeconds=best['uncertaintySeconds']))
    check(not any(item['seconds'] < -.25 for item in negative_delays), 'NEGATIVE_SEND_TIME_BEYOND_CALIBRATION')
    if mode == 'baseline':
        check(percentile(api_delays, 95) is not None and percentile(api_delays, 95) <= 300, 'API_ACCEPT_P95_EXCEEDS_300MS')
        check(percentile(send_delays, 95) is not None and percentile(send_delays, 95) <= 3, 'FIRST_BUSINESS_SEND_P95_EXCEEDS_3S')
        check(workload['stages'] == [dict(name='baseline',rps=20,seconds=600,planned=12000,startAt=workload['startAt'],endAt=workload['startAt']+600)], 'BASELINE_SCHEDULE_CHANGED')
    check(abs(mock['startedAt'] - workload['startAt']) <= .001, 'MOCK_AND_INJECTOR_FAULT_START_NOT_ALIGNED')
    if mode == 'burst':
        check(mock['faultSeconds'] == 15 and mock['timeoutSeconds'] > 2, 'BURST_MOCK_FAULT_PARAMETERS')
        check(any(a['error_code'] == 'HTTP_TIMEOUT' for a in attempts), 'BURST_ACTUAL_WORKER_TIMEOUT_MISSING')
        for provider, deadline in (('B',10),('A',60)):
            group = [t for t in tasks if t['provider_id'] == provider]
            check(len(group) == 100, 'BURST_' + provider + '_COUNT')
            check(all(t['status'] == 'succeeded' and epoch(t['updated_at']) - workload['startAt'] <= deadline for t in group), 'BURST_' + provider + '_DEADLINE')
        check(any(c.get('responseBehavior') == 'timeout' and c.get('provider') == 'A' for c in business), 'BURST_A_TIMEOUT_NOT_OBSERVED')
    claims_summary = None
    if mode in ('burst','recovery'):
        claims = read_jsonl(directory / 'claims.jsonl')
        indexed = {(c['task_id'],c['run_number'],c['lease_token']): c for c in claims}
        check(len(indexed) == len(claims), 'CLAIM_OBSERVATION_NOT_UNIQUE')
        early = []
        for attempt in attempts:
            claim = indexed.get((attempt['task_id'],attempt['run_number'],attempt['lease_token']))
            if claim is None:
                early.append({'taskId':attempt['task_id'],'reason':'MISSING_CLAIM'})
                continue
            if epoch(claim['claimed_at']) < epoch(claim['due_at']):
                early.append({'taskId':attempt['task_id'],'reason':'CLAIM_BEFORE_DUE'})
            send = epoch(attempt['http_send_started_at'])
            # Different clocks: retain calibration tolerance, do not silently round timestamps.
            if send is not None and send < epoch(claim['due_at']) - .25:
                early.append({'taskId':attempt['task_id'],'reason':'SEND_BEFORE_DUE'})
        check(not early, 'CLAIM_OR_SEND_BEFORE_SCHEDULE_OR_MISSING_OBSERVATION')
        claims_summary = dict(observations=len(claims), violations=early,
                              instrument='AFTER UPDATE claim-only audit; inserts in claim transaction; test-only overhead')
    if mode == 'recovery':
        lifecycle = read_jsonl(directory / 'lifecycle.jsonl') if (directory / 'lifecycle.jsonl').exists() else []
        kills = [e for e in lifecycle if e.get('action') == 'kill']
        restarted = {e.get('service') for e in lifecycle if e.get('action') == 'restart'}
        check(len({e.get('service') for e in kills}) >= 2 and all(e.get('service') in restarted for e in kills), 'TWO_WORKERS_KILLED_AND_RESTARTED_NOT_PROVEN')
        check(all(e.get('containerId') and e.get('observedAt') and e.get('activeBefore',0)>0 for e in kills), 'KILL_ID_TIME_ACTIVE_EVIDENCE_MISSING')
    # Sampled resource values and configured bounds. These are sampled peaks, not an invented continuous trace.
    peaks = collections.defaultdict(dict)
    resource_violations = []
    worker_nodes = set()
    for sample in resources:
        if mode != 'recovery':
            check(not sample['errors'], 'RESOURCE_SAMPLING_ERROR')
        for node in sample['nodes']:
            metrics = node['metrics']
            endpoint = node['endpoint']
            if 'notification.worker.capacity' in metrics:
                worker_nodes.add(endpoint)
            for name in metrics:
                value = measurement(metrics,name)
                if value is not None:
                    peaks[endpoint][name] = max(peaks[endpoint].get(name, float('-inf')),value)
            pairs = [('notification.worker.active','notification.worker.capacity'),
                     ('notification.worker.queued','notification.worker.capacity'),
                     ('notification.worker.provider.active.max','notification.worker.provider.capacity'),
                     ('notification.oauth.leaders.active','notification.oauth.leaders.capacity'),
                     ('hikaricp.connections.active','hikaricp.connections.max'),
                     ('jvm.memory.used','jvm.memory.max')]
            for client in ('business','token'):
                suffix = '|client:' + client
                pairs += [(name+suffix,'notification.http.execution.capacity'+suffix) for name in
                          ('notification.http.execution.active','notification.http.execution.queued','notification.http.execution.permits_used')]
                pairs.append(('notification.http.connections.leased'+suffix,'notification.http.connections.max'+suffix))
            for current, limit in pairs:
                actual, bound = measurement(metrics,current), measurement(metrics,limit)
                if actual is not None and bound is not None and bound >= 0 and actual > bound:
                    resource_violations.append(dict(endpoint=endpoint,metric=current,value=actual,bound=bound,time=sample['observedAt']))
            fresh = measurement(metrics,'notification.queue.sample.success')
            age = measurement(metrics,'notification.queue.sample.age.seconds')
            if fresh is not None and fresh != 1 or age is not None and age > 15:
                resource_violations.append(dict(endpoint=endpoint,metric='database.sample.stale',time=sample['observedAt']))
    check(bool(resources), 'NO_RESOURCE_TIMELINE')
    check(len(worker_nodes) == (2 if mode == 'recovery' else 1), 'WORKER_METRIC_COVERAGE_MISSING_OR_WRONG_TOPOLOGY')
    check(not resource_violations, 'RESOURCE_BOUND_OR_FRESHNESS_VIOLATION')
    docker_samples = read_jsonl(directory / 'docker-resources.jsonl')
    check(bool(docker_samples), 'NO_DOCKER_RESOURCE_TIMELINE')
    observed_parameters = []
    for sample in docker_samples:
        if mode != 'recovery':
            check(sample.get('error') is None, 'DOCKER_RESOURCE_SAMPLING_ERROR')
        for container in sample['containers']:
            parameters = container.get('parameters', {})
            if parameters.get('APP_MODE') in ('api','worker','all'):
                observed_parameters.append(parameters)
                check(container.get('memoryLimitBytes',0)>0 and container.get('pidsLimit',0)>0, 'APP_CONTAINER_NOT_RESOURCE_BOUNDED')
            check(not container.get('oomKilled', False), 'CONTAINER_OOM_KILLED')
            if container.get('pidsLimit', 0) and str(container.get('pids','')).isdigit():
                check(int(container['pids']) <= container['pidsLimit'], 'CONTAINER_PIDS_LIMIT_VIOLATION')
            percent = container.get('memoryPercent','').rstrip('%')
            if percent:
                check(float(percent) <= 100, 'CONTAINER_MEMORY_LIMIT_VIOLATION')
    check(bool(observed_parameters), 'EFFECTIVE_DEPLOYMENT_PARAMETERS_MISSING')
    for parameters in observed_parameters:
        if parameters.get('APP_MODE') in ('all','worker'):
            check(parameters.get('WORKER_GLOBAL_CONCURRENCY') == '10' and parameters.get('WORKER_PROVIDER_CONCURRENCY') == '3', 'FIXED_WORKER_CONCURRENCY_CHANGED')
            if mode == 'burst':
                fixed = {'WORKER_POLL_INTERVAL':.1, 'WORKER_BUSINESS_TIMEOUT':2, 'RETRY_BASE_DELAY':.1,
                         'RETRY_MAX_DELAY':1, 'PROTECTION_OPEN_DURATION':1, 'DELIVERY_RUN_DURATION':120}
                check(all(seconds(parameters.get(key)) == value for key,value in fixed.items()) and parameters.get('DELIVERY_MAX_ATTEMPTS') == '12', 'BURST_FIXED_BUDGETS_CHANGED')
        if parameters.get('APP_MODE') in ('all','api') and mode == 'pressure':
            check(parameters.get('ADMISSION_REQUESTS_PER_SECOND') == '100' and parameters.get('ADMISSION_BURST_CAPACITY') == '200' and parameters.get('ADMISSION_MAX_BACKLOG') == '500', 'PRESSURE_FIXED_ADMISSION_CHANGED')
    inventory = load('metric-inventory.json')
    for node in worker_nodes:
        required = ['notification.worker.active','notification.worker.queued','notification.worker.capacity',
                    'notification.worker.provider.active.max','notification.worker.provider.capacity',
                    'notification.http.connections.leased','notification.http.connections.pending','notification.http.connections.max',
                    'notification.http.execution.active','notification.http.execution.queued','notification.http.execution.capacity',
                    'jvm.memory.used','jvm.memory.max','jvm.threads.live','hikaricp.connections.active','hikaricp.connections.max']
        check(all(name in inventory[node] for name in required), 'REQUIRED_WORKER_RESOURCE_METRICS_MISSING')
    for endpoint in worker_nodes:
        final_nodes = [(sample['observedAt'],node) for sample in resources for node in sample['nodes'] if node['endpoint'] == endpoint and node.get('metrics')]
        final = final_nodes[-1][1]['metrics'] if final_nodes else {}
        check(bool(final_nodes) and final_nodes[-1][0] >= workload['drain']['finishedAt'], 'NO_HEALTHY_RESOURCE_SAMPLE_AFTER_DRAIN')
        released = ['notification.worker.active','notification.worker.queued','notification.oauth.inflight','notification.oauth.leaders.active'] + [name+'|client:'+client for client in ('business','token') for name in ('notification.http.connections.leased','notification.http.connections.pending','notification.http.execution.active','notification.http.execution.queued','notification.http.execution.permits_used')]
        check(all(measurement(final,name) == 0 for name in released), 'RESOURCES_NOT_RELEASED_AFTER_DRAIN')
    lag = [r['scheduleLagMs'] for r in requests if r['scheduleLagMs'] is not None]
    check(len(lag) == expected and max(lag,default=float('inf')) <= 1000, 'INJECTOR_FAILED_TO_MAINTAIN_OPEN_LOOP_ARRIVALS')
    stage_summary = []
    for stage in workload['stages']:
        rows = [r for r in requests if r['stage'] == stage['name']]
        status_counts = collections.Counter(r['status'] for r in rows)
        stage_summary.append(dict(stage=stage['name'],plannedRps=stage['rps'],plannedSeconds=stage['seconds'],
                    planned=stage['planned'],actualStarted=sum(r['startedAt'] is not None for r in rows),
                    startedWithinScheduledWindow=sum(r['startedAt'] is not None and stage['startAt']<=r['startedAt']<stage['endAt'] for r in rows),
                    responses=dict(status_counts),scheduleLagP95Ms=percentile([r['scheduleLagMs'] for r in rows if r['scheduleLagMs'] is not None],95)))
    status_counts = dict(collections.Counter(t['status'] for t in tasks))
    report = dict(mode=mode,runId=manifest['runId'],passed=not issues,issues=sorted(set(issues)),
                  planned=expected,responses=dict(statuses),databaseTasks=len(tasks),databaseStatus=status_counts,
                  attempts=len(attempts),businessHttpCalls=len(business),mainTokenHttpCalls=len(tokens),
                  actualSideEffects=mock['sideEffects'],missingFirstSendTaskIds=missing_sends,
                  apiAcceptP95Ms=percentile(api_delays,95),plannedArrivalToResponseP95Ms=percentile(scheduled_delays,95),
                  acceptedToFirstBusinessSendP95Seconds=percentile(send_delays,95),
                  acceptedToFirstReservationP95Seconds=percentile(reserve_delays,95),negativeRawSendDelays=negative_delays,
                  scheduleLagP95Ms=percentile(lag,95),scheduleLagMaxMs=max(lag,default=None),stages=stage_summary,
                  clockCalibration=clock_summary,sampledResourcePeaks=peaks,resourceViolations=resource_violations,
                  sampledBacklogPeak=max((s.get('database',{}).get('pending',0)+s.get('database',{}).get('processing',0) for s in resources),default=None),
                  drainFinishedAt=workload['drain']['finishedAt'],claimSchedule=claims_summary,
                  evidenceSha256={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(directory.iterdir()) if p.is_file() and p.name not in ('analysis.json','reconciliation.json')})
    write_json(directory / 'reconciliation.json', reconciliation)
    write_json(directory / 'analysis.json', report)
    print(json.dumps({key:report[key] for key in ('runId','mode','passed','planned','databaseTasks','apiAcceptP95Ms','acceptedToFirstBusinessSendP95Seconds','issues')},indent=2))
    return 0 if not issues else 1


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (FileNotFoundError,KeyError,ValueError) as failure:
        raise SystemExit('CP10 evidence incomplete or invalid: ' + type(failure).__name__)

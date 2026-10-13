#!/usr/bin/env python3
"""CP10 real open-loop injector. Run only against the isolated Compose test environment."""
import argparse
import concurrent.futures
import hashlib
import json
import math
import os
import pathlib
import platform
import subprocess
import sys
import threading
import time

from common import ROOT, SCHEMA, db, journal, prefix_clause, request, require, task_snapshot, utc, write_json, write_jsonl

ADMIN = os.environ.get('PERF_ADMIN_TOKEN')
BUSINESS = os.environ.get('PERF_BUSINESS_TOKEN')
DEFAULT_METERS = ['jvm.memory.used', 'jvm.memory.max', 'jvm.threads.live', 'process.cpu.usage', 'system.cpu.usage',
                  'hikaricp.connections.active', 'hikaricp.connections.pending', 'hikaricp.connections.max']


def install(api, run, mode, downstream):
    references = []
    for index in range(1, 11):
        tenant = 'tenant-%02d' % index
        provider = ('A' if index <= 5 else 'B') if mode == 'burst' else 'vendor'
        template = dict(templateKey=run + '-' + tenant, version=1, ownerTenantId=tenant, shared=False,
                        providerId=provider, endpointGroup='notify', method='POST',
                        url=downstream + '/business/' + provider,
                        headers={'X-Event-Id': {'from': 'eventId', 'type': 'string', 'required': True}},
                        body={'format': 'json', 'fields': {'eventId': {'from': 'eventId', 'required': True, 'type': 'string'},
                                                         'tenantId': {'constant': tenant},
                                                         'payload': {'from': 'data.payload', 'required': True, 'type': 'string'}}})
        authentication = dict(authProfileKey=run + '-oauth-' + tenant, version=1, tenantId=tenant,
                              type='oauth2_client_credentials', tokenUrl=downstream + '/oauth/token',
                              clientId=os.environ.get('PERF_OAUTH_CLIENT_ID', 'demo-client'),
                              clientSecretRef='env:DEMO_VENDOR_SECRET', clientAuthenticationMethod='client_secret_post', scopes=['notify'])
        published = []
        for resource, value in (('template-versions', template), ('auth-profiles', authentication)):
            created = require(api, 'POST', '/admin/' + resource, value, ADMIN, (201,))
            published.append(require(api, 'POST', '/admin/' + resource + '/' + created['id'] + '/publish',
                                     {'expectedRevision': created['revision']}, ADMIN))
        route_path = '/admin/routes/' + tenant + '/notify'
        status, current = request(api, 'GET', route_path, token=ADMIN)
        if status not in (200, 404):
            raise RuntimeError('ROUTE_READ_' + str(status))
        route = require(api, 'PUT', route_path, dict(expectedRevision=current['revision'] if status == 200 else 0,
                        templateVersionId=published[0]['id'], authProfileId=published[1]['id']), ADMIN)
        references.append(dict(tenantId=tenant, providerId=provider, templateVersionId=published[0]['id'],
                               authProfileId=published[1]['id'], routeRevision=route['revision']))
    return references


def calibrate(api, mock, nodes):
    records = []
    for unused in range(5):
        before = time.time()
        value = db("SELECT json_build_object('epoch',extract(epoch FROM clock_timestamp()),'timezone',current_setting('TimeZone'),'version',version());")[0]
        after = time.time()
        records.append(dict(source='postgres', before=before, after=after, remoteEpoch=float(value['epoch']),
                            offsetEstimate=float(value['epoch']) - (before + after) / 2, uncertaintySeconds=(after - before) / 2,
                            timezone=value['timezone'], version=value['version']))
        for name, base in [('mock', mock)] + [('jvm-' + str(i), node) for i, node in enumerate(nodes)]:
            before = time.time()
            if name == 'mock':
                value = require(base, 'GET', '/control/stats')
                remote = value['serverTime']
            else:
                status, value = request(base, 'GET', '/actuator/metrics/notification.clock.epoch.seconds', token=ADMIN)
                remote = next((m['value'] for m in value.get('measurements', []) if m.get('statistic') == 'VALUE'), None) if status == 200 else None
            after = time.time()
            records.append(dict(source=name, before=before, after=after, remoteEpoch=remote,
                                offsetEstimate=None if remote is None else remote - (before + after) / 2,
                                uncertaintySeconds=(after - before) / 2))
    return records


def body_for(prefix, sequence, mode):
    # Burst interleaves A/B so submitting A cannot consume B's input deadline.
    tenant_index = (sequence % 10) + 1
    tenant = 'tenant-%02d' % tenant_index
    event = prefix + '-%06d' % sequence
    base = {'eventId': event, 'tenantId': tenant, 'payload': ''}
    padding = 1024 - len(json.dumps(base, separators=(',', ':')).encode())
    seed = hashlib.sha256(event.encode()).hexdigest()
    payload = (seed * (padding // len(seed) + 1))[:padding]
    return tenant, event, dict(tenantId=tenant, operation='notify', eventId=event, data={'payload': payload})


def inject(api, prefix, stages, output, mode, mock=None):
    lock = threading.Lock()
    capacity = 200 if mode == 'burst' and any(stage[0] == 'burst' for stage in stages) else 128
    slots = threading.BoundedSemaphore(capacity)
    lead = 1 if mock else 0
    origin_mono, origin_wall = time.monotonic() + lead, time.time() + lead
    if mock:
        require(mock, 'POST', '/control/start', {'startAt': origin_wall})
    sequence = 0
    stage_results = []
    with output.open('w') as handle, concurrent.futures.ThreadPoolExecutor(max_workers=capacity, thread_name_prefix='inject') as pool:
        def record(value):
            with lock:
                handle.write(json.dumps(value, separators=(',', ':')) + '\n')
                handle.flush()

        def send(index, planned_mono, planned_epoch, stage):
            tenant, event, value = body_for(prefix, index, mode)
            start_mono, start_epoch = time.monotonic(), time.time()
            result = dict(sequence=index, eventId=event, tenantId=tenant, stage=stage,
                          plannedAt=planned_epoch, startedAt=start_epoch,
                          scheduleLagMs=max(0, (start_mono - planned_mono) * 1000))
            try:
                status, response = request(api, 'POST', '/v1/notifications', value, BUSINESS, timeout=10)
                result.update(status=status, notificationId=response.get('notificationId'), errorCode=response.get('code'))
            except Exception as error:
                result.update(status=0, notificationId=None, clientFailure=type(error).__name__)
            finally:
                result.update(finishedAt=time.time(), latencyMs=(time.monotonic() - start_mono) * 1000)
                record(result)
                slots.release()

        offset = 0.0
        for stage, rps, seconds in stages:
            count = int(round(rps * seconds))
            stage_results.append(dict(name=stage, rps=rps, seconds=seconds, planned=count,
                                      startAt=origin_wall + offset, endAt=origin_wall + offset + seconds))
            for index in range(count):
                scheduled = offset + (index / rps if rps else 0)
                planned_mono, planned_epoch = origin_mono + scheduled, origin_wall + scheduled
                remaining = planned_mono - time.monotonic()
                if remaining > 0:
                    time.sleep(remaining)
                if slots.acquire(blocking=False):
                    pool.submit(send, sequence, planned_mono, planned_epoch, stage)
                else:
                    tenant, event, value = body_for(prefix, sequence, mode)
                    record(dict(sequence=sequence, eventId=event, tenantId=tenant, stage=stage,
                                plannedAt=planned_epoch, startedAt=None, finishedAt=time.time(), status=0,
                                notificationId=None, clientFailure='INJECTOR_CAPACITY', scheduleLagMs=None, latencyMs=None))
                sequence += 1
            offset += seconds
        remaining = origin_mono + offset - time.monotonic()
        if remaining > 0:
            time.sleep(remaining)
    return dict(startAt=origin_wall, endAt=time.time(), planned=sequence, stages=stage_results,
                maxOutstandingRequests=capacity, scheduling='absolute monotonic deadlines; no closed-loop pacing')


class Sampler:
    def __init__(self, directory, prefix, mock, nodes):
        self.directory, self.prefix, self.mock, self.nodes = directory, prefix, mock, nodes
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.loop, name='measurement', daemon=True)
        self.docker_thread = threading.Thread(target=self.docker_loop, name='docker-measurement', daemon=True)
        self.names = list(DEFAULT_METERS)
        self.names.extend(['notification.worker.active', 'notification.worker.queued', 'notification.worker.capacity',
                           'notification.worker.provider.active.max', 'notification.worker.provider.entries', 'notification.worker.provider.capacity',
                           'notification.oauth.cache.entries', 'notification.oauth.inflight', 'notification.oauth.leaders.active',
                           'notification.oauth.leaders.capacity', 'notification.queue.oldest_due.seconds',
                           'notification.queue.sample.success', 'notification.queue.sample.age.seconds', 'notification.queue.sample.failures'])
        self.tagged = [(name, 'client:' + client) for client in ('business', 'token')
                       for name in ('notification.http.connections.leased', 'notification.http.connections.available',
                                    'notification.http.connections.pending', 'notification.http.connections.max',
                                    'notification.http.execution.active', 'notification.http.execution.queued',
                                    'notification.http.execution.permits_used', 'notification.http.execution.capacity')]
        self.tagged.extend([('notification.tasks', 'status:' + status) for status in ('pending','processing','succeeded','failed')])
        self.available = {}
        for name in os.environ.get('PERF_METRICS', '').split(','):
            if name and name not in self.names:
                self.names.append(name)

    def start(self):
        for node in self.nodes:
            names = require(node, 'GET', '/actuator/metrics', token=ADMIN).get('names', [])
            self.available[node] = set(names)
        write_json(self.directory / 'metric-inventory.json', {k: sorted(v) for k,v in self.available.items()})
        self.thread.start()
        self.docker_thread.start()

    def docker_loop(self):
        with (self.directory / 'docker-resources.jsonl').open('w') as output:
            while not self.stop.is_set():
                sample = {'observedAt': time.time(), 'containers': [], 'error': None}
                try:
                    ids = subprocess.run(['docker','compose','ps','-q','--status','running'],cwd=ROOT,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=10,check=True).stdout.split()
                    if not ids:
                        raise RuntimeError('NO_COMPOSE_CONTAINERS')
                    inspected = json.loads(subprocess.run(['docker','inspect',*ids],cwd=ROOT,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=10,check=True).stdout)
                    # Docker inspect contains env credentials in memory: persist this explicit projection only.
                    metadata = {}
                    for item in inspected:
                        metadata[item['Name'].lstrip('/')] = dict(containerId=item['Id'],imageId=item['Image'],
                            memoryLimitBytes=item['HostConfig']['Memory'],nanoCpus=item['HostConfig']['NanoCpus'],
                            pidsLimit=item['HostConfig'].get('PidsLimit'),oomKilled=item['State']['OOMKilled'],
                            parameters={entry.split('=',1)[0]:entry.split('=',1)[1] for entry in item['Config'].get('Env',[])
                                if entry.split('=',1)[0] in ('APP_MODE','APP_ENVIRONMENT','WORKER_GLOBAL_CONCURRENCY',
                                    'WORKER_PROVIDER_CONCURRENCY','WORKER_POLL_INTERVAL','WORKER_BUSINESS_TIMEOUT',
                                    'WORKER_ATTEMPT_TIMEOUT','WORKER_LEASE_DURATION','RETRY_BASE_DELAY','RETRY_MAX_DELAY',
                                    'PROTECTION_OPEN_DURATION','PROTECTION_RETRY_DELAY','PROTECTION_JITTER',
                                    'DELIVERY_MAX_ATTEMPTS','DELIVERY_RUN_DURATION','ADMISSION_REQUESTS_PER_SECOND',
                                    'ADMISSION_BURST_CAPACITY','ADMISSION_MAX_BACKLOG','OBSERVABILITY_SAMPLE_INTERVAL','DB_POOL_SIZE')})
                    raw = subprocess.run(['docker','stats','--no-stream','--format','{{json .}}',*ids],cwd=ROOT,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=15,check=True).stdout
                    for line in raw.splitlines():
                        item = json.loads(line)
                        sample['containers'].append(dict(name=item['Name'],cpuPercent=item['CPUPerc'],memoryUsage=item['MemUsage'],
                            memoryPercent=item['MemPerc'],pids=item['PIDs'],networkIO=item['NetIO'],**metadata.get(item['Name'],{})))
                except Exception as error:
                    sample['error'] = type(error).__name__
                output.write(json.dumps(sample,separators=(',',':'))+'\n')
                output.flush()
                self.stop.wait(5)

    def loop(self):
        with (self.directory / 'resources.jsonl').open('w') as output:
            while not self.stop.is_set():
                started = time.monotonic()
                sample = {'observedAt': time.time(), 'nodes': [], 'errors': []}
                try:
                    condition = prefix_clause(self.prefix)
                    sample['database'] = db("SELECT json_build_object('now',extract(epoch FROM clock_timestamp()),'pending',count(*) FILTER(WHERE status='pending'),'processing',count(*) FILTER(WHERE status='processing'),'succeeded',count(*) FILTER(WHERE status='succeeded'),'failed',count(*) FILTER(WHERE status='failed'),'oldestDueSeconds',COALESCE(extract(epoch FROM clock_timestamp()-min(next_attempt_at) FILTER(WHERE status='pending' AND next_attempt_at<=clock_timestamp())),0)) FROM notification_task WHERE " + condition + ';')[0]
                    sample['mock'] = require(self.mock, 'GET', '/control/stats')
                    for node in self.nodes:
                        try:
                            metrics = {}
                            for name, tag in [(name, None) for name in self.names] + self.tagged:
                                if name not in self.available[node]:
                                    continue  # API-only mode intentionally has no Worker executor gauges.
                                query = ('?tag=' + tag) if tag else ('?tag=area:heap' if name in ('jvm.memory.used','jvm.memory.max') else '')
                                status, data = request(node, 'GET', '/actuator/metrics/' + name + query, token=ADMIN, timeout=2)
                                if status == 200:
                                    metrics[name + ('|' + tag if tag else '')] = data.get('measurements', [])
                                else:
                                    sample['errors'].append('METRIC_' + str(status) + '_' + name)
                            sample['nodes'].append({'endpoint': node, 'metrics': metrics})
                        except Exception as error:
                            sample['errors'].append('NODE_' + node + '_' + type(error).__name__)
                            sample['nodes'].append({'endpoint': node, 'metrics': {}, 'unavailable': True})
                except Exception as error:
                    sample['errors'].append(type(error).__name__)
                sample['sampleDurationSeconds'] = time.monotonic() - started
                output.write(json.dumps(sample, separators=(',', ':')) + '\n')
                output.flush()
                self.stop.wait(max(0, 1 - sample['sampleDurationSeconds']))

    def close(self):
        self.stop.set()
        self.thread.join(60)
        self.docker_thread.join(45)
        if self.thread.is_alive() or self.docker_thread.is_alive():
            raise RuntimeError('SAMPLER_DID_NOT_STOP')


def drain(prefix, limit):
    until = time.monotonic() + limit
    while True:
        values = db("SELECT json_build_object('active',count(*) FILTER(WHERE status IN ('pending','processing')),'total',count(*)) FROM notification_task WHERE " + prefix_clause(prefix) + ';')[0]
        if values['active'] == 0:
            return {'complete': True, 'finishedAt': time.time(), 'total': values['total']}
        if time.monotonic() >= until:
            return {'complete': False, 'finishedAt': time.time(), 'remaining': values['active'], 'total': values['total']}
        time.sleep(.25)


def reset(mock, configuration):
    until = time.monotonic() + 10
    while True:
        status, result = request(mock, 'POST', '/control/reset', configuration)
        if status == 200:
            return result
        if status != 409 or time.monotonic() >= until:
            raise RuntimeError('MOCK_RESET_' + str(status))
        time.sleep(.1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['baseline', 'pressure', 'burst', 'recovery'])
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--api', default='http://127.0.0.1:18080')
    parser.add_argument('--mock', default='http://127.0.0.1:19090')
    parser.add_argument('--downstream', default='http://mock:9090')
    parser.add_argument('--nodes', default='http://127.0.0.1:18080,http://127.0.0.1:18081')
    args = parser.parse_args()
    if not ADMIN or not BUSINESS:
        parser.error('set PERF_ADMIN_TOKEN and PERF_BUSINESS_TOKEN (fake local credentials only)')
    prefix_clause(args.run_id)
    if len(args.run_id) > 45:
        parser.error('run-id maximum length is 45')
    directory = ROOT / '.cache' / 'perf' / args.run_id
    directory.mkdir(parents=True, exist_ok=False)
    prefix = args.run_id + '-main'
    nodes = args.nodes.split(',')
    manifest = dict(runId=args.run_id, mode=args.mode, workloadPrefix=prefix, startedAt=utc(), python=sys.version,
                    platform=platform.platform(), schema=SCHEMA, nodes=nodes, args={k: v for k, v in vars(args).items()},
                    timestamps={'created_at': 'PostgreSQL durable acceptance transaction timestamp',
                                'started_at': 'PostgreSQL T2 reservation; NOT actual HTTP send',
                                'http_send_started_at': 'Worker java.time wall time immediately before transport; includes prior token wait',
                                'mock.receivedAt': 'Docker Mock wall time after request body read',
                                'client.plannedAt': 'parent wall projection of monotonic open-loop planned arrival'},
                    sourceSha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT / 'scripts/perf').glob('*')) if p.is_file()})
    write_json(directory / 'manifest.json', manifest)
    try:
        backlog = db("SELECT json_build_object('active',count(*)) FROM notification_task WHERE status IN ('pending','processing');")[0]
        if backlog['active']:
            raise RuntimeError('PREEXISTING_ACTIVE_BACKLOG')
        reset(args.mock, dict(runId=args.run_id, mode='baseline'))
        write_json(directory / 'configuration-ids.json', install(args.api, args.run_id, args.mode, args.downstream))
        write_json(directory / 'clock-before.json', calibrate(args.api, args.mock, nodes))
        # Separate warmup evidence, with actual OAuth cache population, never included in the baseline percentiles.
        warm = inject(args.api, args.run_id + '-warm', [('warmup', 20, 5)], directory / 'warmup-requests.jsonl', args.mode)
        warm['drain'] = drain(args.run_id + '-warm', 60)
        warm_tasks, warm_attempts = task_snapshot(args.run_id + '-warm')
        write_json(directory / 'warmup.json', warm)
        write_jsonl(directory / 'warmup-tasks.jsonl', warm_tasks)
        write_jsonl(directory / 'warmup-attempts.jsonl', warm_attempts)
        write_jsonl(directory / 'warmup-journal.jsonl', journal(args.mock))
        if not warm['drain']['complete'] or any(t['status'] != 'succeeded' for t in warm_tasks) or len(warm_tasks) != 100:
            raise RuntimeError('WARMUP_FAILED')
        if args.mode in ('burst', 'recovery'):
            db((ROOT / 'scripts/perf/claim_audit.sql').read_text())
        else:
            db('DROP TRIGGER IF EXISTS perf_claim_observation ON notification_task;')
        write_json(directory / 'mock-start.json', reset(args.mock, dict(runId=args.run_id, mode=args.mode,
                   delaySeconds=1 if args.mode == 'recovery' else .1, preserveTokens=True)))
        stages = {'baseline': [('baseline', 20, 600)], 'pressure': [('rps-' + str(rate), rate, 30) for rate in (20, 40, 80, 160)],
                  'burst': [('burst', 2000, .1)], 'recovery': [('recovery', 10, 20)]}[args.mode]
        sampler = Sampler(directory, prefix, args.mock, nodes)
        sampler.start()
        try:
            workload = inject(args.api, prefix, stages, directory / 'requests.jsonl', args.mode, args.mock)
            write_json(directory / 'workload.json', workload)
            workload['drain'] = drain(prefix, {'baseline': 120, 'pressure': 180, 'burst': 60, 'recovery': 240}[args.mode])
            # Burst acceptance window is t0..t0+.1s; retain observation to t0+60 even if all tasks finish earlier.
            if args.mode == 'burst':
                time.sleep(max(0, workload['startAt'] + 60 - time.time()))
            write_json(directory / 'workload.json', workload)
        finally:
            time.sleep(3)  # Retain quiet-after-drain resource samples, including released connections.
            sampler.close()
        tasks, attempts = task_snapshot(prefix)
        write_jsonl(directory / 'tasks.jsonl', tasks)
        write_jsonl(directory / 'attempts.jsonl', attempts)
        write_jsonl(directory / 'journal.jsonl', journal(args.mock))
        write_json(directory / 'mock-final.json', require(args.mock, 'GET', '/control/stats'))
        write_json(directory / 'clock-after.json', calibrate(args.api, args.mock, nodes))
        if args.mode in ('burst', 'recovery'):
            rows = db("SELECT row_to_json(c) FROM (SELECT c.* FROM perf_observation.claim c JOIN notification_task t ON t.id=c.task_id WHERE " + prefix_clause(prefix) + ' ORDER BY c.claimed_at) c;')
            write_jsonl(directory / 'claims.jsonl', rows)
        # Real planner evidence; no enable_seqscan override, no synthetic table-size claim.
        command = json.loads(os.environ.get('PERF_DB_COMMAND_JSON', '["docker","compose","exec","-T","postgres","psql","-U","notification_migrator","-d","notifications","-At","-v","ON_ERROR_STOP=1"]'))
        process = subprocess.run(command, input='SET search_path=' + SCHEMA + '; EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) SELECT count(*) FROM notification_task WHERE status IN (\'pending\',\'processing\');', text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=ROOT, timeout=30)
        if process.returncode:
            raise RuntimeError('EXPLAIN_FAILED')
        (directory / 'backlog-explain.txt').write_text(process.stdout)
        manifest['finishedAt'] = utc()
        manifest['executionComplete'] = True
        write_json(directory / 'manifest.json', manifest)
        print(str(directory), flush=True)
    except Exception as error:
        manifest['executionComplete'] = False
        manifest['failureClass'] = type(error).__name__
        manifest['safeFailure'] = str(error) if isinstance(error, RuntimeError) else 'SEE_EXECUTION_CONTEXT'
        write_json(directory / 'manifest.json', manifest)
        raise SystemExit('CP10 execution incomplete: ' + manifest['safeFailure'])


if __name__ == '__main__':
    main()

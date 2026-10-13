#!/usr/bin/env python3
"""Bounded, real-HTTP demo for the isolated Compose environment; never resets data."""
import argparse
import datetime
import hashlib
import http.client
import json
import os
import pathlib
import re
import sys
import time
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parents[1]
TENANT, OPERATION = 'tenant-01', 'notify'
MAX_RESPONSE = 1024 * 1024


class DemoFailure(Exception):
    """Only fixed, non-sensitive error codes reach the console/evidence."""


def check(condition, code):
    if not condition:
        raise DemoFailure(code)


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def local_base(value):
    parsed = urllib.parse.urlsplit(value)
    if (parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost')
            or parsed.username or parsed.password or parsed.path not in ('', '/')
            or parsed.query or parsed.fragment):
        raise argparse.ArgumentTypeError('Use an HTTP loopback origin for this local demo.')
    try:
        if not parsed.port:
            raise ValueError()
    except ValueError:
        raise argparse.ArgumentTypeError('An explicit valid port is required.') from None
    return value.rstrip('/')


class Demo:
    def __init__(self, args, output):
        self.args, self.output = args, output
        self.admin = os.environ.get('DEMO_ADMIN_TOKEN', 'rc-demo-admin-token')
        self.business = os.environ.get('DEMO_BUSINESS_TOKEN', 'rc-demo-business-token')
        self.evidence = dict(runId=args.run_id, startedAt=utc(), result='RUNNING', checks=[],
                             tasks=[], configurations=[], routeChanges=[], mockObservations=[])
        self.evidence['scriptSha256'] = hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest()
        self.known_tasks = []
        self.expected_bodies = {}
        self.auth = self.good = None
        self.bad_route_active = False

    def save(self):
        # Explicit projections only: no credentials, request/response bodies, headers or Token values.
        temporary = self.output / 'result.tmp'
        temporary.write_text(json.dumps(self.evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        temporary.replace(self.output / 'result.json')

    def passed(self, name):
        self.evidence['checks'].append(dict(name=name, at=utc()))
        self.save()
        print('PASS ' + name, flush=True)

    def request(self, method, path, body=None, *, admin=False, mock=False, expected=(200,)):
        base = urllib.parse.urlsplit(self.args.mock if mock else self.args.api)
        connection = http.client.HTTPConnection(base.hostname, base.port, timeout=5)
        headers = {'Accept': 'application/json'}
        if not mock and not path.startswith('/health/'):
            headers['Authorization'] = 'Bearer ' + (self.admin if admin else self.business)
        data = None if body is None else json.dumps(body, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        if data is not None:
            headers['Content-Type'] = 'application/json'
        try:
            connection.request(method, path, body=data, headers=headers)
            response = connection.getresponse()
            status = response.status
            raw = response.read(MAX_RESPONSE + 1)
            check(len(raw) <= MAX_RESPONSE, 'RESPONSE_TOO_LARGE')
            check(status in expected, 'UNEXPECTED_HTTP_' + str(status))
            value = json.loads(raw) if raw else {}
            check(isinstance(value, dict), 'INVALID_RESPONSE_SHAPE')
            return status, value
        except DemoFailure:
            raise
        except (OSError, http.client.HTTPException, ValueError, UnicodeError):
            raise DemoFailure('HTTP_OR_JSON_FAILURE') from None
        finally:
            connection.close()

    def stats(self):
        return self.request('GET', '/control/stats', mock=True)[1]

    def template(self, version, *, missing=False):
        config = dict(templateKey=self.args.run_id + ('-missing' if missing else '-template'),
                      version=version, ownerTenantId=TENANT, shared=False, providerId='vendor',
                      endpointGroup='notify', method='POST',
                      url='http://mock:9090/' + ('demo-missing' if missing else 'business/vendor'),
                      headers={'X-Event-Id': {'from': 'eventId', 'type': 'string', 'required': True}},
                      body={'format': 'json', 'fields': {
                          'eventId': {'from': 'eventId', 'type': 'string', 'required': True},
                          'tenantId': {'constant': TENANT},
                          'payload': {'from': 'data.payload', 'type': 'string', 'required': True},
                          'templateVersion': {'constant': version}}})
        created = self.request('POST', '/admin/template-versions', config, admin=True, expected=(201,))[1]
        path = '/admin/template-versions/' + created['id']
        self.request('POST', path + '/validate', admin=True)
        preview = self.request('POST', path + '/preview',
                               {'eventId': self.args.run_id + '-preview', 'data': {'payload': 'demo-payload'}}, admin=True)[1]
        check(preview['bodyBytes'] > 0 and preview['contentType'] == 'application/json', 'PREVIEW_ENCODING')
        check('demo-payload' not in json.dumps(preview['bodyPreview']), 'PREVIEW_REDACTION')
        published = self.request('POST', path + '/publish', {'expectedRevision': created['revision']}, admin=True)[1]
        check(published['status'] == 'published', 'TEMPLATE_NOT_PUBLISHED')
        self.evidence['configurations'].append(dict(kind='template', id=published['id'], version=version,
                                                   expectedMissingTarget=missing, previewBytes=preview['bodyBytes']))
        return published

    def oauth(self):
        config = dict(authProfileKey=self.args.run_id + '-oauth', version=1, tenantId=TENANT,
                      type='oauth2_client_credentials', tokenUrl='http://mock:9090/oauth/token',
                      clientId='demo-client', clientSecretRef='env:DEMO_VENDOR_SECRET',
                      clientAuthenticationMethod='client_secret_post', scopes=['notify'])
        created = self.request('POST', '/admin/auth-profiles', config, admin=True, expected=(201,))[1]
        published = self.request('POST', '/admin/auth-profiles/' + created['id'] + '/publish',
                                 {'expectedRevision': created['revision']}, admin=True)[1]
        check(published['status'] == 'published', 'AUTH_NOT_PUBLISHED')
        self.evidence['configurations'].append(dict(kind='oauth2_client_credentials', id=published['id'], version=1))
        return published

    def route(self, template):
        path = '/admin/routes/' + TENANT + '/' + OPERATION
        status, current = self.request('GET', path, admin=True, expected=(200, 404))
        route = self.request('PUT', path, dict(expectedRevision=current['revision'] if status == 200 else 0,
                                              templateVersionId=template['id'], authProfileId=self.auth['id']), admin=True)[1]
        check(route['templateVersionId'] == template['id'] and route['authProfileId'] == self.auth['id'], 'ROUTE_BINDING')
        self.evidence['routeChanges'].append(dict(revision=route['revision'], templateVersionId=template['id'],
                                                 authProfileId=self.auth['id']))
        return route

    def submission(self, suffix, payload='demo-payload'):
        return dict(tenantId=TENANT, operation=OPERATION, eventId=self.args.run_id + '-' + suffix,
                    data={'payload': payload})

    def submit(self, value):
        accepted = self.request('POST', '/v1/notifications', value, expected=(202,))[1]
        check(accepted['deduplicated'] is False and accepted['runNumber'] == 1, 'NEW_ACCEPTANCE')
        self.known_tasks.append(accepted['notificationId'])
        self.save()
        return accepted['notificationId']

    def get_task(self, task_id):
        return self.request('GET', '/v1/notifications/' + task_id)[1]

    def terminal(self, task_id, status, template, *, run=1, attempts=1):
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            task = self.get_task(task_id)
            if task['status'] in ('succeeded', 'failed'):
                check(task['status'] == status, 'UNEXPECTED_TERMINAL_STATE')
                check(task['templateVersionId'] == template['id'] and task['authProfileId'] == self.auth['id'], 'SNAPSHOT_VERSION_CHANGED')
                check(task['runNumber'] == run and task['runAttemptCount'] == 1
                      and task['totalAttemptCount'] == attempts, 'ATTEMPT_ACCOUNTING')
                check(task['lastHttpStatus'] == (200 if status == 'succeeded' else 404), 'FINAL_HTTP_STATUS')
                history = self.request('GET', '/v1/notifications/' + task_id + '/attempts?limit=100')[1]
                check(history['nextCursor'] is None and len(history['items']) == attempts, 'HISTORY_ACCOUNTING')
                check(all(item['endedAt'] and item['httpSendStartedAt'] for item in history['items']), 'MISSING_ACTUAL_HTTP')
                # TaskView/AttemptView already omit all payloads and authentication material.
                self.evidence['tasks'].append(dict(observedAt=utc(), task=task, attempts=history['items']))
                if status == 'succeeded':
                    # Reconstruct from known demo inputs/constants, using only the published field order.
                    # PostgreSQL jsonb may reorder keys; do not assume the creation request's key order.
                    values = dict(eventId=task['eventId'], tenantId=TENANT,
                                  payload='demo-payload', templateVersion=template['version'])
                    order = list(template['body']['fields'])
                    check(set(order) == set(values), 'UNEXPECTED_PUBLISHED_BODY_FIELDS')
                    encoded = json.dumps({name: values[name] for name in order}, ensure_ascii=False,
                                         separators=(',', ':')).encode('utf-8')
                    self.expected_bodies[task['eventId']] = dict(bodyBytes=len(encoded),
                                                                 bodySha256=hashlib.sha256(encoded).hexdigest())
                self.save()
                return task
            time.sleep(.1)
        raise DemoFailure('TERMINAL_STATE_TIMEOUT')

    def journal(self, after):
        selected, token_calls = [], 0
        for unused in range(251):  # Mock itself caps its journal at 250000 entries.
            page = self.request('GET', '/control/journal?' + urllib.parse.urlencode({'after': after, 'limit': 1000}), mock=True)[1]
            for item in page['items']:
                if item['kind'] == 'token':
                    token_calls += int(item['status'] == 200)
                elif item.get('eventId', '').startswith(self.args.run_id + '-'):
                    selected.append({key: item[key] for key in ('sequence', 'kind', 'receivedAt', 'eventId', 'tenantId',
                                     'provider', 'bodyBytes', 'bodySha256', 'duplicateEffectSuppressed', 'status')})
            check(page['next'] >= after, 'MOCK_CURSOR_REGRESSED')
            after = page['next']
            if not page['hasMore']:
                return selected, token_calls
        raise DemoFailure('MOCK_JOURNAL_LIMIT')

    def run(self):
        self.request('GET', '/health/ready')
        self.request('GET', '/health/ready', mock=True)
        before = self.stats()
        self.auth = self.oauth()
        self.good = self.template(1)
        offline = self.stats()
        check(offline['businessCalls'] == before['businessCalls'] and offline['tokenCalls'] == before['tokenCalls'], 'OFFLINE_CONFIGURATION_MADE_NETWORK_CALL')
        self.passed('配置创建、校验、预览、发布不调用供应商或 Token 端点')
        self.route(self.good)

        first_request = self.submission('v1')
        first = self.submit(first_request)
        first_terminal = self.terminal(first, 'succeeded', self.good)
        first_history = self.evidence['tasks'][-1]['attempts']
        duplicate = self.request('POST', '/v1/notifications', first_request, expected=(200,))[1]
        check(duplicate['notificationId'] == first and duplicate['deduplicated']
              and duplicate['status'] == 'succeeded' and duplicate['runNumber'] == 1, 'IDEMPOTENCY_ID_CHANGED')
        self.request('POST', '/v1/notifications', self.submission('v1', 'changed-payload'), expected=(409,))
        time.sleep(.3)
        check(self.get_task(first) == first_terminal, 'SUCCESS_DUPLICATE_CHANGED_TASK')
        history_after_duplicate = self.request('GET', '/v1/notifications/' + first + '/attempts?limit=100')[1]
        check(history_after_duplicate['nextCursor'] is None and history_after_duplicate['items'] == first_history,
              'SUCCESS_DUPLICATE_CREATED_ATTEMPT')
        self.evidence['succeededEventDuplicate'] = dict(notificationId=first, status=duplicate['status'],
                                                        runNumber=duplicate['runNumber'], httpStatus=200,
                                                        taskAndHistoryUnchanged=True, observationDelaySeconds=.3)
        self.passed('真实 OAuth 与业务 HTTP 成功；同事件相同请求 200，不同请求 409')

        second = self.template(2)
        self.route(second)
        self.terminal(self.submit(self.submission('v2')), 'succeeded', second)
        after_upgrade = self.request('POST', '/v1/notifications', first_request, expected=(200,))[1]
        check(after_upgrade == duplicate and self.get_task(first) == first_terminal, 'OLD_TASK_RE_RENDERED')
        check(self.request('GET', '/v1/notifications/' + first + '/attempts?limit=100')[1] == history_after_duplicate,
              'OLD_TASK_HISTORY_CHANGED')
        self.passed('路由升版只影响新任务，旧事件仍引用 V1')
        self.route(self.good)
        self.terminal(self.submit(self.submission('rollback')), 'succeeded', self.good)
        self.passed('路由回滚后新任务使用 V1')

        missing = self.template(1, missing=True)
        self.route(missing)
        self.bad_route_active = True
        failed = self.submit(self.submission('missing'))
        self.terminal(failed, 'failed', missing)
        self.route(self.good)
        self.bad_route_active = False
        replay_path = '/admin/notifications/' + failed + '/replay'
        replay_request = dict(replayRequestId=self.args.run_id + '-replay', expectedRunNumber=1,
                              reason='Demonstrate immutable original request snapshot')
        response = self.request('POST', replay_path, replay_request, admin=True)[1]
        check(response['runNumber'] == 2 and response['status'] == 'pending', 'REPLAY_RESPONSE')
        final = self.terminal(failed, 'failed', missing, run=2, attempts=2)
        history = self.evidence['tasks'][-1]['attempts']
        check({item['runNumber'] for item in history} == {1, 2}, 'REPLAY_HISTORY_LOST')
        repeated = self.request('POST', replay_path, replay_request, admin=True)[1]
        check(repeated == response, 'REPLAY_IDEMPOTENCY_RESPONSE_CHANGED')
        self.request('POST', replay_path, dict(replay_request, reason='different reason'), admin=True, expected=(409,))
        check(final['lastHttpStatus'] == 404, 'ORIGINAL_SNAPSHOT_NOT_REPLAYED')
        duplicate_failed = self.request('POST', '/v1/notifications', self.submission('missing'), expected=(200,))[1]
        check(duplicate_failed['notificationId'] == failed and duplicate_failed['deduplicated']
              and duplicate_failed['status'] == 'failed' and duplicate_failed['runNumber'] == 2,
              'FAILED_EVENT_DUPLICATE_RESET')
        # Longer than two default Compose poll intervals. An ordinary duplicate must never replay.
        time.sleep(.3)
        check(self.get_task(failed) == final, 'FAILED_DUPLICATE_CHANGED_TASK')
        unchanged_history = self.request('GET', '/v1/notifications/' + failed + '/attempts?limit=100')[1]
        check(unchanged_history['nextCursor'] is None and unchanged_history['items'] == history,
              'FAILED_DUPLICATE_CREATED_ATTEMPT')
        self.evidence['failedEventDuplicate'] = dict(notificationId=failed, status=duplicate_failed['status'],
                                                     runNumber=duplicate_failed['runNumber'], httpStatus=200,
                                                     taskAndHistoryUnchanged=True, observationDelaySeconds=.3)
        self.evidence['expectedFailure'] = dict(notificationId=failed, status='failed', httpStatus=404,
                                                 runNumber=2, totalAttemptCount=2,
                                                 explanation='Replay retains the original missing-target snapshot despite route rollback.')
        self.passed('预期失败：404 任务重投保留原快照；run 增加、历史保留、重投键固定响应')
        self.passed('失败事件普通重复提交仍为 failed/run 2，截止、计数及完整历史不变')

        self.terminal(self.submit(self.submission('healthy-after-replay')), 'succeeded', self.good)
        observed, token_calls = self.journal(before['journalEntries'])
        expected_events = {self.args.run_id + '-' + suffix for suffix in ('v1', 'v2', 'rollback', 'healthy-after-replay')}
        check(len(observed) == 4 and {item['eventId'] for item in observed} == expected_events, 'MOCK_BUSINESS_ACCOUNTING')
        check(all(item['status'] == 200 and not item['duplicateEffectSuppressed'] for item in observed), 'UNEXPECTED_MOCK_DUPLICATE')
        check(set(self.expected_bodies) == expected_events, 'EXPECTED_BODY_ACCOUNTING')
        for item in observed:
            expected_body = self.expected_bodies[item['eventId']]
            check(item['bodyBytes'] == expected_body['bodyBytes']
                  and item['bodySha256'] == expected_body['bodySha256'], 'MOCK_BODY_BYTES_OR_SHA256_MISMATCH')
            item.update(expectedBodyBytes=expected_body['bodyBytes'], expectedBodySha256=expected_body['bodySha256'],
                        bodyMatched=True)
        check(token_calls >= 1, 'NO_REAL_OAUTH_EXCHANGE')
        after = self.stats()
        check(after['journalOverflows'] == before['journalOverflows'] and after['authRejections'] == before['authRejections'], 'MOCK_REJECTED_OR_OVERFLOWED')
        self.evidence['mockObservations'] = observed
        self.evidence['oauthSuccessfulExchangesObserved'] = token_calls
        self.evidence['acceptedTaskIds'] = self.known_tasks
        self.evidence['acceptedTasks'] = 5
        self.evidence['expectedSucceededTasks'] = 4
        self.evidence['expectedFailedTasks'] = 1
        self.passed('对账：5 个新任务，4 个成功，1 个预期失败；无 Mock reset 或数据删除')
        self.evidence.update(result='PASS', finishedAt=utc())
        self.save()

    def fail(self, code):
        # Restore a usable route if a failure interrupted the intentionally bad-route window.
        if self.bad_route_active and self.good and self.auth:
            try:
                self.route(self.good)
                self.evidence['failureRouteRestored'] = True
            except Exception:
                self.evidence['failureRouteRestored'] = False
        self.evidence.update(result='FAIL', failureCode=code, acceptedTaskIds=self.known_tasks, finishedAt=utc())
        self.save()


def main():
    if sys.version_info < (3, 11):
        print('Python 3.11+ is required.', file=sys.stderr)
        return 2
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-id', required=True, help='New identifier, 1–40 ASCII letters/digits/_/-. Reuse is rejected.')
    parser.add_argument('--api', type=local_base, default='http://127.0.0.1:18080')
    parser.add_argument('--mock', type=local_base, default='http://127.0.0.1:19090')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,39}', args.run_id):
        parser.error('run-id must be 1–40 ASCII letters/digits/_/- and start with a letter or digit')
    output = ROOT / '.cache' / 'demo' / args.run_id
    try:
        output.mkdir(parents=True, exist_ok=False, mode=0o700)
    except FileExistsError:
        print('RUN_ID_ALREADY_EXISTS: choose a new run-id; no evidence was overwritten.', file=sys.stderr)
        return 2
    demo = Demo(args, output)
    try:
        demo.run()
        print('PASS evidence=' + str(output / 'result.json'))
        return 0
    except DemoFailure as failure:
        demo.fail(str(failure))
        print('FAIL ' + str(failure) + '; evidence=' + str(output / 'result.json'), file=sys.stderr)
        return 1
    except Exception:
        demo.fail('UNEXPECTED_DEMO_FAILURE')
        print('FAIL UNEXPECTED_DEMO_FAILURE; evidence=' + str(output / 'result.json'), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())

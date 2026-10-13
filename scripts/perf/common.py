"""Small CP10 helpers; command arguments and evidence never contain credentials."""
import datetime
import http.client
import json
import os
import pathlib
import re
import subprocess
import time
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMA = os.environ.get('PERF_DB_SCHEMA', 'notifications')
if not re.fullmatch(r'[a-z_][a-z0-9_]*', SCHEMA):
    raise ValueError('invalid PERF_DB_SCHEMA')


def utc(epoch=None):
    return datetime.datetime.fromtimestamp(time.time() if epoch is None else epoch, datetime.timezone.utc).isoformat()


def write_json(path, value):
    pathlib.Path(path).write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def write_jsonl(path, values):
    with pathlib.Path(path).open('w') as output:
        for value in values:
            output.write(json.dumps(value, separators=(',', ':'), sort_keys=True) + '\n')


def read_jsonl(path):
    return [json.loads(line) for line in pathlib.Path(path).read_text().splitlines() if line.strip()]


def request(base, method, path, value=None, token=None, timeout=10):
    target = urllib.parse.urlsplit(base)
    connection = (http.client.HTTPSConnection if target.scheme == 'https' else http.client.HTTPConnection)(target.hostname, target.port, timeout=timeout)
    headers = {'Connection': 'close'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    raw = None
    if value is not None:
        raw = json.dumps(value, separators=(',', ':'), ensure_ascii=False).encode()
        headers['Content-Type'] = 'application/json'
    try:
        connection.request(method, path, raw, headers)
        response = connection.getresponse()
        body = response.read(1048577)
        if len(body) > 1048576:
            raise ValueError('response limit')
        try:
            decoded = json.loads(body) if body else {}
        except (ValueError, UnicodeError):
            decoded = {}
        # Callers explicitly project fields. Never write this full body or authorization to evidence.
        return response.status, decoded
    finally:
        connection.close()


def require(base, method, path, value=None, token=None, expected=(200,)):
    status, result = request(base, method, path, value, token)
    if status not in expected:
        raise RuntimeError('HTTP_STATUS_' + str(status) + ' at ' + path.split('?')[0])
    return result


def db(sql):
    command = json.loads(os.environ.get('PERF_DB_COMMAND_JSON', '["docker","compose","exec","-T","postgres","psql","-U","notification_migrator","-d","notifications","-At","-v","ON_ERROR_STOP=1"]'))
    result = subprocess.run(command, input='SET search_path=' + SCHEMA + ';\n' + sql, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, cwd=ROOT)
    if result.returncode:
        # SQL/error text may include sensitive DB values; report a safe error instead.
        raise RuntimeError('DATABASE_COMMAND_FAILED_' + str(result.returncode))
    return [json.loads(line) for line in result.stdout.splitlines() if line.startswith('{') or line.startswith('[')]


def prefix_clause(prefix):
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,60}', prefix):
        raise ValueError('run id must be 1..60 ASCII alphanumeric/hyphen/underscore')
    # LIKE escapes underscore so another run cannot be accidentally reconciled.
    return "event_id LIKE '" + prefix.replace('_', '\\_') + "-%' ESCAPE '\\'"


def task_snapshot(prefix):
    condition = prefix_clause(prefix)
    tasks = db("SELECT row_to_json(t) FROM (SELECT id,tenant_id,operation,event_id,provider_id,status,run_number,run_attempt_count,total_attempt_count,created_at,updated_at,deadline_at,next_attempt_at,last_outcome,last_error_code,last_http_status FROM notification_task WHERE " + condition + " ORDER BY created_at,id) t;")
    attempts = db("SELECT row_to_json(a) FROM (SELECT a.task_id,a.run_number,a.attempt_no,a.lease_token,a.started_at,a.request_started_at,a.http_send_started_at,a.ended_at,a.phase,a.outcome,a.http_status,a.error_code,a.execution_deadline FROM notification_attempt a JOIN notification_task t ON t.id=a.task_id WHERE " + condition + " ORDER BY a.task_id,a.run_number,a.attempt_no) a;")
    return tasks, attempts


def journal(mock):
    values, after = [], 0
    while True:
        page = require(mock, 'GET', '/control/journal?after=' + str(after) + '&limit=1000')
        values.extend(page['items'])
        if not page['hasMore']:
            return values
        if page['next'] <= after:
            raise RuntimeError('JOURNAL_CURSOR_DID_NOT_ADVANCE')
        after = page['next']


def epoch(value):
    if value is None:
        return None
    return datetime.datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()

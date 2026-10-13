#!/usr/bin/env python3
"""Bounded, standalone CP10 supplier. No request bodies or credentials are logged."""
import argparse
import base64
import hashlib
import hmac
import json
import os
import secrets
import socketserver
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer


class State:
    def __init__(self):
        self.lock = threading.RLock()
        self.active = self.peak = self.capacity_rejections = 0
        self.reset({})

    def reset(self, configuration):
        with self.lock:
            self.run_id = configuration.get('runId', 'unconfigured')
            self.mode = configuration.get('mode', 'baseline')
            self.delay = float(configuration.get('delaySeconds', .1))
            self.token_delay = float(configuration.get('tokenDelaySeconds', .05))
            self.fault_seconds = float(configuration.get('faultSeconds', 15))
            self.timeout_delay = float(configuration.get('timeoutSeconds', 3))
            self.response_bytes = int(configuration.get('responseBytes', 0))
            if not 0 <= self.response_bytes <= 16 * 1024 * 1024:
                raise ValueError('response byte limit')
            if self.mode not in ('baseline', 'pressure', 'burst', 'recovery') or not all(0 <= v <= 30 for v in (self.delay, self.token_delay, self.fault_seconds, self.timeout_delay)):
                raise ValueError('invalid configuration')
            self.started = time.time()
            self.started_mono = time.monotonic()
            self.journal = []
            self.ledger = set()
            self.tokens = {key: expiry for key, expiry in getattr(self, 'tokens', {}).items() if expiry > time.monotonic()} if configuration.get('preserveTokens', False) else {}
            self.token_calls = self.business_calls = self.auth_rejections = self.journal_overflows = 0
            self.capacity_rejections = 0
            self.peak = self.active

    def append(self, value):
        with self.lock:
            if len(self.journal) >= 250000:
                self.journal_overflows += 1
                return False
            self.journal.append(dict(sequence=len(self.journal) + 1, **value))
            return True

    def stats(self):
        with self.lock:
            return dict(runId=self.run_id, mode=self.mode, serverTime=time.time(), startedAt=self.started,
                        activeHandlers=self.active, peakHandlers=self.peak, handlerCapacity=128,
                        capacityRejections=self.capacity_rejections, journalOverflows=self.journal_overflows,
                        journalEntries=len(self.journal), businessCalls=self.business_calls,
                        tokenCalls=self.token_calls, authRejections=self.auth_rejections,
                        sideEffects=len(self.ledger), delaySeconds=self.delay, tokenDelaySeconds=self.token_delay,
                        faultSeconds=self.fault_seconds, timeoutSeconds=self.timeout_delay, tokenLifetimeSeconds=60,
                        responseBytes=self.response_bytes)


STATE = State()


class BoundedServer(socketserver.ThreadingMixIn, HTTPServer):
    # Thread creation is bounded BEFORE dispatch. Saturation is an explicit failed observation.
    daemon_threads = True
    request_queue_size = 128

    def __init__(self, address, handler):
        super().__init__(address, handler)
        self.slots = threading.BoundedSemaphore(128)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            with STATE.lock:
                STATE.capacity_rejections += 1
            try:
                request.sendall(b'HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n')
            finally:
                self.shutdown_request(request)
            return
        with STATE.lock:
            STATE.active += 1
            STATE.peak = max(STATE.peak, STATE.active)
        try:
            super().process_request(request, address)
        except BaseException:
            self.slots.release()
            with STATE.lock:
                STATE.active -= 1
            raise

    def process_request_thread(self, request, address):
        try:
            request.settimeout(10)
            super().process_request_thread(request, address)
        finally:
            with STATE.lock:
                STATE.active -= 1
            self.slots.release()

    def handle_error(self, request, address):
        # Never print request/header/body/exception context.
        pass


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *unused):
        pass

    def respond(self, status, value):
        raw = json.dumps(value, separators=(',', ':')).encode()
        try:
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(raw)))
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass
        self.close_connection = True

    def read_body(self):
        size = int(self.headers.get('Content-Length', '0'))
        if not 0 <= size <= 65536 or self.headers.get('Transfer-Encoding'):
            raise ValueError('bounded Content-Length required')
        data = self.rfile.read(size)
        if len(data) != size:
            raise ValueError('incomplete body')
        return data

    def do_GET(self):
        parsed = urllib.parse.urlsplit(self.path)
        if parsed.path in ('/health/live', '/health/ready'):
            return self.respond(200, {'status': 'UP', 'serverTime': time.time()})
        if parsed.path == '/control/stats':
            return self.respond(200, STATE.stats())
        if parsed.path == '/control/journal':
            try:
                params = urllib.parse.parse_qs(parsed.query)
                after = int(params.get('after', ['0'])[0])
                limit = int(params.get('limit', ['1000'])[0])
                if after < 0 or not 1 <= limit <= 1000:
                    raise ValueError()
                with STATE.lock:
                    items = STATE.journal[after:after + limit]
                    last = items[-1]['sequence'] if items else after
                    more = last < len(STATE.journal)
                return self.respond(200, {'items': items, 'next': last, 'hasMore': more})
            except ValueError:
                return self.respond(400, {'error': 'INVALID_CURSOR'})
        return self.respond(404, {'error': 'NOT_FOUND'})

    def do_POST(self):
        try:
            body = self.read_body()
            path = urllib.parse.urlsplit(self.path).path
            if path == '/control/reset':
                with STATE.lock:
                    if STATE.active > 1:
                        return self.respond(409, {'error': 'MOCK_BUSY'})
                    STATE.reset(json.loads(body))
                return self.respond(200, STATE.stats())
            if path == '/control/start':
                value = json.loads(body)
                start_at = float(value['startAt'])
                with STATE.lock:
                    if STATE.business_calls or not 0 <= start_at - time.time() <= 10:
                        return self.respond(409, {'error': 'INVALID_START'})
                    STATE.started = start_at
                    STATE.started_mono = time.monotonic() + start_at - time.time()
                return self.respond(200, STATE.stats())
            if path == '/oauth/token':
                return self.token(body)
            if path.startswith('/business/'):
                return self.business(path, body)
            return self.respond(404, {'error': 'NOT_FOUND'})
        except (ValueError, UnicodeError, KeyError):
            return self.respond(400, {'error': 'INVALID_REQUEST'})

    def token(self, body):
        received = time.time()
        form = urllib.parse.parse_qs(body.decode('utf-8'), strict_parsing=True)
        client = form.get('client_id', [''])[0]
        secret = form.get('client_secret', [''])[0]
        authorization = self.headers.get('Authorization', '')
        if authorization.startswith('Basic '):
            decoded = base64.b64decode(authorization[6:], validate=True).decode('utf-8')
            left, right = decoded.split(':', 1)
            client, secret = urllib.parse.unquote_plus(left), urllib.parse.unquote_plus(right)
        expected_secret = os.environ.get('DEMO_VENDOR_SECRET', '')
        expected_client = os.environ.get('PERF_OAUTH_CLIENT_ID', 'demo-client')
        valid = bool(expected_secret) and hmac.compare_digest(secret.encode(), expected_secret.encode()) and hmac.compare_digest(client.encode(), expected_client.encode()) and form.get('grant_type') == ['client_credentials']
        with STATE.lock:
            STATE.token_calls += 1
            if not valid:
                STATE.auth_rejections += 1
        # Deliberately no token request body, body hash, client ID, scope, or credential evidence.
        if not STATE.append({'kind': 'token', 'receivedAt': received, 'path': '/oauth/token', 'status': 200 if valid else 401}):
            return self.respond(503, {'error': 'JOURNAL_CAPACITY'})
        if not valid:
            return self.respond(401, {'error': 'invalid_client'})
        time.sleep(STATE.token_delay)
        token = secrets.token_urlsafe(32)
        with STATE.lock:
            STATE.tokens = {key: expiry for key, expiry in STATE.tokens.items() if expiry > time.monotonic()}
            if len(STATE.tokens) >= 10000:
                STATE.capacity_rejections += 1
                return self.respond(503, {'error': 'TOKEN_CAPACITY'})
            STATE.tokens[token] = time.monotonic() + 60
        return self.respond(200, {'access_token': token, 'token_type': 'Bearer', 'expires_in': 60})

    def business(self, path, body):
        received = time.time()
        bearer = self.headers.get('Authorization', '')
        with STATE.lock:
            valid = bearer.startswith('Bearer ') and STATE.tokens.get(bearer[7:], 0) > time.monotonic()
            STATE.business_calls += 1
            if not valid:
                STATE.auth_rejections += 1
        if not valid:
            STATE.append({'kind': 'business', 'receivedAt': received, 'path': path, 'status': 401})
            return self.respond(401, {'error': 'UNAUTHORIZED'})
        value = json.loads(body)
        event = value['eventId']
        tenant = value['tenantId']
        if not isinstance(event, str) or not isinstance(tenant, str) or len(event) > 128 or len(tenant) > 64:
            raise ValueError()
        if event != self.headers.get('X-Event-Id'):
            return self.respond(400, {'error': 'EVENT_MISMATCH'})
        provider = path.rsplit('/', 1)[-1]
        with STATE.lock:
            if len(STATE.journal) >= 250000:
                STATE.journal_overflows += 1
                return self.respond(503, {'error': 'JOURNAL_CAPACITY'})
            if len(STATE.ledger) >= 250000 and (tenant, event) not in STATE.ledger:
                STATE.capacity_rejections += 1
                return self.respond(503, {'error': 'LEDGER_CAPACITY'})
            duplicate = (tenant, event) in STATE.ledger
            STATE.ledger.add((tenant, event))
            fault = STATE.mode == 'burst' and provider == 'A' and time.monotonic() - STATE.started_mono < STATE.fault_seconds
        STATE.append({'kind': 'business', 'receivedAt': received, 'path': path, 'eventId': event,
                      'tenantId': tenant, 'provider': provider, 'bodyBytes': len(body),
                      'bodySha256': hashlib.sha256(body).hexdigest(), 'duplicateEffectSuppressed': duplicate,
                      'responseBehavior': 'timeout' if fault else 'ack', 'status': None if fault else 200})
        time.sleep(STATE.timeout_delay if fault else STATE.delay)
        if STATE.response_bytes:
            try:
                self.send_response(200)
                self.send_header('Content-Length', str(STATE.response_bytes))
                self.send_header('Connection', 'close')
                self.end_headers()
                remaining = STATE.response_bytes
                block = b'x' * 8192
                while remaining:
                    size = min(remaining, len(block))
                    self.wfile.write(block[:size])
                    remaining -= size
            except (BrokenPipeError, ConnectionResetError, TimeoutError):
                pass
            self.close_connection = True
            return
        return self.respond(200, {'accepted': True})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='0.0.0.0')
    parser.add_argument('--port', type=int, default=9090)
    args = parser.parse_args()
    BoundedServer((args.host, args.port), Handler).serve_forever(poll_interval=.1)


if __name__ == '__main__':
    main()

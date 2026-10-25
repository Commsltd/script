# SPDX-License-Identifier: GPL-3.0-only
"""Small transport boundary independent of Kodi, so it can be tested off-device."""
import hmac
import hashlib
import json
import os
import socket
import stat
import threading
import time
import re
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn


def request_signature(token, path, timestamp, nonce, body):
    prefix = 'uktv-request-v1\nPOST\n%s\n%s\n%s\n' % (path, timestamp, nonce)
    return hmac.new(token.encode(), prefix.encode() + body, hashlib.sha256).hexdigest()


def response_signature(token, nonce, status, body):
    prefix = 'uktv-response-v1\n%s\n%d\n' % (nonce, status)
    return hmac.new(token.encode(), prefix.encode() + body, hashlib.sha256).hexdigest()


def read_token(path):
    fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
    with os.fdopen(fd, 'rb') as handle:
        if not stat.S_ISREG(os.fstat(handle.fileno()).st_mode):
            raise RuntimeError('Invalid local provisioning')
        raw = handle.read(258)
    token = raw.strip()
    if len(raw) > 257 or not 32 <= len(token) <= 256 or any(b < 33 or b > 126 for b in token):
        raise RuntimeError('Invalid local provisioning')
    return token.decode('ascii')


def load_token(private_path, provision_path):
    # A shared-storage file is only accepted at first provisioning. Once paired,
    # its presence must never replace the existing app-private credential.
    if os.path.lexists(private_path):
        token = read_token(private_path)
        os.chmod(private_path, 0o600)
        return token
    if not os.path.exists(provision_path):
        raise RuntimeError('UK TV bridge requires local provisioning')
    token = read_token(provision_path)
    fd = os.open(private_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    with os.fdopen(fd, 'w') as handle:
        handle.write(token)
    os.unlink(provision_path)
    return token


class LocalServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    block_on_close = False
    request_queue_size = 4
    request_timeout = 3.0

    def __init__(self, address, handler, token):
        if address[0] != '127.0.0.1':
            raise ValueError('The bridge must bind to loopback')
        self.token = token
        self.slots = threading.BoundedSemaphore(4)
        self.nonces = {}
        self.auth_lock = threading.Lock()
        super().__init__(address, handler)

    def authenticate(self, path, timestamp, nonce, signature, body):
        now = int(time.time())
        if not re.fullmatch(r'[0-9]{1,12}', timestamp) or abs(now-int(timestamp)) > 60:
            return False
        if not re.fullmatch(r'[a-f0-9]{32}', nonce) or not re.fullmatch(r'[a-f0-9]{64}', signature):
            return False
        if not hmac.compare_digest(signature, request_signature(self.token, path, timestamp, nonce, body)):
            return False
        with self.auth_lock:
            self.nonces = {key:expiry for key,expiry in self.nonces.items() if expiry >= now}
            if nonce in self.nonces or len(self.nonces) >= 1024:
                return False
            # Retain through the entire validity interval, including future timestamps.
            self.nonces[nonce] = int(timestamp) + 60
        return True

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()

    def handle_error(self, request, client_address):
        # Never dump request bodies, credentials or provider links into Kodi logs.
        pass


class LocalHandler(BaseHTTPRequestHandler):
    server_version = 'UKTV'
    sys_version = ''

    def setup(self):
        self.request.settimeout(self.server.request_timeout)
        super().setup()
        # Inactivity timeouts alone allow a slow trickle to monopolise a slot.
        self.deadline = threading.Timer(self.server.request_timeout, self.expire)
        self.deadline.daemon = True
        self.deadline.start()

    def expire(self):
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass

    def finish(self):
        self.deadline.cancel()
        super().finish()

    def log_message(self, *args):
        pass

    def reply(self, code, data):
        raw = json.dumps(data).encode()
        if len(raw) > 2 * 1024 * 1024:
            code, raw = 500, b'{"error":"Response too large"}'
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        if getattr(self, 'request_nonce', None):
            self.send_header('X-UKTV-Signature', response_signature(self.server.token, self.request_nonce, code, raw))
        self.send_header('Connection', 'close')
        self.end_headers()
        self.close_connection = True
        self.wfile.write(raw)

    def do_POST(self):
        try:
            # This is a native loopback API, never a browser API. Exact Host also
            # stops DNS-rebinding web origins from addressing the service.
            expected_host = '127.0.0.1:%d' % self.server.server_port
            if self.headers.get_all('Host', []) != [expected_host] or self.headers.get_all('Origin'):
                return self.reply(403, {'error': 'Native local requests only'})
            auth = [self.headers.get_all(key, []) for key in ('X-UKTV-Time','X-UKTV-Nonce','X-UKTV-Signature')]
            if any(len(values) != 1 for values in auth):
                return self.reply(401, {'error': 'Unauthorised'})
            lengths = self.headers.get_all('Content-Length', [])
            if self.headers.get_all('Transfer-Encoding') or len(lengths) != 1 or len(lengths[0]) > 4 or not lengths[0].isascii() or not lengths[0].isdigit():
                return self.reply(400, {'error': 'Invalid request framing'})
            length = int(lengths[0])
            if not 0 <= length <= 4096:
                return self.reply(413, {'error': 'Request too large'})
            if self.headers.get_all('Content-Type', []) != ['application/json']:
                return self.reply(415, {'error': 'JSON required'})
            raw = self.rfile.read(length)
            if len(raw) != length:
                return self.reply(400, {'error': 'Incomplete request'})
            timestamp, nonce, signature = [values[0] for values in auth]
            if not self.server.authenticate(self.path, timestamp, nonce, signature, raw):
                return self.reply(401, {'error': 'Unauthorised'})
            self.request_nonce = nonce
            body = json.loads(raw or b'{}')
            if not isinstance(body, dict):
                return self.reply(400, {'error': 'JSON object required'})
            self.dispatch(body)
        except (ValueError, KeyError, TypeError):
            self.reply(400, {'error': 'Invalid request'})
        except (OSError, TimeoutError):
            self.close_connection = True

import http.client
import json
import os
import socket
import sys
import tempfile
import threading
import time
import unittest
import secrets
import hmac
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'service.uktelevision.crewtest'))
from bridge_security import LocalHandler, LocalServer, load_token, request_signature, response_signature

TOKEN = 'test-only-token-' + 'a' * 40


class AuthVectors(unittest.TestCase):
    def test_matches_android_vectors(self):
        self.assertEqual('4644a02180ecac49931a6a713e4008fd1b75ccbb93ec28bcc88e4cff2966a3fe', request_signature('a'*64,'/status','1700000000','b'*32,b'{}'))
        self.assertEqual('077b3b9e7943f8d2de59d7012cb335b40110919d0054670db927571678f8f00b', response_signature('a'*64,'b'*32,200,b'{"status":"idle"}'))


class TestHandler(LocalHandler):
    def dispatch(self, body):
        self.reply(200, {'ok': True})


class BridgeTransportTests(unittest.TestCase):
    def setUp(self):
        self.server = LocalServer(('127.0.0.1', 0), TestHandler, TOKEN)
        self.server.request_timeout = 0.6
        self.worker = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': .02}, daemon=True)
        self.worker.start()
        self.port = self.server.server_port

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(timeout=2)

    def sign(self, body, path='/status', timestamp=None):
        timestamp = str(int(time.time())) if timestamp is None else str(timestamp)
        nonce=secrets.token_hex(16)
        return {'X-UKTV-Time':timestamp,'X-UKTV-Nonce':nonce,
                'X-UKTV-Signature':request_signature(TOKEN,path,timestamp,nonce,body)}

    def test_replay_expiry_body_and_route_tampering_rejected(self):
        headers=self.sign(b'{}')
        first=self.request(extra=headers)
        self.assertEqual(200,first[0])
        self.assertTrue(hmac.compare_digest(first[2]['X-UKTV-Signature'],response_signature(TOKEN,headers['X-UKTV-Nonce'],first[0],first[1])))
        self.assertEqual(401,self.request(extra=headers)[0])
        self.assertEqual(401,self.request(body=b'{"changed":true}',extra=self.sign(b'{}'))[0])
        self.assertEqual(401,self.request(extra=self.sign(b'{}',path='/quit'))[0])
        self.assertEqual(401,self.request(extra=self.sign(b'{}',timestamp=int(time.time())-120))[0])

    def request(self, body=b'{}', extra=None, authenticated=True):
        client = http.client.HTTPConnection('127.0.0.1', self.port, timeout=2)
        headers = {'Content-Type': 'application/json'}
        if authenticated:
            headers.update(self.sign(body))
        headers.update(extra or {})
        try:
            client.request('POST', '/status', body=body, headers=headers)
            response = client.getresponse()
            return response.status, response.read(), dict(response.getheaders())
        finally:
            client.close()

    def test_native_authenticated_request_and_non_cacheable_response(self):
        code, body, headers = self.request()
        self.assertEqual(200, code)
        self.assertEqual({'ok': True}, json.loads(body))
        self.assertEqual('no-store', headers['Cache-Control'])
        self.assertEqual('close', headers['Connection'])
        self.assertIn('X-UKTV-Signature', headers)

    def test_unauthenticated_and_browser_origins_rejected(self):
        self.assertEqual(401, self.request(authenticated=False)[0])
        self.assertEqual(401, self.request(extra={'X-UKTV-Signature': '0'*64})[0])
        self.assertEqual(403, self.request(extra={'Origin': 'https://video.example.com'})[0])
        self.assertEqual(403, self.request(extra={'Origin': 'null'})[0])
        self.assertEqual(403, self.request(extra={'Host': 'rebind.example.com:' + str(self.port)})[0])

    def test_request_body_and_framing_limits(self):
        self.assertEqual(413, self.request(body=b'x'*4097)[0])
        self.assertEqual(400, self.request(body=b'[]')[0])
        self.assertEqual(400, self.request(body=b'{broken')[0])
        self.assertEqual(400, self.request(extra={'Transfer-Encoding': 'chunked'})[0])
        self.assertEqual(415, self.request(extra={'Content-Type': 'text/plain'})[0])

    def test_duplicate_content_length_is_rejected(self):
        with socket.create_connection(('127.0.0.1', self.port), timeout=2) as client:
            request = 'POST /status HTTP/1.1\r\nHost: 127.0.0.1:%d\r\n' % self.port
            request += ''.join('%s: %s\r\n' % pair for pair in self.sign(b'{}').items())
            request += 'Content-Type: application/json\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}'
            client.sendall(request.encode())
            self.assertIn(b'400', client.recv(4096).split(b'\r\n')[0])

    def test_partial_client_does_not_block_valid_requests_and_is_closed(self):
        with socket.create_connection(('127.0.0.1', self.port), timeout=2) as stalled:
            stalled.sendall(b'POST /status HTTP/1.1\r\nX-Slow: ')
            self.assertEqual(200, self.request()[0])
            started = time.monotonic()
            self.assertEqual(b'', stalled.recv(4096))
            self.assertLess(time.monotonic()-started, 1.8)

    def test_body_trickle_cannot_extend_absolute_deadline(self):
        with socket.create_connection(('127.0.0.1', self.port), timeout=2) as client:
            request = 'POST /status HTTP/1.1\r\nHost: 127.0.0.1:%d\r\n' % self.port
            request += ''.join('%s: %s\r\n' % pair for pair in self.sign(b' '*1000).items())
            request += 'Content-Type: application/json\r\nContent-Length: 1000\r\n\r\n'
            client.sendall(request.encode())
            started=time.monotonic()
            for _ in range(10):
                try:
                    client.sendall(b' ')
                except OSError:
                    break
                time.sleep(.1)
            try:
                self.assertEqual(b'', client.recv(4096))
            except ConnectionResetError:
                pass  # Both EOF and reset close an unfinished request on deadline.
            self.assertLess(time.monotonic()-started, 1.8)
            self.assertEqual(200, self.request()[0])

    def test_server_cannot_be_exposed_to_lan(self):
        with self.assertRaises(ValueError):
            LocalServer(('0.0.0.0', 0), TestHandler, TOKEN)


class TokenProvisioningTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.private = Path(self.directory.name) / 'private-token'
        self.shared = Path(self.directory.name) / 'shared-token'

    def test_initial_provision_is_private_and_shared_copy_removed(self):
        self.shared.write_text(TOKEN)
        self.assertEqual(TOKEN,load_token(self.private,self.shared))
        self.assertFalse(self.shared.exists())
        self.assertEqual(0o600,self.private.stat().st_mode & 0o777)

    def test_shared_storage_cannot_overwrite_existing_pairing(self):
        self.private.write_text(TOKEN)
        self.shared.write_text('attacker-'+'x'*64)
        self.assertEqual(TOKEN,load_token(self.private,self.shared))
        self.assertEqual(TOKEN,self.private.read_text())

    def test_invalid_private_file_does_not_fall_back_to_shared_token(self):
        self.private.write_text('bad')
        self.shared.write_text(TOKEN)
        with self.assertRaises(RuntimeError):load_token(self.private,self.shared)
        self.assertEqual('bad',self.private.read_text())

    def test_symlinks_and_oversized_tokens_rejected(self):
        self.shared.write_text(TOKEN)
        self.private.symlink_to(self.shared)
        with self.assertRaises(OSError):load_token(self.private,self.shared)
        self.private.unlink()
        self.shared.write_text('x'*10000)
        with self.assertRaises(RuntimeError):load_token(self.private,self.shared)
        self.assertFalse(self.private.exists())


if __name__ == '__main__':
    unittest.main()

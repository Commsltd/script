#!/usr/bin/env python3
"""Local HLS fixture: a missing source, and a source recovering after four 503s."""
import argparse
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from functools import partial
from threading import Lock

class Handler(SimpleHTTPRequestHandler):
    remaining = 4
    lock = Lock()
    def do_GET(self):
        if self.path == '/reset-flaky':
            with self.lock: type(self).remaining = 4
            self.send_response(204); self.end_headers(); return
        if self.path == '/flaky.m3u8':
            with self.lock:
                failed = type(self).remaining > 0
                type(self).remaining -= 1
            if failed:
                self.send_error(503); return
            self.path = '/stream.m3u8'
        super().do_GET()

if __name__ == '__main__':
    p=argparse.ArgumentParser();p.add_argument('--directory',required=True);p.add_argument('--port',type=int,default=8765);p.add_argument('--bind',default='127.0.0.1');args=p.parse_args()
    ThreadingHTTPServer((args.bind,args.port),partial(Handler,directory=args.directory)).serve_forever()

# Local HTTP/1.1 server for the iOS transport test. Prints its port, then serves until killed.
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

log = []


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *args):
        pass

    def reply(self, status, body=b'', headers=()):
        self.send_response(status)
        for name, value in headers:
            self.send_header(name, value)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def handle_any(self):
        length = int(self.headers.get('Content-Length') or 0)
        if length:
            self.rfile.read(length)
        if self.path == '/__log':
            return self.reply(200, json.dumps(log).encode())
        log.append({'path': self.path, 'method': self.command, 'port': self.client_address[1], 'cookie': self.headers.get('Cookie')})
        if self.path == '/redirect':
            return self.reply(302, headers=[('Location', '/target')])
        if self.path == '/set-cookie':
            return self.reply(200, headers=[('Set-Cookie', 'session=1; Path=/')])
        if self.path == '/busy':
            return self.reply(503, headers=[('Retry-After', '0')])
        if self.path == '/drop':
            self.close_connection = True
            return
        self.reply(200, b'{}')

    do_GET = do_POST = handle_any


server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
print(server.server_address[1], flush=True)
server.serve_forever()

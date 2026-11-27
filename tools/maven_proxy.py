"""Local build-only cache for official Maven Central/Google over the host TLS stack."""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.request import urlopen
from urllib.error import HTTPError
import os

CACHE = Path('D:/Codex-NeonPlayer-Maven036')
ORIGINS = {'central': 'https://repo.maven.apache.org/maven2/', 'google': 'https://dl.google.com/dl/android/maven2/'}

class Handler(BaseHTTPRequestHandler):
    def do_HEAD(self): self.respond(False)
    def do_GET(self): self.respond(True)
    def log_message(self, *_): pass
    def respond(self, body):
        parts = self.path.split('?')[0].lstrip('/').split('/')
        if parts[0] not in ORIGINS or any(p in ('..', '.') or '\\' in p for p in parts):
            self.send_error(400); return
        path = CACHE.joinpath(*parts)
        try:
            if not path.is_file():
                with urlopen(ORIGINS[parts[0]] + '/'.join(parts[1:]), timeout=25) as remote:
                    data = remote.read()
                path.parent.mkdir(parents=True, exist_ok=True)
                # Atomic replace also tolerates parallel checksum/artifact requests.
                import tempfile
                with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as tmp:
                    tmp.write(data); temp = tmp.name
                os.replace(temp, path)
            self.send_response(200); self.send_header('Content-Length', str(path.stat().st_size)); self.end_headers()
            if body:
                with path.open('rb') as source:
                    import shutil
                    shutil.copyfileobj(source, self.wfile)
        except HTTPError as e: self.send_error(e.code)
        except Exception: self.send_error(502)

ThreadingHTTPServer(('127.0.0.1', 18845), Handler).serve_forever()

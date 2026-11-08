"""Il finto GitHub della prova di "Aggiorna" (.github/ci/prova-aggiornamento.sh, docs/50 parte I).

Si comporta come le tre parti di GitHub che usa lo script di installazione:

- /latest.json: il servizio API (come api.github.com/repos/.../releases/latest). Se nella cartella c'è il file
  "api-rotta", risponde 503, come quando GitHub non ce la fa ("We couldn't respond to your request in time");
- /releases/latest: la pagina normale delle versioni, che RINVIA (302) a /releases/tag/vX.Y.Z, l'ultima versione,
  scritta nel file "ultima-versione";
- /releases/download/vX.Y.Z/<file> e /download/vX.Y.Z/<file>: i file della versione (lo ZIP e SHA256SUMS).

Uso: python3 finto-github.py <porta> <cartella>
"""

import http.server
import pathlib
import sys

porta = int(sys.argv[1])
cartella = pathlib.Path(sys.argv[2])


class FintoGitHub(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(cartella), **kwargs)

    def do_GET(self):
        percorso = self.path.split("?")[0]
        if percorso == "/latest.json" and (cartella / "api-rotta").exists():
            self.send_error(503, "Il servizio API e' rotto apposta (prova)")
            return
        if percorso == "/releases/latest":
            if not (cartella / "ultima-versione").exists():
                self.send_error(503, "Anche la pagina e' rotta apposta (prova)")
                return
            versione = (cartella / "ultima-versione").read_text().strip()
            self.send_response(302)
            self.send_header("Location", f"/releases/tag/v{versione}")
            self.end_headers()
            return
        if percorso.startswith("/releases/tag/"):
            pagina = b"<html><body>La pagina della versione (prova)</body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(pagina)))
            self.end_headers()
            self.wfile.write(pagina)
            return
        if percorso.startswith("/releases/download/"):
            # Gli stessi file di /download/: la pagina li indica con il percorso di GitHub vero.
            self.path = self.path.replace("/releases/download/", "/download/", 1)
        super().do_GET()


http.server.ThreadingHTTPServer(("127.0.0.1", porta), FintoGitHub).serve_forever()

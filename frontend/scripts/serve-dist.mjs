// Serve la build di produzione (dist/frontend/browser) su http://localhost:4300, per provare l'app installabile
// (docs/42, parte F). Le richieste /api vanno al backend (http://localhost:8080), come fa ng serve con proxy.conf.json.
//
// Perché non ng serve: il service worker funziona solo con la build di produzione. Perché http e non https: il
// browser considera sicuro "localhost" anche senza certificato, e lì il service worker si registra; con il
// certificato fatto da sé di ng serve --ssl invece la registrazione fallisce. Sul telefono servirà https vero:
// arriverà con la Fase 4.
//
// Solo moduli di Node, nessuna dipendenza. Ascolta solo su 127.0.0.1: dalla rete non si raggiunge.
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { createServer, request } from 'node:http';
import { extname, join, normalize, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(fileURLToPath(new URL('../dist/frontend/browser', import.meta.url)));
const PORT = 4300;
const BACKEND = { host: 'localhost', port: 8080 };

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
};

/**
 * Il file da mandare per un indirizzo. Attenzione al "path traversal": un indirizzo come /../../etc/passwd non deve
 * uscire dalla cartella della build. normalize() risolve i "..", e poi controlliamo che il risultato sia ancora
 * dentro ROOT. Un indirizzo che non è un file (/riepilogo, /movimenti/12) è una pagina dell'app: index.html.
 */
async function fileFor(urlPath) {
  let path;
  try {
    path = normalize(join(ROOT, decodeURIComponent(urlPath)));
  } catch {
    return null; // un indirizzo con una codifica sbagliata (%E0%A4%A)
  }
  if (path !== ROOT && !path.startsWith(ROOT + sep)) {
    return null;
  }
  const info = await stat(path).catch(() => null);
  if (info?.isFile()) {
    return path;
  }
  return extname(urlPath) === '' ? join(ROOT, 'index.html') : null;
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://localhost');

  if (url.pathname === '/api' || url.pathname.startsWith('/api/')) {
    // Il backend: la richiesta passa così com'è, e la risposta torna così com'è.
    const proxied = request(
      { ...BACKEND, path: req.url, method: req.method, headers: req.headers },
      (answer) => {
        res.writeHead(answer.statusCode ?? 502, answer.headers);
        answer.pipe(res);
      },
    );
    proxied.on('error', () => {
      res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' });
      res.end('Il backend non risponde: è avviato?');
    });
    req.pipe(proxied);
    return;
  }

  const file = await fileFor(url.pathname);
  if (file === null) {
    res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('Non trovato');
    return;
  }
  // I file con un "hash" nel nome (main-W32IKYYO.js, i caratteri in media/) non cambiano mai: se cambia il
  // contenuto, cambia il nome. Il browser li può tenere per sempre. Tutti gli altri (index.html, il service worker,
  // il manifest, le icone) vanno ricontrollati ogni volta (no-cache), altrimenti una versione nuova non si vedrebbe.
  const name = file
    .slice(ROOT.length + 1)
    .split(sep)
    .join('/');
  const hashed = /-[A-Za-z0-9_]{8,}\.(js|css)$/.test(name) || name.startsWith('media/');
  res.writeHead(200, {
    'content-type': TYPES[extname(file)] ?? 'application/octet-stream',
    'cache-control': hashed ? 'public, max-age=31536000, immutable' : 'no-cache',
    'x-content-type-options': 'nosniff',
  });
  createReadStream(file).pipe(res);
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`WalletTracker (build di produzione): http://localhost:${PORT}`);
});

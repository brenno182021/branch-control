import {context} from 'esbuild';
import {createServer, request as httpRequest} from 'node:http';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

process.env.NODE_ENV = 'development';
const {root, options, prepare} = await import('./build.mjs');
await prepare();
const buildContext = await context({...options, minify: false, sourcemap: true});
await buildContext.watch();
await buildContext.rebuild();
const dist = path.join(root, 'dist');
const mime = {'.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.map': 'application/json', '.svg': 'image/svg+xml'};

const server = createServer(async (req, res) => {
  if (req.url.startsWith('/api/')) {
    const upstream = httpRequest({
      host: '127.0.0.1', port: Number(process.env.BRANCH_CONTROL_API_PORT || 8087),
      method: req.method, path: req.url, headers: req.headers
    }, result => { res.writeHead(result.statusCode, result.headers); result.pipe(res); });
    upstream.on('error', () => { res.writeHead(502, {'Content-Type': 'application/json'}); res.end(JSON.stringify({message: 'Backend indisponível. Inicie o Spring Boot na porta 8087.'})); });
    req.pipe(upstream);
    return;
  }
  try {
    const pathname = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
    const target = path.resolve(dist, '.' + (pathname === '/' ? '/index.html' : pathname));
    if (target !== dist && !target.startsWith(dist + path.sep)) { res.writeHead(403).end(); return; }
    const content = await readFile(target);
    res.writeHead(200, {'Content-Type': mime[path.extname(target)] || 'application/octet-stream', 'Cache-Control': 'no-store'});
    res.end(content);
  } catch { res.writeHead(404).end('Arquivo não encontrado.'); }
});
server.listen(5173, '127.0.0.1', () => console.log('Frontend: http://127.0.0.1:5173 — atualize o navegador após editar os fontes.'));
async function stop() { server.close(); await buildContext.dispose(); process.exit(0); }
process.on('SIGINT', stop);
process.on('SIGTERM', stop);


import {build} from 'esbuild';
import {mkdir, copyFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

export const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const options = {
  absWorkingDir: root,
  entryPoints: ['src/main.jsx'],
  bundle: true,
  outfile: 'dist/assets/app.js',
  jsx: 'automatic',
  target: ['es2020'],
  define: {'process.env.NODE_ENV': JSON.stringify(process.env.NODE_ENV === 'development' ? 'development' : 'production')},
  minify: process.env.NODE_ENV !== 'development',
  sourcemap: process.env.NODE_ENV === 'development',
  logLevel: 'info',
  legalComments: 'eof'
};

export async function prepare() {
  await mkdir(path.join(root, 'dist/assets'), {recursive: true});
  await copyFile(path.join(root, 'index.html'), path.join(root, 'dist/index.html'));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await prepare();
  await build(options);
}


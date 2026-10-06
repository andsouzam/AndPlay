#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const root = path.resolve(import.meta.dirname, '..');
const errors = [];
const warnings = [];

function read(rel) {
  return fs.readFileSync(path.join(root, rel), 'utf8');
}

function fail(message) {
  errors.push(message);
}

function runNodeCheck(rel) {
  try {
    execFileSync(process.execPath, ['--check', path.join(root, rel)], { stdio: 'pipe' });
  } catch (error) {
    const detail = error.stdout?.toString() || error.stderr?.toString() || error.message;
    fail('node --check falhou: ' + rel + '\n' + detail);
  }
}

const jsFiles = [
  'assets/js/public-config.js',
  'assets/js/supabase-config.js',
  'assets/js/security.js',
  'assets/js/tv-epg.js',
  'assets/js/account.js',
  'assets/js/app.js',
  'assets/js/player-ui.js',
  'cast-receiver/receiver.js'
];

for (const file of jsFiles) {
  if (!fs.existsSync(path.join(root, file))) fail('Arquivo JS ausente: ' + file);
  else runNodeCheck(file);
}

const html = read('index.html');
const jsSource = jsFiles.map(read).join('\n');

if (!/Content-Security-Policy/i.test(html)) fail('index.html não possui Content-Security-Policy.');
if (/(?:onclick|onerror|onload|oncanplay|onmouseover)\s*=\s*["']/.test(jsSource)) {
  fail('Há handlers inline em JavaScript Web; use delegação/event listeners.');
}

const ids = [...html.matchAll(/\bid=["']([^"']+)["']/gi)].map(match => match[1]);
const duplicates = [...new Set(ids.filter((id, index) => ids.indexOf(id) !== index))];
if (duplicates.length) fail('IDs duplicados no index.html: ' + duplicates.join(', '));

const localScripts = [...html.matchAll(/<script[^>]+src=["']([^"']+)["']/gi)]
  .map(match => match[1])
  .filter(src => src.startsWith('./'));
for (const src of localScripts) {
  const clean = src.split('?')[0];
  if (!fs.existsSync(path.join(root, clean.replace(/^\.\//, '')))) {
    fail('Script local referenciado mas ausente: ' + src);
  }
}

if (!html.includes('assets/js/tv-epg.js')) fail('tv-epg.js não está carregado pelo index.html.');
if (!read('assets/js/app.js').includes('loadFullMovies(true)')) {
  warnings.push('Home não está usando revalidação explícita do cache de filmes.');
}
if (read('assets/js/app.js').includes('loadFullLive().catch(() => [])')) {
  fail('Busca global voltou a carregar o catálogo de TV.');
}
if (read('assets/js/app.js').includes('onclick="selectAllMedia()"')) {
  fail('Busca ainda usa onclick inline.');
}
if (fs.existsSync(path.join(root, 'index.html.bak'))) {
  fail('Arquivo legado index.html.bak ainda existe.');
}
try {
  const status = execFileSync('git', ['-C', root, 'status', '--short'], { encoding: 'utf8' });
  const changed = status.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
  const androidChanges = changed.filter(line => {
    const clean = line.replace(/^[ MADRCU?!]+\s*/, '').trim();
    return clean === 'android' || clean.startsWith('android/');
  });
  if (androidChanges.length) fail('Foram detectadas alterações em android/: ' + androidChanges.join(', '));

  execFileSync('git', ['-C', root, 'diff', '--check'], { stdio: 'pipe' });
} catch (error) {
  const detail = error.stdout?.toString() || error.stderr?.toString() || error.message;
  fail('Validação Git falhou.\n' + detail);
}

for (const warning of warnings) console.warn('[WARN] ' + warning);
if (errors.length) {
  console.error('\nWEB CHECK: FALHOU');
  for (const error of errors) console.error('\n[ERRO] ' + error);
  process.exitCode = 1;
} else {
  console.log('WEB CHECK: OK');
  console.log('- JavaScript: sintaxe válida');
  console.log('- index.html: IDs/scripts/CSP verificados');
  console.log('- HTML dinâmico: sem handlers inline');
  console.log('- Busca global: Filmes/Séries apenas');
  console.log('- TV: isolamento de carga/EPG verificado');
  console.log('- Git: diff --check OK e android/ sem alterações');
}

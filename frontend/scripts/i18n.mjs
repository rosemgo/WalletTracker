// Le traduzioni della dashboard (docs/lingue-2-inglese.md, parte E).
//
//   npm run i18n         dopo aver aggiunto o cambiato un testo: aggiorna src/locale/en.json
//   npm run i18n:check   controlla che non manchi niente (lo lancia anche la CI)
//
// Come funziona. I testi dell'app sono scritti in italiano, nei template (attributo i18n) e nel TypeScript
// ($localize). Angular li sa elencare tutti (ng extract-i18n): a ognuno dà un identificativo, calcolato dal testo.
// src/locale/en.json ha, per ogni identificativo, la frase italiana ("it", qui solo per chi traduce) e la traduzione
// ("en"). Cambiando una frase italiana cambia il suo identificativo: la traduzione vecchia non vale più, e questo
// script lo dice, invece di lasciare in giro una traduzione che non corrisponde.
//
// Solo moduli di Node, nessuna dipendenza.
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const LANGUAGES = ['en'];
const fileOf = (language) => join(ROOT, 'src', 'locale', `${language}.json`);

/** I testi dell'app, come li elenca Angular: identificativo → frase italiana. */
function extract() {
  const directory = mkdtempSync(join(tmpdir(), 'wallettracker-i18n-'));
  try {
    execFileSync(
      'npx',
      ['ng', 'extract-i18n', '--format', 'json', '--output-path', directory, '--out-file', 'messages.json'],
      { cwd: ROOT, stdio: ['ignore', 'ignore', 'inherit'] },
    );
    return JSON.parse(readFileSync(join(directory, 'messages.json'), 'utf8')).translations;
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

function read(language) {
  try {
    return JSON.parse(readFileSync(fileOf(language), 'utf8')).translations ?? {};
  } catch {
    return {};
  }
}

/** I segnaposto di una frase: {$INTERPOLATION}, {$START_TAG_STRONG}... e i rami di plurali e scelte (=1, other). */
function placeholders(text) {
  const names = [...text.matchAll(/\{\$([A-Za-z0-9_]+)\}/g)].map((m) => m[1]);
  const cases = [...text.matchAll(/(VAR_PLURAL|VAR_SELECT)[^{]*?, (plural|select),/g)].map((m) => m[1]);
  return [...names, ...cases].sort().join(' ');
}

/** I rami di un plurale o di una scelta: "=1 other". In inglese devono esserci gli stessi dell'italiano. */
function branches(text) {
  if (!/^\s*\{VAR_(PLURAL|SELECT)/.test(text)) {
    return '';
  }
  const keys = [];
  let depth = 0;
  let word = '';
  for (const char of text) {
    if (char === '{') {
      if (depth === 1 && word.trim()) {
        const key = word.trim().split(/[\s,]+/).pop();
        keys.push(key);
      }
      depth++;
      word = '';
    } else if (char === '}') {
      depth--;
      word = '';
    } else if (depth === 1) {
      word += char;
    }
  }
  return keys.sort().join(' ');
}

function sync() {
  const source = extract();
  for (const language of LANGUAGES) {
    const old = read(language);
    const translations = {};
    let missing = 0;
    for (const [id, it] of Object.entries(source)) {
      const en = old[id]?.[language] ?? '';
      translations[id] = { it, [language]: en };
      if (!en.trim()) {
        missing++;
      }
    }
    const gone = Object.entries(old).filter(([id]) => !(id in source));
    writeFileSync(fileOf(language), JSON.stringify({ locale: language, translations }, null, 2) + '\n');
    console.log(`${language}: ${Object.keys(source).length} testi, ${missing} da tradurre.`);
    if (gone.length > 0) {
      console.log(`Non servono più (la frase è cambiata o è stata tolta): le loro traduzioni, da riusare se serve.`);
      for (const [, entry] of gone) {
        console.log(`  - ${JSON.stringify(entry.it)}\n    ${JSON.stringify(entry[language])}`);
      }
    }
  }
}

function check() {
  const source = extract();
  const problems = [];
  for (const language of LANGUAGES) {
    const translations = read(language);
    for (const [id, it] of Object.entries(source)) {
      const entry = translations[id];
      if (entry === undefined) {
        problems.push(`${language}: manca la traduzione di ${JSON.stringify(it)}`);
      } else if (entry.it !== it) {
        problems.push(`${language}: la frase italiana di ${id} non è aggiornata`);
      } else if (!entry[language]?.trim()) {
        problems.push(`${language}: traduzione vuota per ${JSON.stringify(it)}`);
      } else if (placeholders(entry[language]) !== placeholders(it)) {
        problems.push(
          `${language}: i segnaposto non corrispondono\n    it: ${JSON.stringify(it)}\n    ${language}: ${JSON.stringify(entry[language])}`,
        );
      } else if (branches(entry[language]) !== branches(it)) {
        problems.push(
          `${language}: i rami (=1, other, true...) non corrispondono\n    it: ${JSON.stringify(it)}\n    ${language}: ${JSON.stringify(entry[language])}`,
        );
      }
    }
    for (const id of Object.keys(translations)) {
      if (!(id in source)) {
        problems.push(`${language}: ${id} non serve più (${JSON.stringify(translations[id].it)})`);
      }
    }
  }
  if (problems.length > 0) {
    console.error(problems.join('\n'));
    console.error(`\n${problems.length} problemi nelle traduzioni. Lancia "npm run i18n" e completa src/locale.`);
    process.exit(1);
  }
  console.log(`Traduzioni complete: ${Object.keys(source).length} testi.`);
}

const command = process.argv[2];
if (command === 'sync') {
  sync();
} else if (command === 'check') {
  check();
} else {
  console.error('Uso: node scripts/i18n.mjs sync | check');
  process.exit(2);
}

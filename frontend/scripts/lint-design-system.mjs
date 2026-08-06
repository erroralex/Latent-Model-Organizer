/**
 * Design-system adherence check.
 *
 * Guards the two failure modes that have actually bitten this frontend:
 *
 *   1. A var() reference with no definition and no fallback. This is what broke
 *      rendering when themes/*.css stopped being imported -- 15 variables across
 *      ~135 references silently fell back to UA defaults, and nothing caught it
 *      because the build succeeds regardless.
 *
 *   2. A raw colour literal outside the token layer, i.e. a colour that bypasses
 *      the design system. Tailwind statuses and slate borders had crept in.
 *
 * The design system also ships _adherence.oxlintrc.json, but its rules are AST
 * selectors over JS/JSX (`JSXOpeningElement`, `Literal[value=/#hex/]`). This app
 * is Vue with plain CSS, so those selectors match nothing here -- hence this
 * check, which reads the files where the violations actually live.
 *
 * Run: npm run lint:ds
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const SRC = join(fileURLToPath(new URL('.', import.meta.url)), '..', 'src');

/** Files allowed to declare raw colour literals: the token layer itself. */
const TOKEN_LAYER = join('assets', 'css', 'latent');

const VAR_DEF = /(?<![\w-])(--[a-z0-9-]+)\s*:/gi;
const VAR_USE = /var\(\s*(--[a-z0-9-]+)\s*([,)])/gi;
const HEX = /#[0-9a-f]{3,8}\b/gi;

// Deliberately not flagged: rgba()/hsla() with literal channels. Alpha compositing
// for shadows and scrims is ordinary CSS -- the design system's own token files do
// it -- so flagging it produces noise rather than findings.

function walk(dir) {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) return walk(full);
    return /\.(css|vue)$/.test(entry) ? [full] : [];
  });
}

const files = walk(SRC);
const defined = new Set();

for (const file of files) {
  for (const [, name] of readFileSync(file, 'utf8').matchAll(VAR_DEF)) defined.add(name);
}

const errors = [];
const warnings = [];

for (const file of files) {
  const rel = relative(SRC, file);
  const inTokenLayer = rel.startsWith(TOKEN_LAYER + sep);

  readFileSync(file, 'utf8').split(/\r?\n/).forEach((line, i) => {
    const at = `src${sep}${rel}:${i + 1}`;

    for (const [, name, next] of line.matchAll(VAR_USE)) {
      if (next === ')' && !defined.has(name)) {
        errors.push(`${at}  undefined variable with no fallback: ${name}`);
      }
    }

    if (inTokenLayer) return;
    for (const [hex] of line.matchAll(HEX)) {
      warnings.push(`${at}  raw hex ${hex} -- prefer a design-system token via var()`);
    }
  });
}

if (warnings.length) {
  console.warn(`Raw hex colours outside the token layer: ${warnings.length}`);
  for (const w of warnings) console.warn(`  ${w}`);
  console.warn('');
}

if (errors.length) {
  console.error(`Undefined variables: ${errors.length}\n`);
  for (const e of errors) console.error(`  ${e}`);
  console.error('\nThese resolve to nothing at runtime and fall back to browser defaults.');
  process.exit(1);
}

console.log(
  `Design-system adherence: no undefined variables ` +
  `(${files.length} files, ${defined.size} tokens, ${warnings.length} hex warning(s)).`
);

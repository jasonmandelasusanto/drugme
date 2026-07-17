#!/usr/bin/env node
/**
 * Validates tools/synonyms.json against the generated catalog.
 *
 *   node tools/validate-synonyms.mjs
 *
 * Aliases are hand-written, and hand-written medical mappings are exactly the kind of
 * thing that rots quietly: a target gets renamed upstream, or someone adds a plausible
 * -looking alias that points at a different drug. This check runs in CI and before any
 * catalog regeneration is committed.
 *
 * It cannot verify that an alias is *clinically* the same drug — that is a human
 * judgement, documented per-entry in synonyms.json. It does catch the mechanical
 * failures: targets that do not exist, self-references, and duplicate keys.
 */

import { readFile } from 'node:fs/promises';

const drugsPath = new URL('../app/src/main/assets/drugs.json', import.meta.url);
const synPath = new URL('./synonyms.json', import.meta.url);

const drugs = JSON.parse(await readFile(drugsPath, 'utf8'));
const rawSyn = await readFile(synPath, 'utf8');
const syn = JSON.parse(rawSyn);

const names = new Set(drugs.map((d) => d.name));
const errors = [];
const warnings = [];

// Duplicate keys survive JSON.parse silently (last wins), so check the raw text.
const keyCounts = new Map();
for (const m of rawSyn.matchAll(/^\s*"([^"]+)"\s*:/gm)) {
  keyCounts.set(m[1], (keyCounts.get(m[1]) ?? 0) + 1);
}
for (const [k, n] of keyCounts) {
  if (n > 1 && !k.startsWith('_')) errors.push(`duplicate key "${k}" appears ${n}x`);
}

let count = 0;
for (const [alias, target] of Object.entries(syn)) {
  if (alias.startsWith('_')) continue;
  count++;

  if (alias !== alias.trim() || alias !== alias.toLowerCase()) {
    errors.push(`alias "${alias}" must be lowercase and trimmed`);
  }
  if (!names.has(target)) {
    errors.push(`alias "${alias}" -> "${target}" : target not in catalog`);
  }
  if (alias === target) {
    warnings.push(`alias "${alias}" is a self-reference (the name already resolves)`);
  }
  if (names.has(alias)) {
    warnings.push(`alias "${alias}" already exists as a real drug name; alias is redundant`);
  }
}

for (const w of warnings) process.stderr.write(`WARN  ${w}\n`);
for (const e of errors) process.stderr.write(`ERROR ${e}\n`);

if (errors.length) {
  process.stderr.write(`\n${errors.length} error(s). Refusing to accept synonyms.json.\n`);
  process.exit(1);
}
process.stderr.write(`OK: ${count} aliases, all targets resolve. ${warnings.length} warning(s).\n`);

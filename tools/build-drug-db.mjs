#!/usr/bin/env node
/**
 * Generates app/src/main/assets/drugs.json from RxNav (RxNorm + RxClass).
 *
 * Run occasionally and commit the result — the app never calls RxNav at runtime.
 *
 *   node tools/build-drug-db.mjs
 *
 * ---------------------------------------------------------------------------
 * SAFETY: only `may_treat` may ever reach the output.
 *
 * RxClass exposes several drug->disease relations from MED-RT. For metformin it
 * returns, from the same endpoint:
 *
 *   may_treat -> Diabetes Mellitus, Type 2
 *   ci_with   -> Acidosis, Liver Diseases, Diabetic Ketoacidosis
 *
 * `ci_with` means CONTRAINDICATED WITH — conditions where the drug is dangerous.
 * Ingesting disease classes without filtering the relation would show a user that
 * metformin treats liver disease. This script therefore pulls each relation
 * explicitly rather than asking for "the disease classes", and asserts the output
 * before writing. DrugCatalogTest re-checks the shipped asset.
 * ---------------------------------------------------------------------------
 *
 * Why disease-first rather than drug-first: enumerating ~6k disease classes and
 * inverting their members costs ~6k requests, while walking every RxNorm drug would
 * cost ~20k+. It also yields exactly the drugs that have a known indication, which
 * is a far better autosuggest list than RxNorm's raw name dump (that one is full of
 * chemical names like "(-)-ambroxide" that no patient will ever type).
 */

import { writeFile, mkdir } from 'node:fs/promises';
import { dirname } from 'node:path';

const BASE = 'https://rxnav.nlm.nih.gov/REST';
const OUT = new URL('../app/src/main/assets/drugs.json', import.meta.url).pathname;

// RxNav asks for <= 20 req/sec. Stay under it: being throttled or blocked mid-run
// would corrupt a partial catalog into looking complete.
const CONCURRENCY = 8;
const DELAY_MS = 60;

const ALLOWED_RELA = 'may_treat';

/** Relations that must never appear as an indication. Asserted, not assumed. */
const FORBIDDEN_RELA = ['ci_with', 'induces', 'may_diagnose', 'may_prevent', 'contraindicated_with'];

/**
 * Structural MeSH nodes to drop.
 *
 * MED-RT asserts may_treat against every ancestor in the MeSH tree, not just the specific
 * indication, so metformin legitimately comes back linked to "Disease" and to "Diseases,
 * Life Phases, Behavior Mechanisms and Physiologic States" — classes that literally every
 * drug in the catalog belongs to (4534 and 4526 of 4534 members respectively). They are
 * taxonomy scaffolding, not indications, and "metformin treats Disease" is noise in a
 * picker.
 *
 * Deliberately a small explicit list rather than a member-count cutoff: broad classes are
 * not automatically useless. "Infections" (1009 members) and "Skin Diseases" (945) are
 * exactly what a user would recognise for an antibiotic or a topical, and a threshold that
 * removed the scaffolding would take those with it.
 */
const STRUCTURAL_CLASSES = new Set([
  'Disease',
  'Diseases, Life Phases, Behavior Mechanisms and Physiologic States',
  'Pathological Conditions, Signs and Symptoms',
  'Pathologic Processes',
  'Physiological Phenomena',
  'Biological Phenomena',
  'Chemically-Induced Disorders',
]);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function getJson(url, attempt = 1) {
  try {
    const res = await fetch(url, { headers: { Accept: 'application/json' } });
    if (res.status === 429 || res.status >= 500) throw new Error(`HTTP ${res.status}`);
    if (!res.ok) return null;
    return await res.json();
  } catch (err) {
    if (attempt >= 4) {
      throw new Error(`giving up on ${url}: ${err.message}`);
    }
    await sleep(500 * 2 ** attempt);
    return getJson(url, attempt + 1);
  }
}

async function mapLimit(items, limit, fn) {
  const out = [];
  let i = 0;
  let done = 0;
  const workers = Array.from({ length: limit }, async () => {
    while (i < items.length) {
      const idx = i++;
      out[idx] = await fn(items[idx], idx);
      done++;
      if (done % 250 === 0) {
        process.stderr.write(`  ${done}/${items.length}\n`);
      }
      await sleep(DELAY_MS);
    }
  });
  await Promise.all(workers);
  return out;
}

async function fetchDiseaseClasses() {
  const data = await getJson(`${BASE}/rxclass/allClasses.json?classTypes=DISEASE`);
  const list = data?.rxclassMinConceptList?.rxclassMinConcept ?? [];
  return list.map((c) => ({ id: c.classId, name: c.className }));
}

async function fetchMembers(disease) {
  const url =
    `${BASE}/rxclass/classMembers.json?classId=${encodeURIComponent(disease.id)}` +
    `&relaSource=MEDRT&rela=${ALLOWED_RELA}`;
  const data = await getJson(url);
  const members = data?.drugMemberGroup?.drugMember ?? [];
  return members.map((m) => ({
    rxcui: m.minConcept.rxcui,
    name: m.minConcept.name,
    tty: m.minConcept.tty,
  }));
}

function assertNoForbiddenRelations(url) {
  // The request itself pins rela=may_treat. This guards against a future edit
  // loosening the query without noticing what else comes back.
  for (const bad of FORBIDDEN_RELA) {
    if (url.includes(`rela=${bad}`)) {
      throw new Error(`Refusing to query forbidden relation "${bad}" — see header comment.`);
    }
  }
}

async function main() {
  assertNoForbiddenRelations(`rela=${ALLOWED_RELA}`);

  process.stderr.write('Fetching DISEASE classes...\n');
  const diseases = await fetchDiseaseClasses();
  process.stderr.write(`  ${diseases.length} disease classes\n`);
  if (diseases.length < 1000) {
    throw new Error(`Only ${diseases.length} disease classes — RxNav likely degraded. Aborting.`);
  }

  process.stderr.write(`Fetching may_treat members (concurrency ${CONCURRENCY})...\n`);
  const memberLists = await mapLimit(diseases, CONCURRENCY, fetchMembers);

  // Invert disease -> drugs into drug -> diseases.
  const byRxcui = new Map();
  diseases.forEach((disease, idx) => {
    for (const m of memberLists[idx] ?? []) {
      // IN = ingredient, PIN = precise ingredient. Anything else (brand packs, dose
      // forms) is noise for a type-ahead of drug names.
      if (m.tty !== 'IN' && m.tty !== 'PIN') continue;

      let entry = byRxcui.get(m.rxcui);
      if (!entry) {
        entry = { rxcui: m.rxcui, name: m.name.toLowerCase(), diseases: [] };
        byRxcui.set(m.rxcui, entry);
      }
      if (!entry.diseases.some((d) => d.id === disease.id)) {
        entry.diseases.push({ id: disease.id, name: disease.name });
      }
    }
  });

  // How many drugs treat each class — a proxy for how specific the class is.
  const breadth = new Map();
  for (const entry of byRxcui.values()) {
    for (const d of entry.diseases) breadth.set(d.id, (breadth.get(d.id) ?? 0) + 1);
  }

  const drugs = [...byRxcui.values()]
    .map((d) => ({
      ...d,
      diseases: d.diseases
        .filter((x) => !STRUCTURAL_CLASSES.has(x.name))
        // Most specific first. The UI shows the leading entries, and "Diabetes Mellitus,
        // Type 2" is what a user recognises for metformin — not "Metabolic Diseases",
        // which is equally true and useless. Ordering rather than truncating keeps the
        // broader classes available for anyone who wants them.
        .sort((a, b) => (breadth.get(a.id) ?? 0) - (breadth.get(b.id) ?? 0) || a.name.localeCompare(b.name)),
    }))
    .filter((d) => d.diseases.length > 0)
    .sort((a, b) => a.name.localeCompare(b.name));

  if (drugs.length < 500) {
    throw new Error(`Only ${drugs.length} drugs extracted — that is implausibly low. Aborting.`);
  }

  await mkdir(dirname(OUT), { recursive: true });
  await writeFile(OUT, JSON.stringify(drugs));

  const totalLinks = drugs.reduce((n, d) => n + d.diseases.length, 0);
  process.stderr.write(
    `\nWrote ${OUT}\n` +
      `  drugs:          ${drugs.length}\n` +
      `  drug-disease links: ${totalLinks}\n` +
      `  relation:       ${ALLOWED_RELA} only\n`,
  );
}

main().catch((err) => {
  process.stderr.write(`FAILED: ${err.stack}\n`);
  process.exit(1);
});

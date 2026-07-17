#!/usr/bin/env node
/**
 * Generates app/src/main/assets/diseases.json — the conditions a user can say they have.
 *
 *   node tools/build-disease-db.mjs
 *
 * ---------------------------------------------------------------------------
 * WHY THIS IS SEPARATE FROM drugs.json
 *
 * The first design derived the condition from the drug: pick metformin, get offered
 * "Diabetes Mellitus, Type 2". That inverts the relationship. People know what they have
 * before they know what they take, they often take one drug for a condition RxNorm does
 * not list against it (off-label, or simply a gap in MED-RT), and plenty of what people
 * track — vitamins, supplements, contraceptives — has no `may_treat` edge at all. Deriving
 * the condition from the drug quietly told those users their situation was invalid.
 *
 * So the condition is now the user's own statement, chosen from the full MeSH disease
 * vocabulary, independent of whatever they take for it.
 * ---------------------------------------------------------------------------
 *
 * Source: RxClass allClasses (classTypes=DISEASE), which is MeSH's disease branch as
 * exposed by RxNav — ~6k concepts, no API key, one request.
 */

import { writeFile, mkdir } from 'node:fs/promises';
import { dirname } from 'node:path';

const BASE = 'https://rxnav.nlm.nih.gov/REST';
const OUT = new URL('../app/src/main/assets/diseases.json', import.meta.url).pathname;

/**
 * Taxonomy scaffolding, not conditions anyone has.
 *
 * MeSH's disease branch includes structural parents like "Disease" and "Pathological
 * Conditions, Signs and Symptoms". They are true of everything and useful to no one typing
 * into a picker.
 */
const STRUCTURAL = new Set([
  'Disease',
  'Diseases, Life Phases, Behavior Mechanisms and Physiologic States',
  'Pathological Conditions, Signs and Symptoms',
  'Pathologic Processes',
  'Physiological Phenomena',
  'Biological Phenomena',
  'Chemically-Induced Disorders',
  'Disease Attributes',
  'Disease Models, Animal',
]);

async function main() {
  process.stderr.write('Fetching MeSH DISEASE classes...\n');
  const res = await fetch(`${BASE}/rxclass/allClasses.json?classTypes=DISEASE`, {
    headers: { Accept: 'application/json' },
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const data = await res.json();

  const raw = data?.rxclassMinConceptList?.rxclassMinConcept ?? [];
  process.stderr.write(`  ${raw.length} raw classes\n`);
  if (raw.length < 1000) {
    throw new Error(`Only ${raw.length} classes — RxNav likely degraded. Aborting.`);
  }

  const seen = new Set();
  const diseases = [];
  for (const c of raw) {
    if (STRUCTURAL.has(c.className)) continue;
    if (seen.has(c.classId)) continue;
    seen.add(c.classId);
    diseases.push({ id: c.classId, name: c.className });
  }

  diseases.sort((a, b) => a.name.localeCompare(b.name));

  await mkdir(dirname(OUT), { recursive: true });
  await writeFile(OUT, JSON.stringify(diseases));

  process.stderr.write(
    `\nWrote ${OUT}\n  conditions: ${diseases.length}\n  dropped structural: ${raw.length - diseases.length}\n`,
  );
  process.stderr.write(`  sample: ${diseases.slice(0, 3).map((d) => d.name).join(' | ')}\n`);
}

main().catch((err) => {
  process.stderr.write(`FAILED: ${err.stack}\n`);
  process.exit(1);
});

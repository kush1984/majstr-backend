/**
 * The portal is a single static page with inline JS and no test runner, so this exercises the two
 * functions the sections change touches by slicing them out and running them for real. It also
 * parses the whole script block, which catches a syntax slip that would otherwise only show up as a
 * blank page in front of a client.
 */
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const html = readFileSync(process.argv[2], 'utf-8');

// 1. the whole inline script must parse
const script = html.match(/<script>([\s\S]*?)<\/script>/)?.[1];
assert.ok(script, 'no inline <script> found');
new Function(script);                     // throws on a syntax error
console.log('ok   script parses');

// 2. the two functions the change touches, with the tiny helpers they call
const slice = (name) => {
  const at = script.indexOf(`function ${name}(`);
  assert.ok(at >= 0, `${name} not found`);
  let depth = 0, i = script.indexOf('{', at);
  for (let j = i; j < script.length; j++) {
    if (script[j] === '{') depth++;
    else if (script[j] === '}' && --depth === 0) return script.slice(at, j + 1);
  }
  throw new Error(`unbalanced braces in ${name}`);
};

const sandbox = new Function(`
  const escapeHtml = s => String(s ?? '');
  const unit = c => c;
  const amount = v => String(v);
  const moneyFormat = new Intl.NumberFormat('uk-UA', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const percentFormat = new Intl.NumberFormat('uk-UA', { maximumFractionDigits: 2 });
  function money(value) { return moneyFormat.format(Number(value)) + ' грн'; }
  ${slice('toSections')}
  ${slice('renderItems')}
  ${slice('adjustNote')}
  return { toSections, renderItems, adjustNote, money };
`)();

const line = (name, category, type, sortOrder, lineTotal) =>
  ({ name, category, type, unit: 'M2', quantity: 1, unitPrice: lineTotal, lineTotal, sortOrder });

// --- grouping follows sortOrder, not the array or the alphabet ---------------------------------
// Підготовка carries TWO items here so this scenario stays a clean test of "≥2 → subtotal shown
// per section" — the single-item omission case gets its own dedicated scenario below.
const items = [
  line('Укладання', 'Плитка', 'WORK', 0, 1000),
  line('Затирка', 'Плитка', 'WORK', 1, 500),
  line('Грунтування', 'Підготовка', 'WORK', 2, 200),
  line('Ґрунтовка', 'Підготовка', 'WORK', 3, 150),
  line('Клей', 'Плитка', 'MATERIAL', 4, 300),
];
const sections = sandbox.toSections(items.filter(i => i.type === 'WORK'));
assert.deepEqual(sections.map(s => `${s.category}:${s.items.map(i => i.name).join(',')}`),
  ['Плитка:Укладання,Затирка', 'Підготовка:Грунтування,Ґрунтовка']);
console.log('ok   sections follow sortOrder (Плитка before Підготовка, against the alphabet)');

// --- the rendered table carries a band and a subtotal per section (each has ≥2 items) ----------
const works = sandbox.renderItems(items, 'WORK');
assert.ok(works.includes('Плитка') && works.includes('Підготовка'), 'section bands missing');
assert.ok(works.indexOf('Плитка') < works.indexOf('Підготовка'), 'section order lost');
assert.equal((works.match(/Разом по розділу/g) ?? []).length, 2, 'one subtotal per section');
assert.ok(works.includes('>1500<'), 'Плитка subtotal 1000+500 missing');
assert.ok(works.includes('>350<'), 'Підготовка subtotal 200+150 missing');
console.log('ok   a band and a subtotal per section, in the arranged order');

// --- a section with exactly ONE item gets no subtotal row — it would just repeat the line -------
const oneEach = [
  line('Укладання', 'Плитка', 'WORK', 0, 1000),
  line('Грунтування', 'Підготовка', 'WORK', 1, 200),
];
const oneEachRendered = sandbox.renderItems(oneEach, 'WORK');
assert.ok(oneEachRendered.includes('Плитка') && oneEachRendered.includes('Підготовка'), 'section bands missing');
assert.equal((oneEachRendered.match(/Разом по розділу/g) ?? []).length, 0,
  'a single-item section must not get a subtotal row');
console.log('ok   single-item sections show a band but no subtotal');

// --- an estimate with no categories renders exactly as before ----------------------------------
const flat = [line('A', null, 'WORK', 0, 100), line('B', '', 'WORK', 1, 200)];
const plain = sandbox.renderItems(flat, 'WORK');
assert.ok(!plain.includes('Без категорії'), 'unfiled lines must not get a heading');
assert.ok(!plain.includes('Разом по розділу'), 'a subtotal equal to the total is noise');
assert.ok(plain.includes('A') && plain.includes('B'), 'lines still render');
console.log('ok   no categories → no bands, no subtotals (unchanged for those masters)');

// --- one named section still gets its band, but (being one item) no subtotal row ---------------
const single = sandbox.renderItems([line('A', 'Демонтаж', 'WORK', 0, 100)], 'WORK');
assert.ok(single.includes('Демонтаж'), 'section band missing');
assert.ok(!single.includes('Разом по розділу'), 'a lone item needs no subtotal repeating itself');
console.log('ok   a single named section is still shown as one, without a redundant subtotal');

// --- the markup/discount recap shows a % when the server named one, sum-only when it fell back --
const withPercent = sandbox.adjustNote(1776, -725, 12, 15);
assert.ok(withPercent.includes('Надбавка 12%'), 'markup % missing');
assert.ok(withPercent.includes('Знижка 15%'), 'discount % missing');
assert.ok(withPercent.includes(sandbox.money(1776)) && withPercent.includes(sandbox.money(725)), 'amounts missing');
console.log('ok   adjustNote shows % + sum when the server names a single contributing line');

const sumOnly = sandbox.adjustNote(1776, -725, null, null);
assert.ok(sumOnly.includes('Надбавка') && !sumOnly.includes('Надбавка 12%'), 'markup must fall back to sum-only');
assert.ok(sumOnly.includes('Знижка') && !/Знижка \d/.test(sumOnly), 'discount must fall back to sum-only');
console.log('ok   adjustNote falls back to sum-only when there is no single % to name (no fabricated number)');

const nothingToAdjust = sandbox.adjustNote(0, 0, null, null);
assert.equal(nothingToAdjust, '', 'no markup/discount → no recap at all');
console.log('ok   adjustNote renders nothing when the estimate has no markup or discount');

console.log('\nall portal checks passed');

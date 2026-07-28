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
  ${slice('toSections')}
  ${slice('renderItems')}
  return { toSections, renderItems };
`)();

const line = (name, category, type, sortOrder, lineTotal) =>
  ({ name, category, type, unit: 'M2', quantity: 1, unitPrice: lineTotal, lineTotal, sortOrder });

// --- grouping follows sortOrder, not the array or the alphabet ---------------------------------
const items = [
  line('Укладання', 'Плитка', 'WORK', 0, 1000),
  line('Затирка', 'Плитка', 'WORK', 1, 500),
  line('Грунтування', 'Підготовка', 'WORK', 2, 200),
  line('Клей', 'Плитка', 'MATERIAL', 3, 300),
];
const sections = sandbox.toSections(items.filter(i => i.type === 'WORK'));
assert.deepEqual(sections.map(s => `${s.category}:${s.items.map(i => i.name).join(',')}`),
  ['Плитка:Укладання,Затирка', 'Підготовка:Грунтування']);
console.log('ok   sections follow sortOrder (Плитка before Підготовка, against the alphabet)');

// --- the rendered table carries a band and a subtotal per section ------------------------------
const works = sandbox.renderItems(items, 'WORK');
assert.ok(works.includes('Плитка') && works.includes('Підготовка'), 'section bands missing');
assert.ok(works.indexOf('Плитка') < works.indexOf('Підготовка'), 'section order lost');
assert.equal((works.match(/Разом по розділу/g) ?? []).length, 2, 'one subtotal per section');
assert.ok(works.includes('>1500<'), 'Плитка subtotal 1000+500 missing');
assert.ok(works.includes('>200<'), 'Підготовка subtotal missing');
console.log('ok   a band and a subtotal per section, in the arranged order');

// --- an estimate with no categories renders exactly as before ----------------------------------
const flat = [line('A', null, 'WORK', 0, 100), line('B', '', 'WORK', 1, 200)];
const plain = sandbox.renderItems(flat, 'WORK');
assert.ok(!plain.includes('Без категорії'), 'unfiled lines must not get a heading');
assert.ok(!plain.includes('Разом по розділу'), 'a subtotal equal to the total is noise');
assert.ok(plain.includes('A') && plain.includes('B'), 'lines still render');
console.log('ok   no categories → no bands, no subtotals (unchanged for those masters)');

// --- one named section still gets its band ----------------------------------------------------
const single = sandbox.renderItems([line('A', 'Демонтаж', 'WORK', 0, 100)], 'WORK');
assert.ok(single.includes('Демонтаж') && single.includes('Разом по розділу'));
console.log('ok   a single named section is still shown as one');

console.log('\nall portal checks passed');

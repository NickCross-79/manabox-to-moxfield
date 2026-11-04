const test = require('node:test');
const assert = require('node:assert');
const { convert, parseCsv } = require('../converter.js');

const SAMPLE = [
  'Binder Name,Binder Type,Name,Set code,Set name,Collector number,Foil,Rarity,Quantity,ManaBox ID,Scryfall ID,Purchase price,Misprint,Altered,Condition,Language,Purchase price currency',
  'Trade Binder,binder,Sol Ring,c21,Commander 2021,263,normal,uncommon,2,1,abc,1.50,false,false,near_mint,en,USD',
  'Main,binder,Sol Ring,c21,Commander 2021,263,normal,uncommon,1,1,abc,1.50,false,false,near_mint,en,USD',
  'Main,binder,"Jace, the Mind Sculptor",2xm,Double Masters,52,foil,mythic,1,2,def,80,false,false,lightly_played,ja,USD',
  'Wishlist,list,Black Lotus,lea,Alpha,232,normal,rare,1,3,ghi,,false,false,near_mint,en,USD',
  'Atraxa EDH,deck,Sol Ring,c21,Commander 2021,263,normal,uncommon,1,1,abc,1.50,false,false,near_mint,en,USD',
].join('\n');

test('parses quoted fields', () => {
  assert.deepStrictEqual(parseCsv('a,"b, c","d""e"\r\n1,2,3'), [['a', 'b, c', 'd"e'], ['1', '2', '3']]);
});

test('keeps only binder cards, merges duplicates', () => {
  const { records, stats, csv } = convert(SAMPLE);
  assert.strictEqual(stats.removed.list.cards, 1);
  assert.strictEqual(stats.removed.deck.cards, 1);
  assert.deepStrictEqual(stats.kept, { 'Trade Binder': 2, Main: 2 });
  assert.strictEqual(stats.totalCards, 4);
  assert.strictEqual(records.length, 2);
  const sol = records.find((r) => r.name === 'Sol Ring');
  assert.strictEqual(sol.count, 3);
  assert.strictEqual(sol.edition, 'c21');
  assert.strictEqual(sol.condition, 'Near Mint');
  const jace = records.find((r) => r.name.startsWith('Jace'));
  assert.strictEqual(jace.foil, 'foil');
  assert.strictEqual(jace.language, 'Japanese');
  assert.strictEqual(jace.condition, 'Lightly Played');
  assert.ok(csv.startsWith('Count,Tradelist Count,Name,Edition,Condition,Language,Foil,Tags,'));
  assert.ok(csv.includes('"Jace, the Mind Sculptor"'));
});

test('binder tags keep binders separate; filter can be disabled', () => {
  const { records } = convert(SAMPLE, { binderTags: true, bindersOnly: false });
  assert.strictEqual(records.length, 5);
  assert.ok(records.some((r) => r.tags === 'Trade Binder' && r.count === 2));
});

test('exports without a Binder Type column keep every card', () => {
  const csv = 'Name,Set code,Collector number,Foil,Quantity,Condition,Language\nSol Ring,c21,263,normal,2,near_mint,en';
  const { records, stats } = convert(csv);
  assert.strictEqual(stats.hasBinderType, false);
  assert.strictEqual(records[0].count, 2);
});

test('rejects non-ManaBox files', () => {
  assert.throws(() => convert('foo,bar\n1,2'), /ManaBox/);
});

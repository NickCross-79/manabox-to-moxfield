/*
 * ManaBox -> Moxfield collection CSV converter.
 * Pure functions, no dependencies. Works in the browser (window.ManaboxToMoxfield)
 * and in Node (module.exports).
 */
(function (root) {
  'use strict';

  const MOXFIELD_HEADERS = [
    'Count', 'Tradelist Count', 'Name', 'Edition', 'Condition', 'Language',
    'Foil', 'Tags', 'Last Modified', 'Collector Number', 'Alter', 'Proxy',
    'Purchase Price',
  ];

  const CONDITIONS = {
    mint: 'Mint',
    near_mint: 'Near Mint',
    nearmint: 'Near Mint',
    nm: 'Near Mint',
    excellent: 'Lightly Played',
    good: 'Lightly Played',
    lightly_played: 'Lightly Played',
    lp: 'Lightly Played',
    played: 'Moderately Played',
    moderately_played: 'Moderately Played',
    mp: 'Moderately Played',
    heavily_played: 'Heavily Played',
    hp: 'Heavily Played',
    poor: 'Damaged',
    damaged: 'Damaged',
    dmg: 'Damaged',
  };

  const LANGUAGES = {
    en: 'English', es: 'Spanish', fr: 'French', de: 'German', it: 'Italian',
    pt: 'Portuguese', ja: 'Japanese', ko: 'Korean', ru: 'Russian',
    zhs: 'Chinese Simplified', zht: 'Chinese Traditional', he: 'Hebrew',
    la: 'Latin', grc: 'Ancient Greek', ar: 'Arabic', sa: 'Sanskrit', ph: 'Phyrexian',
  };

  // Parse CSV text (RFC 4180: quoted fields, escaped quotes, newlines in quotes).
  function parseCsv(text) {
    if (text.charCodeAt(0) === 0xfeff) text = text.slice(1); // strip BOM
    const rows = [];
    let row = [];
    let field = '';
    let inQuotes = false;
    for (let i = 0; i < text.length; i++) {
      const c = text[i];
      if (inQuotes) {
        if (c === '"') {
          if (text[i + 1] === '"') { field += '"'; i++; } else { inQuotes = false; }
        } else {
          field += c;
        }
      } else if (c === '"') {
        inQuotes = true;
      } else if (c === ',') {
        row.push(field); field = '';
      } else if (c === '\n' || c === '\r') {
        if (c === '\r' && text[i + 1] === '\n') i++;
        row.push(field); field = '';
        rows.push(row); row = [];
      } else {
        field += c;
      }
    }
    if (field !== '' || row.length) { row.push(field); rows.push(row); }
    return rows.filter((r) => r.some((v) => v.trim() !== ''));
  }

  function toCsv(rows) {
    return rows.map((r) => r.map((v) => {
      const s = v == null ? '' : String(v);
      return /[",\r\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
    }).join(',')).join('\r\n') + '\r\n';
  }

  const norm = (s) => s.toLowerCase().replace(/[^a-z0-9]/g, '');

  function mapCondition(v) {
    const key = (v || '').trim().toLowerCase().replace(/[\s-]+/g, '_');
    return CONDITIONS[key] || (key ? v.trim() : 'Near Mint');
  }

  function mapLanguage(v) {
    const s = (v || '').trim();
    if (!s) return 'English';
    return LANGUAGES[s.toLowerCase()] || s;
  }

  function mapFoil(v) {
    const s = (v || '').trim().toLowerCase();
    if (s === 'foil' || s === 'true' || s === 'yes') return 'foil';
    if (s === 'etched') return 'etched';
    return '';
  }

  const truthy = (v) => ['true', 'yes', '1'].includes((v || '').trim().toLowerCase());

  /**
   * Convert ManaBox CSV text into Moxfield collection CSV text.
   * options:
   *   bindersOnly - keep only cards whose ManaBox "Binder Type" is "binder"
   *                 (drops decks and lists/wishlists). Default true.
   *   binderTags  - add the ManaBox binder name as a Moxfield tag. Default false.
   *   merge       - combine identical printings into one row. Default true.
   */
  function convert(text, options) {
    const opts = Object.assign({ bindersOnly: true, binderTags: false, merge: true }, options);
    const rows = parseCsv(text);
    if (!rows.length) throw new Error('The file is empty.');

    const header = rows[0].map(norm);
    const col = (...names) => {
      for (const n of names) { const i = header.indexOf(norm(n)); if (i !== -1) return i; }
      return -1;
    };
    const idx = {
      name: col('Name'),
      set: col('Set code', 'Set'),
      number: col('Collector number'),
      foil: col('Foil'),
      qty: col('Quantity', 'Count'),
      condition: col('Condition'),
      language: col('Language'),
      altered: col('Altered'),
      price: col('Purchase price'),
      binder: col('Binder Name', 'Binder'),
      binderType: col('Binder Type'),
    };
    if (idx.name === -1 || idx.qty === -1) {
      throw new Error('This does not look like a ManaBox export (missing "Name" or "Quantity" column).');
    }

    const get = (r, i) => (i === -1 ? '' : (r[i] || '').trim());
    const out = new Map();
    const stats = {
      inputRows: 0,
      skippedInvalid: 0,
      totalCards: 0,
      hasBinderType: idx.binderType !== -1,
      kept: {},    // binder name -> card count
      removed: {}, // binder type -> { cards, binders: { name: count } }
    };

    for (const r of rows.slice(1)) {
      stats.inputRows++;
      const name = get(r, idx.name);
      const qty = parseInt(get(r, idx.qty), 10);
      if (!name || !(qty > 0)) { stats.skippedInvalid++; continue; }

      const binder = get(r, idx.binder) || '(no binder)';
      const type = get(r, idx.binderType).toLowerCase();
      if (opts.bindersOnly && stats.hasBinderType && type !== 'binder') {
        const t = type || '(none)';
        const bucket = stats.removed[t] || (stats.removed[t] = { cards: 0, binders: {} });
        bucket.cards += qty;
        bucket.binders[binder] = (bucket.binders[binder] || 0) + qty;
        continue;
      }
      stats.kept[binder] = (stats.kept[binder] || 0) + qty;

      const rec = {
        count: qty,
        name,
        edition: get(r, idx.set).toLowerCase(),
        condition: mapCondition(get(r, idx.condition)),
        language: mapLanguage(get(r, idx.language)),
        foil: mapFoil(get(r, idx.foil)),
        tags: opts.binderTags ? get(r, idx.binder) : '',
        number: get(r, idx.number),
        alter: truthy(get(r, idx.altered)) ? 'True' : 'False',
        price: get(r, idx.price),
      };
      stats.totalCards += qty;

      const key = opts.merge
        ? [rec.name, rec.edition, rec.number, rec.condition, rec.language, rec.foil, rec.alter, rec.tags].join('\u0001')
        : String(out.size);
      const existing = out.get(key);
      if (existing) existing.count += qty; else out.set(key, rec);
    }

    const records = [...out.values()];
    const csv = toCsv([MOXFIELD_HEADERS].concat(records.map((x) => [
      x.count, 0, x.name, x.edition, x.condition, x.language, x.foil, x.tags,
      '', x.number, x.alter, 'False', x.price,
    ])));
    stats.outputRows = records.length;
    return { csv, records, stats };
  }

  const api = { convert, parseCsv, toCsv, MOXFIELD_HEADERS };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.ManaboxToMoxfield = api;
})(typeof window !== 'undefined' ? window : globalThis);

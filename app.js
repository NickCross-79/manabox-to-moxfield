(function () {
  'use strict';
  const { convert } = window.ManaboxToMoxfield;
  const $ = (id) => document.getElementById(id);
  const state = { text: null, sourceName: null, output: null, outName: null };

  const store = {
    get(k) { try { return localStorage.getItem(k); } catch { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch { /* ignore */ } },
  };

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }
  const standalone = window.matchMedia('(display-mode: standalone)').matches;
  if (!standalone) $('install-hint').hidden = false;

  $('opt-tags').checked = store.get('binderTags') === '1';
  $('opt-tags').addEventListener('change', (e) => {
    store.set('binderTags', e.target.checked ? '1' : '0');
    if (state.text) run();
  });

  $('picker').addEventListener('change', async (e) => {
    const f = e.target.files[0];
    if (f) load(await f.text(), f.name);
    e.target.value = '';
  });

  document.addEventListener('click', (e) => {
    const a = e.target.closest('[data-action]');
    if (!a) return;
    ({ save, share, reset })[a.dataset.action]();
  });

  function show(id) {
    for (const s of ['empty', 'error', 'result']) $(s).hidden = s !== id;
    window.scrollTo(0, 0);
  }

  function load(text, name) {
    state.text = text;
    state.sourceName = name;
    run();
  }

  function run() {
    let res;
    try {
      res = convert(state.text, { bindersOnly: true, binderTags: $('opt-tags').checked });
    } catch (err) {
      $('error-msg').textContent = err.message;
      show('error');
      return;
    }
    const { csv, records, stats } = res;
    if (!records.length) {
      const removed = Object.values(stats.removed).reduce((n, b) => n + b.cards, 0);
      $('error-msg').textContent = removed
        ? `No cards are in binders. ${removed} cards were in decks or lists and were removed.`
        : 'No cards were found in this file.';
      show('error');
      return;
    }
    state.output = csv;
    state.outName = `moxfield-collection-${new Date().toISOString().slice(0, 10)}.csv`;

    const kept = Object.entries(stats.kept).sort((a, b) => b[1] - a[1]);
    $('kept-cards').textContent = stats.totalCards.toLocaleString();
    $('kept-summary').textContent =
      `from ${kept.length} binder${kept.length === 1 ? '' : 's'} · ${records.length.toLocaleString()} unique rows`;
    $('no-type-warning').hidden = stats.hasBinderType;
    $('filename').textContent = state.outName;

    $('kept-list').replaceChildren(...kept.map(([name, n]) => row(name, n)));

    const removedRows = [];
    for (const [type, bucket] of Object.entries(stats.removed)) {
      for (const [name, n] of Object.entries(bucket.binders).sort((a, b) => b[1] - a[1])) {
        removedRows.push(row(name, n, type));
      }
    }
    $('removed-card').hidden = !removedRows.length;
    $('removed-list').replaceChildren(...removedRows);

    $('share-btn').hidden = !canShareFile();
    show('result');
  }

  function row(name, n, type) {
    const li = document.createElement('li');
    const left = document.createElement('span');
    left.className = 'name';
    left.textContent = name;
    if (type) {
      const t = document.createElement('div');
      t.className = 'type';
      t.textContent = type;
      left.appendChild(t);
    }
    const right = document.createElement('span');
    right.className = 'n';
    right.textContent = `${n.toLocaleString()} card${n === 1 ? '' : 's'}`;
    li.append(left, right);
    return li;
  }

  function outFile() {
    return new File([state.output], state.outName, { type: 'text/csv' });
  }

  function canShareFile() {
    try { return !!navigator.canShare && navigator.canShare({ files: [outFile()] }); } catch { return false; }
  }

  function save() {
    const url = URL.createObjectURL(new Blob([state.output], { type: 'text/csv' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = state.outName;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  }

  async function share() {
    try { await navigator.share({ files: [outFile()], title: state.outName }); } catch { /* cancelled */ }
  }

  function reset() {
    state.text = state.output = null;
    show('empty');
  }

  // Pick up a file sent from ManaBox's share menu (stored by sw.js).
  async function checkShared() {
    if (!new URLSearchParams(location.search).has('shared')) return;
    history.replaceState(null, '', location.pathname);
    try {
      const cache = await caches.open('shared-file');
      const res = await cache.match('latest');
      if (!res) return;
      const name = decodeURIComponent(res.headers.get('X-Filename') || 'manabox.csv');
      const text = await res.text();
      await cache.delete('latest');
      load(text, name);
    } catch (err) {
      $('error-msg').textContent = 'The shared file could not be opened: ' + err.message;
      show('error');
    }
  }
  checkShared();
})();

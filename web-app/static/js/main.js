// Boots the app: finds out whether server.py is behind this page, loads the user's data and the
// Qur'an text, starts the player, then routes between views with the URL hash (#/surahs, …).

import { h, icon, toast } from './lib/dom.js';
import { probeServer } from './services/api.js';
import * as quran from './services/quranData.js';
import * as content from './services/content.js';
import { registerServiceWorker } from './services/offline.js';
import { store } from './state/store.js';
import { PlayerEngine } from './player/engine.js';
import { trackTitle } from './core/quran.js';
import { playerView } from './views/player.js';
import { surahsView } from './views/surahs.js';
import { surahView } from './views/surah.js';
import { addByDescriptionView } from './views/addByDescription.js';
import { settingsView } from './views/settings.js';
import { reciteView } from './views/recite.js';
import { accountView } from './views/account.js';
import { adminView } from './views/admin.js';
import { videoView } from './views/video.js';
import { showIntro } from './views/intro.js';

import { applyDocumentLanguage, tx } from './core/i18n.js';
const ROUTES = {
  player: playerView,
  surahs: surahsView,
  surah: surahView,
  add: addByDescriptionView,
  recite: reciteView,
  settings: settingsView,
  account: accountView,
  admin: adminView,
  video: videoView,
};

// Views that live under a navigation item without having one of their own.
const NAV_PARENT = { surah: 'surahs', add: 'surahs', video: 'player' };

const THEME_COLORS = { dark: '#0a1710', light: '#f1f8f3' };

const viewHost = document.getElementById('view');
const nav = document.getElementById('nav');
const tabbar = document.getElementById('tabbar');
const sidebarFoot = document.getElementById('sidebar-foot');
const sidebarToggle = document.getElementById('sidebar-toggle');
const mini = document.getElementById('mini-player');

let server = { available: false };
let engine = null;
let current = null; // { name, params, view }

// -- navigation ------------------------------------------------------------------------------

function navItems() {
  const items = [
    { route: 'player', label: tx('Player'), icon: 'headphones' },
    { route: 'surahs', label: tx('Surahs'), icon: 'book' },
  ];
  if (store.settings.reciteBetaEnabled) items.push({ route: 'recite', label: tx('Recite'), icon: 'mic' });
  items.push({ route: 'settings', label: tx('Settings'), icon: 'settings' });
  if (server.available) {
    items.push({ route: 'account', label: store.user ? tx('Account') : tx('Sign in'), icon: 'user' });
    if (store.user?.role === 'admin') items.push({ route: 'admin', label: tx('Users'), icon: 'users' });
  }
  return items;
}

function renderNav() {
  const active = current ? NAV_PARENT[current.name] ?? current.name : null;
  const link = (item) =>
    h('a', {
      href: `#/${item.route}`,
      'aria-current': item.route === active ? 'page' : undefined,
      'data-testid': `nav-${item.route}`,
      title: item.label,
    }, icon(item.icon), h('span', {}, item.label));
  const items = navItems();
  nav.replaceChildren(...items.map(link));
  tabbar.replaceChildren(...items.map(link));

  const learned = store.learned.size === 1 ? tx('1 learned ayah') : tx('{n} learned ayahs', { n: store.learned.size });
  sidebarFoot.replaceChildren(
    store.user
      ? h('div', {}, h('strong', { style: { color: 'var(--text)' } }, store.user.displayName), h('div', {}, learned))
      : h('div', {}, h('div', {}, learned), h('div', { class: 'tiny' }, server.available ? tx('Not signed in — saved in this browser') : tx('Saved in this browser'))),
  );
}

function parseHash() {
  const parts = location.hash.replace(/^#\/?/, '').split('/').filter(Boolean).map(decodeURIComponent);
  return { name: parts[0] ?? '', params: parts.slice(1) };
}

function go(path) {
  const target = `#${path.startsWith('/') ? path : `/${path}`}`;
  if (location.hash === target) route();
  else location.hash = target;
}

function defaultRoute() {
  return store.learned.size ? 'player' : 'surahs';
}

function route({ focus = true } = {}) {
  let { name, params } = parseHash();
  if (!ROUTES[name] || (name === 'admin' && !server.available)) {
    name = defaultRoute();
    params = [];
    history.replaceState(null, '', `#/${name}`);
  }
  mount(name, params, { focus });
}

function mount(name, params, { focus = true } = {}) {
  current?.view.destroy?.();
  const view = ROUTES[name]({ store, engine, server, go, params });
  current = { name, params, view };
  viewHost.replaceChildren(view.el);
  document.title = view.title && view.title !== 'Learned Ayahs' ? `${view.title} · ${tx('Learned Ayahs')}` : tx('Learned Ayahs');
  renderNav();
  renderMini();
  window.scrollTo(0, 0);
  if (focus) viewHost.focus({ preventScroll: true });
}

function remount() {
  if (current) mount(current.name, current.params, { focus: false });
}

// -- mini player -----------------------------------------------------------------------------

function renderMini() {
  const t = engine?.track;
  const show = Boolean(t) && current?.name !== 'player' && current?.name !== 'recite' && (engine.isActive || engine.intro);
  mini.hidden = !show;
  if (!show) return;
  mini.replaceChildren(
    h('button', {
      class: 'btn icon-only primary',
      type: 'button',
      'aria-label': engine.playing ? tx('Pause') : tx('Play'),
      'data-testid': 'mini-toggle',
      onClick: () => engine.toggle(),
    }, icon(engine.playing ? 'pause' : 'play', { filled: true })),
    h('div', { class: 'title', role: 'link', tabindex: '0', onClick: () => go('/player'), onKeydown: (e) => e.key === 'Enter' && go('/player') },
      engine.introLabel ?? trackTitle(t.surah, t.ayah),
      h('span', {}, engine.modeLabel)),
    h('button', { class: 'btn icon-only ghost', type: 'button', 'aria-label': tx('Next'), onClick: () => engine.next() }, icon('next')),
  );
}

// -- sidebar ---------------------------------------------------------------------------------

// Folding the sidebar down to its icons is a choice about this screen, so it is kept in this
// browser rather than synced with the rest of the settings.
const SIDEBAR_KEY = 'la.sidebarCollapsed';

function readCollapsed() {
  try {
    return localStorage.getItem(SIDEBAR_KEY) === '1';
  } catch {
    return false;
  }
}

function applySidebar(collapsed) {
  document.getElementById('app').classList.toggle('collapsed', collapsed);
  sidebarToggle.replaceChildren(icon('sidebar'), h('span', {}, collapsed ? tx('Expand') : tx('Collapse')));
  sidebarToggle.setAttribute('aria-expanded', String(!collapsed));
  sidebarToggle.setAttribute('aria-label', collapsed ? 'Expand the sidebar' : 'Collapse the sidebar');
  sidebarToggle.title = collapsed ? tx('Expand the sidebar') : tx('Collapse the sidebar');
}

sidebarToggle.addEventListener('click', () => {
  const collapsed = !document.getElementById('app').classList.contains('collapsed');
  try {
    localStorage.setItem(SIDEBAR_KEY, collapsed ? '1' : '0');
  } catch {
    // Private windows may refuse storage; the sidebar still folds for this visit.
  }
  applySidebar(collapsed);
});
applySidebar(readCollapsed());

// -- theme -----------------------------------------------------------------------------------

const systemDark = matchMedia('(prefers-color-scheme: dark)');

function applyTheme() {
  const pref = store.settings.theme;
  const theme = pref === 'LIGHT' ? 'light' : pref === 'SYSTEM' ? (systemDark.matches ? 'dark' : 'light') : 'dark';
  document.documentElement.dataset.theme = theme;
  // The mushaf style's face for every Arabic line (.arabic uses var(--quran-font)).
  document.documentElement.style.setProperty('--quran-font', content.mushafFamilySync());
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLORS[theme]);
}

// -- boot ------------------------------------------------------------------------------------

function bootError(message) {
  viewHost.replaceChildren(h('div', { class: 'page page-narrow' }, h('section', { class: 'card empty' },
    h('h2', {}, tx('Could not start')),
    h('p', {}, message),
    h('button', { class: 'btn primary', type: 'button', onClick: () => location.reload() }, icon('refresh'), tx('Try again')))));
}

async function boot() {
  applyDocumentLanguage();
  server = await probeServer();
  await store.init(server);
  applyTheme();
  try {
    await quran.loadText();
  } catch {
    bootError("The Qur'an text could not be loaded. Check your connection — once loaded it is kept for offline use.");
    return;
  }
  // Word meanings are not needed for the first paint; fetch them alongside.
  quran.loadTranslations().catch(() => {});

  // The chosen translation and word-meaning language (by catalog id) follow the settings.
  content.bindSettings(() => store.settings);
  content.loadCatalog().then(applyTheme, () => {});
  quran.setMeaningSource((surah, ayah) => content.meaningsSync(surah, ayah));

  engine = new PlayerEngine(store, { proxy: Boolean(server.available && server.audioProxy) });
  engine.restore();
  engine.on('state', renderMini);
  engine.on('track', renderMini);
  // Word meanings in another language load a surah at a time, as that surah comes up.
  const meaningsForTrack = () => { if (engine.track) content.ensureMeanings(engine.track.surah).catch(() => {}); };
  engine.on('track', meaningsForTrack);
  content.onContentLoaded(() => engine.refreshWords());
  meaningsForTrack();
  engine.on('error', () => engine.error && toast(engine.error, { error: true }));

  store.on((what) => {
    if (what === 'settings' || what === 'all') {
      applyTheme();
      meaningsForTrack();
    }
    if (what === 'session') {
      remount();
      return;
    }
    if (['settings', 'all', 'learned', 'sync'].includes(what)) renderNav();
  });
  systemDark.addEventListener('change', applyTheme);

  // Another device may have changed the list; refresh when the tab comes back, save when it goes.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') store.pull();
    else store.flush();
  });
  window.addEventListener('online', () => store.reconnect());

  window.addEventListener('hashchange', () => route());
  route({ focus: false });

  // Automated tests (?test) inspect the app's state through this handle.
  if (new URLSearchParams(location.search).has('test')) window.__app = { store, engine, server, go };

  if (!store.settings.seenIntro) showIntro(store, () => store.setSetting('seenIntro', true));
  registerServiceWorker();
}

boot().catch((err) => {
  console.error(err);
  bootError(err?.message ?? 'Something went wrong.');
});

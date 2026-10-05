// Boots the app: finds out whether server.py is behind this page, loads the user's data and the
// Qur'an text, starts the player, then routes between views with the URL hash (#/surahs, …).

import { h, icon, toast } from './lib/dom.js';
import { probeServer } from './services/api.js';
import * as quran from './services/quranData.js';
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
import { showIntro } from './views/intro.js';

const ROUTES = {
  player: playerView,
  surahs: surahsView,
  surah: surahView,
  add: addByDescriptionView,
  recite: reciteView,
  settings: settingsView,
  account: accountView,
  admin: adminView,
};

// Views that live under a navigation item without having one of their own.
const NAV_PARENT = { surah: 'surahs', add: 'surahs' };

const THEME_COLORS = { dark: '#0a1710', light: '#f1f8f3' };

const viewHost = document.getElementById('view');
const nav = document.getElementById('nav');
const tabbar = document.getElementById('tabbar');
const sidebarFoot = document.getElementById('sidebar-foot');
const mini = document.getElementById('mini-player');

let server = { available: false };
let engine = null;
let current = null; // { name, params, view }

// -- navigation ------------------------------------------------------------------------------

function navItems() {
  const items = [
    { route: 'player', label: 'Player', icon: 'headphones' },
    { route: 'surahs', label: 'Surahs', icon: 'book' },
  ];
  if (store.settings.reciteBetaEnabled) items.push({ route: 'recite', label: 'Recite', icon: 'mic' });
  items.push({ route: 'settings', label: 'Settings', icon: 'settings' });
  if (server.available) {
    items.push({ route: 'account', label: store.user ? 'Account' : 'Sign in', icon: 'user' });
    if (store.user?.role === 'admin') items.push({ route: 'admin', label: 'Users', icon: 'users' });
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
    }, icon(item.icon), h('span', {}, item.label));
  const items = navItems();
  nav.replaceChildren(...items.map(link));
  tabbar.replaceChildren(...items.map(link));

  const learned = `${store.learned.size} learned ayah${store.learned.size === 1 ? '' : 's'}`;
  sidebarFoot.replaceChildren(
    store.user
      ? h('div', {}, h('strong', { style: { color: 'var(--text)' } }, store.user.displayName), h('div', {}, learned))
      : h('div', {}, h('div', {}, learned), h('div', { class: 'tiny' }, server.available ? 'Not signed in — saved in this browser' : 'Saved in this browser')),
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
  document.title = view.title && view.title !== 'Learned Ayahs' ? `${view.title} · Learned Ayahs` : 'Learned Ayahs';
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
      'aria-label': engine.playing ? 'Pause' : 'Play',
      'data-testid': 'mini-toggle',
      onClick: () => engine.toggle(),
    }, icon(engine.playing ? 'pause' : 'play', { filled: true })),
    h('div', { class: 'title', role: 'link', tabindex: '0', onClick: () => go('/player'), onKeydown: (e) => e.key === 'Enter' && go('/player') },
      engine.introLabel ?? trackTitle(t.surah, t.ayah),
      h('span', {}, engine.modeLabel)),
    h('button', { class: 'btn icon-only ghost', type: 'button', 'aria-label': 'Next', onClick: () => engine.next() }, icon('next')),
  );
}

// -- theme -----------------------------------------------------------------------------------

const systemDark = matchMedia('(prefers-color-scheme: dark)');

function applyTheme() {
  const pref = store.settings.theme;
  const theme = pref === 'LIGHT' ? 'light' : pref === 'SYSTEM' ? (systemDark.matches ? 'dark' : 'light') : 'dark';
  document.documentElement.dataset.theme = theme;
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLORS[theme]);
}

// -- boot ------------------------------------------------------------------------------------

function bootError(message) {
  viewHost.replaceChildren(h('div', { class: 'page page-narrow' }, h('section', { class: 'card empty' },
    h('h2', {}, 'Could not start'),
    h('p', {}, message),
    h('button', { class: 'btn primary', type: 'button', onClick: () => location.reload() }, icon('refresh'), 'Try again'))));
}

async function boot() {
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

  engine = new PlayerEngine(store, { proxy: Boolean(server.available && server.audioProxy) });
  engine.restore();
  engine.on('state', renderMini);
  engine.on('track', renderMini);
  engine.on('error', () => engine.error && toast(engine.error, { error: true }));

  store.on((what) => {
    if (what === 'settings' || what === 'all') applyTheme();
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

  window.addEventListener('hashchange', () => route());
  route({ focus: false });

  // Automated tests (?test) inspect the app's state through this handle.
  if (new URLSearchParams(location.search).has('test')) window.__app = { store, engine, server, go };

  if (!store.settings.seenIntro) {
    showIntro(() => store.setSetting('seenIntro', true));
  }
  registerServiceWorker();
}

boot().catch((err) => {
  console.error(err);
  bootError(err?.message ?? 'Something went wrong.');
});

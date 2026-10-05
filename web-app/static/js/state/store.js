// App state: who is signed in, their learned ayahs, bookmarks, settings and progress.
//
// Local-first, like the Android app: a guest keeps everything in this browser. When server.py is
// present and the user signs in, their account becomes the source of truth and every change is
// saved to it (debounced), while a local copy keeps the app working offline.
//
// The learned list is the user's and only the user's: nothing is ever marked on their behalf.

import { api, ApiError } from '../services/api.js';
import { isValidGlobalId } from '../core/quran.js';

export const DEFAULT_SETTINGS = Object.freeze({
  // Playback (PlayerSettings.kt defaults)
  playbackMode: 'REVISE',
  repeatMode: 'OFF',
  revisionDelaySeconds: 0,
  reciter: 'MAHER_AL_MUAIQLY',
  wordReciter: 'QURAN_COM',
  useWordClips: false,
  seekInsideAyahAudio: false,
  // Reading
  theme: 'DARK',
  showWordTranslations: true,
  ayahOverflowMode: 'AUTO_SCROLL',
  // Gestures
  swipeToNavigate: true,
  tapToAdvance: true,
  // Recite & review (opt-in beta)
  reciteBetaEnabled: false,
  autoPlayMistakeAudio: true,
  autoAdvanceSuccess: true,
  // Onboarding
  seenIntro: false,
  // Web only: stream from everyayah.com directly instead of the server's audio cache.
  streamDirect: false,
});

export const REVISION_DELAY_CHOICES = [0, 3, 5, 10];

const STORAGE_PREFIX = 'la:v1:';
const SAVE_DELAY_MS = 600;

function sanitizeIds(values) {
  const out = new Set();
  for (const v of values ?? []) if (isValidGlobalId(v)) out.add(v);
  return out;
}

function sanitizeSettings(raw) {
  const out = { ...DEFAULT_SETTINGS };
  for (const [k, v] of Object.entries(raw ?? {})) {
    if (k in DEFAULT_SETTINGS && typeof v === typeof DEFAULT_SETTINGS[k]) out[k] = v;
  }
  if (!REVISION_DELAY_CHOICES.includes(out.revisionDelaySeconds)) out.revisionDelaySeconds = 0;
  return out;
}

function readLocal(key) {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_PREFIX + key) ?? 'null');
  } catch {
    return null;
  }
}

function removeLocal(key) {
  try {
    localStorage.removeItem(STORAGE_PREFIX + key);
  } catch {
    // Nothing stored, or storage unavailable.
  }
}

function writeLocal(key, value) {
  try {
    localStorage.setItem(STORAGE_PREFIX + key, JSON.stringify(value));
  } catch {
    // Private mode / quota: the session still works, it just won't persist locally.
  }
}

export class Store extends EventTarget {
  constructor() {
    super();
    this.server = { available: false, registrationOpen: false };
    this.user = null;
    this.learned = new Set();
    this.bookmarks = new Set();
    this.settings = { ...DEFAULT_SETTINGS };
    this.progress = { lastGlobalId: 0 };
    this.dirty = new Set();
    this.saveTimer = null;
    this.syncState = 'local'; // local | synced | saving | offline | error
  }

  get profileKey() {
    return this.user ? `user:${this.user.id}` : 'guest';
  }

  emit(what) {
    this.dispatchEvent(new CustomEvent('change', { detail: what }));
  }

  on(fn) {
    const listener = (e) => fn(e.detail);
    this.addEventListener('change', listener);
    return () => this.removeEventListener('change', listener);
  }

  // -- boot / profiles -------------------------------------------------------------------

  async init(server) {
    this.server = server;
    if (server.available) {
      try {
        const me = await api('GET', 'auth/me');
        this.server.registrationOpen = me.registrationOpen;
        if (me.user) {
          this.user = me.user;
          this.loadLocal(); // instant start from the local copy...
          await this.pull(); // ...then the account's data
          return;
        }
      } catch {
        // Server unreachable right now: fall through to the local guest profile.
      }
    }
    this.loadLocal();
  }

  loadLocal() {
    const saved = readLocal(this.profileKey);
    this.learned = sanitizeIds(saved?.learned);
    this.bookmarks = sanitizeIds(saved?.bookmarks);
    this.settings = sanitizeSettings(saved?.settings);
    this.progress = { lastGlobalId: 0, ...(saved?.progress ?? {}) };
    this.syncState = this.user ? 'synced' : 'local';
    this.emit('all');
  }

  persistLocal() {
    writeLocal(this.profileKey, {
      learned: [...this.learned],
      bookmarks: [...this.bookmarks],
      settings: this.settings,
      progress: this.progress,
    });
  }

  applyRemote(data) {
    this.learned = sanitizeIds(data.learned);
    this.bookmarks = sanitizeIds(data.bookmarks);
    this.settings = sanitizeSettings(data.settings);
    this.progress = { lastGlobalId: 0, ...(data.progress ?? {}) };
    this.revision = data.revision;
    this.persistLocal();
    this.emit('all');
  }

  /** Refreshes from the account (on sign-in, and when the tab regains focus). */
  async pull() {
    if (!this.user || this.dirty.size) return;
    try {
      const data = await api('GET', 'data');
      if (this.dirty.size) return; // a local edit raced the fetch; it wins and will be saved
      if (data.revision === 0 && !data.updatedAt) {
        // A brand-new account: seed it with this device's settings (not its ayahs).
        this.dirty.add('settings');
        this.scheduleSave();
      } else {
        this.applyRemote(data);
      }
      this.setSync('synced');
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        this.forgetSession(); // signed out elsewhere, password changed, or account disabled
      } else {
        this.setSync('offline');
      }
    }
  }

  setSync(state) {
    this.syncState = state;
    this.emit('sync');
  }

  // -- saving ------------------------------------------------------------------------------

  touch(field) {
    this.persistLocal();
    if (this.user) {
      this.dirty.add(field);
      this.scheduleSave();
    }
    this.emit(field);
  }

  scheduleSave() {
    clearTimeout(this.saveTimer);
    this.saveTimer = setTimeout(() => this.flush(), SAVE_DELAY_MS);
  }

  async flush() {
    if (!this.user || !this.dirty.size) return;
    const fields = [...this.dirty];
    this.dirty.clear();
    const patch = {};
    for (const f of fields) {
      if (f === 'learned') patch.learned = [...this.learned].sort((a, b) => a - b);
      if (f === 'bookmarks') patch.bookmarks = [...this.bookmarks].sort((a, b) => a - b);
      if (f === 'settings') patch.settings = this.settings;
      if (f === 'progress') patch.progress = this.progress;
    }
    this.setSync('saving');
    try {
      const data = await api('PUT', 'data', patch);
      this.revision = data.revision;
      this.setSync(this.dirty.size ? 'saving' : 'synced');
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        this.forgetSession(); // the session ended on the server; nothing to retry
        return;
      }
      fields.forEach((f) => this.dirty.add(f)); // retry later
      this.setSync(err.status === 0 ? 'offline' : 'error');
      clearTimeout(this.saveTimer);
      this.saveTimer = setTimeout(() => this.flush(), 15000);
    }
  }

  // -- learned ayahs -----------------------------------------------------------------------

  isLearned(id) {
    return this.learned.has(id);
  }

  setLearnedIds(ids) {
    this.learned = sanitizeIds(ids);
    this.touch('learned');
  }

  toggleLearned(id) {
    if (this.learned.has(id)) this.learned.delete(id);
    else if (isValidGlobalId(id)) this.learned.add(id);
    this.touch('learned');
  }

  addLearned(ids) {
    let added = 0;
    for (const id of ids) {
      if (isValidGlobalId(id) && !this.learned.has(id)) {
        this.learned.add(id);
        added++;
      }
    }
    this.touch('learned');
    return added;
  }

  removeLearned(ids) {
    for (const id of ids) this.learned.delete(id);
    this.touch('learned');
  }

  // -- bookmarks ---------------------------------------------------------------------------

  toggleBookmark(id) {
    if (this.bookmarks.has(id)) this.bookmarks.delete(id);
    else if (isValidGlobalId(id)) this.bookmarks.add(id);
    this.touch('bookmarks');
  }

  addBookmarks(ids) {
    for (const id of ids) if (isValidGlobalId(id)) this.bookmarks.add(id);
    this.touch('bookmarks');
  }

  // -- settings / progress -----------------------------------------------------------------

  setSetting(key, value) {
    if (!(key in DEFAULT_SETTINGS) || this.settings[key] === value) return;
    this.settings = sanitizeSettings({ ...this.settings, [key]: value });
    this.touch('settings');
  }

  setLastGlobalId(id) {
    if (!isValidGlobalId(id) || this.progress.lastGlobalId === id) return;
    this.progress = { ...this.progress, lastGlobalId: id };
    // Progress changes on every ayah; save it, but don't wake every listener for it.
    this.persistLocal();
    if (this.user) {
      this.dirty.add('progress');
      this.scheduleSave();
    }
  }

  // -- accounts ----------------------------------------------------------------------------

  async signIn(username, password, { register = false, displayName = '' } = {}) {
    const wasGuest = !this.user;
    const guestLearned = new Set(this.learned);
    const body = register ? { username, password, displayName } : { username, password };
    await this.flush();
    const res = await api('POST', register ? 'auth/register' : 'auth/login', body);
    // Fetch the account before switching to it, so the app never shows a half-loaded profile.
    const data = await api('GET', 'data');
    const accountIsNew = data.revision === 0 && !data.updatedAt;
    this.user = res.user;
    if (accountIsNew) {
      // A new account starts from what this browser had as a guest: its ayahs, bookmarks and
      // settings (so, for one, the welcome is not shown again).
      if (!wasGuest) {
        this.learned = new Set();
        this.bookmarks = new Set();
      }
      ['learned', 'bookmarks', 'settings', 'progress'].forEach((f) => this.dirty.add(f));
      this.persistLocal();
      await this.flush();
      this.emit('all');
    } else {
      this.applyRemote(data);
    }
    this.setSync('synced');
    this.emit('session');
    return { guestLearned: wasGuest ? guestLearned : new Set(), accountIsNew };
  }

  async signOut() {
    await this.flush();
    try {
      await api('POST', 'auth/logout');
    } catch {
      // Signing out locally still works if the server is unreachable.
    }
    this.forgetSession();
  }

  /** Leaves the account on this device: back to the guest profile, the account's copy removed. */
  forgetSession() {
    clearTimeout(this.saveTimer);
    this.dirty.clear();
    removeLocal(this.profileKey);
    this.user = null;
    this.loadLocal();
    this.emit('session');
  }

  /** Merge a guest list into the signed-in account (union), used after sign-in. */
  mergeLearned(ids) {
    return this.addLearned(ids);
  }
}

export const store = new Store();

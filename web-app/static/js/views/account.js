// Accounts: sign in / create an account, profile, password, sign out, delete account.
// Without server.py (a static host) the app runs as a single local profile.

import { confirmDialog, dialog, h, icon, toast } from '../lib/dom.js';
import { api } from '../services/api.js';

const SYNC_LABELS = {
  local: 'Saved in this browser',
  synced: 'Synced to your account',
  saving: 'Saving…',
  offline: 'Offline — changes will sync when the server is reachable',
  error: 'Could not save — will retry',
};

function field(label, input) {
  const id = `f-${Math.random().toString(36).slice(2, 8)}`;
  input.id = id;
  return h('div', { class: 'field' }, h('label', { for: id }, label), input);
}

export function accountView({ store, server, go }) {
  const page = h('div', { class: 'page page-narrow' });

  function render() {
    page.replaceChildren(h('header', { class: 'page-head' }, h('div', {}, h('h1', {}, store.user ? 'Account' : 'Sign in'))));
    if (!server.available) page.append(localOnly());
    else if (!store.user) page.append(signInForms());
    else page.append(...signedIn());
  }

  function localOnly() {
    return h('section', { class: 'card stack' },
      h('h2', {}, 'This browser only'),
      h('p', { class: 'muted', style: { margin: 0 } },
        'Your learned ayahs, bookmarks and settings are saved in this browser. Accounts — to sync between devices and share a server with family or a class — are available when the app is run with its server (python3 server.py).'),
      h('p', { class: 'small muted', style: { margin: 0 } }, 'To move your list to another device, use Export and Import in Settings.'),
    );
  }

  function signInForms() {
    let mode = 'signin';
    const box = h('section', { class: 'card stack' });
    const draw = () => {
      const username = h('input', { class: 'input', autocomplete: 'username', required: true, 'data-testid': 'username' });
      const password = h('input', { class: 'input', type: 'password', autocomplete: mode === 'signin' ? 'current-password' : 'new-password', required: true, 'data-testid': 'password' });
      const display = h('input', { class: 'input', autocomplete: 'name', 'data-testid': 'display-name' });
      const error = h('div', { class: 'notice error', hidden: true, 'data-testid': 'auth-error' });
      const submit = h('button', { class: 'btn primary', type: 'submit', 'data-testid': 'auth-submit' }, mode === 'signin' ? 'Sign in' : 'Create account');
      const form = h('form', { class: 'stack' },
        field('Username', username),
        mode === 'register' ? field('Display name (optional)', display) : null,
        field('Password', password),
        mode === 'register' ? h('p', { class: 'tiny muted', style: { margin: 0 } }, 'At least 8 characters.') : null,
        error,
        h('div', { class: 'row' }, submit));
      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        submit.disabled = true;
        error.hidden = true;
        try {
          const guestCount = store.user ? 0 : store.learned.size;
          const result = await store.signIn(username.value.trim(), password.value, {
            register: mode === 'register',
            displayName: display.value.trim(),
          });
          toast(mode === 'register' ? 'Account created.' : `Welcome back, ${store.user.displayName}.`);
          if (!result.accountIsNew && result.guestLearned.size) {
            const fresh = [...result.guestLearned].filter((id) => !store.learned.has(id));
            if (fresh.length) {
              const merge = await dialog({
                title: 'Add this browser’s ayahs?',
                content: h('p', { class: 'muted' }, `Before signing in you had marked ${guestCount} ayahs in this browser; ${fresh.length} of them are not in your account. Add them to your account?`),
                actions: [{ label: 'Keep account as is', value: false }, { label: `Add ${fresh.length} ayahs`, value: true, primary: true }],
              });
              if (merge) store.mergeLearned(fresh);
            }
          }
          go('/player');
        } catch (err) {
          error.textContent = err.message;
          error.hidden = false;
          submit.disabled = false;
        }
      });
      const switcher = server.registrationOpen || mode === 'register'
        ? h('p', { class: 'small muted', style: { margin: 0 } },
          mode === 'signin' ? 'New here? ' : 'Already have an account? ',
          h('a', { href: '#', 'data-testid': 'auth-switch', onClick: (e) => { e.preventDefault(); mode = mode === 'signin' ? 'register' : 'signin'; draw(); } },
            mode === 'signin' ? 'Create an account' : 'Sign in'))
        : h('p', { class: 'small muted', style: { margin: 0 } }, 'Registration is closed on this server. Ask its administrator for an account.');
      box.replaceChildren(
        h('h2', {}, mode === 'signin' ? 'Sign in' : 'Create an account'),
        h('p', { class: 'card-sub' }, 'Sync your learned ayahs, bookmarks and settings across your devices. Without an account everything stays in this browser.'),
        form,
        switcher,
      );
    };
    draw();
    return box;
  }

  function signedIn() {
    const u = store.user;
    const sync = h('span', { class: `badge ${store.syncState === 'synced' ? 'ok' : ''}`, 'data-testid': 'sync-state' }, SYNC_LABELS[store.syncState] ?? store.syncState);

    const displayInput = h('input', { class: 'input', value: u.displayName, maxlength: '64' });
    const profile = h('section', { class: 'card stack' },
      h('div', { class: 'row' }, icon('user'), h('h2', {}, u.displayName), h('span', { class: 'spacer' }), u.role === 'admin' ? h('span', { class: 'badge ok' }, 'Administrator') : null),
      h('p', { class: 'muted small', style: { margin: 0 } }, `Signed in as ${u.username} · ${store.learned.size} learned ayahs · `, sync),
      field('Display name', displayInput),
      h('div', { class: 'row wrap' },
        h('button', {
          class: 'btn', type: 'button',
          onClick: async () => {
            try {
              const res = await api('PATCH', 'account', { displayName: displayInput.value.trim() });
              store.user = res.user;
              store.emit('session');
              toast('Saved.');
            } catch (err) {
              toast(err.message, { error: true });
            }
          },
        }, 'Save name'),
        u.role === 'admin' ? h('a', { class: 'btn', href: '#/admin' }, icon('shield'), 'Manage users') : null,
        h('button', {
          class: 'btn ghost',
          type: 'button',
          'data-testid': 'sign-out',
          onClick: async () => {
            await store.flush();
            if (store.dirty.size && !(await confirmDialog('Sign out with unsaved changes?', 'Some changes have not reached the server yet (it may be unreachable). Signing out now loses them.', { confirm: 'Sign out', danger: true }))) return;
            await store.signOut();
            toast('Signed out.');
          },
        }, icon('logout'), 'Sign out')),
    );

    const current = h('input', { class: 'input', type: 'password', autocomplete: 'current-password' });
    const next = h('input', { class: 'input', type: 'password', autocomplete: 'new-password' });
    const pwForm = h('form', { class: 'card stack' },
      h('h2', {}, 'Change password'),
      h('p', { class: 'card-sub' }, 'Other devices signed in to this account will be signed out.'),
      field('Current password', current),
      field('New password (at least 8 characters)', next),
      h('div', {}, h('button', { class: 'btn', type: 'submit' }, 'Change password')));
    pwForm.addEventListener('submit', async (e) => {
      e.preventDefault();
      try {
        await api('POST', 'account/password', { currentPassword: current.value, newPassword: next.value });
        current.value = '';
        next.value = '';
        toast('Password changed.');
      } catch (err) {
        toast(err.message, { error: true });
      }
    });

    const danger = h('section', { class: 'card stack' },
      h('h2', {}, 'Delete account'),
      h('p', { class: 'card-sub' }, 'Removes your account and its learned ayahs, bookmarks and settings from this server. Export first if you want to keep your list.'),
      h('div', {}, h('button', {
        class: 'btn danger', type: 'button',
        onClick: async () => {
          const pw = h('input', { class: 'input', type: 'password', autocomplete: 'current-password' });
          const ok = await dialog({
            title: 'Delete your account?',
            content: h('div', { class: 'stack' }, h('p', { class: 'muted' }, 'This cannot be undone.'), field('Confirm with your password', pw)),
            actions: [{ label: 'Cancel', value: false }, { label: 'Delete account', value: true, danger: true }],
          });
          if (!ok) return;
          try {
            await api('DELETE', 'account', { password: pw.value });
            store.forgetSession();
            toast('Account deleted.');
          } catch (err) {
            toast(err.message, { error: true });
          }
        },
      }, icon('trash'), 'Delete account')));

    return [profile, pwForm, danger];
  }

  render();
  const off = store.on((what) => {
    if (what === 'session' || what === 'sync') render();
  });
  return { el: page, destroy: off, title: 'Account' };
}

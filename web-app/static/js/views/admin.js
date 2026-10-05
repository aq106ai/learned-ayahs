// User management for administrators: create accounts, change roles, disable/enable, reset
// passwords, delete accounts, and open or close self-registration.

import { confirmDialog, dialog, h, icon, toast } from '../lib/dom.js';
import { api } from '../services/api.js';

const when = (iso) => (iso ? new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) : '—');

export function adminView({ store }) {
  const page = h('div', { class: 'page' });
  if (store.user?.role !== 'admin') {
    page.append(h('section', { class: 'card empty' }, h('h2', {}, 'Administrators only'), h('p', {}, 'Sign in with an administrator account to manage users.')));
    return { el: page, title: 'Users' };
  }

  const table = h('tbody');
  const regToggle = h('input', { class: 'switch', type: 'checkbox', 'data-testid': 'registration-open' });

  async function load() {
    try {
      const [{ users }, settings] = await Promise.all([api('GET', 'admin/users'), api('GET', 'admin/settings')]);
      regToggle.checked = settings.registrationOpen;
      table.replaceChildren(...users.map(row));
    } catch (err) {
      toast(err.message, { error: true });
    }
  }

  async function act(fn, done) {
    try {
      await fn();
      if (done) toast(done);
      load();
    } catch (err) {
      toast(err.message, { error: true });
    }
  }

  function row(u) {
    const me = u.id === store.user.id;
    return h('tr', { 'data-testid': `user-${u.username}` },
      h('td', {}, h('strong', {}, u.displayName), h('div', { class: 'tiny muted' }, u.username, me ? ' (you)' : '')),
      h('td', {}, h('span', { class: `badge ${u.role === 'admin' ? 'ok' : ''}` }, u.role === 'admin' ? 'Admin' : 'User'),
        u.disabled ? h('span', { class: 'badge warn', style: { marginLeft: '6px' } }, 'Disabled') : null),
      h('td', {}, String(u.learnedCount)),
      h('td', { class: 'small muted' }, when(u.createdAt)),
      h('td', { class: 'small muted' }, when(u.lastLoginAt)),
      h('td', {}, h('div', { class: 'row', style: { gap: '4px', justifyContent: 'flex-end' } },
        h('button', {
          class: 'btn small', type: 'button', disabled: me,
          onClick: () => act(() => api('PATCH', `admin/users/${u.id}`, { role: u.role === 'admin' ? 'user' : 'admin' }), 'Role updated.'),
        }, u.role === 'admin' ? 'Make user' : 'Make admin'),
        h('button', {
          class: 'btn small', type: 'button', disabled: me,
          onClick: () => act(() => api('PATCH', `admin/users/${u.id}`, { disabled: !u.disabled }), u.disabled ? 'Enabled.' : 'Disabled and signed out.'),
        }, u.disabled ? 'Enable' : 'Disable'),
        h('button', {
          class: 'btn small', type: 'button',
          onClick: async () => {
            const pw = h('input', { class: 'input', type: 'password', autocomplete: 'new-password' });
            const ok = await dialog({
              title: `Reset password for ${u.username}`,
              content: h('div', { class: 'field' }, h('label', {}, 'New password (at least 8 characters)'), pw),
              actions: [{ label: 'Cancel', value: false }, { label: 'Reset password', value: true, primary: true }],
            });
            if (ok) act(() => api('PATCH', `admin/users/${u.id}`, { password: pw.value }), 'Password reset; their devices were signed out.');
          },
        }, 'Reset password'),
        h('button', {
          class: 'btn small danger', type: 'button', disabled: me, 'aria-label': `Delete ${u.username}`,
          onClick: async () => {
            if (await confirmDialog(`Delete ${u.username}?`, 'Their account and learned ayahs are removed from this server.', { confirm: 'Delete', danger: true })) {
              act(() => api('DELETE', `admin/users/${u.id}`), 'User deleted.');
            }
          },
        }, icon('trash')),
      )),
    );
  }

  regToggle.addEventListener('change', () =>
    act(() => api('PATCH', 'admin/settings', { registrationOpen: regToggle.checked }), regToggle.checked ? 'Anyone can now create an account.' : 'Registration closed.'));

  const newName = h('input', { class: 'input', placeholder: 'username', autocomplete: 'off', 'data-testid': 'new-username' });
  const newPw = h('input', { class: 'input', type: 'password', placeholder: 'password (8+ characters)', autocomplete: 'new-password', 'data-testid': 'new-password' });
  const newRole = h('select', { class: 'select' }, h('option', { value: 'user' }, 'User'), h('option', { value: 'admin' }, 'Administrator'));
  const create = h('form', { class: 'row wrap' }, newName, newPw, newRole, h('button', { class: 'btn primary', type: 'submit', 'data-testid': 'create-user' }, icon('plus'), 'Create'));
  create.addEventListener('submit', (e) => {
    e.preventDefault();
    act(() => api('POST', 'admin/users', { username: newName.value.trim(), password: newPw.value, role: newRole.value }), `Created ${newName.value.trim()}.`)
      .then(() => {
        newName.value = '';
        newPw.value = '';
      });
  });

  page.append(
    h('header', { class: 'page-head' },
      h('div', {}, h('h1', {}, 'Users'), h('p', { class: 'muted' }, 'Everyone with an account on this server.')),
      h('a', { class: 'btn ghost', href: '#/account' }, icon('user'), 'Your account')),
    h('section', { class: 'card' },
      h('label', { class: 'toggle-row' },
        h('span', { class: 'text' }, h('strong', {}, 'Open registration'), h('span', {}, 'Let anyone who can reach this server create an account. Close it once your family or class is set up.')),
        regToggle)),
    h('section', { class: 'card stack' }, h('h2', {}, 'Create an account'), create),
    h('section', { class: 'card' }, h('div', { class: 'table-wrap' },
      h('table', { class: 'table' },
        h('thead', {}, h('tr', {}, ['Name', 'Role', 'Learned', 'Joined', 'Last sign-in', ''].map((t) => h('th', {}, t)))),
        table))),
  );
  load();
  return { el: page, title: 'Users' };
}

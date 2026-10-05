// Tiny DOM helpers: an element builder, icons, toasts and dialogs. No framework, no innerHTML
// with untrusted data — text always goes in as text nodes.

const SVG_NS = 'http://www.w3.org/2000/svg';

/**
 * h('button', {class: 'btn', onClick: fn, 'aria-label': 'Play'}, icon('play'), 'Play')
 * Props: class, dataset, style (object), on<Event> handlers, boolean attributes, anything else
 * becomes an attribute. Children: strings, numbers, nodes, arrays; null/false are skipped.
 */
export function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props ?? {})) {
    if (value == null || value === false) continue;
    if (key === 'class') el.className = value;
    else if (key === 'dataset') Object.assign(el.dataset, value);
    else if (key === 'style') Object.assign(el.style, value);
    else if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === 'value') el.value = value;
    else if (key === 'checked') el.checked = Boolean(value);
    else if (value === true) el.setAttribute(key, '');
    else el.setAttribute(key, String(value));
  }
  append(el, children);
  return el;
}

export function append(el, children) {
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

export function clear(el) {
  while (el.firstChild) el.firstChild.remove();
  return el;
}

// Feather-style line icons (MIT). Each entry is a list of SVG elements.
const ICONS = {
  play: [['polygon', { points: '6 4 20 12 6 20 6 4' }]],
  pause: [['rect', { x: 6, y: 4, width: 4, height: 16 }], ['rect', { x: 14, y: 4, width: 4, height: 16 }]],
  next: [['polygon', { points: '5 4 15 12 5 20 5 4' }], ['line', { x1: 19, y1: 5, x2: 19, y2: 19 }]],
  prev: [['polygon', { points: '19 20 9 12 19 4 19 20' }], ['line', { x1: 5, y1: 19, x2: 5, y2: 5 }]],
  list: [
    ['line', { x1: 8, y1: 6, x2: 21, y2: 6 }],
    ['line', { x1: 8, y1: 12, x2: 21, y2: 12 }],
    ['line', { x1: 8, y1: 18, x2: 21, y2: 18 }],
    ['line', { x1: 3, y1: 6, x2: 3.01, y2: 6 }],
    ['line', { x1: 3, y1: 12, x2: 3.01, y2: 12 }],
    ['line', { x1: 3, y1: 18, x2: 3.01, y2: 18 }],
  ],
  book: [
    ['path', { d: 'M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z' }],
    ['path', { d: 'M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z' }],
  ],
  headphones: [
    ['path', { d: 'M3 18v-6a9 9 0 0 1 18 0v6' }],
    ['path', { d: 'M21 19a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3zM3 19a2 2 0 0 0 2 2h1a2 2 0 0 0 2-2v-3a2 2 0 0 0-2-2H3z' }],
  ],
  settings: [
    ['circle', { cx: 12, cy: 12, r: 3 }],
    ['path', { d: 'M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z' }],
  ],
  search: [['circle', { cx: 11, cy: 11, r: 8 }], ['line', { x1: 21, y1: 21, x2: 16.65, y2: 16.65 }]],
  star: [['polygon', { points: '12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2' }]],
  check: [['polyline', { points: '20 6 9 17 4 12' }]],
  mic: [
    ['path', { d: 'M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z' }],
    ['path', { d: 'M19 10v2a7 7 0 0 1-14 0v-2' }],
    ['line', { x1: 12, y1: 19, x2: 12, y2: 23 }],
    ['line', { x1: 8, y1: 23, x2: 16, y2: 23 }],
  ],
  square: [['rect', { x: 6, y: 6, width: 12, height: 12, rx: 2 }]],
  x: [['line', { x1: 18, y1: 6, x2: 6, y2: 18 }], ['line', { x1: 6, y1: 6, x2: 18, y2: 18 }]],
  upload: [
    ['path', { d: 'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4' }],
    ['polyline', { points: '17 8 12 3 7 8' }],
    ['line', { x1: 12, y1: 3, x2: 12, y2: 15 }],
  ],
  download: [
    ['path', { d: 'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4' }],
    ['polyline', { points: '7 10 12 15 17 10' }],
    ['line', { x1: 12, y1: 15, x2: 12, y2: 3 }],
  ],
  user: [['path', { d: 'M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2' }], ['circle', { cx: 12, cy: 7, r: 4 }]],
  users: [
    ['path', { d: 'M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2' }],
    ['circle', { cx: 9, cy: 7, r: 4 }],
    ['path', { d: 'M23 21v-2a4 4 0 0 0-3-3.87' }],
    ['path', { d: 'M16 3.13a4 4 0 0 1 0 7.75' }],
  ],
  plus: [['line', { x1: 12, y1: 5, x2: 12, y2: 19 }], ['line', { x1: 5, y1: 12, x2: 19, y2: 12 }]],
  edit: [['path', { d: 'M12 20h9' }], ['path', { d: 'M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4z' }]],
  copy: [
    ['rect', { x: 9, y: 9, width: 13, height: 13, rx: 2 }],
    ['path', { d: 'M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1' }],
  ],
  layers: [
    ['polygon', { points: '12 2 2 7 12 12 22 7 12 2' }],
    ['polyline', { points: '2 17 12 22 22 17' }],
    ['polyline', { points: '2 12 12 17 22 12' }],
  ],
  repeat: [
    ['polyline', { points: '17 1 21 5 17 9' }],
    ['path', { d: 'M3 11V9a4 4 0 0 1 4-4h14' }],
    ['polyline', { points: '7 23 3 19 7 15' }],
    ['path', { d: 'M21 13v2a4 4 0 0 1-4 4H3' }],
  ],
  chevronUp: [['polyline', { points: '18 15 12 9 6 15' }]],
  chevronDown: [['polyline', { points: '6 9 12 15 18 9' }]],
  chevronLeft: [['polyline', { points: '15 18 9 12 15 6' }]],
  volume: [
    ['polygon', { points: '11 5 6 9 2 9 2 15 6 15 11 19 11 5' }],
    ['path', { d: 'M15.54 8.46a5 5 0 0 1 0 7.07' }],
  ],
  logout: [
    ['path', { d: 'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4' }],
    ['polyline', { points: '16 17 21 12 16 7' }],
    ['line', { x1: 21, y1: 12, x2: 9, y2: 12 }],
  ],
  shield: [['path', { d: 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z' }]],
  cloud: [['path', { d: 'M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z' }]],
  trash: [
    ['polyline', { points: '3 6 5 6 21 6' }],
    ['path', { d: 'M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2' }],
  ],
  refresh: [
    ['polyline', { points: '23 4 23 10 17 10' }],
    ['path', { d: 'M20.49 15a9 9 0 1 1-2.12-9.36L23 10' }],
  ],
};

export function icon(name, { filled = false, label } = {}) {
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('class', `icon${filled ? ' filled' : ''}`);
  if (label) {
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', label);
  } else {
    svg.setAttribute('aria-hidden', 'true');
  }
  for (const [tag, attrs] of ICONS[name] ?? []) {
    const child = document.createElementNS(SVG_NS, tag);
    for (const [k, v] of Object.entries(attrs)) child.setAttribute(k, String(v));
    svg.append(child);
  }
  return svg;
}

export function toast(message, { error = false, timeout = 3500 } = {}) {
  const host = document.getElementById('toasts');
  if (!host) return;
  const el = h('div', { class: `toast${error ? ' error' : ''}` }, message);
  host.append(el);
  setTimeout(() => el.remove(), timeout);
}

/**
 * A modal dialog. `content` is a node; `actions` are {label, value, primary, danger}.
 * Resolves with the chosen action's value (or null on Escape / backdrop).
 */
export function dialog({ title, content, actions = [{ label: 'OK', value: true, primary: true }], dismissible = true }) {
  return new Promise((resolve) => {
    const previous = document.activeElement;
    const close = (value) => {
      wrap.remove();
      document.removeEventListener('keydown', onKey);
      previous?.focus?.();
      resolve(value);
    };
    const onKey = (e) => {
      if (e.key === 'Escape' && dismissible) close(null);
    };
    const buttons = actions.map((a) =>
      h(
        'button',
        {
          class: `btn${a.primary ? ' primary' : ''}${a.danger ? ' danger' : ''}`,
          type: 'button',
          onClick: async () => {
            if (a.onClick) {
              const keepOpen = await a.onClick();
              if (keepOpen === false) return;
            }
            close(a.value);
          },
        },
        a.label,
      ),
    );
    const box = h(
      'div',
      { class: 'dialog', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'dialog-title' },
      h('h2', { id: 'dialog-title' }, title),
      content,
      h('div', { class: 'actions' }, buttons),
    );
    const wrap = h('div', {
      class: 'dialog-wrap',
      onClick: (e) => {
        if (e.target === wrap && dismissible) close(null);
      },
    }, box);
    document.body.append(wrap);
    document.addEventListener('keydown', onKey);
    (box.querySelector('input, textarea, select') ?? buttons[buttons.length - 1])?.focus();
  });
}

export function confirmDialog(title, message, { confirm = 'Confirm', danger = false } = {}) {
  return dialog({
    title,
    content: h('p', { class: 'muted' }, message),
    actions: [
      { label: 'Cancel', value: false },
      { label: confirm, value: true, primary: !danger, danger },
    ],
  });
}

export function formatTime(ms) {
  if (!Number.isFinite(ms) || ms < 0) ms = 0;
  const total = Math.floor(ms / 1000);
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
}

export function downloadText(filename, text, type = 'application/json') {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = h('a', { href: url, download: filename });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function pickFile(accept = '.json,application/json,text/plain') {
  return new Promise((resolve) => {
    const input = h('input', { type: 'file', accept, style: { display: 'none' } });
    input.addEventListener('change', async () => {
      const file = input.files?.[0];
      input.remove();
      resolve(file ? { name: file.name, text: await file.text() } : null);
    });
    document.body.append(input);
    input.click();
  });
}

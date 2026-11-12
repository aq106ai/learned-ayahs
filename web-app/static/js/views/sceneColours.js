// The app's colours when the player has a background: taken from the scene on screen, dark or
// light as the theme says, and kept across every page (Manage, Settings, dialogs) so the app
// reads as one thing. Only the player draws the picture itself. Android does the same in
// ui/theme/Color.kt (applyShownScene).
//
// The last scene is remembered, so a reload on another page comes back in the same colours.

import { applyPalette, paletteFor } from '../video/palette.js';

const KEY = 'la.sceneColours';
// Photos share one neutral palette: their own colours are too varied to read text against.
const PHOTO = { colors: [[16, 20, 24, 255], [16, 20, 24, 255], [16, 20, 24, 255], [255, 255, 255, 255]] };

let scene = read();
let palette = null;

function read() {
  try {
    const saved = JSON.parse(localStorage.getItem(KEY) ?? 'null');
    return Array.isArray(saved?.colors) && saved.colors.length >= 2 ? saved : null;
  } catch {
    return null;
  }
}

function write() {
  try {
    if (scene) localStorage.setItem(KEY, JSON.stringify(scene));
    else localStorage.removeItem(KEY);
  } catch {
    // Private mode: the colours still apply, they just are not remembered.
  }
}

/** The scene now behind the player (a scene spec), or null for none. Returns the palette. */
export function setScene(spec) {
  scene = spec ? { kind: spec.kind, colors: spec.colors } : null;
  write();
  return applySceneColours();
}

/** Re-applies the colours: call when the theme changes. `plain`: no background is chosen. */
export function applySceneColours({ plain = false } = {}) {
  if (plain && scene) {
    scene = null;
    write();
  }
  const light = document.documentElement.dataset.theme === 'light';
  const source = scene?.kind === 'PHOTO' ? PHOTO : scene;
  palette = source ? paletteFor(source, light) : null;
  applyPalette(document.documentElement, palette);
  document.documentElement.classList.toggle('scene-palette', Boolean(palette));
  return palette;
}

export const currentPalette = () => palette;

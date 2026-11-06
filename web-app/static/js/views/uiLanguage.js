// The interface language picker (port of ui/settings/UiLanguagePicker.kt): the browser's, or one
// we ship, each named in its own script. Choosing one reloads the page in it (core/i18n.js).

import { h } from '../lib/dom.js';
import { UI_LANGUAGES, setUiLanguage, tx, uiLanguageChoice } from '../core/i18n.js';

export function uiLanguagePicker() {
  const choice = uiLanguageChoice();
  const options = [['', tx("Browser's language")], ...UI_LANGUAGES.map((l) => [l.code, l.nativeName])];
  return h('div', { class: 'chips', role: 'radiogroup', 'aria-label': tx('App language') }, options.map(([code, label]) => h('button', {
    class: 'chip', type: 'button', role: 'radio', lang: code || undefined,
    'aria-checked': String(choice === code), 'aria-pressed': String(choice === code),
    'data-testid': `ui-language-${code || 'browser'}`,
    onClick: () => { if (choice !== code) setUiLanguage(code); },
  }, label)));
}

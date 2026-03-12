/**
 * @file main.js — Latent Model Organizer (Vue entry point)
 *
 * Import order matters:
 *  1. Theme CSS first — neon.css uses :root so it's the universal baseline.
 *     All other themes override via body class, so they must come after.
 *  2. Component CSS after themes so component rules can reference theme vars.
 *  3. Lmo_buttons.css last in the component block — it intentionally overrides
 *     the generic rules in buttons.css for nav-btn / primary-btn etc.
 *  4. Theme class is stamped onto <body> SYNCHRONOUSLY here, before createApp(),
 *     so the very first paint already has the correct theme. This eliminates
 *     the white flash that occurs when applyTheme() runs inside onMounted().
 */

// ── Theme CSS (neon = :root baseline, others = body-class overrides) ───────
import './assets/css/themes/neon.css'
import './assets/css/themes/gold.css'
import './assets/css/themes/light.css'
import './assets/css/themes/fanfriction.css'
import './assets/css/themes/fanfriction-light.css'

// ── Component CSS ───────────────────────────────────────────────────────────
import './assets/css/components/base.css'
import './assets/css/components/layout.css'
import './assets/css/components/buttons.css'
import './assets/css/components/Lmo_buttons.css'     // nav-btn / primary-btn / glass-input
import './assets/css/components/primevue-overrides.css'

// ── PrimeVue + PrimeIcons ───────────────────────────────────────────────────
// import 'primevue/resources/themes/aura-dark-green/theme.css'
// import 'primeicons/primeicons.css'

import { createApp } from 'vue'
// import PrimeVue from 'primevue/config'
import App from './App.vue'

// ── Stamp theme class synchronously BEFORE first paint ─────────────────────
// Reads the saved preference from localStorage (same key App.vue uses).
// Falls back to 'neon'. Because this runs before mount, the browser never
// renders a single frame without the correct theme class on <body>.
(function applyInitialTheme() {
    try {
        const saved = localStorage.getItem('lmo:theme') || 'neon';
        document.body.classList.add(`theme-${saved}`);
    } catch {
        document.body.classList.add('theme-neon');
    }
})();

// ── Bootstrap ───────────────────────────────────────────────────────────────
const app = createApp(App)
app.use(PrimeVue, { ripple: true })
app.mount('#app')
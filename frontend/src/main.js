/**
 * The main entry point for the Vue 3 application.
 *
 * This bootstrap script orchestrates the initial application lifecycle, ensuring a
 * flicker-free startup experience by applying user theme preferences synchronously
 * before the first paint. It handles the critical registration of the PrimeVue
 * component framework and manages the complex cascade of theme and component
 * stylesheets.
 *
 * Key Bootstrap Phases:
 * - Theme Propagation: Injects global CSS variables from theme-specific files.
 * - Framework Initialization: Configures the Vue application instance with PrimeVue.
 * - CSS Cascade Management: Enforces a strict import order to ensure component
 *   styles correctly override base resets and theme defaults.
 * - State Restoration: Synchronizes the initial DOM state with persistent
 *   localStorage settings.
 */

import './assets/css/themes/neon.css'
import './assets/css/themes/gold.css'
import './assets/css/themes/light.css'
import './assets/css/themes/fanfriction.css'
import './assets/css/themes/fanfriction-light.css'

import './assets/css/components/base.css'
import './assets/css/components/layout.css'
import './assets/css/components/buttons.css'
import './assets/css/components/primevue-overrides.css'

import 'primeicons/primeicons.css'

import {createApp} from 'vue'
import PrimeVue from 'primevue/config'
import App from './App.vue'

(function applyInitialTheme() {
    try {
        const saved = localStorage.getItem('lmo:theme') || 'neon';
        document.body.classList.add(`theme-${saved}`);
    } catch {
        document.body.classList.add('theme-neon');
    }
})();

const app = createApp(App)
app.use(PrimeVue, {ripple: true})
app.mount('#app')

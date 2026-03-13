/**
 * <h1>Main Entry Point</h1>
 * <p>
 * This script orchestrates the bootstrap process for the Vue 3 application.
 * It ensures the consistent application of user preferences, initializes UI frameworks,
 * and manages the global stylesheet cascade.
 * </p>
 *
 * <h2>Bootstrap Sequence</h2>
 * <ol>
 *   <li><b>Theme Pre-loading:</b> Synchronously applies the user's saved theme from {@code localStorage} to the body class to prevent flash-of-unstyled-content (FOUC).</li>
 *   <li><b>Asset Import:</b> Imports critical theme variables and component-level CSS modules.</li>
 *   <li><b>Framework Setup:</b> Initializes the Vue application instance and configures PrimeVue for high-performance UI components.</li>
 *   <li><b>DOM Mounting:</b> Attaches the root application component to the {@code #app} container.</li>
 * </ol>
 *
 * @see App.vue
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

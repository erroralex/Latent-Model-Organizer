/**
 * The main entry point for the Vue 3 application.
 *
 * This file orchestrates the initial application bootstrap process, including:
 * - Asset Importation: Loading global CSS components and PrimeVue design system overrides.
 * - Application Creation: Initializing the Vue application instance with the root App component.
 * - DOM Mounting: Attaching the reactive application instance to the #app container in the HTML.
 */

import './assets/css/components/base.css'
import './assets/css/components/layout.css'
import './assets/css/components/buttons.css'
import './assets/css/components/primevue-overrides.css'

import { createApp } from 'vue'
import App from './App.vue'

createApp(App).mount('#app')

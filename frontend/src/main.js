/**
 * Main Entry Point
 * 
 * Orchestrates the bootstrap process for the Vue 3 application using the Latent Design System.
 */

import './assets/css/latent/styles.css'

import './assets/css/components/base.css'
import './assets/css/components/layout.css'
import './assets/css/components/buttons.css'
import './assets/css/components/primevue-overrides.css'

import 'primeicons/primeicons.css'

import { createApp } from 'vue'
import PrimeVue from 'primevue/config'
import App from './App.vue'

const app = createApp(App)
app.use(PrimeVue, { ripple: true })
app.mount('#app')

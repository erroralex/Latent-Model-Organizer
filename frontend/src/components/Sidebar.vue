<script setup>
/**
 * SIDEBAR.VUE
 *
 * The primary navigation and utility control component for the application.
 * It provides high-level switching between the core Sorter and Fetcher workflows,
 * while also hosting system-wide utility toggles.
 *
 * NAVIGATION & UTILITIES:
 * - Workflow Selection: Toggles the active view between model organization and metadata fetching.
 * - Console Management: Provides a toggle for the integrated Server-Sent Events (SSE) log stream.
 * - Settings Access: Controls the visibility of the global settings modal.
 * - Dynamic Branding: Synchronizes the displayed logo with the currently active theme.
 *
 * @see App.vue
 * @see ConsoleWindow.vue
 */
import { computed } from 'vue';

const props = defineProps({
  activeTab: { type: String, required: true },
  showSettings: { type: Boolean, required: true },
  consoleOpen: { type: Boolean, required: true },
  isProcessing: { type: Boolean, default: false },
  currentTheme: { type: String, default: 'neon' }
});

const emit = defineEmits(['update:activeTab', 'update:showSettings', 'update:consoleOpen']);

const logoPath = computed(() => {
  const map = {
    'neon': 'alx_logo_neon.png',
    'gold': 'alx_logo_gold.png',
    'light': 'alx_logo_light.png',
    'fanfriction': 'alx_logo_fan.png',
    'fanfriction-light': 'alx_logo_fan_light.png'
  };
  const fileName = map[props.currentTheme] || 'alx_logo_neon.png';
  return new URL(`../assets/${fileName}`, import.meta.url).href;
});
</script>

<template>
  <aside class="sidebar glass-panel">
    <nav class="sidebar-nav">

      <div class="sidebar-nav-group">
        <button
          class="nav-btn sidebar-btn"
          :class="{ 'active-nav-btn': activeTab === 'sort' }"
          @click="emit('update:activeTab', 'sort')"
          :disabled="isProcessing"
          title="Sorter"
        >
          <i class="pi pi-sort-alt"></i>
          <span class="btn-label">Sorter</span>
        </button>

        <button
          class="nav-btn sidebar-btn"
          :class="{ 'active-nav-btn': activeTab === 'fetch' }"
          @click="emit('update:activeTab', 'fetch')"
          :disabled="isProcessing"
          title="Fetcher"
        >
          <i class="pi pi-cloud-download"></i>
          <span class="btn-label">Fetcher</span>
        </button>
      </div>

      <div class="sidebar-nav-spacer"></div>

      <div class="sidebar-nav-group sidebar-bottom-group">

        <label class="toggle-label sidebar-toggle">
          <input type="checkbox" :checked="consoleOpen" @change="emit('update:consoleOpen', $event.target.checked)" class="sr-only" />
          <span class="toggle-track" :class="{ checked: consoleOpen }"><span class="toggle-thumb"></span></span>
          <span class="btn-label">Show Console</span>
        </label>

        <button
          class="nav-btn sidebar-btn icon-only-mobile"
          :class="{ 'active-nav-btn': showSettings }"
          @click="emit('update:showSettings', !showSettings)"
          title="Settings"
        >
          <i class="pi pi-cog"></i>
          <span class="btn-label">Settings</span>
        </button>

        <img :src="logoPath" alt="Theme Logo" class="sidebar-logo" />

      </div>

    </nav>
  </aside>
</template>

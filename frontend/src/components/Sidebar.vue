<script setup>
/**
 * Sidebar.vue
 *
 * Navigation sidebar for Latent Model Organizer aligned with the Latent Design System.
 */
import alxLogoUrl from '../assets/alx_logo.png';
import { ArrowUpDown, CloudDownload, Settings as SettingsIcon } from 'lucide-vue-next';
import LSwitch from '@/components/ds/LSwitch.vue';

const props = defineProps({
  activeTab: { type: String, required: true },
  showSettings: { type: Boolean, required: true },
  consoleOpen: { type: Boolean, required: true },
  isProcessing: { type: Boolean, default: false }
});

const emit = defineEmits(['update:activeTab', 'update:showSettings', 'update:consoleOpen']);

const openDevProfile = () => {
  const url = 'https://github.com/erroralex';
  if (window.electronAPI?.openExternal) {
    window.electronAPI.openExternal(url);
  } else {
    window.open(url, '_blank');
  }
};
</script>

<template>
  <aside class="sidebar-ds">
    <nav class="sidebar-nav-ds">
      <div class="sidebar-group">
        <button
          class="nav-item-ds"
          :class="{ active: activeTab === 'sort' }"
          @click="emit('update:activeTab', 'sort')"
          :disabled="isProcessing"
        >
          <span class="active-indicator-bar" v-if="activeTab === 'sort'"></span>
          <ArrowUpDown :size="17" class="nav-icon" />
          <span>Sorter</span>
        </button>

        <button
          class="nav-item-ds"
          :class="{ active: activeTab === 'fetch' }"
          @click="emit('update:activeTab', 'fetch')"
          :disabled="isProcessing"
        >
          <span class="active-indicator-bar" v-if="activeTab === 'fetch'"></span>
          <CloudDownload :size="17" class="nav-icon" />
          <span>Fetcher</span>
        </button>
      </div>

      <div class="sidebar-spacer"></div>

      <div class="sidebar-group sidebar-bottom">
        <LSwitch
            class="console-switch"
            :model-value="consoleOpen"
            label="Console"
            @update:model-value="v => emit('update:consoleOpen', v)"
        />

        <button
          class="nav-item-ds"
          :class="{ active: showSettings }"
          @click="emit('update:showSettings', !showSettings)"
        >
          <span class="active-indicator-bar" v-if="showSettings"></span>
          <SettingsIcon :size="17" class="nav-icon" />
          <span>Settings</span>
        </button>

        <a
          href="https://github.com/erroralex"
          rel="noopener noreferrer"
          title="Built by Alexander Nilsson"
          class="dev-credit-link"
          @click.prevent="openDevProfile"
        >
          <img :src="alxLogoUrl" alt="Alexander Nilsson" class="dev-logo-img" />
        </a>
      </div>
    </nav>
  </aside>
</template>

<style scoped>
.sidebar-ds {
  width: var(--sidebar-width);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  padding: 16px 12px;
  border-right: 1px solid var(--color-border-subtle);
  background: rgba(14, 15, 19, 0.6);
  backdrop-filter: var(--blur-glass);
}

.sidebar-nav-ds {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 8px;
}

.sidebar-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.sidebar-spacer {
  flex: 1;
}

.sidebar-bottom {
  border-top: 1px solid var(--color-border-subtle);
  padding-top: 12px;
}

.nav-item-ds {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 9px 12px 9px 14px;
  border-radius: var(--radius-md, 8px);
  border: none;
  background: transparent;
  color: var(--color-text-tertiary);
  font-size: var(--text-body-sm, 13px);
  font-weight: var(--weight-semibold, 600);
  font-family: var(--font-sans);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-standard), color var(--duration-fast) var(--ease-standard);
  text-align: left;
  overflow: hidden;
}

.nav-item-ds:hover:not(:disabled) {
  color: var(--color-text-primary);
  background: var(--color-surface-1);
}

.nav-item-ds.active {
  color: var(--color-text-primary);
  background: var(--color-surface-1);
  font-weight: var(--weight-semibold, 600);
}

.active-indicator-bar {
  position: absolute;
  left: 0;
  top: 6px;
  bottom: 6px;
  width: 3px;
  border-radius: 0 2px 2px 0;
  background: var(--gradient-brand);
}

.nav-icon {
  font-size: 1.05rem;
  color: inherit;
  transition: color var(--duration-fast);
}

/* Match .nav-item-ds's padding so "Console" lines up with "Settings" below it. */
.console-switch {
  padding: 8px 12px 8px 14px;
}

.dev-credit-link {
  display: flex;
  justify-content: center;
  align-items: center;
  margin-top: 12px;
  opacity: 0.55;
  transition: opacity var(--duration-fast);
}

.dev-credit-link:hover {
  opacity: 1;
}

.dev-logo-img {
  max-width: 120px;
  height: auto;
  max-height: 44px;
  object-fit: contain;
}
</style>

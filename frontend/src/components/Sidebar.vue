<script setup>
/**
 * Sidebar.vue
 *
 * Navigation sidebar for Latent Model Organizer aligned with the Latent Design System.
 */
import alxLogoUrl from '../assets/alx_logo.png';

const props = defineProps({
  activeTab: { type: String, required: true },
  showSettings: { type: Boolean, required: true },
  consoleOpen: { type: Boolean, required: true },
  isProcessing: { type: Boolean, default: false }
});

const emit = defineEmits(['update:activeTab', 'update:showSettings', 'update:consoleOpen']);
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
          <i class="pi pi-sort-alt nav-icon"></i>
          <span>Sorter</span>
        </button>

        <button
          class="nav-item-ds"
          :class="{ active: activeTab === 'fetch' }"
          @click="emit('update:activeTab', 'fetch')"
          :disabled="isProcessing"
        >
          <i class="pi pi-cloud-download nav-icon"></i>
          <span>Fetcher</span>
        </button>
      </div>

      <div class="sidebar-spacer"></div>

      <div class="sidebar-group sidebar-bottom">
        <label class="toggle-control-ds">
          <input
            type="checkbox"
            :checked="consoleOpen"
            @change="emit('update:consoleOpen', $event.target.checked)"
            class="sr-only"
          />
          <span class="toggle-track-ds" :class="{ checked: consoleOpen }">
            <span class="toggle-thumb-ds"></span>
          </span>
          <span class="toggle-text">Console</span>
        </label>

        <button
          class="nav-item-ds"
          :class="{ active: showSettings }"
          @click="emit('update:showSettings', !showSettings)"
        >
          <i class="pi pi-cog nav-icon"></i>
          <span>Settings</span>
        </button>

        <a
          href="https://github.com/erroralex"
          target="_blank"
          rel="noopener noreferrer"
          title="Built by Alexander Nilsson"
          class="dev-credit-link"
        >
          <img :src="alxLogoUrl" alt="Alexander Nilsson" class="dev-logo-img" />
        </a>
      </div>
    </nav>
  </aside>
</template>

<style scoped>
.sidebar-ds {
  width: var(--sidebar-width, 200px);
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
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 14px;
  border-radius: var(--radius-md);
  border: 1px solid transparent;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--text-body-sm, 13px);
  font-weight: var(--weight-medium, 500);
  font-family: var(--font-sans);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-standard);
  text-align: left;
}

.nav-item-ds:hover:not(:disabled) {
  color: var(--color-text-primary);
  background: var(--color-surface-1);
  border-color: var(--color-border-subtle);
}

.nav-item-ds.active {
  color: var(--color-text-primary);
  background: var(--color-accent-primary-bg);
  border-color: rgba(79, 216, 208, 0.3);
  font-weight: var(--weight-semibold);
}

.nav-item-ds.active .nav-icon {
  color: var(--color-accent-primary);
}

.nav-icon {
  font-size: 1.05rem;
  color: var(--color-text-tertiary);
  transition: color var(--duration-fast);
}

.toggle-control-ds {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  cursor: pointer;
  user-select: none;
}

.toggle-track-ds {
  position: relative;
  width: 36px;
  height: 20px;
  border-radius: var(--radius-full);
  background: var(--color-surface-2);
  border: 1px solid var(--color-border-default);
  transition: background var(--duration-fast), border-color var(--duration-fast);
  flex-shrink: 0;
}

.toggle-track-ds.checked {
  background: var(--color-accent-primary);
  border-color: var(--color-accent-primary);
}

.toggle-thumb-ds {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--color-text-secondary);
  transition: transform var(--duration-fast) var(--ease-standard), background var(--duration-fast);
}

.toggle-track-ds.checked .toggle-thumb-ds {
  transform: translateX(16px);
  background: var(--color-text-on-accent);
}

.toggle-text {
  font-size: var(--text-body-sm, 13px);
  color: var(--color-text-secondary);
  font-weight: var(--weight-medium);
}

.dev-credit-link {
  display: flex;
  justify-content: center;
  align-items: center;
  margin-top: 12px;
  opacity: 0.6;
  transition: opacity var(--duration-fast);
}

.dev-credit-link:hover {
  opacity: 1;
}

.dev-logo-img {
  width: 64px;
  height: auto;
}
</style>

<script setup>
/**
 * SETTINGSMODAL.VUE
 *
 * A centralized configuration interface for managing application-wide preferences.
 * It provides controls for visual themes, operational defaults, and external support integrations.
 *
 * CONFIGURATION MODULES:
 * - Appearance: Offers real-time theme switching with visual swatches and metadata-driven labeling.
 * - Operational Defaults: Configures persistent states for core backend behaviors like deep scanning and dry-run mode.
 * - External Integration: Provides links to external platforms (e.g., Ko-fi) via Electron's secure shell interface.
 * - Application Info: Displays versioning and technology stack metadata for transparency.
 *
 * @see useTheme.js
 * @see App.vue
 */
const props = defineProps({
  currentTheme: {type: String, required: true},
  availableThemes: {type: Array, required: true},
  isRecursive: {type: Boolean, required: true},
  isDryRun: {type: Boolean, required: true},
});
const emit = defineEmits(['applyTheme', 'update:isRecursive', 'update:isDryRun', 'close']);

const openKofi = () => window.electronAPI?.openExternal('https://ko-fi.com');

const THEME_META = {
  neon: {label: 'Deep Neon', icon: 'pi-bolt', accent: '#66fcf1'},
  gold: {label: 'Sleek Gold', icon: 'pi-star', accent: '#e69d67'},
  light: {label: 'Clean Light', icon: 'pi-sun', accent: '#007bff'},
  fanfriction: {label: 'Fan Friction', icon: 'pi-heart', accent: '#d2b48c'},
  'fanfriction-light': {label: 'Fan Friction Light', icon: 'pi-heart-fill', accent: '#a0522d'},
};
</script>

<template>
  <div class="modal-overlay" @click.self="emit('close')">
    <div class="glass-panel settings-box" role="dialog" aria-modal="true">

      <div class="modal-header">
        <div class="modal-title-group">
          <i class="pi pi-cog" style="color: var(--accent-primary)"></i>
          <h2 class="modal-title">Settings</h2>
        </div>
        <button class="nav-btn icon-only" @click="emit('close')" title="Close">
          <i class="pi pi-times"></i>
        </button>
      </div>

      <div class="settings-body">

        <section class="settings-section">
          <h3 class="settings-section-title">
            <i class="pi pi-palette"></i> Appearance
          </h3>
          <div class="theme-grid">
            <button
                v-for="themeKey in availableThemes"
                :key="themeKey"
                class="theme-card"
                :class="{ active: currentTheme === themeKey }"
                @click="emit('applyTheme', themeKey)"
            >
              <span class="theme-swatch" :style="{ background: THEME_META[themeKey]?.accent ?? '#888' }"></span>
              <span class="theme-label">{{ THEME_META[themeKey]?.label ?? themeKey }}</span>
              <i v-if="currentTheme === themeKey" class="pi pi-check theme-check"></i>
            </button>
          </div>
        </section>

        <section class="settings-section">
          <h3 class="settings-section-title">
            <i class="pi pi-sliders-h"></i> Default Toggles
          </h3>
          <div class="toggle-list">
            <div class="toggle-row">
              <div class="toggle-info">
                <span class="toggle-name">Deep Scan</span>
                <span class="toggle-desc">Recursively scan all subfolders</span>
              </div>
              <label class="toggle-switch">
                <input type="checkbox" :checked="isRecursive"
                       @change="e => emit('update:isRecursive', e.target.checked)" class="sr-only"/>
                <span class="toggle-track" :class="{ checked: isRecursive }"><span class="toggle-thumb"></span></span>
              </label>
            </div>
            <div class="toggle-row">
              <div class="toggle-info">
                <span class="toggle-name">Dry Run</span>
                <span class="toggle-desc">Simulate operations without moving files</span>
              </div>
              <label class="toggle-switch">
                <input type="checkbox" :checked="isDryRun" @change="e => emit('update:isDryRun', e.target.checked)"
                       class="sr-only"/>
                <span class="toggle-track" :class="{ checked: isDryRun }"><span class="toggle-thumb"></span></span>
              </label>
            </div>
          </div>
        </section>

        <section class="settings-section">
          <h3 class="settings-section-title">
            <i class="pi pi-heart"></i> Support
          </h3>
          <button class="kofi-btn" @click="openKofi">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
              <path
                  d="M23.881 8.948c-.773-4.085-4.859-4.593-4.859-4.593H.723c-.604 0-.679.798-.679.798s-.082 7.324-.022 11.822c.164 2.424 2.586 2.672 2.586 2.672s8.267-.023 11.966-.049c2.438-.426 2.683-2.566 2.658-3.734 4.352.24 7.422-2.831 6.649-6.916zm-11.062 3.511c-1.246 1.453-4.011 3.976-4.011 3.976s-.121.119-.31.023c-.076-.057-.108-.09-.108-.09-.443-.441-3.368-3.049-4.034-3.954-.709-.965-1.041-2.7-.091-3.71.951-1.01 3.005-1.086 4.363.407 0 0 1.565-1.782 3.468-.963 1.904.82 1.832 3.011.723 4.311zm6.173.478c-.928.116-1.682.028-1.682.028V7.284h1.77s1.971.551 1.971 2.638c0 1.913-.985 2.667-2.059 3.015z"/>
            </svg>
            Support on Ko-fi
          </button>
          <p class="support-text">If LMO saves you time, consider buying a coffee. ☕</p>
        </section>

        <section class="settings-section about-section">
          <span class="about-text">Latent Model Organizer · Java 21 + Vue 3 + Electron</span>
        </section>

      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(0, 0, 0, 0.7);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
}

.settings-box {
  width: 100%;
  max-width: 460px;
  max-height: 86vh;
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  overflow: hidden;
  padding: 0;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 20px 14px;
  border-bottom: 1px solid var(--border-input);
  flex-shrink: 0;
}

.modal-title-group {
  display: flex;
  align-items: center;
  gap: 10px;
}

.modal-title {
  margin: 0;
  font-size: 1.1rem;
  font-weight: 700;
  color: var(--text-primary);
}

.settings-body {
  flex: 1;
  overflow-y: auto;
  padding: 4px 0 8px;
  min-height: 0;
}

.settings-section {
  padding: 18px 20px;
  border-bottom: 1px solid var(--border-input);
}

.settings-section:last-child {
  border-bottom: none;
}

.settings-section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 14px;
  font-size: 0.78rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.07em;
  color: var(--text-secondary);
}

.theme-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}

.theme-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  background: var(--bg-input);
  border: 1px solid var(--border-input);
  color: var(--text-secondary);
  font-size: 0.85rem;
  font-weight: 600;
  transition: all 0.15s;
}

.theme-card:hover {
  border-color: var(--accent-primary);
  color: var(--text-primary);
}

.theme-card.active {
  border-color: var(--accent-primary);
  color: var(--text-primary);
  background: var(--bg-btn-inner);
}

.theme-swatch {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  flex-shrink: 0;
}

.theme-label {
  flex: 1;
  text-align: left;
}

.theme-check {
  color: var(--accent-primary);
  font-size: 0.8rem;
}

.toggle-list {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.toggle-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px solid var(--border-input);
}

.toggle-row:last-child {
  border-bottom: none;
}

.toggle-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.toggle-name {
  font-size: 0.88rem;
  font-weight: 600;
  color: var(--text-primary);
}

.toggle-desc {
  font-size: 0.76rem;
  color: var(--text-muted);
}

.toggle-switch {
  flex-shrink: 0;
  cursor: pointer;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}

.toggle-track {
  display: block;
  position: relative;
  width: 40px;
  height: 22px;
  border-radius: 11px;
  background: var(--bg-input);
  border: 1px solid var(--border-input);
  transition: background 0.2s, border-color 0.2s;
}

.toggle-track.checked {
  background: var(--accent-primary);
  border-color: var(--accent-primary);
}

.toggle-thumb {
  position: absolute;
  top: 3px;
  left: 3px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--text-secondary);
  transition: transform 0.2s, background 0.2s;
}

.toggle-track.checked .toggle-thumb {
  transform: translateX(18px);
  background: #000;
}

.kofi-btn {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 10px 20px;
  border-radius: 8px;
  font-size: 0.9rem;
  font-weight: 700;
  cursor: pointer;
  background: #FF5E5B;
  color: #fff;
  border: none;
  transition: filter 0.2s, transform 0.1s;
}

.kofi-btn:hover {
  filter: brightness(1.1);
  transform: translateY(-1px);
}

.support-text {
  margin: 10px 0 0;
  font-size: 0.8rem;
  color: var(--text-muted);
}

.about-section {
  padding: 12px 20px;
}

.about-text {
  font-size: 0.75rem;
  color: var(--text-muted);
}
</style>

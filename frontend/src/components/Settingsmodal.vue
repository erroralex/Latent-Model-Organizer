<script setup>
/**
 * Settingsmodal.vue
 *
 * Settings dialog for Latent Model Organizer aligned with the Latent Design System.
 */
import { ref, onMounted } from 'vue';
import { Settings as SettingsIcon, Palette, SlidersHorizontal, Heart } from 'lucide-vue-next';

const props = defineProps({
  currentTheme: { type: String, required: true },
  availableThemes: { type: Array, required: true },
  isRecursive: { type: Boolean, required: true },
  isDryRun: { type: Boolean, required: true },
  apiBase: { type: String, required: true },
  apiToken: { type: String, required: true },
});
const emit = defineEmits(['applyTheme', 'update:isRecursive', 'update:isDryRun', 'close']);

const appVersion = ref('dev');

const openKofi = () => window.electronAPI?.openExternal('https://ko-fi.com/error_alex');

onMounted(async () => {
  try {
    const res = await fetch(`${props.apiBase}/api/version`, {
      headers: {
        ...(props.apiToken ? { 'Authorization': `Bearer ${props.apiToken}` } : {})
      }
    });
    if (res.ok) {
      const data = await res.json();
      appVersion.value = data.version || 'dev';
    }
  } catch (e) {
    console.warn("Could not fetch app version", e);
  }
});
</script>

<template>
  <div class="modal-scrim-ds" @click.self="emit('close')">
    <div class="modal-box-ds" role="dialog" aria-modal="true">

      <div class="modal-header-ds">
        <div class="modal-title-group-ds">
          <SettingsIcon :size="18" class="modal-header-icon-ds" />
          <h2 class="modal-title-ds">Settings</h2>
        </div>
        <button class="win-btn-ds" @click="emit('close')" title="Close">
          <span>✕</span>
        </button>
      </div>

      <div class="modal-body-ds">

        <!-- Theme Info -->
        <section class="modal-section-ds">
          <h3 class="modal-section-title-ds">
            <Palette :size="14" /> Appearance
          </h3>
          <div class="theme-info-box-ds">
            <div class="swatch-ds"></div>
            <div class="theme-desc-ds">
              <span class="theme-name-ds">Latent Design System</span>
              <span class="theme-sub-ds">Unified Dark Theme (Cyan & Violet)</span>
            </div>
            <span class="badge-ds accent">Active</span>
          </div>
        </section>

        <!-- Default Toggles -->
        <section class="modal-section-ds">
          <h3 class="modal-section-title-ds">
            <SlidersHorizontal :size="14" /> Default Toggles
          </h3>
          <div class="toggle-list-ds">
            <div class="toggle-row-ds">
              <div class="toggle-info-ds">
                <span class="toggle-name-ds">Deep Scan</span>
                <span class="toggle-desc-ds">Recursively scan all subfolders</span>
              </div>
              <label class="toggle-control-ds">
                <input type="checkbox" :checked="isRecursive"
                       @change="e => emit('update:isRecursive', e.target.checked)" class="sr-only"/>
                <span class="toggle-track-ds" :class="{ checked: isRecursive }"><span class="toggle-thumb-ds"></span></span>
              </label>
            </div>
            <div class="toggle-row-ds">
              <div class="toggle-info-ds">
                <span class="toggle-name-ds">Dry Run</span>
                <span class="toggle-desc-ds">Simulate operations without moving files</span>
              </div>
              <label class="toggle-control-ds">
                <input type="checkbox" :checked="isDryRun" @change="e => emit('update:isDryRun', e.target.checked)"
                       class="sr-only"/>
                <span class="toggle-track-ds" :class="{ checked: isDryRun }"><span class="toggle-thumb-ds"></span></span>
              </label>
            </div>
          </div>
        </section>

        <!-- Support Section -->
        <section class="modal-section-ds">
          <h3 class="modal-section-title-ds">
            <Heart :size="14" /> Support
          </h3>
          <button class="kofi-btn-ds" @click="openKofi">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
              <path
                  d="M23.881 8.948c-.773-4.085-4.859-4.593-4.859-4.593H.723c-.604 0-.679.798-.679.798s-.082 7.324-.022 11.822c.164 2.424 2.586 2.672 2.586 2.672s8.267-.023 11.966-.049c2.438-.426 2.683-2.566 2.658-3.734 4.352.24 7.422-2.831 6.649-6.916zm-11.062 3.511c-1.246 1.453-4.011 3.976-4.011 3.976s-.121.119-.31.023c-.076-.057-.108-.09-.108-.09-.443-.441-3.368-3.049-4.034-3.954-.709-.965-1.041-2.7-.091-3.71.951-1.01 3.005-1.086 4.363.407 0 0 1.565-1.782 3.468-.963 1.904.82 1.832 3.011.723 4.311zm6.173.478c-.928.116-1.682.028-1.682.028V7.284h1.77s1.971.551 1.971 2.638c0 1.913-.985 2.667-2.059 3.015z"/>
            </svg>
            Support on Ko-fi
          </button>
        </section>

        <section class="modal-section-ds version-section-ds">
          <span class="version-text-ds">Latent Model Organizer v{{ appVersion }}</span>
        </section>

      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-scrim-ds {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: var(--color-surface-overlay);
  backdrop-filter: var(--blur-glass);
}

.modal-box-ds {
  width: 100%;
  max-width: 440px;
  background: var(--color-surface-1);
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-panel);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.modal-header-ds {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--color-border-subtle);
  background: var(--color-surface-2);
}

.modal-title-group-ds {
  display: flex;
  align-items: center;
  gap: 10px;
}

.modal-header-icon-ds {
  color: var(--color-accent-primary);
  font-size: 1.1rem;
}

.modal-title-ds {
  margin: 0;
  font-size: var(--text-h3, 17px);
  font-weight: var(--weight-bold);
  color: var(--color-text-primary);
}

.modal-body-ds {
  padding: 0;
}

.modal-section-ds {
  padding: 16px 20px;
  border-bottom: 1px solid var(--color-border-subtle);
}

.modal-section-title-ds {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 12px;
  font-size: var(--text-caption, 11px);
  font-weight: var(--weight-bold);
  text-transform: uppercase;
  letter-spacing: var(--tracking-caps);
  color: var(--color-text-secondary);
}

.theme-info-box-ds {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  background: var(--color-surface-2);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
}

.swatch-ds {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--gradient-brand);
}

.theme-desc-ds {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.theme-name-ds {
  font-size: var(--text-body-sm, 13px);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
}

.theme-sub-ds {
  font-size: var(--text-caption, 11px);
  color: var(--color-text-tertiary);
}

.toggle-list-ds {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.toggle-row-ds {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.toggle-info-ds {
  display: flex;
  flex-direction: column;
}

.toggle-name-ds {
  font-size: var(--text-body-sm, 13px);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
}

.toggle-desc-ds {
  font-size: var(--text-caption, 11px);
  color: var(--color-text-tertiary);
}

.toggle-control-ds {
  display: flex;
  align-items: center;
  cursor: pointer;
}

.toggle-track-ds {
  position: relative;
  width: 36px;
  height: 20px;
  border-radius: var(--radius-full);
  background: var(--color-surface-2);
  border: 1px solid var(--color-border-default);
  transition: background var(--duration-fast), border-color var(--duration-fast);
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

.kofi-btn-ds {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border-radius: var(--radius-md);
  font-size: var(--text-body-sm, 13px);
  font-weight: var(--weight-bold);
  cursor: pointer;
  background: #FF5E5B;
  color: #fff;
  border: none;
  transition: opacity var(--duration-fast);
}

.kofi-btn-ds:hover {
  opacity: 0.9;
}

.badge-ds.accent {
  background: var(--color-accent-primary-bg);
  color: var(--color-accent-primary);
  border: 1px solid rgba(79, 216, 208, 0.25);
  font-size: var(--text-caption, 11px);
  padding: 2px 8px;
  border-radius: var(--radius-full);
}

.win-btn-ds {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-sm);
  border: 1px solid transparent;
  background: transparent;
  color: var(--color-text-tertiary);
  font-size: 13px;
  cursor: pointer;
}

.win-btn-ds:hover {
  background: var(--color-surface-3);
  color: var(--color-text-primary);
}

.version-section-ds {
  border-bottom: none;
  text-align: center;
}

.version-text-ds {
  font-size: var(--text-caption, 11px);
  color: var(--color-text-tertiary);
  font-family: var(--font-mono);
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}
</style>

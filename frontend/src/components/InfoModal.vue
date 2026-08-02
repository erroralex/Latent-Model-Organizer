<script setup>
/**
 * InfoModal.vue
 *
 * Info dialog component for Latent Model Organizer aligned with the Latent Design System.
 */
import { HelpCircle, Check } from 'lucide-vue-next';

defineProps({
  title: { type: String, required: true },
});
const emit = defineEmits(['close']);
</script>

<template>
  <div class="modal-scrim-ds" @click.self="emit('close')">
    <div class="modal-box-ds" role="dialog" aria-modal="true">

      <div class="modal-header-ds">
        <div class="modal-title-group-ds">
          <HelpCircle :size="18" class="modal-icon-ds" />
          <h2 class="modal-title-ds">{{ title }}</h2>
        </div>
        <button class="win-btn-ds" @click="emit('close')" title="Close">
          <span>✕</span>
        </button>
      </div>

      <div class="modal-body-ds">
        <slot></slot>
      </div>

      <div class="modal-footer-ds">
        <button class="btn-ds secondary" @click="emit('close')">
          <Check :size="16" /> Got it
        </button>
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
  max-width: 480px;
  max-height: 80vh;
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

.modal-icon-ds {
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
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  font-size: var(--text-body-sm, 13px);
  line-height: var(--text-body-lh, 1.55);
  color: var(--color-text-secondary);
}

.modal-footer-ds {
  padding: 12px 20px;
  border-top: 1px solid var(--color-border-subtle);
  background: var(--color-surface-2);
  display: flex;
  justify-content: flex-end;
}

.btn-ds {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 8px 16px;
  border-radius: var(--radius-md);
  font-size: var(--text-body-sm, 13px);
  font-weight: var(--weight-semibold);
  font-family: var(--font-sans);
  cursor: pointer;
  border: 1px solid transparent;
}

.btn-ds.secondary {
  background: var(--color-surface-3);
  color: var(--color-text-primary);
  border-color: var(--color-border-subtle);
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
</style>

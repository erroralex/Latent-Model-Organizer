<script setup>
/**
 * INFOMODAL.VUE
 *
 * A generic, reusable informational dialog component.
 * It provides a standardized layout for help text, tooltips, and system documentation
 * using the application's glassmorphic design language.
 *
 * COMPONENT LAYOUT:
 * - Header: Displays the modal title and a quick close button.
 * - Body: A scrollable content area that accepts arbitrary HTML or components via the default slot.
 * - Footer: Contains a primary action button (e.g., "Got it") to dismiss the dialog.
 * - Interactivity: Supports background-click-to-close and keyboard navigation.
 */
defineProps({
  title: { type: String, required: true },
});
const emit = defineEmits(['close']);
</script>

<template>
  <div class="modal-overlay" @click.self="emit('close')">
    <div class="glass-panel modal-box" role="dialog" aria-modal="true">

      <div class="modal-header">
        <div class="modal-title-group">
          <i class="pi pi-question-circle" style="color: var(--accent-primary)"></i>
          <h2 class="modal-title">{{ title }}</h2>
        </div>
        <button class="nav-btn icon-only" @click="emit('close')" title="Close">
          <i class="pi pi-times"></i>
        </button>
      </div>

      <div class="modal-body custom-scrollbar">
        <slot></slot>
      </div>

      <div class="modal-footer">
        <button class="secondary-btn" @click="emit('close')">
          <i class="pi pi-check"></i> Got it
        </button>
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

.modal-box {
  width: 100%;
  max-width: 480px;
  max-height: 80vh;
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

.modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 24px 20px;
  min-height: 0;
  font-size: 0.95rem;
  line-height: 1.6;
  color: var(--text-secondary);
}

.modal-footer {
  padding: 14px 20px;
  border-top: 1px solid var(--border-input);
  display: flex;
  justify-content: flex-end;
  flex-shrink: 0;
}
</style>

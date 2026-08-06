<script setup>
/**
 * LSwitch
 *
 * Visual treatment from Latent-Library, markup from LMO's existing toggles: a
 * real <input type="checkbox"> visually hidden inside its <label>. Latent-Library's
 * version is a label with a click handler and no input, which is neither
 * keyboard-reachable nor announced as a switch.
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  label: { type: String, default: '' },
  // For call sites whose visible text sits outside the control (Settingsmodal).
  ariaLabel: { type: String, default: '' },
  disabled: { type: Boolean, default: false }
});

const emit = defineEmits(['update:modelValue', 'change']);

const onChange = (event) => {
  if (props.disabled) return;
  const next = event.target.checked;
  emit('update:modelValue', next);
  emit('change', next);
};
</script>

<template>
  <label class="l-switch" :class="{ disabled }">
    <input
        type="checkbox"
        class="l-switch-input"
        :checked="modelValue"
        :disabled="disabled"
        :aria-label="ariaLabel || undefined"
        @change="onChange"
    />
    <span class="l-switch-track" :class="{ active: modelValue }">
      <span class="l-switch-thumb" />
    </span>
    <span v-if="label || $slots.default" class="l-switch-label">
      <slot>{{ label }}</slot>
    </span>
  </label>
</template>

<style scoped>
.l-switch {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  user-select: none;
  font-family: var(--font-sans);
  font-size: 13px;
  color: var(--color-text-primary);
}

/* Visually hidden, still focusable and still announced. */
.l-switch-input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}

.l-switch-track {
  position: relative;
  width: 36px;
  height: 20px;
  border-radius: var(--radius-full);
  background: var(--color-surface-2);
  border: 1px solid var(--color-border-default);
  transition: background var(--duration-base) var(--ease-standard),
              border-color var(--duration-base) var(--ease-standard);
  flex-shrink: 0;
}

.l-switch-track.active {
  background: var(--color-accent-primary);
  border-color: var(--color-accent-primary);
}

/* Focus must be visible when tabbing to the hidden input. */
.l-switch-input:focus-visible + .l-switch-track {
  outline: 2px solid var(--color-border-focus);
  outline-offset: 2px;
}

.l-switch-thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--color-text-secondary);
  transition: transform var(--duration-base) var(--ease-standard),
              background var(--duration-base) var(--ease-standard);
}

.l-switch-track.active .l-switch-thumb {
  transform: translateX(16px);
  background: var(--color-text-on-accent);
}

.l-switch:hover:not(.disabled) .l-switch-track {
  border-color: var(--color-border-strong);
}

.l-switch.disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

@media (prefers-reduced-motion: reduce) {
  .l-switch-track,
  .l-switch-thumb {
    transition: none;
  }
}
</style>

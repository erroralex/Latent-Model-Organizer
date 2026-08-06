<script setup>
/**
 * LBadge
 *
 * Variants follow the design system's _adherence.oxlintrc.json contract.
 * Latent-Library's copy substitutes `secondary` for `outline`; that is drift,
 * and this implementation deliberately does not reproduce it.
 */
defineProps({
  variant: {
    type: String,
    default: 'neutral',
    validator: (v) => ['neutral', 'accent', 'success', 'warning', 'danger', 'outline'].includes(v)
  },
  size: {
    type: String,
    default: 'md',
    validator: (v) => ['sm', 'md'].includes(v)
  }
});
</script>

<template>
  <span class="l-badge" :class="[variant, size]">
    <slot />
  </span>
</template>

<style scoped>
.l-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border-radius: var(--radius-full);
  font-family: var(--font-sans);
  font-weight: var(--weight-semibold);
  letter-spacing: 0.02em;
  white-space: nowrap;
  user-select: none;
  border: 1px solid transparent;
}

/* No 10px token exists in the type scale; left literal. */
.l-badge.sm { padding: 1px 6px; font-size: 10px; }
.l-badge.md { padding: 2px 8px; font-size: var(--text-caption); }

.l-badge.neutral {
  background: var(--color-surface-2);
  color: var(--color-text-secondary);
  border-color: var(--color-border-subtle);
}

.l-badge.accent {
  background: var(--color-accent-primary-bg);
  color: var(--color-accent-primary);
  border-color: var(--color-accent-primary-bg);
}

.l-badge.success {
  background: var(--color-success-bg);
  color: var(--color-success);
  border-color: var(--color-success-bg);
}

.l-badge.warning {
  background: var(--color-warning-bg);
  color: var(--color-warning);
  border-color: var(--color-warning-bg);
}

.l-badge.danger {
  background: var(--color-danger-bg);
  color: var(--color-danger);
  border-color: var(--color-danger-bg);
}

.l-badge.outline {
  background: transparent;
  color: var(--color-text-secondary);
  border-color: var(--color-border-default);
}
</style>

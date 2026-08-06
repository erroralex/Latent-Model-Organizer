<script setup>
/**
 * StatusPill
 *
 * Presentational connection indicator. Deliberately owns no transport: the
 * design-system contract is props `status` and `label` only. Polling lives in
 * useBackendStatus.
 */
import { computed } from 'vue';

const props = defineProps({
  status: {
    type: String,
    default: 'starting',
    validator: (v) => ['online', 'starting', 'offline'].includes(v)
  },
  label: {
    type: String,
    default: 'Backend'
  }
});

const statusText = computed(() => {
  switch (props.status) {
    case 'online': return `${props.label}: Online`;
    case 'offline': return `${props.label}: Offline`;
    default: return `${props.label}: Starting...`;
  }
});
</script>

<template>
  <div class="status-pill" :class="status" role="status" aria-live="polite">
    <span class="status-dot" aria-hidden="true" />
    <span class="status-label">{{ statusText }}</span>
  </div>
</template>

<style scoped>
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: var(--radius-full);
  border: 1px solid var(--color-border-subtle);
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--color-text-secondary);
  user-select: none;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: var(--radius-full);
  flex-shrink: 0;
}

.status-pill.online {
  background: var(--color-success-bg);
}
.status-pill.online .status-dot {
  background: var(--color-success);
  animation: ds-pulse 2s infinite var(--ease-standard);
}

.status-pill.starting {
  background: var(--color-warning-bg);
}
.status-pill.starting .status-dot {
  background: var(--color-warning);
  animation: ds-pulse 1s infinite var(--ease-standard);
}

.status-pill.offline {
  background: var(--color-danger-bg);
}
.status-pill.offline .status-dot {
  background: var(--color-danger);
}

@media (prefers-reduced-motion: reduce) {
  .status-pill .status-dot {
    animation: none;
  }
}
</style>

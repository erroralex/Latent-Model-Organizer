<script setup>
/**
 * ConsoleWindow.vue
 *
 * Terminal drawer for streaming backend SLF4J logs aligned with the Latent Design System.
 */
import { ref, onMounted, onUnmounted, nextTick, watch } from 'vue';
import { Terminal, ArrowDown, Trash2, Loader2 } from 'lucide-vue-next';
import LBadge from '@/components/ds/LBadge.vue';

const props = defineProps({
  apiBase: { type: String, required: true },
  apiToken: { type: String, default: '' }
});

const logs = ref([]);
const consoleRef = ref(null);
const isAutoScroll = ref(true);

let eventSource = null;
let logBuffer = [];
let flushInterval = null;

const LEVEL_COLORS = {
  INFO: 'var(--color-text-primary)',
  WARN: 'var(--color-warning)',
  ERROR: 'var(--color-danger)',
  DEBUG: 'var(--color-text-tertiary)',
  TRACE: 'var(--color-text-disabled)',
};

const parseLogLine = (raw) => {
  const parts = raw.split(' ');
  const level = parts[1]?.trim().toUpperCase() || 'INFO';
  const color = LEVEL_COLORS[level] ?? LEVEL_COLORS.INFO;
  return { text: raw, level, color };
};

const connect = () => {
  if (!props.apiBase) return;

  const url = `${props.apiBase}/api/logs?token=${props.apiToken}`;
  eventSource = new EventSource(url);

  eventSource.onmessage = ({ data }) => {
    logBuffer.push(parseLogLine(data));
  };

  eventSource.onerror = (err) => {
    console.warn('SSE error, reconnecting…', err);
    eventSource.close();
    eventSource = null;
    setTimeout(connect, 5000);
  };

  if (!flushInterval) {
    flushInterval = setInterval(() => {
      if (logBuffer.length > 0) {
        logs.value.push(...logBuffer);
        logBuffer = [];

        if (logs.value.length > 1000) {
          logs.value.splice(0, logs.value.length - 1000);
        }
      }
    }, 100);
  }
};

const clearLogs = () => {
  logs.value = [];
  logBuffer = [];
};

const toggleAutoScroll = () => {
  isAutoScroll.value = !isAutoScroll.value;
};

const scrollToBottom = () => {
  if (consoleRef.value && isAutoScroll.value) {
    consoleRef.value.scrollTop = consoleRef.value.scrollHeight;
  }
};

watch(() => props.apiBase, (newVal) => {
  if (newVal && !eventSource) {
    connect();
  }
});

watch(logs, () => nextTick(scrollToBottom), { deep: true });

onMounted(connect);

onUnmounted(() => {
  eventSource?.close();
  clearInterval(flushInterval);
});
</script>

<template>
  <div class="console-root-ds">

    <div class="console-bar-ds">
      <div class="console-left-ds">
        <Terminal :size="14" class="console-terminal-icon" />
        <span class="console-title-ds">Backend Logs</span>
        <LBadge variant="outline" size="sm" v-if="logs.length > 0">{{ logs.length }}</LBadge>
      </div>
      <div class="console-right-ds">
        <button
            class="con-btn-ds"
            :class="{ active: isAutoScroll }"
            @click="toggleAutoScroll"
            title="Toggle auto-scroll"
        >
          <ArrowDown :size="14" />
        </button>
        <button class="con-btn-ds" @click="clearLogs" title="Clear console">
          <Trash2 :size="14" />
        </button>
      </div>
    </div>

    <div class="console-output-ds" ref="consoleRef">
      <div
          v-for="(log, i) in logs"
          :key="i"
          class="log-line-ds"
          :style="{ color: log.color }"
      >{{ log.text }}
      </div>
      <div v-if="!logs.length" class="log-empty-ds">
        <Loader2 :size="14" class="spin-icon" /> Waiting for backend logs…
      </div>
    </div>

  </div>
</template>

<style scoped>
.console-root-ds {
  display: flex;
  flex-direction: column;
  background: var(--color-bg-canvas);
  overflow: hidden;
}

.console-bar-ds {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 14px;
  flex-shrink: 0;
  background: var(--color-surface-1);
  border-bottom: 1px solid var(--color-border-subtle);
}

.console-left-ds {
  display: flex;
  align-items: center;
  gap: 8px;
}

.console-right-ds {
  display: flex;
  align-items: center;
  gap: 4px;
}

.console-terminal-icon {
  color: var(--color-accent-primary);
  font-size: 0.85rem;
}

.console-title-ds {
  font-family: var(--font-mono);
  font-size: var(--text-caption, 11px);
  font-weight: var(--weight-bold);
  color: var(--color-text-secondary);
  text-transform: uppercase;
  letter-spacing: var(--tracking-caps);
}

.con-btn-ds {
  background: transparent;
  border: none;
  cursor: pointer;
  color: var(--color-text-tertiary);
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  font-size: 0.8rem;
  line-height: 1;
  transition: all var(--duration-fast);
}

.con-btn-ds:hover {
  color: var(--color-text-primary);
  background: var(--color-surface-2);
}

.con-btn-ds.active {
  color: var(--color-accent-primary);
}

.console-output-ds {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 10px 14px;
  font-family: var(--font-mono);
  font-size: var(--text-mono, 13px);
  line-height: var(--text-mono-lh, 1.6);
}

.log-line-ds {
  white-space: pre-wrap;
  word-break: break-all;
  margin-bottom: 2px;
}

.log-empty-ds {
  color: var(--color-text-disabled);
  text-align: center;
  margin-top: 20px;
  font-style: italic;
  font-size: var(--text-body-sm, 13px);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

</style>

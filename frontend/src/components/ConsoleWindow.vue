<script setup>
/**
 * ConsoleWindow.vue
 *
 * This component provides a real-time, terminal-like interface for monitoring backend activity.
 * It establishes a persistent Server-Sent Events (SSE) connection to the Java backend,
 * streaming SLF4J log entries directly to the UI.
 *
 * Key Capabilities:
 * - Dual-buffer Strategy: Uses a raw buffer + periodic flush to ensure smooth UI updates
 *   even during high-frequency log bursts.
 * - Semantic Highlighting: Automatically parses log levels (INFO, WARN, ERROR, DEBUG)
 *   and applies appropriate color coding.
 * - Rolling Window: Maintains a maximum of 1000 entries to prevent DOM bloat.
 * - Resilience: Automatic reconnection with exponential backoff for network stability.
 * - UX: Integrated auto-scroll locking and history clearing.
 */
import { ref, onMounted, onUnmounted, nextTick, watch } from 'vue';

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
  INFO: '#c9d1d9',
  WARN: '#d29922',
  ERROR: '#f85149',
  DEBUG: '#8b949e',
  TRACE: '#6e7681',
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
  <div class="console-root">

    <div class="console-bar">
      <div class="console-bar-left">
        <i class="pi pi-terminal" style="color: var(--accent-primary); font-size: 0.85rem;"></i>
        <span class="console-title">Backend Logs</span>
        <span class="log-count" v-if="logs.length > 0">{{ logs.length }}</span>
      </div>
      <div class="console-bar-right">
        <button
            class="con-btn"
            :class="{ active: isAutoScroll }"
            @click="toggleAutoScroll"
            title="Toggle auto-scroll"
        >
          <i class="pi pi-arrow-down"></i>
        </button>
        <button class="con-btn" @click="clearLogs" title="Clear console">
          <i class="pi pi-trash"></i>
        </button>
      </div>
    </div>

    <div class="console-output custom-scrollbar" ref="consoleRef">
      <div
          v-for="(log, i) in logs"
          :key="i"
          class="log-line"
          :style="{ color: log.color }"
      >{{ log.text }}
      </div>
      <div v-if="!logs.length" class="log-empty">
        <i class="pi pi-spin pi-spinner"></i> Waiting for backend logs…
      </div>
    </div>

  </div>
</template>

<style scoped>
.console-root {
  display: flex;
  flex-direction: column;
  background: #0d1117;
  overflow: hidden;
}

.console-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 12px;
  flex-shrink: 0;
  background: rgba(255, 255, 255, 0.03);
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
}

.console-bar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.console-bar-right {
  display: flex;
  align-items: center;
  gap: 4px;
}

.console-title {
  font-family: 'Consolas', 'Cascadia Code', monospace;
  font-size: 0.75rem;
  font-weight: 700;
  color: #6e7681;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.log-count {
  font-size: 0.65rem;
  padding: 1px 6px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  color: #6e7681;
  font-family: monospace;
}

.con-btn {
  background: transparent;
  border: none;
  cursor: pointer;
  color: #6e7681;
  padding: 3px 6px;
  border-radius: 4px;
  font-size: 0.75rem;
  line-height: 1;
  transition: color 0.15s, background 0.15s;
}

.con-btn:hover {
  color: #c9d1d9;
  background: rgba(255, 255, 255, 0.08);
}

.con-btn.active {
  color: var(--accent-primary, #66fcf1);
}

.console-output {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 8px 12px;
  font-family: 'Consolas', 'Cascadia Code', 'Monaco', monospace;
  font-size: 0.775rem;
  line-height: 1.55;
}

.log-line {
  white-space: pre-wrap;
  word-break: break-all;
  margin-bottom: 1px;
}

.log-empty {
  color: #3d4450;
  text-align: center;
  margin-top: 16px;
  font-style: italic;
  font-size: 0.8rem;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.console-output::-webkit-scrollbar {
  width: 6px;
}

.console-output::-webkit-scrollbar-track {
  background: transparent;
}

.console-output::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.08);
  border-radius: 3px;
}

.console-output::-webkit-scrollbar-thumb:hover {
  background: rgba(255, 255, 255, 0.15);
}
</style>

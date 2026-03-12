/**
 * A real-time log console component.
 *
 * This component connects to the backend's Server-Sent Events (SSE) endpoint (/api/logs)
 * to stream application logs directly to the frontend. It features a dark-themed,
 * terminal-like interface with level-based color coding and auto-scrolling.
 */
<script setup>
import { ref, onMounted, onUnmounted, nextTick, watch } from 'vue';

const logs = ref([]);
const consoleRef = ref(null);
let eventSource = null;
const isAutoScroll = ref(true);

const connectToLogs = () => {
  const apiUrl = window.location.hostname === 'localhost' ? '/api/logs' : 'http://localhost:8080/api/logs';

  eventSource = new EventSource(apiUrl);

  eventSource.onmessage = (event) => {
    const rawMessage = event.data;
    const parsedLog = parseLogLine(rawMessage);

    logs.value.push(parsedLog);

    if (logs.value.length > 1000) {
      logs.value.shift();
    }
  };

  eventSource.onerror = (err) => {
    console.error("SSE Connection Error:", err);
    eventSource.close();
    setTimeout(connectToLogs, 5000);
  };
};

const parseLogLine = (rawLine) => {
  // Extract level from line format: "HH:mm:ss LEVEL Logger - Message"
  const parts = rawLine.split(' ');
  const level = parts[1] || 'INFO';

  let type = 'info';
  if (level === 'WARN') type = 'warn';
  else if (level === 'ERROR') type = 'error';
  else if (level === 'DEBUG') type = 'debug';

  return { text: rawLine, type };
};

const scrollToBottom = () => {
  if (consoleRef.value && isAutoScroll.value) {
    consoleRef.value.scrollTop = consoleRef.value.scrollHeight;
  }
};

watch(logs, () => {
  nextTick(scrollToBottom);
}, { deep: true });

onMounted(() => {
  connectToLogs();
});

onUnmounted(() => {
  if (eventSource) {
    eventSource.close();
  }
});

const clearLogs = () => {
    logs.value = [];
};

const toggleAutoScroll = () => {
    isAutoScroll.value = !isAutoScroll.value;
};
</script>

<template>
  <div class="glass-panel console-wrapper">
    <div class="console-header">
      <span class="console-title">Backend Logs</span>
      <div class="console-actions">
        <button
            class="icon-btn"
            @click="toggleAutoScroll"
            :class="{ active: isAutoScroll }"
            title="Toggle Auto-Scroll"
        >
            ⬇
        </button>
        <button
            class="icon-btn"
            @click="clearLogs"
            title="Clear Logs"
        >
            ⊘
        </button>
      </div>
    </div>

    <div class="console-output custom-scrollbar" ref="consoleRef">
      <div
        v-for="(log, index) in logs"
        :key="index"
        class="log-line"
        :class="log.type"
      >
        {{ log.text }}
      </div>
      <div v-if="logs.length === 0" class="log-placeholder">
        Waiting for logs...
      </div>
    </div>
  </div>
</template>

<style scoped>
/* Color Coding for Log Levels */
.log-line.info { color: #c9d1d9; }
.log-line.warn { color: #d29922; }
.log-line.error { color: #f85149; font-weight: bold; }
.log-line.debug { color: #8b949e; }
</style>

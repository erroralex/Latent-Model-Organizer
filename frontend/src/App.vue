<script setup>
/**
 * App.vue
 *
 * Primary layout shell for Latent Model Organizer aligned with the Latent Design System.
 */
import { ref, computed, onMounted, watch } from 'vue';
import { useTheme } from './composables/useTheme';
import { useUiZoom } from './composables/useUiZoom';
import ConsoleWindow from './components/ConsoleWindow.vue';
import SummaryModal from './components/Summarymodal.vue';
import SettingsModal from './components/Settingsmodal.vue';
import Sidebar from './components/Sidebar.vue';
import SorterView from './views/SorterView.vue';
import FetcherView from './views/FetcherView.vue';
import latentMarkUrl from './assets/latent-mark.svg';
import { Loader2, CheckCircle2, AlertTriangle, XCircle, Info } from 'lucide-vue-next';

const { currentTheme, availableThemes, applyTheme } = useTheme();
useUiZoom();

const lsGet = (k, fb) => {
  try {
    const v = localStorage.getItem(k);
    return v !== null ? JSON.parse(v) : fb;
  } catch {
    return fb;
  }
};

const lsSet = (k, v) => {
  try {
    localStorage.setItem(k, JSON.stringify(v));
  } catch { }
};

const activeTab = ref(lsGet('lmo:activeTab', 'sort'));
const isRecursive = ref(lsGet('lmo:isRecursive', true));
const isDryRun = ref(lsGet('lmo:isDryRun', false));
const consoleOpen = ref(true);
const showSettings = ref(false);

watch(activeTab,   v => lsSet('lmo:activeTab',   v));
watch(isRecursive, v => lsSet('lmo:isRecursive',  v));
watch(isDryRun,    v => lsSet('lmo:isDryRun',     v));

const statusMessage     = ref('Ready.');
const isProcessing      = ref(false);
const isCancelling      = ref(false);
const showReportModal   = ref(false);
const operationReport   = ref(null);
const canUndo           = ref(false);
const lastTargetDirectory = ref(lsGet('lmo:lastTargetDir', ''));
const isBackendReady    = ref(false);

const apiBase = ref('http://localhost:8080');
const apiToken = ref('');

watch(isProcessing, (newValue) => {
  if (!newValue) {
    isCancelling.value = false;
  }
});

const callApi = async (endpoint, body, msg) => {
  isProcessing.value = true;
  statusMessage.value = msg;
  try {
    const res = await fetch(`${apiBase.value}${endpoint}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(apiToken.value ? { 'Authorization': `Bearer ${apiToken.value}` } : {})
      },
      body: JSON.stringify(body),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.message || `Server error ${res.status}`);
    statusMessage.value = '✅ ' + (data.message || 'Done!');
    operationReport.value = data;
    showReportModal.value = true;
    return data;
  } catch (err) {
    statusMessage.value = '❌ ' + err.message;
  } finally {
    isProcessing.value = false;
  }
};

const cancelOperation = async () => {
  isCancelling.value = true;
  statusMessage.value = '⏳ Sending cancellation signal...';
  try {
    const res = await fetch(`${apiBase.value}/api/cancel`, {
      method: 'POST',
      headers: {
        ...(apiToken.value ? { 'Authorization': `Bearer ${apiToken.value}` } : {})
      },
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.message || 'Failed to send cancel signal.');
    statusMessage.value = '⚠️ Cancellation requested. Waiting for task to terminate.';
  } catch (err) {
    statusMessage.value = `❌ ${err.message}`;
    isCancelling.value = false;
  }
};

const handleStartOrganize = async ({ sourceDirectory, targetDirectory, allowedArchitectures }) => {
  if (!sourceDirectory || !targetDirectory) {
    statusMessage.value = '⚠️ Please select both source and target directories.';
    return;
  }
  if (!allowedArchitectures.length) {
    statusMessage.value = '⚠️ Please select at least one architecture.';
    return;
  }
  canUndo.value = false;
  const data = await callApi(
      '/api/organize',
      { sourceDirectory, targetDirectory, allowedArchitectures, isRecursive: isRecursive.value, isDryRun: isDryRun.value },
      '⏳ Scanning and organising models...'
  );
  if (data && !isDryRun.value && data.totalProcessed > 0) {
    canUndo.value = true;
    lastTargetDirectory.value = targetDirectory;
    lsSet('lmo:lastTargetDir', targetDirectory);
  }
};

const handleUndo = async () => {
  const data = await callApi(
      '/api/undo',
      { targetDirectory: lastTargetDirectory.value },
      '⏳ Undoing last sort...'
  );
  if (data) {
    canUndo.value = false;
  }
};

const handleStartFetch = ({ targetDirectory }) => {
  if (!targetDirectory) {
    statusMessage.value = '⚠️ Please select a folder to scan.';
    return;
  }
  callApi(
      '/api/fetch',
      { targetDirectory, isRecursive: isRecursive.value, isDryRun: isDryRun.value },
      '⏳ Scanning for missing metadata...'
  );
};

const handleStartBackfill = ({ targetDirectory }) => {
  if (!targetDirectory) {
    statusMessage.value = '⚠️ Please select a folder to scan.';
    return;
  }
  callApi(
      '/api/backfill-metadata',
      { targetDirectory, isRecursive: isRecursive.value, isDryRun: isDryRun.value },
      '⏳ Writing trigger words and descriptions from existing sidecars...'
  );
};

const minimizeWindow = () => window.windowAPI?.minimize();
const maximizeWindow = () => window.windowAPI?.maximize();
const closeWindow    = () => window.windowAPI?.close();

const initializeBackendConnection = async () => {
  try {
    if (window.electronAPI && window.electronAPI.getBackendPort) {
      const backend = await window.electronAPI.getBackendPort();
      if (backend && backend.port) {
        apiBase.value = `http://127.0.0.1:${backend.port}`;
        apiToken.value = backend.token;

        if (backend.port === 8080 && backend.token === null) {
          statusMessage.value = "❌ FATAL: Java engine failed to start.";
        } else {
          console.log(`[LMO] Bound to backend at ${apiBase.value}`);
        }
      }
    }
  } catch (e) {
    console.error('[LMO] Failed to retrieve backend port via IPC:', e);
    statusMessage.value = '❌ Failed to connect to backend.';
  } finally {
    isBackendReady.value = true;
  }
};

onMounted(async () => {
  applyTheme();
  await initializeBackendConnection();
});

const statusClass = computed(() => {
  if (statusMessage.value.startsWith('❌')) return 'status-error';
  if (statusMessage.value.startsWith('⚠️')) return 'status-warn';
  if (statusMessage.value.startsWith('✅')) return 'status-ok';
  return '';
});

const closeReport = () => {
  showReportModal.value = false;
  operationReport.value = null;
};

const statusIcon = computed(() => {
  if (isProcessing.value) return Loader2;
  if (statusMessage.value.startsWith('✅')) return CheckCircle2;
  if (statusMessage.value.startsWith('⚠️')) return AlertTriangle;
  if (statusMessage.value.startsWith('❌')) return XCircle;
  return Info;
});
</script>

<template>
  <div class="layout-wrapper-ds">
    <!-- Titlebar (52px frameless) -->
    <header class="titlebar-ds">
      <div class="brand-group-ds no-drag">
        <img :src="latentMarkUrl" alt="Latent" class="brand-mark-img" />
        <span class="app-title-ds">Latent Model Organizer</span>
      </div>

      <div class="titlebar-drag-region"></div>

      <div class="window-controls-ds no-drag">
        <button class="win-btn-ds" @click="minimizeWindow" title="Minimize">
          <span>–</span>
        </button>
        <button class="win-btn-ds" @click="maximizeWindow" title="Maximize">
          <span>▢</span>
        </button>
        <button class="win-btn-ds danger" @click="closeWindow" title="Close">
          <span>✕</span>
        </button>
      </div>
    </header>

    <div class="app-body-ds">
      <Sidebar
          :activeTab="activeTab"
          :showSettings="showSettings"
          :consoleOpen="consoleOpen"
          :isProcessing="isProcessing"
          @update:activeTab="v => activeTab = v"
          @update:showSettings="v => showSettings = v"
          @update:consoleOpen="v => consoleOpen = v"
      />

      <main class="app-main-ds">
        <div class="content-scroll-ds">
          <div class="content-container-ds">
            <div class="card-ds main-card-ds">
              <div v-if="isBackendReady">
                <SorterView
                    v-if="activeTab === 'sort'"
                    :isProcessing="isProcessing"
                    :isCancelling="isCancelling"
                    :isRecursive="isRecursive"
                    :isDryRun="isDryRun"
                    :canUndo="canUndo"
                    :apiBase="apiBase"
                    :apiToken="apiToken"
                    @update:isRecursive="v => isRecursive = v"
                    @update:isDryRun="v => isDryRun = v"
                    @start-organize="handleStartOrganize"
                    @cancel-operation="cancelOperation"
                    @undo="handleUndo"
                />

                <FetcherView
                    v-else
                    :isProcessing="isProcessing"
                    :isCancelling="isCancelling"
                    :isRecursive="isRecursive"
                    :isDryRun="isDryRun"
                    @update:isRecursive="v => isRecursive = v"
                    @update:isDryRun="v => isDryRun = v"
                    @start-fetch="handleStartFetch"
                    @start-backfill="handleStartBackfill"
                    @cancel-operation="cancelOperation"
                />
              </div>
              <div v-else class="backend-connecting-ds">
                <Loader2 :size="24" class="spin-icon connecting-spin-icon" />
                <span>Connecting to Engine...</span>
              </div>
            </div>

            <!-- Status Pill Bar -->
            <div class="status-bar-ds" :class="statusClass">
              <component
                  :is="statusIcon"
                  :size="16"
                  :class="{ 'spin-icon': isProcessing }"
              />
              <span>{{ statusMessage }}</span>
            </div>
          </div>
        </div>

        <transition name="drawer">
          <ConsoleWindow v-if="consoleOpen" :apiBase="apiBase" :apiToken="apiToken" class="console-drawer-ds"/>
        </transition>
      </main>
    </div>

    <SummaryModal
        v-if="showReportModal && operationReport"
        :report="operationReport"
        :isDryRun="isDryRun"
        @close="closeReport"
    />

    <SettingsModal
        v-if="showSettings"
        :currentTheme="currentTheme"
        :availableThemes="availableThemes"
        :isRecursive="isRecursive"
        :isDryRun="isDryRun"
        :apiBase="apiBase"
        :apiToken="apiToken"
        @applyTheme="applyTheme"
        @update:isRecursive="v => isRecursive = v"
        @update:isDryRun="v => isDryRun = v"
        @close="showSettings = false"
    />
  </div>
</template>

<style>
/* App Layout Scoped / Global Rules for Latent Design System */
body {
  margin: 0;
  background: var(--color-bg-canvas, #0A0A0D);
  color: var(--color-text-primary, #F2F3F7);
  font-family: var(--font-sans);
  height: 100vh;
  overflow: hidden;
}

.layout-wrapper-ds {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}

/* Titlebar */
.titlebar-ds {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 52px;
  padding: 0 16px;
  background: rgba(14, 15, 19, 0.8);
  backdrop-filter: var(--blur-glass);
  border-bottom: 1px solid var(--color-border-subtle);
  user-select: none;
  -webkit-app-region: drag;
  z-index: 1000;
}

.brand-group-ds {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brand-mark-img {
  width: 24px;
  height: 24px;
}

.app-title-ds {
  font-size: var(--text-body-lg, 16px);
  font-weight: var(--weight-bold, 700);
  background: var(--gradient-brand-text);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  letter-spacing: var(--tracking-tight);
}

.titlebar-drag-region {
  flex: 1;
  height: 100%;
}

.window-controls-ds {
  display: flex;
  align-items: center;
  gap: 4px;
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
  transition: all var(--duration-fast) var(--ease-standard);
}

.win-btn-ds:hover {
  background: var(--color-surface-1);
  color: var(--color-text-primary);
  border-color: var(--color-border-subtle);
}

.win-btn-ds.danger:hover {
  background: var(--color-danger-bg);
  color: var(--color-danger);
  border-color: rgba(242, 102, 91, 0.3);
}

.app-body-ds {
  flex: 1;
  display: flex;
  overflow: hidden;
  min-height: 0;
}

.app-main-ds {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-height: 0;
  position: relative;
}

.content-scroll-ds {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}

.content-container-ds {
  max-width: 820px;
  margin: 0 auto;
  padding: 28px;
}

.card-ds {
  background: var(--color-surface-1);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.main-card-ds {
  padding: 28px;
  margin-bottom: 16px;
}

.backend-connecting-ds {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 360px;
  font-size: var(--text-h3, 17px);
  color: var(--color-text-secondary);
}

.connecting-spin-icon {
  font-size: 1.5rem;
  color: var(--color-accent-primary);
}

/* Status Bar */
.status-bar-ds {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  border-radius: var(--radius-md);
  background: var(--color-surface-1);
  border: 1px solid var(--color-border-subtle);
  font-size: var(--text-body-sm, 13px);
  color: var(--color-text-secondary);
  font-weight: var(--weight-medium);
}

.status-bar-ds.status-ok {
  color: var(--color-success);
  border-color: rgba(61, 214, 140, 0.2);
  background: var(--color-success-bg);
}

.status-bar-ds.status-warn {
  color: var(--color-warning);
  border-color: rgba(245, 184, 78, 0.2);
  background: var(--color-warning-bg);
}

.status-bar-ds.status-error {
  color: var(--color-danger);
  border-color: rgba(242, 102, 91, 0.2);
  background: var(--color-danger-bg);
}

.console-drawer-ds {
  flex-shrink: 0;
  height: 220px;
  border-top: 1px solid var(--color-border-subtle);
}

.no-drag {
  -webkit-app-region: no-drag;
}
</style>

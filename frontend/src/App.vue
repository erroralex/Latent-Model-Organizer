<script setup>
/**
 * App.vue
 *
 * The primary layout orchestration component for the Latent Model Organizer.
 * It manages global application state, including navigation, configuration,
 * and system-wide notifications.
 *
 * Key Capabilities:
 * - Dynamic View Switching: Manages navigation between Sorter and Fetcher modules.
 * - API Orchestration: Centralizes HTTP communication and handles ephemeral port discovery
 *   via Electron IPC for backend connectivity.
 * - State Persistence: Synchronizes user preferences and directory paths with LocalStorage.
 * - Integrated UI Shell: Controls the custom frame-less title bar, sidebar, and log console.
 * - Progress Tracking: Coordinates with child components to provide real-time operation status.
 */
import { ref, computed, onMounted, watch } from 'vue';
import { useTheme } from './composables/useTheme';
import ConsoleWindow from './components/ConsoleWindow.vue';
import SummaryModal from './components/Summarymodal.vue';
import SettingsModal from './components/Settingsmodal.vue';
import Sidebar from './components/Sidebar.vue';
import SorterView from './views/SorterView.vue';
import FetcherView from './views/FetcherView.vue';

const { currentTheme, availableThemes, applyTheme } = useTheme();

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
const showReportModal   = ref(false);
const operationReport   = ref(null);
const canUndo           = ref(false);
const lastTargetDirectory = ref(lsGet('lmo:lastTargetDir', ''));

const apiBase = ref('');
const apiToken = ref('');

const callApi = async (endpoint, body, msg) => {
  isProcessing.value = true;
  statusMessage.value = msg;
  try {
    const res = await fetch(`${apiBase.value}${endpoint}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${apiToken.value}`
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

const minimizeWindow = () => window.windowAPI?.minimize();
const maximizeWindow = () => window.windowAPI?.maximize();
const closeWindow    = () => window.windowAPI?.close();

onMounted(async () => {
  applyTheme(currentTheme.value);

  if (window.electronAPI?.getBackendPort) {
    try {
      const result = await window.electronAPI.getBackendPort();
      if (result && result.port) {
        apiBase.value = `http://127.0.0.1:${result.port}`;
        apiToken.value = result.token || '';
        console.log(`[App] Backend API base resolved to: ${apiBase.value}`);
      }
    } catch (err) {
      console.warn('[App] Could not retrieve backend port from main process:', err);
    }
  }
});

const statusClass = computed(() => {
  if (statusMessage.value.startsWith('❌')) return 'is-error';
  if (statusMessage.value.startsWith('⚠️')) return 'is-warn';
  if (statusMessage.value.startsWith('✅')) return 'is-ok';
  return '';
});

const closeReport = () => {
  showReportModal.value = false;
  operationReport.value = null;
};
</script>

<template>
  <div class="layout-wrapper">
    <header class="menubar-glass draggable-header">
      <div class="hdr-logo">
        <svg width="26" height="26" viewBox="0 0 24 24" fill="none" class="logo-icon no-drag">
          <path d="M12 2L2 7l10 5 10-5-10-5z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"
                stroke-linejoin="round"/>
          <path d="M2 17l10 5 10-5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"
                stroke-linejoin="round"/>
          <path d="M2 12l10 5 10-5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"
                stroke-linejoin="round"/>
        </svg>
        <span class="app-name text-gradient">Latent Model Organizer</span>
      </div>

      <div class="hdr-drag-region"></div>

      <div class="hdr-controls">
        <button class="nav-btn icon-only no-drag" @click="minimizeWindow" title="Minimize">
          <i class="pi pi-minus"></i>
        </button>
        <button class="nav-btn icon-only no-drag" @click="maximizeWindow" title="Maximize">
          <i class="pi pi-window-maximize"></i>
        </button>
        <button class="nav-btn icon-only window-close-btn no-drag" @click="closeWindow" title="Close">
          <i class="pi pi-times"></i>
        </button>
      </div>
    </header>

    <div class="app-body">
      <Sidebar
          :activeTab="activeTab"
          :showSettings="showSettings"
          :consoleOpen="consoleOpen"
          :isProcessing="isProcessing"
          :currentTheme="currentTheme"
          @update:activeTab="v => activeTab = v"
          @update:showSettings="v => showSettings = v"
          @update:consoleOpen="v => consoleOpen = v"
      />

      <main class="app-main">
        <div class="content-scroll">
          <div class="content-container">
            <div class="glass-panel main-card">
              <SorterView
                  v-if="activeTab === 'sort'"
                  :isProcessing="isProcessing"
                  :isRecursive="isRecursive"
                  :isDryRun="isDryRun"
                  :canUndo="canUndo"
                  :apiBase="apiBase"
                  :apiToken="apiToken"
                  @update:isRecursive="v => isRecursive = v"
                  @update:isDryRun="v => isDryRun = v"
                  @start-organize="handleStartOrganize"
                  @undo="handleUndo"
              />

              <FetcherView
                  v-else
                  :isProcessing="isProcessing"
                  :isRecursive="isRecursive"
                  :isDryRun="isDryRun"
                  @update:isRecursive="v => isRecursive = v"
                  @update:isDryRun="v => isDryRun = v"
                  @start-fetch="handleStartFetch"
              />
            </div>

            <div class="status-bar glass-panel" :class="statusClass">
              <i class="pi"
                 :class="{
                  'pi-check-circle':         statusMessage.startsWith('✅'),
                  'pi-exclamation-triangle': statusMessage.startsWith('⚠️'),
                  'pi-times-circle':         statusMessage.startsWith('❌'),
                  'pi-info-circle':          statusMessage === 'Ready.',
                  'pi-spin pi-spinner':      isProcessing,
                }"
              ></i>
              {{ statusMessage }}
            </div>
          </div>
        </div>

        <transition name="drawer">
          <ConsoleWindow v-if="consoleOpen" :apiBase="apiBase" :apiToken="apiToken" class="console-drawer"/>
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
        @applyTheme="applyTheme"
        @update:isRecursive="v => isRecursive = v"
        @update:isDryRun="v => isDryRun = v"
        @close="showSettings = false"
    />
  </div>
</template>

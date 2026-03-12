/**
 * The root component of the Latent Model Organizer frontend.
 *
 * This component serves as the primary user interface, providing a centralized
 * dashboard for orchestrating model organization tasks. It integrates theme management,
 * directory selection via Electron IPC, and handles the communication with the
 * backend REST API.
 *
 * Key functionalities include:
 * - Directory Orchestration: Managing source and target path selection via Electron dialogs.
 * - Architecture Filtering: Providing a UI for selecting specific model architectures to process.
 * - Process Monitoring: Communicating task status and handling API responses.
 * - Window Management: Implementing custom title bar controls (minimize, maximize, close)
 *   compatible with Electron's frameless window architecture.
 */
<script setup>
import { ref } from 'vue';
import { useTheme } from './composables/useTheme';

const { currentTheme, availableThemes, applyTheme } = useTheme();

const sourceFolder = ref('');
const targetFolder = ref('');
const statusMessage = ref('Ready.');
const isProcessing = ref(false);

const knownArchitectures = ['SD 1.5', 'SD 1.4', 'SDXL', 'Pony', 'Flux', 'SD 3.5', 'Illustrious', 'Sana', 'Noob V', 'Unknown'];
const selectedArchitectures = ref([...knownArchitectures]);

const minimizeWindow = () => {
  window.windowAPI?.minimize();
};

const maximizeWindow = () => {
  window.windowAPI?.maximize();
};

const closeWindow = () => {
  window.windowAPI?.close();
};

const pickSource = async () => {
  const folder = await window.electronAPI?.selectFolder();
  if (folder) sourceFolder.value = folder;
};

const pickTarget = async () => {
  const folder = await window.electronAPI?.selectFolder();
  if (folder) targetFolder.value = folder;
};

const executeOrganization = async () => {
  if (!sourceFolder.value || !targetFolder.value) {
    statusMessage.value = "⚠️ Please select both source and target directories.";
    return;
  }

  if (selectedArchitectures.value.length === 0) {
    statusMessage.value = "⚠️ Please select at least one architecture.";
    return;
  }

  isProcessing.value = true;
  statusMessage.value = "⏳ Scanning and organizing models... Please wait.";

  const apiUrl = window.location.hostname === 'localhost' ? '/api/organize' : 'http://localhost:8080/api/organize';

  try {
    const response = await fetch(apiUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sourceDirectory: sourceFolder.value,
        targetDirectory: targetFolder.value,
        allowedArchitectures: selectedArchitectures.value
      })
    });

    if (!response.ok) {
      throw new Error(`Server responded with status: ${response.status}`);
    }

    const data = await response.json();
    statusMessage.value = "✅ " + (data.message || "Organization Complete!");
  } catch (error) {
    console.error("Organization failed:", error);
    statusMessage.value = "❌ Error: Could not connect to the backend.";
  } finally {
    isProcessing.value = false;
  }
};
</script>

<template>
  <div class="app-wrapper">
    <header class="app-header draggable-header">
      <div class="header-left no-drag">
        <h1 class="text-gradient">Latent Model Organizer</h1>
      </div>

      <div class="header-right no-drag">
        <div class="theme-switcher">
          <label for="theme-select">Theme:</label>
          <select
              id="theme-select"
              :value="currentTheme"
              @change="(e) => applyTheme(e.target.value)"
          >
            <option v-for="theme in availableThemes" :key="theme" :value="theme">
              {{ theme.charAt(0).toUpperCase() + theme.slice(1) }}
            </option>
          </select>
        </div>

        <div class="window-controls">
          <button class="win-btn" @click="minimizeWindow" title="Minimize">─</button>
          <button class="win-btn" @click="maximizeWindow" title="Maximize">□</button>
          <button class="win-btn close" @click="closeWindow" title="Close">✕</button>
        </div>
      </div>
    </header>

    <main class="container">
      <div class="glass-panel card">
        <div class="form-group">
          <label>Source Directory (Unorganized Models):</label>
          <div class="input-row">
            <input type="text" class="glass-input" readonly :value="sourceFolder" placeholder="No folder selected..." />
            <button class="secondary-btn" @click="pickSource" :disabled="isProcessing">Browse</button>
          </div>
        </div>

        <div class="form-group">
          <label>Target Directory (Organized Output):</label>
          <div class="input-row">
            <input type="text" class="glass-input" readonly :value="targetFolder" placeholder="No folder selected..." />
            <button class="secondary-btn" @click="pickTarget" :disabled="isProcessing">Browse</button>
          </div>
        </div>

        <div class="form-group">
          <label>Architectures to Organize:</label>
          <div class="checkbox-grid">
            <label v-for="arch in knownArchitectures" :key="arch" class="checkbox-label">
              <input
                  type="checkbox"
                  :value="arch"
                  v-model="selectedArchitectures"
                  :disabled="isProcessing"
              >
              <span>{{ arch }}</span>
            </label>
          </div>
        </div>

        <button class="primary-btn" @click="executeOrganization" :disabled="isProcessing">
          <span v-if="isProcessing">Organizing...</span>
          <span v-else>Start Organization</span>
        </button>
      </div>

      <div class="status-bar glass-panel" :class="{ 'error': statusMessage.includes('❌') }">
        {{ statusMessage }}
      </div>
    </main>
  </div>
</template>

<style scoped>
/* Scoped styles are now minimal, relying on global component CSS */
h1 {
  margin: 0;
  font-size: 1.5rem;
}
</style>

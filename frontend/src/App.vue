/**
 * The root component of the Latent Model Organizer frontend.
 *
 * This component serves as the primary user interface, providing a centralized
 * dashboard for orchestrating model organization tasks. It integrates theme management,
 * directory selection via Electron IPC, and handles the communication with the
 * backend REST API.
 */
<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useTheme } from './composables/useTheme';
import ConsoleWindow from './components/ConsoleWindow.vue';

const { currentTheme, availableThemes, applyTheme } = useTheme();

// --- Application State ---
const activeTab        = ref('sort');
const sourceFolder     = ref('');
const targetFolder     = ref('');
const statusMessage    = ref('Ready.');
const isProcessing     = ref(false);

// --- Architecture Data ---
const knownArchitectures = [
  // Flux family
  'Flux .1 S', 'Flux .1 D', 'Flux .1 Krea', 'Flux .1 Kontext',
  'Flux .2 D',
  'Flux .2 Klein 9B', 'Flux .2 Klein 9B-base',
  'Flux .2 Klein 4B', 'Flux .2 Klein 4B-base',
  // Stable Diffusion
  'SD 1.4', 'SD 1.5', 'SD 1.5 LCM', 'SD 1.5 Hyper',
  'SD 2.0', 'SD 2.1',
  'SD 3.5',
  // XL family
  'SDXL 1.0', 'SDXL Lightning', 'SDXL Hyper', 'SDXL Turbo',
  // Pony family
  'Pony', 'Pony V7',
  // Illustrious / NoobAI
  'Illustrious', 'NoobAI',
  // Video — Wan
  'Wan Video 1.3B t2v',
  'Wan Video 14B t2v', 'Wan Video 14B i2v 480p', 'Wan Video 14B i2v 720p',
  'Wan Video 2.2 TI2V-5B', 'Wan Video 2.2 T2V-A14B', 'Wan Video 2.2 I2V-A14B',
  'Wan Video 2.5 T2V', 'Wan Video 2.5 I2V',
  // Other video
  'LTXV', 'LTXV2',
  'Mochi', 'CogVideoX',
  'Hunyuan 1', 'Hunyuan Video',
  // Other image
  'Sana', 'HiDream',
  'PixArt α', 'PixArt Σ',
  'Aura Flow', 'Lumina', 'Kolors',
  'Chroma', 'Anima', 'Qwen',
  // Z Image
  'Z Image Turbo', 'Z Image Base',
  // Catch-all
  'Uncategorized', 'Unknown',
];

const selectedArchitectures = ref([...knownArchitectures]);

// --- Architecture Dropdown State ---
const dropdownOpen   = ref(false);
const archSearch     = ref('');
const dropdownRef    = ref(null);

const filteredArchitectures = computed(() => {
  const q = archSearch.value.trim().toLowerCase();
  return q ? knownArchitectures.filter(a => a.toLowerCase().includes(q)) : knownArchitectures;
});

const selectionSummary = computed(() => {
  const n = selectedArchitectures.value.length;
  const total = knownArchitectures.length;
  if (n === 0)     return 'None selected';
  if (n === total) return 'All architectures';
  if (n === 1)     return selectedArchitectures.value[0];
  return `${n} architectures selected`;
});

const allSelected  = computed(() => selectedArchitectures.value.length === knownArchitectures.length);
const noneSelected = computed(() => selectedArchitectures.value.length === 0);

const selectAll  = () => { selectedArchitectures.value = [...knownArchitectures]; };
const clearAll   = () => { selectedArchitectures.value = []; };
const toggleArch = (arch) => {
  const idx = selectedArchitectures.value.indexOf(arch);
  if (idx === -1) selectedArchitectures.value.push(arch);
  else            selectedArchitectures.value.splice(idx, 1);
};
const isSelected = (arch) => selectedArchitectures.value.includes(arch);

// Close dropdown on outside click or Escape
const handleKeydown = (e) => { if (e.key === 'Escape') dropdownOpen.value = false; };
const handleClickOutside = (e) => {
  if (dropdownRef.value && !dropdownRef.value.contains(e.target)) {
    dropdownOpen.value = false;
  }
};
onMounted(() => {
  document.addEventListener('mousedown', handleClickOutside);
  document.addEventListener('keydown', handleKeydown);
});
onUnmounted(() => {
  document.removeEventListener('mousedown', handleClickOutside);
  document.removeEventListener('keydown', handleKeydown);
});

// --- Window Controls ---
const minimizeWindow = () => window.windowAPI?.minimize();
const maximizeWindow = () => window.windowAPI?.maximize();
const closeWindow    = () => window.windowAPI?.close();

// --- Directory Selection ---
const pickSource = async () => {
  const folder = await window.electronAPI?.selectFolder();
  if (folder) sourceFolder.value = folder;
};
const pickTarget = async () => {
  const folder = await window.electronAPI?.selectFolder();
  if (folder) targetFolder.value = folder;
};

// --- API ---
const API_BASE = window.location.hostname === 'localhost' ? '' : 'http://localhost:8080';

const callApi = async (endpoint, body, processingMessage) => {
  isProcessing.value = true;
  statusMessage.value = processingMessage;
  try {
    const response = await fetch(`${API_BASE}${endpoint}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!response.ok) throw new Error(`Server status: ${response.status}`);
    const data = await response.json();
    statusMessage.value = '✅ ' + (data.message || 'Done!');
  } catch (error) {
    console.error(`${endpoint} failed:`, error);
    statusMessage.value = '❌ Error: Could not connect to the backend.';
  } finally {
    isProcessing.value = false;
  }
};

const executeOrganization = () => {
  if (!sourceFolder.value || !targetFolder.value) {
    statusMessage.value = '⚠️ Please select both source and target directories.';
    return;
  }
  if (selectedArchitectures.value.length === 0) {
    statusMessage.value = '⚠️ Please select at least one architecture.';
    return;
  }
  callApi('/api/organize', {
    sourceDirectory:      sourceFolder.value,
    targetDirectory:      targetFolder.value,
    allowedArchitectures: selectedArchitectures.value,
  }, '⏳ Scanning and organizing models… Please wait.');
};

const executeFetch = () => {
  if (!targetFolder.value) {
    statusMessage.value = '⚠️ Please select a folder to scan.';
    return;
  }
  callApi('/api/fetch', {
    targetDirectory: targetFolder.value,
  }, '⏳ Scanning for missing metadata… This may take a while.');
};

// --- Status bar CSS class ---
const statusClass = computed(() => {
  if (statusMessage.value.includes('❌')) return 'status-error';
  if (statusMessage.value.includes('⚠️')) return 'status-warn';
  if (statusMessage.value.includes('✅')) return 'status-ok';
  return '';
});
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
          <select id="theme-select" :value="currentTheme" @change="(e) => applyTheme(e.target.value)">
            <option v-for="theme in availableThemes" :key="theme" :value="theme">
              {{ theme.charAt(0).toUpperCase() + theme.slice(1) }}
            </option>
          </select>
        </div>

        <div class="window-controls">
          <button class="win-btn"       @click="minimizeWindow" title="Minimize">─</button>
          <button class="win-btn"       @click="maximizeWindow" title="Maximize">□</button>
          <button class="win-btn close" @click="closeWindow"    title="Close">✕</button>
        </div>
      </div>
    </header>

    <main class="container">
      <div class="glass-panel card">

        <!-- Tab Navigation -->
        <div class="tabs-container">
          <button class="tab-btn" :class="{ active: activeTab === 'sort' }"  @click="activeTab = 'sort'"  :disabled="isProcessing">Sort Models</button>
          <button class="tab-btn" :class="{ active: activeTab === 'fetch' }" @click="activeTab = 'fetch'" :disabled="isProcessing">Fetch Missing Info</button>
        </div>

        <!-- ── Sort Tab ── -->
        <div v-if="activeTab === 'sort'" class="tab-content">

          <div class="form-group">
            <label>Source Directory <span class="label-hint">(unorganized models)</span></label>
            <div class="input-row">
              <input type="text" class="glass-input" readonly :value="sourceFolder" placeholder="Select source folder…" />
              <button class="secondary-btn" @click="pickSource" :disabled="isProcessing">Browse</button>
            </div>
          </div>

          <div class="form-group">
            <label>Target Directory <span class="label-hint">(organized output)</span></label>
            <div class="input-row">
              <input type="text" class="glass-input" readonly :value="targetFolder" placeholder="Select target folder…" />
              <button class="secondary-btn" @click="pickTarget" :disabled="isProcessing">Browse</button>
            </div>
          </div>

          <!-- Architecture Dropdown -->
          <div class="form-group">
            <label>Architectures to Organize</label>

            <div class="arch-dropdown" ref="dropdownRef">
              <button
                  class="arch-trigger glass-input"
                  :class="{ open: dropdownOpen, 'is-none': noneSelected }"
                  @click="dropdownOpen = !dropdownOpen"
                  :disabled="isProcessing"
                  type="button"
              >
                <span class="arch-trigger-text">{{ selectionSummary }}</span>
                <span class="arch-trigger-badge" v-if="!allSelected && !noneSelected">
                  {{ selectedArchitectures.length }}/{{ knownArchitectures.length }}
                </span>
                <svg class="arch-chevron" :class="{ rotated: dropdownOpen }" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <polyline points="6 9 12 15 18 9"/>
                </svg>
              </button>

              <div v-if="dropdownOpen" class="arch-panel glass-panel">
                <div class="arch-panel-header">
                  <input
                      v-model="archSearch"
                      class="arch-search glass-input"
                      placeholder="Search architectures…"
                      autofocus
                  />
                  <div class="arch-bulk-actions">
                    <button class="link-btn" @click="selectAll"  :disabled="allSelected">All</button>
                    <span class="bulk-divider">·</span>
                    <button class="link-btn" @click="clearAll"   :disabled="noneSelected">None</button>
                  </div>
                </div>

                <ul class="arch-list">
                  <li
                      v-for="arch in filteredArchitectures"
                      :key="arch"
                      class="arch-item"
                      :class="{ selected: isSelected(arch) }"
                      @click="toggleArch(arch)"
                  >
                    <span class="arch-check">
                      <svg v-if="isSelected(arch)" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                        <polyline points="20 6 9 17 4 12"/>
                      </svg>
                    </span>
                    <span>{{ arch }}</span>
                  </li>
                  <li v-if="filteredArchitectures.length === 0" class="arch-empty">
                    No architectures match "{{ archSearch }}"
                  </li>
                </ul>
              </div>
            </div>
          </div>

          <button class="primary-btn" @click="executeOrganization" :disabled="isProcessing">
            <span v-if="isProcessing">Organizing…</span>
            <span v-else>Start Organization</span>
          </button>
        </div>

        <!-- ── Fetch Tab ── -->
        <div v-else class="tab-content">
          <div class="form-group">
            <label>Target Directory <span class="label-hint">(folder to scan)</span></label>
            <div class="input-row">
              <input type="text" class="glass-input" readonly :value="targetFolder" placeholder="Select folder to scan…" />
              <button class="secondary-btn" @click="pickTarget" :disabled="isProcessing">Browse</button>
            </div>
            <p class="helper-text">Recursively scans for models missing Civitai metadata and preview images.</p>
          </div>

          <div class="info-box">
            <p>ℹ️ Calculates SHA-256 hashes for all models without a <code>.civitai.info</code> sidecar, queries the Civitai API, and downloads metadata + preview images in-place.</p>
            <p><b>No files will be moved.</b></p>
          </div>

          <button class="primary-btn" @click="executeFetch" :disabled="isProcessing">
            <span v-if="isProcessing">Fetching…</span>
            <span v-else>Start Fetching Metadata</span>
          </button>
        </div>

      </div>

      <div class="status-bar glass-panel" :class="statusClass">
        {{ statusMessage }}
      </div>

      <ConsoleWindow />
    </main>
  </div>
</template>

<style scoped>
h1 {
  margin: 0;
  font-size: 1.5rem;
}
</style>

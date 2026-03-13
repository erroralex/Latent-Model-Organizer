<script setup>
/**
 * LATENT MODEL ORGANIZER - MAIN APPLICATION COMPONENT
 *
 * The App.vue component serves as the primary user interface and orchestration layer
 * for the Latent Model Organizer frontend. It provides a sophisticated, glassmorphic
 * desktop experience built with Vue 3, tailored for high-performance model management.
 *
 * CORE MODULES:
 * - Sorter: Orchestrates the classification and relocation of local model files
 *   into architecture-specific directory structures.
 * - Fetcher: Facilitates recursive scanning and metadata enrichment from the
 *   Civitai API without modifying the directory hierarchy.
 *
 * KEY TECHNICAL FEATURES:
 * - State Persistence: Synchronizes user preferences and directory paths with
 *   LocalStorage for session continuity.
 * - Real-time Monitoring: Integrates with a Server-Sent Events (SSE) console
 *   to provide live feedback from the backend processing engine.
 * - Responsive Architecture: Implements a reactive architecture selection system
 *   with filtering and bulk action capabilities.
 * - Electron Integration: Interfaces with native OS APIs for folder selection
 *   and window management.
 */
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
import { useTheme } from './composables/useTheme';
import ConsoleWindow from './components/ConsoleWindow.vue';
import SummaryModal  from './components/Summarymodal.vue';
import SettingsModal from './components/Settingsmodal.vue';

const { currentTheme, availableThemes, applyTheme } = useTheme();

const lsGet = (k, fb) => { try { const v = localStorage.getItem(k); return v !== null ? JSON.parse(v) : fb; } catch { return fb; } };
const lsSet = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} };

const ALL_ARCHS = [
  'Flux .1 S','Flux .1 D','Flux .1 Krea','Flux .1 Kontext','Flux .2 D',
  'Flux .2 Klein 9B','Flux .2 Klein 9B-base','Flux .2 Klein 4B','Flux .2 Klein 4B-base',
  'SD 1.4','SD 1.5','SD 1.5 LCM','SD 1.5 Hyper','SD 2.0','SD 2.1','SD 3.5',
  'SDXL 1.0','SDXL Lightning','SDXL Hyper','SDXL Turbo',
  'Pony','Pony V7','Illustrious','NoobAI',
  'Wan Video 1.3B t2v','Wan Video 14B t2v','Wan Video 14B i2v 480p','Wan Video 14B i2v 720p',
  'Wan Video 2.2 TI2V-5B','Wan Video 2.2 T2V-A14B','Wan Video 2.2 I2V-A14B',
  'Wan Video 2.5 T2V','Wan Video 2.5 I2V',
  'LTXV','LTXV2','Mochi','CogVideoX','Hunyuan 1','Hunyuan Video',
  'Sana','HiDream','PixArt α','PixArt Σ','Aura Flow','Lumina','Kolors',
  'Chroma','Anima','Qwen','Z Image Turbo','Z Image Base',
  'Uncategorized','Unknown',
];

const activeTab             = ref(lsGet('lmo:activeTab', 'sort'));
const sourceFolder          = ref(lsGet('lmo:sourceFolder', ''));
const targetFolder          = ref(lsGet('lmo:targetFolder', ''));
const isRecursive           = ref(lsGet('lmo:isRecursive', true));
const isDryRun              = ref(lsGet('lmo:isDryRun', false));
const selectedArchitectures = ref(lsGet('lmo:selectedArchs', [...ALL_ARCHS]));

watch(activeTab,             v => lsSet('lmo:activeTab', v));
watch(sourceFolder,          v => lsSet('lmo:sourceFolder', v));
watch(targetFolder,          v => lsSet('lmo:targetFolder', v));
watch(isRecursive,           v => lsSet('lmo:isRecursive', v));
watch(isDryRun,              v => lsSet('lmo:isDryRun', v));
watch(selectedArchitectures, v => lsSet('lmo:selectedArchs', v), { deep: true });

const statusMessage   = ref('Ready.');
const isProcessing    = ref(false);
const showReportModal = ref(false);
const operationReport = ref(null);
const showSettings    = ref(false);
const consoleOpen     = ref(true);

const dropdownOpen = ref(false);
const archSearch   = ref('');
const dropdownRef  = ref(null);

const filteredArchs    = computed(() => { const q = archSearch.value.trim().toLowerCase(); return q ? ALL_ARCHS.filter(a => a.toLowerCase().includes(q)) : ALL_ARCHS; });
const selectionSummary = computed(() => { const n = selectedArchitectures.value.length; const t = ALL_ARCHS.length; if (n === 0) return 'None selected'; if (n === t) return 'All architectures'; if (n === 1) return selectedArchitectures.value[0]; return `${n} of ${t} selected`; });
const allSelected      = computed(() => selectedArchitectures.value.length === ALL_ARCHS.length);
const noneSelected     = computed(() => selectedArchitectures.value.length === 0);
const selectAll        = () => { selectedArchitectures.value = [...ALL_ARCHS]; };
const clearAll         = () => { selectedArchitectures.value = []; };
const toggleArch       = (a) => { const i = selectedArchitectures.value.indexOf(a); if (i === -1) selectedArchitectures.value.push(a); else selectedArchitectures.value.splice(i, 1); };
const isSelected       = (a) => selectedArchitectures.value.includes(a);

const onKeydown        = (e) => { if (e.key === 'Escape') dropdownOpen.value = false; };
const onClickOutside   = (e) => { if (dropdownRef.value && !dropdownRef.value.contains(e.target)) dropdownOpen.value = false; };
onMounted(()   => { applyTheme(currentTheme.value); document.addEventListener('mousedown', onClickOutside); document.addEventListener('keydown', onKeydown); });
onUnmounted(() => { document.removeEventListener('mousedown', onClickOutside); document.removeEventListener('keydown', onKeydown); });

const minimizeWindow = () => window.windowAPI?.minimize();
const maximizeWindow = () => window.windowAPI?.maximize();
const closeWindow    = () => window.windowAPI?.close();
const pickSource     = async () => { const f = await window.electronAPI?.selectFolder(); if (f) sourceFolder.value = f; };
const pickTarget     = async () => { const f = await window.electronAPI?.selectFolder(); if (f) targetFolder.value = f; };

const API_BASE = window.location?.hostname === 'localhost' ? '' : 'http://localhost:8080';

const callApi = async (endpoint, body, msg) => {
  isProcessing.value = true;
  statusMessage.value = msg;
  try {
    const res  = await fetch(`${API_BASE}${endpoint}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const data = await res.json();
    if (!res.ok) throw new Error(data.message || `Server error ${res.status}`);
    statusMessage.value  = '✅ ' + (data.message || 'Done!');
    operationReport.value = data;
    showReportModal.value  = true;
  } catch (err) {
    statusMessage.value = '❌ ' + err.message;
  } finally {
    isProcessing.value = false;
  }
};

const executeOrganization = () => {
  if (!sourceFolder.value || !targetFolder.value) { statusMessage.value = '⚠️ Please select both source and target directories.'; return; }
  if (!selectedArchitectures.value.length)         { statusMessage.value = '⚠️ Please select at least one architecture.'; return; }
  callApi('/api/organize', { sourceDirectory: sourceFolder.value, targetDirectory: targetFolder.value, allowedArchitectures: selectedArchitectures.value, isRecursive: isRecursive.value, isDryRun: isDryRun.value }, '⏳ Scanning and organizing models…');
};

const executeFetch = () => {
  if (!targetFolder.value) { statusMessage.value = '⚠️ Please select a folder to scan.'; return; }
  callApi('/api/fetch', { targetDirectory: targetFolder.value, isRecursive: isRecursive.value, isDryRun: isDryRun.value }, '⏳ Scanning for missing metadata…');
};

const statusClass = computed(() => {
  if (statusMessage.value.startsWith('❌')) return 'is-error';
  if (statusMessage.value.startsWith('⚠️')) return 'is-warn';
  if (statusMessage.value.startsWith('✅')) return 'is-ok';
  return '';
});

const closeReport = () => { showReportModal.value = false; operationReport.value = null; };
</script>

<template>
  <div class="layout-wrapper">

    <header class="menubar-glass draggable-header">

      <div class="hdr-logo">
        <svg width="26" height="26" viewBox="0 0 24 24" fill="none" class="logo-icon no-drag">
          <path d="M12 2L2 7l10 5 10-5-10-5z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          <path d="M2 17l10 5 10-5"           stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          <path d="M2 12l10 5 10-5"           stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        <span class="app-name text-gradient">Latent Model Organizer</span>
      </div>

      <nav class="hdr-nav">
        <button class="nav-btn no-drag" :class="{ 'active-nav-btn': activeTab === 'sort'  }" @click="activeTab = 'sort'"  :disabled="isProcessing">
          <i class="pi pi-sort-alt"></i> Sorter
        </button>
        <button class="nav-btn no-drag" :class="{ 'active-nav-btn': activeTab === 'fetch' }" @click="activeTab = 'fetch'" :disabled="isProcessing">
          <i class="pi pi-cloud-download"></i> Fetcher
        </button>

        <div class="nav-divider"></div>

        <button class="nav-btn icon-only no-drag" :class="{ 'active-nav-btn': showSettings }" @click="showSettings = true"          title="Settings">        <i class="pi pi-cog"></i>             </button>
        <button class="nav-btn icon-only no-drag" :class="{ 'active-nav-btn': consoleOpen  }" @click="consoleOpen = !consoleOpen"   title="Toggle Console">  <i class="pi pi-server"></i>          </button>
      </nav>

      <div class="hdr-drag-region"></div>

      <div class="hdr-controls">
        <button class="nav-btn icon-only no-drag"                                               @click="minimizeWindow"              title="Minimize">         <i class="pi pi-minus"></i>           </button>
        <button class="nav-btn icon-only no-drag"                                               @click="maximizeWindow"              title="Maximize">         <i class="pi pi-window-maximize"></i> </button>
        <button class="nav-btn icon-only window-close-btn no-drag"                              @click="closeWindow"                 title="Close">            <i class="pi pi-times"></i>           </button>
      </div>
    </header>

    <main class="app-main">
      <div class="content-scroll">
        <div class="content-container">

          <div class="glass-panel main-card">

            <!-- SORT TAB -->
            <div v-if="activeTab === 'sort'" class="tab-content">

              <div class="form-group">
                <label class="form-label">Source Directory <span class="label-hint">unorganized models</span></label>
                <div class="input-row">
                  <input class="glass-input" type="text" readonly :value="sourceFolder" placeholder="Select source folder…" />
                  <button class="secondary-btn" @click="pickSource" :disabled="isProcessing"><i class="pi pi-folder-open"></i> Browse</button>
                </div>
              </div>

              <div class="form-group">
                <label class="form-label">Target Directory <span class="label-hint">organized output</span></label>
                <div class="input-row">
                  <input class="glass-input" type="text" readonly :value="targetFolder" placeholder="Select target folder…" />
                  <button class="secondary-btn" @click="pickTarget" :disabled="isProcessing"><i class="pi pi-folder-open"></i> Browse</button>
                </div>
              </div>

              <div class="form-group">
                <label class="form-label">
                  Architectures
                  <span class="label-hint" v-if="!allSelected && !noneSelected">{{ selectedArchitectures.length }}/{{ ALL_ARCHS.length }}</span>
                </label>
                <div class="arch-dropdown" ref="dropdownRef">
                  <button class="arch-trigger glass-input" :class="{ open: dropdownOpen, 'is-none': noneSelected }" @click="dropdownOpen = !dropdownOpen" :disabled="isProcessing" type="button">
                    <span class="arch-trigger-text">{{ selectionSummary }}</span>
                    <span class="arch-trigger-badge" v-if="!allSelected && !noneSelected">{{ selectedArchitectures.length }}</span>
                    <svg class="arch-chevron" :class="{ rotated: dropdownOpen }" viewBox="0 0 24 24"><polyline points="6 9 12 15 18 9"/></svg>
                  </button>
                  <div v-if="dropdownOpen" class="arch-panel glass-panel">
                    <div class="arch-panel-header">
                      <input v-model="archSearch" class="arch-search glass-input" placeholder="Search…" autofocus />
                      <div class="arch-bulk-actions">
                        <button class="link-btn" @click="selectAll" :disabled="allSelected">All</button>
                        <span class="bulk-divider">·</span>
                        <button class="link-btn" @click="clearAll" :disabled="noneSelected">None</button>
                      </div>
                    </div>
                    <ul class="arch-list">
                      <li v-for="arch in filteredArchs" :key="arch" class="arch-item" :class="{ selected: isSelected(arch) }" @click="toggleArch(arch)">
                        <span class="arch-check">
                          <svg v-if="isSelected(arch)" viewBox="0 0 24 24"><polyline points="20 6 9 17 4 12"/></svg>
                        </span>
                        {{ arch }}
                      </li>
                      <li v-if="!filteredArchs.length" class="arch-empty">No matches for "{{ archSearch }}"</li>
                    </ul>
                  </div>
                </div>
              </div>
            </div>

            <!-- FETCH TAB -->
            <div v-else class="tab-content">
              <div class="form-group">
                <label class="form-label">Target Directory <span class="label-hint">folder to scan</span></label>
                <div class="input-row">
                  <input class="glass-input" type="text" readonly :value="targetFolder" placeholder="Select folder to scan…" />
                  <button class="secondary-btn" @click="pickTarget" :disabled="isProcessing"><i class="pi pi-folder-open"></i> Browse</button>
                </div>
                <p class="helper-text">Recursively scans for models missing Civitai metadata and preview images.</p>
              </div>
              <div class="info-box">
                <i class="pi pi-info-circle info-icon"></i>
                <div>
                  <p>Calculates SHA-256 hashes for all models without a <code>.civitai.info</code> sidecar, queries the Civitai API, and downloads metadata + preview images in-place.</p>
                  <p><strong>No files will be moved.</strong></p>
                </div>
              </div>
            </div>

            <!-- SHARED OPTIONS -->
            <div class="options-row">
              <label class="toggle-label">
                <input type="checkbox" v-model="isRecursive" :disabled="isProcessing" class="sr-only" />
                <span class="toggle-track" :class="{ checked: isRecursive }"><span class="toggle-thumb"></span></span>
                <span>Deep Scan</span>
                <span class="label-hint">subfolders</span>
              </label>
              <label class="toggle-label">
                <input type="checkbox" v-model="isDryRun" :disabled="isProcessing" class="sr-only" />
                <span class="toggle-track" :class="{ checked: isDryRun }"><span class="toggle-thumb"></span></span>
                <span>Dry Run</span>
                <span class="label-hint">simulate only</span>
              </label>
            </div>

            <!-- ACTION BUTTON -->
            <button v-if="activeTab === 'sort'" class="primary-btn" @click="executeOrganization" :disabled="isProcessing">
              <i class="pi" :class="isProcessing ? 'pi-spin pi-spinner' : 'pi-sort-alt'"></i>
              {{ isProcessing ? 'Organizing…' : 'Start Organization' }}
            </button>
            <button v-else class="primary-btn" @click="executeFetch" :disabled="isProcessing">
              <i class="pi" :class="isProcessing ? 'pi-spin pi-spinner' : 'pi-cloud-download'"></i>
              {{ isProcessing ? 'Fetching…' : 'Start Fetching Metadata' }}
            </button>

          </div>

          <!-- STATUS BAR -->
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

      <!-- CONSOLE DRAWER -->
      <transition name="drawer">
        <ConsoleWindow v-if="consoleOpen" class="console-drawer" />
      </transition>
    </main>

    <!-- MODALS -->
    <SummaryModal  v-if="showReportModal && operationReport" :report="operationReport" :isDryRun="isDryRun" @close="closeReport" />
    <SettingsModal v-if="showSettings" :currentTheme="currentTheme" :availableThemes="availableThemes" :isRecursive="isRecursive" :isDryRun="isDryRun"
                   @applyTheme="applyTheme" @update:isRecursive="v => isRecursive = v" @update:isDryRun="v => isDryRun = v" @close="showSettings = false" />

  </div>
</template>

<style scoped>
.layout-wrapper { display: flex; flex-direction: column; height: 100vh; overflow: hidden; }

.menubar-glass {
  display: flex; align-items: center; gap: 4px;
  padding: 0 6px 0 16px; height: 52px; flex-shrink: 0;
  background: var(--bg-header);
  backdrop-filter: var(--glass-blur); -webkit-backdrop-filter: var(--glass-blur);
  border-bottom: 1px solid var(--border-light);
  box-shadow: var(--shadow-panel);
  z-index: 1000;
}
.draggable-header { -webkit-app-region: drag; user-select: none; }
.no-drag          { -webkit-app-region: no-drag; }

.hdr-logo   { display: flex; align-items: center; gap: 10px; margin-right: 16px; flex-shrink: 0; }
.hdr-nav    { display: flex; gap: 8px; flex-shrink: 0; }
.hdr-controls { display: flex; align-items: center; gap: 2px; margin-left: auto; flex-shrink: 0; }
.hdr-drag-region { flex-grow: 1; height: 100%; -webkit-app-region: drag; }

.nav-divider { width: 1px; background: var(--border-light); height: 24px; margin: 0 4px; align-self: center; }

.logo-icon { color: var(--accent-primary); flex-shrink: 0; }
.app-name  { font-size: 1.15rem; font-weight: 800; white-space: nowrap; }

.app-main {
  flex: 1; display: flex; flex-direction: column; overflow: hidden; min-height: 0;
}
.content-scroll    { flex: 1; overflow-y: auto; min-height: 0; }
.content-container { max-width: 780px; margin: 0 auto; padding: 28px 24px 20px; }

.main-card { border-radius: 12px; padding: 28px; margin-bottom: 16px; }

.tab-content { display: flex; flex-direction: column; gap: 0; }
.form-group  { margin-bottom: 22px; }
.form-label  { display: block; font-size: 0.82rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.06em; color: var(--text-secondary); margin-bottom: 8px; }
.label-hint  { font-size: 0.72rem; font-weight: 400; text-transform: none; letter-spacing: 0; opacity: 0.55; margin-left: 6px; }
.input-row   { display: flex; gap: 8px; }
.helper-text { font-size: 0.82em; color: var(--text-muted); margin: 8px 0 0; }

.info-box {
  display: flex; gap: 14px; align-items: flex-start;
  background: var(--bg-input); border-left: 3px solid var(--accent-primary);
  border-radius: 6px; padding: 16px; font-size: 0.88em; color: var(--text-secondary);
}
.info-box p  { margin: 4px 0; }
.info-icon   { color: var(--accent-primary); font-size: 1.1rem; flex-shrink: 0; margin-top: 2px; }

.options-row {
  display: flex; gap: 28px; padding: 18px 0; margin-bottom: 4px;
  border-top: 1px solid var(--border-input);
}
.toggle-label { display: flex; align-items: center; gap: 10px; cursor: pointer; font-size: 0.9rem; font-weight: 600; color: var(--text-secondary); }
.toggle-label:hover { color: var(--text-primary); }
.sr-only { position: absolute; width: 1px; height: 1px; opacity: 0; pointer-events: none; }

.toggle-track {
  position: relative; width: 40px; height: 22px; border-radius: 11px; flex-shrink: 0;
  background: var(--bg-input); border: 1px solid var(--border-input);
  transition: background 0.2s, border-color 0.2s;
}
.toggle-track.checked { background: var(--accent-primary); border-color: var(--accent-primary); }
.toggle-thumb {
  position: absolute; top: 3px; left: 3px; width: 14px; height: 14px; border-radius: 50%;
  background: var(--text-secondary); transition: transform 0.2s, background 0.2s;
}
.toggle-track.checked .toggle-thumb { transform: translateX(18px); background: var(--bg-app, #000); }

.arch-dropdown    { position: relative; }
.arch-trigger     { display: flex; align-items: center; width: 100%; cursor: pointer; text-align: left; gap: 8px; user-select: none; }
.arch-trigger.is-none  { opacity: 0.6; }
.arch-trigger.open     { border-color: var(--accent-primary) !important; }
.arch-trigger-text     { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.arch-trigger-badge    { font-size: 0.7rem; padding: 1px 7px; border-radius: 999px; background: var(--accent-primary); color: #000; font-weight: 700; flex-shrink: 0; }
.arch-chevron          { width: 16px; height: 16px; flex-shrink: 0; opacity: 0.6; transition: transform 0.2s; }
.arch-chevron.rotated  { transform: rotate(180deg); }
.arch-panel {
  position: absolute; top: calc(100% + 6px); left: 0; right: 0; z-index: 100;
  border-radius: 8px; overflow: hidden; box-shadow: 0 8px 32px rgba(0,0,0,0.4);
  display: flex; flex-direction: column; max-height: 300px;
  background: var(--bg-panel-opaque) !important;
}
.arch-panel-header   { display: flex; align-items: center; gap: 8px; padding: 8px; border-bottom: 1px solid var(--border-input); flex-shrink: 0; }
.arch-search         { flex: 1; font-size: 0.82rem; padding: 6px 10px; }
.arch-bulk-actions   { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }
.bulk-divider        { opacity: 0.4; }
.arch-list           { list-style: none; margin: 0; padding: 4px 0; overflow-y: auto; flex: 1; }
.arch-item           { display: flex; align-items: center; gap: 8px; padding: 7px 12px; cursor: pointer; font-size: 0.85rem; color: var(--text-primary); transition: background 0.1s; }
.arch-item:hover     { background: var(--bg-btn-inner); }
.arch-item.selected  { color: var(--accent-primary); background: rgba(102,252,241,0.06); }
.arch-check          { width: 16px; height: 16px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; color: var(--accent-primary); }
.arch-check svg      { width: 13px; height: 13px; }
.arch-empty          { padding: 12px 16px; font-size: 0.8rem; opacity: 0.5; font-style: italic; color: var(--text-muted); }

.status-bar {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 18px; border-radius: 8px; font-weight: 600; font-size: 0.9rem;
  color: var(--text-secondary);
}
.status-bar.is-ok    { color: var(--status-success); }
.status-bar.is-warn  { color: var(--status-warning); }
.status-bar.is-error { color: var(--status-danger); }

.console-drawer {
  flex-shrink: 0; height: 220px; border-top: 1px solid var(--border-light);
}
.drawer-enter-active, .drawer-leave-active { transition: height 0.25s ease, opacity 0.2s ease; }
.drawer-enter-from, .drawer-leave-to       { height: 0 !important; opacity: 0; }
</style>

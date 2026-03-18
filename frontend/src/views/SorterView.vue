<script setup>
/**
 * SorterView.vue
 *
 * The primary interface for orchestrating the model organization workflow.
 * It manages directory selection, architectural filtering, and execution logic.
 *
 * Key Capabilities:
 * - Native Directory Management: Uses Electron-native dialogs and shell integration.
 * - Architecture Filtering: Searchable multi-select system for targeting specific model types.
 * - Progressive Execution: Toggles between standard, recursive, and simulation (dry run) modes.
 * - Real-time Status: Integrated stopwatch and asymptotic/real progress tracking via API polling.
 * - Undo Support: Direct interface to reverse the last organizational run.
 */
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import InfoModal from '../components/InfoModal.vue';

const props = defineProps({
  isProcessing: { type: Boolean, default: false },
  isRecursive:  { type: Boolean, default: true  },
  isDryRun:     { type: Boolean, default: false  },
  canUndo:      { type: Boolean, default: false  },
  apiBase:      { type: String,  default: ''     },
  apiToken:     { type: String,  default: ''     },
});

const emit = defineEmits(['start-organize', 'update:isRecursive', 'update:isDryRun', 'undo']);

const lsGet = (k, fb) => {
  try { const v = localStorage.getItem(k); return v !== null ? JSON.parse(v) : fb; } catch { return fb; }
};
const lsSet = (k, v) => {
  try { localStorage.setItem(k, JSON.stringify(v)); } catch { }
};

const ALL_ARCHS = [
  'Flux .1 S', 'Flux .1 D', 'Flux .1 Krea', 'Flux .1 Kontext', 'Flux .2 D',
  'Flux .2 Klein 9B', 'Flux .2 Klein 9B-base', 'Flux .2 Klein 4B', 'Flux .2 Klein 4B-base',
  'SD 1.4', 'SD 1.5', 'SD 1.5 LCM', 'SD 1.5 Hyper', 'SD 2.0', 'SD 2.1', 'SD 3.5',
  'SDXL 1.0', 'SDXL Lightning', 'SDXL Hyper', 'SDXL Turbo',
  'Pony', 'Pony V7', 'Illustrious', 'NoobAI',
  'Wan Video 1.3B t2v', 'Wan Video 14B t2v', 'Wan Video 14B i2v 480p', 'Wan Video 14B i2v 720p',
  'Wan Video 2.2 TI2V-5B', 'Wan Video 2.2 T2V-A14B', 'Wan Video 2.2 I2V-A14B',
  'Wan Video 2.5 T2V', 'Wan Video 2.5 I2V',
  'LTXV', 'LTXV2', 'Mochi', 'CogVideoX', 'Hunyuan 1', 'Hunyuan Video',
  'Sana', 'HiDream', 'PixArt α', 'PixArt Σ', 'Aura Flow', 'Lumina', 'Kolors',
  'Chroma', 'Anima', 'Qwen', 'Z Image Turbo', 'Z Image Base',
  'Uncategorized', 'Unknown',
];

const sourceFolder = ref(lsGet('lmo:sourceFolder', ''));
const targetFolder = ref(lsGet('lmo:targetFolder', ''));

watch(sourceFolder, v => lsSet('lmo:sourceFolder', v));
watch(targetFolder, v => lsSet('lmo:targetFolder', v));

const pickSource = async () => {
  const f = await window.electronAPI?.selectFolder();
  if (f) sourceFolder.value = f;
};
const pickTarget = async () => {
  const f = await window.electronAPI?.selectFolder();
  if (f) targetFolder.value = f;
};

const openFolder = async (folderPath) => {
  if (folderPath && window.electronAPI?.openFolder) {
    await window.electronAPI.openFolder(folderPath);
  }
};

const selectedArchitectures = ref(lsGet('lmo:selectedArchs', [...ALL_ARCHS]));
const showInfo     = ref(false);
const dropdownOpen = ref(false);
const archSearch   = ref('');
const dropdownRef  = ref(null);

watch(selectedArchitectures, v => lsSet('lmo:selectedArchs', v), { deep: true });

const filteredArchs = computed(() => {
  const q = archSearch.value.trim().toLowerCase();
  return q ? ALL_ARCHS.filter(a => a.toLowerCase().includes(q)) : ALL_ARCHS;
});
const selectionSummary = computed(() => {
  const n = selectedArchitectures.value.length;
  const t = ALL_ARCHS.length;
  if (n === 0) return 'None selected';
  if (n === t) return 'All architectures';
  if (n === 1) return selectedArchitectures.value[0];
  return `${n} of ${t} selected`;
});
const allSelected  = computed(() => selectedArchitectures.value.length === ALL_ARCHS.length);
const noneSelected = computed(() => selectedArchitectures.value.length === 0);

const selectAll  = () => { selectedArchitectures.value = [...ALL_ARCHS]; };
const clearAll   = () => { selectedArchitectures.value = []; };
const toggleArch = (a) => {
  const i = selectedArchitectures.value.indexOf(a);
  if (i === -1) selectedArchitectures.value.push(a);
  else selectedArchitectures.value.splice(i, 1);
};
const isSelected = (a) => selectedArchitectures.value.includes(a);

const onKeydown      = (e) => { if (e.key === 'Escape') dropdownOpen.value = false; };
const onClickOutside = (e) => { if (dropdownRef.value && !dropdownRef.value.contains(e.target)) dropdownOpen.value = false; };

onMounted(() => {
  document.addEventListener('mousedown', onClickOutside);
  document.addEventListener('keydown',   onKeydown);
});
onUnmounted(() => {
  document.removeEventListener('mousedown', onClickOutside);
  document.removeEventListener('keydown',   onKeydown);
  clearInterval(stopwatchTimerRef);
  clearInterval(progressTimerRef);
});

const elapsedMs  = ref(0);
const stopwatchActive = ref(false);
let stopwatchStart   = 0;
let stopwatchTimerRef = null;

const elapsedFormatted = computed(() => {
  const totalSec = Math.floor(elapsedMs.value / 1000);
  const h   = Math.floor(totalSec / 3600);
  const m   = Math.floor((totalSec % 3600) / 60);
  const sec = totalSec % 60;
  const mm  = String(m).padStart(2, '0');
  const ss  = String(sec).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
});

const progressPct      = ref(0);
const progressDone     = ref(false);
const progressLabel    = ref('');
const progressVisible  = computed(() => props.isProcessing || progressDone.value);
let progressTimerRef   = null;
let resetTimeoutRef    = null;

const PROGRESS_POLL_MS   = 300;
const SCAN_FILL_TARGET   = 8;
const SCAN_FILL_STEP     = 0.4;

async function pollProgress() {
  try {
    const res  = await fetch(`${props.apiBase}/api/progress`, {
      headers: { 'Authorization': `Bearer ${props.apiToken}` }
    });
    if (!res.ok) return;
    const data = await res.json();

    const { processed, total } = data;

    if (total > 0) {
      const real = (processed / total) * 100;
      progressPct.value   = Math.max(progressPct.value, Math.min(real, 99));
      progressLabel.value = `${processed} / ${total} groups`;
    } else {
      if (progressPct.value < SCAN_FILL_TARGET) {
        progressPct.value = Math.min(progressPct.value + SCAN_FILL_STEP, SCAN_FILL_TARGET);
      }
      progressLabel.value = 'Scanning...';
    }
  } catch {
  }
}

watch(() => props.isProcessing, (processing) => {
  if (processing) {
    clearTimeout(resetTimeoutRef);

    elapsedMs.value       = 0;
    stopwatchActive.value = true;
    stopwatchStart        = Date.now();
    stopwatchTimerRef = setInterval(() => {
      elapsedMs.value = Date.now() - stopwatchStart;
    }, 100);

    progressPct.value   = 0;
    progressDone.value  = false;
    progressLabel.value = 'Scanning...';
    progressTimerRef = setInterval(pollProgress, PROGRESS_POLL_MS);

  } else if (stopwatchActive.value) {
    clearInterval(stopwatchTimerRef);
    stopwatchActive.value = false;
    elapsedMs.value = Date.now() - stopwatchStart;

    clearInterval(progressTimerRef);
    progressPct.value   = 100;
    progressLabel.value = '';
    progressDone.value  = true;

    resetTimeoutRef = setTimeout(() => {
      progressDone.value = false;
      progressPct.value  = 0;
    }, 4000);
  }
});

const handleExecute = () => {
  emit('start-organize', {
    sourceDirectory:      sourceFolder.value,
    targetDirectory:      targetFolder.value,
    allowedArchitectures: selectedArchitectures.value,
  });
};
</script>

<template>
  <div class="tab-content">

    <div class="section-header">
      <h2 class="section-title">Model Organizer</h2>
      <button class="info-btn" @click="showInfo = true" title="What is this?">
        <i class="pi pi-question-circle"></i>
      </button>
    </div>

    <div class="form-group">
      <label class="form-label">Source Directory <span class="label-hint">unorganized models</span></label>
      <div class="input-row">
        <input class="glass-input" type="text" readonly :value="sourceFolder" placeholder="Select source folder..."/>
        <button class="secondary-btn" @click="pickSource" :disabled="isProcessing">
          <i class="pi pi-folder-open"></i> Browse
        </button>
        <button
            class="icon-btn"
            @click="openFolder(sourceFolder)"
            :disabled="!sourceFolder || isProcessing"
            title="Open folder in Explorer"
        >
          <i class="pi pi-external-link"></i>
        </button>
      </div>
    </div>

    <div class="form-group">
      <label class="form-label">Target Directory <span class="label-hint">organized output</span></label>
      <div class="input-row">
        <input class="glass-input" type="text" readonly :value="targetFolder" placeholder="Select target folder..."/>
        <button class="secondary-btn" @click="pickTarget" :disabled="isProcessing">
          <i class="pi pi-folder-open"></i> Browse
        </button>
        <button
            class="icon-btn"
            @click="openFolder(targetFolder)"
            :disabled="!targetFolder || isProcessing"
            title="Open folder in Explorer"
        >
          <i class="pi pi-external-link"></i>
        </button>
      </div>
    </div>

    <div class="form-group">
      <label class="form-label">
        Architectures
        <span class="label-hint" v-if="!allSelected && !noneSelected">
          {{ selectedArchitectures.length }}/{{ ALL_ARCHS.length }}
        </span>
      </label>
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
            {{ selectedArchitectures.length }}
          </span>
          <svg class="arch-chevron" :class="{ rotated: dropdownOpen }" viewBox="0 0 24 24">
            <polyline points="6 9 12 15 18 9"/>
          </svg>
        </button>
        <div v-if="dropdownOpen" class="arch-panel glass-panel">
          <div class="arch-panel-header">
            <input v-model="archSearch" class="arch-search glass-input" placeholder="Search..." autofocus/>
            <div class="arch-bulk-actions">
              <button class="link-btn" @click="selectAll" :disabled="allSelected">All</button>
              <span class="bulk-divider">·</span>
              <button class="link-btn" @click="clearAll" :disabled="noneSelected">None</button>
            </div>
          </div>
          <ul class="arch-list">
            <li
                v-for="arch in filteredArchs"
                :key="arch"
                class="arch-item"
                :class="{ selected: isSelected(arch) }"
                @click="toggleArch(arch)"
            >
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

    <div class="options-row">
      <label class="toggle-label">
        <input type="checkbox" :checked="isRecursive" @change="emit('update:isRecursive', $event.target.checked)"
               :disabled="isProcessing" class="sr-only"/>
        <span class="toggle-track" :class="{ checked: isRecursive }"><span class="toggle-thumb"></span></span>
        <span>Deep Scan</span>
        <span class="label-hint">subfolders</span>
      </label>
      <label class="toggle-label">
        <input type="checkbox" :checked="isDryRun" @change="emit('update:isDryRun', $event.target.checked)"
               :disabled="isProcessing" class="sr-only"/>
        <span class="toggle-track" :class="{ checked: isDryRun }"><span class="toggle-thumb"></span></span>
        <span>Dry Run</span>
        <span class="label-hint">simulate only</span>
      </label>
    </div>

    <transition name="progress-fade">
      <div v-if="progressVisible" class="progress-section">
        <div class="progress-header">
          <span class="progress-label">
            <i class="pi" :class="progressDone ? 'pi-check-circle' : 'pi-spin pi-spinner'"></i>
            {{ progressDone ? 'Done' : (progressLabel || 'Organizing...') }}
          </span>
          <span class="stopwatch" :class="{ 'stopwatch-done': progressDone }">
            <i class="pi pi-stopwatch"></i>
            {{ elapsedFormatted }}
          </span>
        </div>
        <div class="progress-track">
          <div
              class="progress-fill"
              :class="{ 'progress-done': progressDone, 'progress-active': isProcessing }"
              :style="{ width: progressPct.toFixed(1) + '%' }"
          ></div>
        </div>
      </div>
    </transition>

    <button class="primary-btn" @click="handleExecute" :disabled="isProcessing">
      <i class="pi" :class="isProcessing ? 'pi-spin pi-spinner' : 'pi-sort-alt'"></i>
      {{ isProcessing ? 'Organizing...' : 'Start Organization' }}
    </button>

    <button
        class="secondary-btn"
        @click="emit('undo')"
        :disabled="isProcessing || !canUndo"
        title="Move all files from the last real sort back to their original locations. Not available after dry runs."
    >
      <i class="pi pi-undo"></i>
      Undo Last Sort
    </button>

    <InfoModal v-if="showInfo" title="Model Organizer Info" @close="showInfo = false">
      <p>This tool scans massive <code>.safetensors</code> libraries using zero-memory byte parsing.</p>
      <p>It automatically identifies model architectures by reading header metadata without loading the full file.</p>
      <p>Orphaned sidecar files (like preview images <code>.png</code>, <code>.json</code> metadata, and text files) are
        grouped with their parent model and moved together as an atomic unit to keep your library clean.</p>
    </InfoModal>

  </div>
</template>

<style scoped>
.icon-btn {
  flex-shrink: 0;
  display:     inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  padding: 0;
  background: var(--bg-input);
  border: 1px solid var(--border-input);
  border-radius: 8px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: color 0.15s, border-color 0.15s, background 0.15s;
}

.icon-btn:hover:not(:disabled) {
  color: var(--accent-primary);
  border-color: var(--accent-primary);
  background: var(--bg-hover);
}

.icon-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.progress-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 0 2px;
}

.progress-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 0.8rem;
}

.progress-label {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-secondary);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.progress-label .pi-check-circle {
  color: var(--status-success);
}

.stopwatch {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
  font-size: 0.85rem;
  letter-spacing: 0.03em;
  transition: color 0.3s;
}

.stopwatch-done {
  color: var(--status-success);
  font-weight: 700;
}

.progress-track {
  width: 100%;
  height: 5px;
  background: var(--bg-input);
  border-radius: 999px;
  overflow: hidden;
  border: 1px solid var(--border-input);
}

.progress-fill {
  height: 100%;
  border-radius: 999px;
  background: var(--grad-hover, linear-gradient(90deg, var(--accent-primary), var(--accent-secondary, var(--accent-primary))));
  transition: width 0.3s ease-out;
  position: relative;
  overflow: hidden;
}

.progress-fill.progress-active::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(
      90deg,
      transparent 0%,
      rgba(255, 255, 255, 0.28) 50%,
      transparent 100%
  );
  background-size: 200% 100%;
  animation: shimmer 1.4s infinite;
}

.progress-fill.progress-done {
  background: var(--status-success, #22c55e);
  transition: width 0.25s ease-in, background 0.3s ease;
}

.progress-fill.progress-done::after {
  display: none;
}

@keyframes shimmer {
  0%   { transform: translateX(-100%); }
  100% { transform: translateX(100%); }
}

.progress-fade-enter-active {
  transition: opacity 0.25s ease, transform 0.25s ease;
}
.progress-fade-leave-active {
  transition: opacity 0.6s ease, transform 0.4s ease;
}
.progress-fade-enter-from,
.progress-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>

<script setup>
/**
 * SorterView.vue
 *
 * Model Organization view for Latent Model Organizer aligned with the Latent Design System.
 */
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import InfoModal from '../components/InfoModal.vue';
import {
  HelpCircle, FolderOpen, ExternalLink, ChevronDown, CheckSquare, Square,
  CheckCircle2, Loader2, Timer, ArrowUpDown, XCircle, Undo2,
} from 'lucide-vue-next';

const props = defineProps({
  isProcessing: { type: Boolean, default: false },
  isCancelling: { type: Boolean, default: false },
  isRecursive:  { type: Boolean, default: true  },
  isDryRun:     { type: Boolean, default: false  },
  canUndo:      { type: Boolean, default: false  },
  apiBase:      { type: String,  default: ''     },
  apiToken:     { type: String,  default: ''     },
});

const emit = defineEmits(['start-organize', 'cancel-operation', 'update:isRecursive', 'update:isDryRun', 'undo']);

const lsGet = (k, fb) => {
  try { const v = localStorage.getItem(k); return v !== null ? JSON.parse(v) : fb; } catch { return fb; }
};
const lsSet = (k, v) => {
  try { localStorage.setItem(k, JSON.stringify(v)); } catch { }
};

const ALL_ARCHS = ref(['Unknown']);

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

const selectedArchitectures = ref(lsGet('lmo:selectedArchs', []));
const showInfo     = ref(false);
const dropdownOpen = ref(false);
const archSearch   = ref('');
const dropdownRef  = ref(null);

watch(selectedArchitectures, v => lsSet('lmo:selectedArchs', v), { deep: true });

const filteredArchs = computed(() => {
  const q = archSearch.value.trim().toLowerCase();
  return q ? ALL_ARCHS.value.filter(a => a.toLowerCase().includes(q)) : ALL_ARCHS.value;
});
const selectionSummary = computed(() => {
  const n = selectedArchitectures.value.length;
  const t = ALL_ARCHS.value.length;
  if (n === 0) return 'None selected';
  if (n === t) return 'All architectures';
  if (n === 1) return selectedArchitectures.value[0];
  return `${n} of ${t} selected`;
});
const allSelected  = computed(() => selectedArchitectures.value.length === ALL_ARCHS.value.length);
const noneSelected = computed(() => selectedArchitectures.value.length === 0);

const selectAll  = () => { selectedArchitectures.value = [...ALL_ARCHS.value]; };
const clearAll   = () => { selectedArchitectures.value = []; };
const toggleArch = (a) => {
  const i = selectedArchitectures.value.indexOf(a);
  if (i === -1) selectedArchitectures.value.push(a);
  else selectedArchitectures.value.splice(i, 1);
};
const isSelected = (a) => selectedArchitectures.value.includes(a);

const onKeydown      = (e) => { if (e.key === 'Escape') dropdownOpen.value = false; };
const onClickOutside = (e) => {
  const clickedTrigger = dropdownRef.value && dropdownRef.value.contains(e.target);
  const clickedPanel   = e.target.closest('.arch-panel-ds');
  if (!clickedTrigger && !clickedPanel) dropdownOpen.value = false;
};

async function loadArchitectures() {
  if (!props.apiBase) return;
  try {
    const res = await fetch(`${props.apiBase}/api/architectures`, {
      headers: { 'Authorization': `Bearer ${props.apiToken}` }
    });
    if (res.ok) {
      ALL_ARCHS.value = await res.json();
      const saved = lsGet('lmo:selectedArchs', []);
      if (saved.length === 0) {
        selectedArchitectures.value = [...ALL_ARCHS.value];
      } else {
        selectedArchitectures.value = saved.filter(a => ALL_ARCHS.value.includes(a));
      }
    }
  } catch (err) {
    console.error('Failed to load architectures:', err);
  }
}

watch(() => props.apiBase, (newVal) => {
  if (newVal) loadArchitectures();
});

onMounted(() => {
  document.addEventListener('mousedown', onClickOutside);
  document.addEventListener('keydown',   onKeydown);
  if (props.apiBase) loadArchitectures();
});
onUnmounted(() => {
  document.removeEventListener('mousedown', onClickOutside);
  document.removeEventListener('keydown',   onKeydown);
  clearInterval(stopwatchTimerRef);
  clearInterval(progressTimerRef);
});

const teleportStyle = ref({});
const triggerRef    = ref(null);

const updateDropdownPos = () => {
  if (!triggerRef.value) return;
  const r = triggerRef.value.getBoundingClientRect();
  teleportStyle.value = {
    position: 'fixed',
    top:  r.bottom + 6 + 'px',
    left: r.left + 'px',
    width: r.width + 'px',
    zIndex: 99999,
  };
};

const openDropdown = () => {
  updateDropdownPos();
  dropdownOpen.value = true;
};

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
  <div class="view-container-ds">
    <div class="view-header-ds">
      <h1 class="view-title-ds">Sorter</h1>
      <button class="info-icon-btn" @click="showInfo = true" title="What is this?">
        <HelpCircle :size="16" />
      </button>
    </div>

    <!-- Source Directory -->
    <div class="card-group-ds">
      <div class="card-header-ds">
        <span class="card-title-ds">Source Directory</span>
        <span class="card-hint-ds">Drag & drop folder here or click to browse</span>
      </div>
      <div class="input-row-ds">
        <input class="input-ds mono" type="text" readonly :value="sourceFolder" placeholder="Select source folder..."/>
        <button class="btn-ds secondary" @click="pickSource" :disabled="isProcessing">
          <FolderOpen :size="16" /> Browse
        </button>
        <button
            class="btn-ds icon-only"
            @click="openFolder(sourceFolder)"
            :disabled="!sourceFolder || isProcessing"
            title="Open folder in Explorer"
        >
          <ExternalLink :size="16" />
        </button>
      </div>
    </div>

    <!-- Target Directory -->
    <div class="card-group-ds">
      <div class="card-header-ds">
        <span class="card-title-ds">Target Directory</span>
        <span class="card-hint-ds">Drag & drop folder here or click to browse</span>
      </div>
      <div class="input-row-ds">
        <input class="input-ds mono" type="text" readonly :value="targetFolder" placeholder="Select target folder..."/>
        <button class="btn-ds secondary" @click="pickTarget" :disabled="isProcessing">
          <FolderOpen :size="16" /> Browse
        </button>
        <button
            class="btn-ds icon-only"
            @click="openFolder(targetFolder)"
            :disabled="!targetFolder || isProcessing"
            title="Open folder in Explorer"
        >
          <ExternalLink :size="16" />
        </button>
      </div>
    </div>

    <!-- Architectures Filter -->
    <div class="card-group-ds">
      <div class="card-header-ds">
        <span class="card-title-ds">Architectures</span>
        <span class="card-hint-ds" v-if="!allSelected && !noneSelected">
          {{ selectedArchitectures.length }}/{{ ALL_ARCHS.length }} selected
        </span>
      </div>
      <div class="arch-dropdown-ds" ref="dropdownRef">
        <button
            ref="triggerRef"
            class="input-ds arch-trigger-ds"
            :class="{ open: dropdownOpen, 'is-none': noneSelected }"
            @click="dropdownOpen ? dropdownOpen = false : openDropdown()"
            :disabled="isProcessing"
            type="button"
        >
          <span class="arch-summary">{{ selectionSummary }}</span>
          <span class="badge-ds accent" v-if="!allSelected && !noneSelected">
            {{ selectedArchitectures.length }}
          </span>
          <ChevronDown :size="12" class="arch-chevron-icon" :class="{ rotated: dropdownOpen }" />
        </button>
        <Teleport to="body">
          <div v-if="dropdownOpen" class="arch-panel-ds" :style="teleportStyle">
            <div class="arch-panel-header-ds">
              <input v-model="archSearch" class="input-ds arch-search-ds" placeholder="Search architectures..." autofocus/>
              <div class="arch-bulk-ds">
                <button class="btn-link-ds" @click="selectAll" :disabled="allSelected">All</button>
                <span>·</span>
                <button class="btn-link-ds" @click="clearAll" :disabled="noneSelected">None</button>
              </div>
            </div>
            <ul class="arch-list-ds">
              <li
                  v-for="arch in filteredArchs"
                  :key="arch"
                  class="arch-item-ds"
                  :class="{ selected: isSelected(arch) }"
                  @click="toggleArch(arch)"
              >
                <component :is="isSelected(arch) ? CheckSquare : Square" :size="14"
                           :class="isSelected(arch) ? 'arch-check-active' : 'arch-check-inactive'" />
                <span>{{ arch }}</span>
              </li>
              <li v-if="!filteredArchs.length" class="arch-empty-ds">No matches for "{{ archSearch }}"</li>
            </ul>
          </div>
        </Teleport>
      </div>

      <!-- Quick architecture pill preview -->
      <div class="arch-pills-ds" v-if="selectedArchitectures.length > 0 && selectedArchitectures.length <= 10">
        <span v-for="a in selectedArchitectures" :key="a" class="badge-ds accent">{{ a }}</span>
      </div>
    </div>

    <!-- Toggles Row -->
    <div class="options-row-ds">
      <label class="toggle-control-ds">
        <input type="checkbox" :checked="isRecursive" @change="emit('update:isRecursive', $event.target.checked)"
               :disabled="isProcessing" class="sr-only"/>
        <span class="toggle-track-ds" :class="{ checked: isRecursive }"><span class="toggle-thumb-ds"></span></span>
        <span class="toggle-label-text">Deep Scan (subfolders)</span>
      </label>
      <label class="toggle-control-ds">
        <input type="checkbox" :checked="isDryRun" @change="emit('update:isDryRun', $event.target.checked)"
               :disabled="isProcessing" class="sr-only"/>
        <span class="toggle-track-ds" :class="{ checked: isDryRun }"><span class="toggle-thumb-ds"></span></span>
        <span class="toggle-label-text">Dry Run (simulate)</span>
      </label>
    </div>

    <!-- Progress Section -->
    <transition name="progress-fade">
      <div v-if="progressVisible" class="progress-section-ds">
        <div class="progress-header-ds">
          <span class="progress-label-ds">
            <component :is="progressDone ? CheckCircle2 : Loader2" :size="14" :class="{ 'spin-icon': !progressDone }" />
            {{ progressDone ? 'Done' : (progressLabel || 'Organizing...') }}
          </span>
          <span class="stopwatch-ds" :class="{ 'stopwatch-done': progressDone }">
            <Timer :size="14" />
            {{ elapsedFormatted }}
          </span>
        </div>
        <div class="progress-track-ds">
          <div
              class="progress-fill-ds"
              :class="{ 'progress-done': progressDone, 'progress-active': isProcessing }"
              :style="{ width: progressPct.toFixed(1) + '%' }"
          ></div>
        </div>
      </div>
    </transition>

    <!-- Actions Row -->
    <div class="actions-row-ds">
      <button class="btn-ds cta full-width" @click="handleExecute" :disabled="isProcessing">
        <component :is="isProcessing ? Loader2 : ArrowUpDown" :size="16" :class="{ 'spin-icon': isProcessing }" />
        <span>{{ isProcessing ? 'Organizing...' : 'Start Organizing' }}</span>
      </button>
      <button
          v-if="isProcessing"
          class="btn-ds danger"
          @click="emit('cancel-operation')"
          :disabled="isCancelling"
      >
        <component :is="isCancelling ? Loader2 : XCircle" :size="16" :class="{ 'spin-icon': isCancelling }" />
        <span>{{ isCancelling ? 'Cancelling...' : 'Cancel' }}</span>
      </button>
    </div>

    <!-- Undo Option -->
    <div class="undo-row-ds" v-if="canUndo">
      <button
          class="btn-ds secondary full-width"
          @click="emit('undo')"
          :disabled="isProcessing"
          title="Move all files from the last real sort back to their original locations."
      >
        <Undo2 :size="16" />
        <span>Undo Last Sort</span>
      </button>
    </div>

    <InfoModal v-if="showInfo" title="Model Organizer Info" @close="showInfo = false">
      <p>This tool scans massive <code>.safetensors</code> libraries using zero-memory byte parsing.</p><br>
      <p>It automatically identifies model architectures by reading header metadata without loading the full file.</p><br>
      <p>Orphaned sidecar files (like preview images <code>.png</code>, <code>.json</code> metadata, and text files) are
        grouped with their parent model and moved together as an atomic unit to keep your library clean.</p><br>
      <p>Sorts models into corresponding architectural sub-folders</p>
    </InfoModal>

  </div>
</template>

<style scoped>
.view-container-ds {
  display: flex;
  flex-direction: column;
  gap: 18px;
  max-width: 720px;
  margin: 0 auto;
}

.view-header-ds {
  display: flex;
  align-items: center;
  gap: 12px;
}

.view-title-ds {
  margin: 0;
  font-size: var(--text-h2, 22px);
  font-weight: var(--weight-extrabold, 800);
  color: var(--color-text-primary);
  letter-spacing: var(--tracking-tight);
}

.info-icon-btn {
  background: none;
  border: none;
  color: var(--color-text-tertiary);
  cursor: pointer;
  padding: 4px;
  border-radius: 50%;
  transition: color var(--duration-fast);
  display: flex;
  align-items: center;
  justify-content: center;
}

.info-icon-btn:hover {
  color: var(--color-accent-primary);
}

.card-group-ds {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px;
  background: var(--color-surface-1);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.card-header-ds {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title-ds {
  font-size: var(--text-body-sm, 13px);
  font-weight: var(--weight-semibold, 600);
  color: var(--color-text-primary);
}

.card-hint-ds {
  font-size: var(--text-caption, 11px);
  color: var(--color-text-tertiary);
}

.input-row-ds {
  display: flex;
  gap: 8px;
  align-items: center;
}

.input-ds {
  width: 100%;
  padding: 10px 14px;
  background: var(--color-bg-canvas);
  border: 1px solid var(--color-border-default);
  border-radius: var(--radius-md);
  color: var(--color-text-primary);
  font-size: var(--text-body-sm, 13px);
  font-family: var(--font-sans);
  outline: none;
  transition: border-color var(--duration-fast);
}

.input-ds.mono {
  font-family: var(--font-mono);
  font-size: var(--text-mono, 13px);
}

.input-ds:focus {
  border-color: var(--color-accent-primary);
  box-shadow: var(--glow-primary);
}

.btn-ds {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 10px 18px;
  border-radius: var(--radius-md);
  font-size: var(--text-body-sm, 13px);
  font-weight: var(--weight-semibold, 600);
  font-family: var(--font-sans);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-standard);
  border: 1px solid transparent;
  outline: none;
  white-space: nowrap;
}

.btn-ds.full-width {
  width: 100%;
}

.btn-ds.cta {
  background: var(--gradient-brand);
  color: var(--color-text-on-accent);
  border: none;
  font-weight: var(--weight-bold);
}

.btn-ds.cta:hover:not(:disabled) {
  opacity: 0.92;
  box-shadow: var(--glow-primary);
}

.btn-ds.secondary {
  background: var(--color-surface-2);
  color: var(--color-text-primary);
  border-color: var(--color-border-subtle);
}

.btn-ds.secondary:hover:not(:disabled) {
  border-color: var(--color-border-strong);
  box-shadow: var(--glow-primary);
}

.btn-ds.danger {
  background: var(--color-danger-bg);
  color: var(--color-danger);
  border-color: rgba(242, 102, 91, 0.3);
}

.btn-ds.danger:hover:not(:disabled) {
  box-shadow: var(--glow-danger);
}

.btn-ds.icon-only {
  width: 38px;
  height: 38px;
  padding: 0;
  background: var(--color-surface-2);
  color: var(--color-text-secondary);
  border-color: var(--color-border-subtle);
}

.btn-ds.icon-only:hover:not(:disabled) {
  color: var(--color-accent-primary);
  border-color: var(--color-accent-primary);
}

.btn-ds:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.badge-ds {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: var(--radius-full);
  font-size: var(--text-caption, 11px);
  font-weight: var(--weight-semibold);
}

.badge-ds.accent {
  background: var(--color-accent-primary-bg);
  color: var(--color-accent-primary);
  border: 1px solid rgba(79, 216, 208, 0.25);
}

.arch-dropdown-ds {
  position: relative;
}

.arch-trigger-ds {
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
  text-align: left;
}

.arch-chevron-icon {
  font-size: 11px;
  color: var(--color-text-tertiary);
  transition: transform var(--duration-fast);
}

.arch-chevron-icon.rotated {
  transform: rotate(180deg);
}

.arch-panel-ds {
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-popover);
  display: flex;
  flex-direction: column;
  max-height: 280px;
  background: var(--color-surface-2);
  border: 1px solid var(--color-border-strong);
  overflow: hidden;
}

.arch-panel-header-ds {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  border-bottom: 1px solid var(--color-border-subtle);
}

.arch-search-ds {
  padding: 6px 10px;
  font-size: var(--text-body-sm, 13px);
}

.arch-bulk-ds {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-caption, 11px);
  color: var(--color-text-tertiary);
}

.btn-link-ds {
  background: none;
  border: none;
  color: var(--color-accent-primary);
  cursor: pointer;
  font-size: var(--text-caption, 11px);
  padding: 0;
}

.btn-link-ds:disabled {
  color: var(--color-text-disabled);
  cursor: not-allowed;
}

.arch-list-ds {
  list-style: none;
  margin: 0;
  padding: 4px 0;
  overflow-y: auto;
  flex: 1;
}

.arch-item-ds {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  cursor: pointer;
  font-size: var(--text-body-sm, 13px);
  color: var(--color-text-primary);
  transition: background var(--duration-fast);
}

.arch-item-ds:hover {
  background: var(--color-surface-3);
}

.arch-item-ds.selected {
  color: var(--color-accent-primary);
  font-weight: var(--weight-medium);
}

.arch-check-active {
  color: var(--color-accent-primary);
}

.arch-check-inactive {
  color: var(--color-text-disabled);
}

.arch-empty-ds {
  padding: 12px;
  font-size: var(--text-body-sm, 13px);
  color: var(--color-text-tertiary);
  font-style: italic;
  text-align: center;
}

.arch-pills-ds {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 4px;
}

.options-row-ds {
  display: flex;
  gap: 24px;
  padding: 4px 0;
}

.toggle-control-ds {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  user-select: none;
}

.toggle-track-ds {
  position: relative;
  width: 36px;
  height: 20px;
  border-radius: var(--radius-full);
  background: var(--color-surface-2);
  border: 1px solid var(--color-border-default);
  transition: background var(--duration-fast), border-color var(--duration-fast);
  flex-shrink: 0;
}

.toggle-track-ds.checked {
  background: var(--color-accent-primary);
  border-color: var(--color-accent-primary);
}

.toggle-thumb-ds {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--color-text-secondary);
  transition: transform var(--duration-fast) var(--ease-standard), background var(--duration-fast);
}

.toggle-track-ds.checked .toggle-thumb-ds {
  transform: translateX(16px);
  background: var(--color-text-on-accent);
}

.toggle-label-text {
  font-size: var(--text-body-sm, 13px);
  color: var(--color-text-secondary);
  font-weight: var(--weight-medium);
}

.progress-section-ds {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 4px 0;
}

.progress-header-ds {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--text-body-sm, 13px);
}

.progress-label-ds {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--color-text-secondary);
  font-weight: var(--weight-semibold);
}

.stopwatch-ds {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--color-text-tertiary);
  font-family: var(--font-mono);
  font-size: var(--text-mono, 13px);
}

.stopwatch-done {
  color: var(--color-success);
  font-weight: var(--weight-bold);
}

.progress-track-ds {
  width: 100%;
  height: 6px;
  background: var(--color-surface-2);
  border-radius: var(--radius-full);
  overflow: hidden;
  border: 1px solid var(--color-border-subtle);
}

.progress-fill-ds {
  height: 100%;
  border-radius: var(--radius-full);
  background: var(--gradient-brand);
  transition: width 0.3s ease-out;
}

.progress-fill-ds.progress-done {
  background: var(--color-success);
}

.actions-row-ds, .undo-row-ds {
  display: flex;
  gap: 12px;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}
</style>
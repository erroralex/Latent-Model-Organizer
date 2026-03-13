<script setup>
/**
 * SORTERVIEW.VUE
 *
 * The primary interface for orchestrating the model organization workflow.
 * It manages the selection of source and target directories, architectural filtering,
 * and the execution of the sorting logic.
 *
 * KEY FEATURES:
 * - Directory Management: Uses Electron-native dialogs for reliable folder selection.
 * - Architecture Filtering: Features a searchable, multi-select dropdown to target specific model types.
 * - Execution Modes: Toggles between standard organization, deep recursive scanning, and non-destructive dry runs.
 * - Undo Integration: Provides a direct interface to trigger the restoration of the previous filesystem state.
 */
import {ref, computed, watch, onMounted, onUnmounted} from 'vue';
import InfoModal from '../components/InfoModal.vue';

const props = defineProps({
  isProcessing: {type: Boolean, default: false},
  isRecursive: {type: Boolean, default: true},
  isDryRun: {type: Boolean, default: false},
  canUndo: {type: Boolean, default: false},
});

const emit = defineEmits(['start-organize', 'update:isRecursive', 'update:isDryRun', 'open-info', 'undo']);

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
  } catch {
  }
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
const selectedArchitectures = ref(lsGet('lmo:selectedArchs', [...ALL_ARCHS]));

watch(sourceFolder, v => lsSet('lmo:sourceFolder', v));
watch(targetFolder, v => lsSet('lmo:targetFolder', v));
watch(selectedArchitectures, v => lsSet('lmo:selectedArchs', v), {deep: true});

const showInfo = ref(false);
const dropdownOpen = ref(false);
const archSearch = ref('');
const dropdownRef = ref(null);

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
const allSelected = computed(() => selectedArchitectures.value.length === ALL_ARCHS.length);
const noneSelected = computed(() => selectedArchitectures.value.length === 0);

const selectAll = () => {
  selectedArchitectures.value = [...ALL_ARCHS];
};
const clearAll = () => {
  selectedArchitectures.value = [];
};
const toggleArch = (a) => {
  const i = selectedArchitectures.value.indexOf(a);
  if (i === -1) selectedArchitectures.value.push(a); else selectedArchitectures.value.splice(i, 1);
};
const isSelected = (a) => selectedArchitectures.value.includes(a);

const onKeydown = (e) => {
  if (e.key === 'Escape') dropdownOpen.value = false;
};
const onClickOutside = (e) => {
  if (dropdownRef.value && !dropdownRef.value.contains(e.target)) dropdownOpen.value = false;
};

onMounted(() => {
  document.addEventListener('mousedown', onClickOutside);
  document.addEventListener('keydown', onKeydown);
});
onUnmounted(() => {
  document.removeEventListener('mousedown', onClickOutside);
  document.removeEventListener('keydown', onKeydown);
});

const pickSource = async () => {
  const f = await window.electronAPI?.selectFolder();
  if (f) sourceFolder.value = f;
};
const pickTarget = async () => {
  const f = await window.electronAPI?.selectFolder();
  if (f) targetFolder.value = f;
};

const handleExecute = () => {
  emit('start-organize', {
    sourceDirectory: sourceFolder.value,
    targetDirectory: targetFolder.value,
    allowedArchitectures: selectedArchitectures.value
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
        <input class="glass-input" type="text" readonly :value="sourceFolder" placeholder="Select source folder…"/>
        <button class="secondary-btn" @click="pickSource" :disabled="isProcessing"><i class="pi pi-folder-open"></i>
          Browse
        </button>
      </div>
    </div>

    <div class="form-group">
      <label class="form-label">Target Directory <span class="label-hint">organized output</span></label>
      <div class="input-row">
        <input class="glass-input" type="text" readonly :value="targetFolder" placeholder="Select target folder…"/>
        <button class="secondary-btn" @click="pickTarget" :disabled="isProcessing"><i class="pi pi-folder-open"></i>
          Browse
        </button>
      </div>
    </div>

    <div class="form-group">
      <label class="form-label">
        Architectures
        <span class="label-hint" v-if="!allSelected && !noneSelected">{{
            selectedArchitectures.length
          }}/{{ ALL_ARCHS.length }}</span>
      </label>
      <div class="arch-dropdown" ref="dropdownRef">
        <button class="arch-trigger glass-input" :class="{ open: dropdownOpen, 'is-none': noneSelected }"
                @click="dropdownOpen = !dropdownOpen" :disabled="isProcessing" type="button">
          <span class="arch-trigger-text">{{ selectionSummary }}</span>
          <span class="arch-trigger-badge" v-if="!allSelected && !noneSelected">{{
              selectedArchitectures.length
            }}</span>
          <svg class="arch-chevron" :class="{ rotated: dropdownOpen }" viewBox="0 0 24 24">
            <polyline points="6 9 12 15 18 9"/>
          </svg>
        </button>
        <div v-if="dropdownOpen" class="arch-panel glass-panel">
          <div class="arch-panel-header">
            <input v-model="archSearch" class="arch-search glass-input" placeholder="Search…" autofocus/>
            <div class="arch-bulk-actions">
              <button class="link-btn" @click="selectAll" :disabled="allSelected">All</button>
              <span class="bulk-divider">·</span>
              <button class="link-btn" @click="clearAll" :disabled="noneSelected">None</button>
            </div>
          </div>
          <ul class="arch-list">
            <li v-for="arch in filteredArchs" :key="arch" class="arch-item" :class="{ selected: isSelected(arch) }"
                @click="toggleArch(arch)">
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

    <button class="primary-btn" @click="handleExecute" :disabled="isProcessing">
      <i class="pi" :class="isProcessing ? 'pi-spin pi-spinner' : 'pi-sort-alt'"></i>
      {{ isProcessing ? 'Organizing…' : 'Start Organization' }}
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
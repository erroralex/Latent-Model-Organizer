<script setup>
/**
 * FETCHERVIEW.VUE
 *
 * Provides the user interface for the metadata enrichment workflow.
 * This component focuses on scanning existing model libraries to retrieve
 * missing metadata and preview images from external sources.
 *
 * FUNCTIONAL SCOPE:
 * - Target Selection: Allows the user to specify a directory for metadata scanning.
 * - Process Initiation: Triggers the backend fetch operation using SHA-256 hashing and API lookups.
 * - In-Place Updates: Downloads sidecar files (.civitai.info and preview images) directly next to the model files.
 * - Visual Feedback: Displays informational boxes explaining the non-destructive nature of the fetch process.
 *
 * @see App.vue
 * @see CivitaiApiClient.java
 */
import { ref, watch } from 'vue';
import InfoModal from '../components/InfoModal.vue';

const props = defineProps({
  isProcessing: { type: Boolean, default: false },
  isCancelling: { type: Boolean, default: false },
  isRecursive: { type: Boolean, default: true },
  isDryRun: { type: Boolean, default: false },
});

const emit = defineEmits(['start-fetch', 'cancel-operation', 'update:isRecursive', 'update:isDryRun', 'open-info']);

const lsGet = (k, fb) => { try { const v = localStorage.getItem(k); return v !== null ? JSON.parse(v) : fb; } catch { return fb; } };
const lsSet = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} };

const targetFolder = ref(lsGet('lmo:targetFolder', ''));
const showInfo = ref(false);

watch(targetFolder, v => lsSet('lmo:targetFolder', v));

const pickTarget = async () => { const f = await window.electronAPI?.selectFolder(); if (f) targetFolder.value = f; };

const handleExecute = () => {
  emit('start-fetch', {
    targetDirectory: targetFolder.value
  });
};
</script>

<template>
  <div class="tab-content">

    <div class="section-header">
      <h2 class="section-title">Metadata Fetcher</h2>
      <button class="info-btn" @click="showInfo = true" title="What is this?">
        <i class="pi pi-question-circle"></i>
      </button>
    </div>

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

    <div class="options-row">
      <label class="toggle-label">
        <input type="checkbox" :checked="isRecursive" @change="emit('update:isRecursive', $event.target.checked)" :disabled="isProcessing" class="sr-only" />
        <span class="toggle-track" :class="{ checked: isRecursive }"><span class="toggle-thumb"></span></span>
        <span>Deep Scan</span>
        <span class="label-hint">subfolders</span>
      </label>
      <label class="toggle-label">
        <input type="checkbox" :checked="isDryRun" @change="emit('update:isDryRun', $event.target.checked)" :disabled="isProcessing" class="sr-only" />
        <span class="toggle-track" :class="{ checked: isDryRun }"><span class="toggle-thumb"></span></span>
        <span>Dry Run</span>
        <span class="label-hint">simulate only</span>
      </label>
    </div>

    <div class="button-group">
      <button class="primary-btn" @click="handleExecute" :disabled="isProcessing">
        <i class="pi" :class="isProcessing ? 'pi-spin pi-spinner' : 'pi-cloud-download'"></i>
        {{ isProcessing ? 'Fetching…' : 'Start Fetching Metadata' }}
      </button>
      <button
          v-if="isProcessing"
          class="secondary-btn cancel-btn"
          @click="emit('cancel-operation')"
          :disabled="isCancelling"
      >
        <i class="pi" :class="isCancelling ? 'pi-spin pi-spinner' : 'pi-times-circle'"></i>
        {{ isCancelling ? 'Cancelling...' : 'Cancel' }}
      </button>
    </div>

    <InfoModal v-if="showInfo" title="Metadata Fetcher Info" @close="showInfo = false">
      <p>This tool performs a smart scan of your model library to find files missing Civitai metadata.</p><br>
      <p>It calculates a highly optimized <strong>SHA256 hash</strong> of the model file, queries the Civitai API, and downloads the official metadata JSON and preview image.</p><br>
      <p>Files are updated in-place. No models are moved during this process.</p>
    </InfoModal>

  </div>
</template>
<style scoped>
.button-group {
  display: flex;
  gap: 12px;
  align-items: stretch;
}
.button-group .primary-btn {
  flex-grow: 1;
}
.cancel-btn {
  border-color: var(--status-error-faded);
  color: var(--status-error);
}
.cancel-btn:hover:not(:disabled) {
  background: var(--status-error-faded);
  border-color: var(--status-error);
  color: #fff;
}
</style>

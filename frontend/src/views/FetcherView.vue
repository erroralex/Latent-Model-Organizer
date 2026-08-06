<script setup>
/**
 * FetcherView.vue
 *
 * Metadata enrichment view for Latent Model Organizer aligned with the Latent Design System.
 */
import { ref, watch } from 'vue';
import InfoModal from '../components/InfoModal.vue';
import { HelpCircle, FolderOpen, Info, CloudDownload, XCircle, Tags, Loader2 } from 'lucide-vue-next';
import LBadge from '@/components/ds/LBadge.vue';
import LSwitch from '@/components/ds/LSwitch.vue';

const props = defineProps({
  isProcessing: { type: Boolean, default: false },
  isCancelling: { type: Boolean, default: false },
  isRecursive: { type: Boolean, default: true },
  isDryRun: { type: Boolean, default: false },
});

const emit = defineEmits(['start-fetch', 'start-backfill', 'cancel-operation', 'update:isRecursive', 'update:isDryRun']);

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

const handleBackfill = () => {
  emit('start-backfill', {
    targetDirectory: targetFolder.value
  });
};
</script>

<template>
  <div class="view-container-ds">
    <div class="view-header-ds">
      <h1 class="view-title-ds">Fetcher</h1>
      <button class="info-icon-btn" @click="showInfo = true" title="What is this?">
        <HelpCircle :size="16" />
      </button>
    </div>

    <!-- Scan Folder Card -->
    <div class="card-group-ds">
      <div class="card-header-ds">
        <span class="card-title-ds">Scan Folder</span>
        <span class="card-hint-ds">Drag & drop folder here or click to browse</span>
      </div>
      <div class="input-row-ds">
        <input class="input-ds mono" type="text" readonly :value="targetFolder" placeholder="Select folder to scan…" />
        <button class="btn-ds secondary" @click="pickTarget" :disabled="isProcessing">
          <FolderOpen :size="16" /> Browse
        </button>
      </div>
      <p class="card-helper-ds">Recursively scans for models missing Civitai metadata and preview images.</p>
    </div>

    <!-- Info Banner -->
    <div class="info-banner-ds">
      <Info :size="18" class="info-banner-icon" />
      <div class="info-banner-text">
        <p>Calculates SHA-256 hashes for all models without a <code>.civitai.info</code> sidecar, queries the Civitai API, and downloads metadata + preview images in-place.</p>
        <p><strong>No files will be moved.</strong></p>
      </div>
    </div>

    <!-- Toggles Row -->
    <div class="options-row-ds">
      <LSwitch
          :model-value="isRecursive"
          :disabled="isProcessing"
          label="Deep Scan (subfolders)"
          @update:model-value="v => emit('update:isRecursive', v)"
      />
      <LSwitch
          :model-value="isDryRun"
          :disabled="isProcessing"
          label="Dry Run (simulate)"
          @update:model-value="v => emit('update:isDryRun', v)"
      />
    </div>

    <!-- Main Actions -->
    <div class="actions-row-ds">
      <button class="btn-ds cta full-width" @click="handleExecute" :disabled="isProcessing">
        <component :is="isProcessing ? Loader2 : CloudDownload" :size="16" :class="{ 'spin-icon': isProcessing }" />
        <span>{{ isProcessing ? 'Fetching…' : 'Start Fetching Metadata' }}</span>
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

    <!-- Trigger Words & Descriptions Backfill -->
    <div class="card-group-ds backfill-section-ds">
      <div class="card-header-ds">
        <span class="card-title-ds">Trigger Words &amp; Descriptions</span>
        <LBadge variant="outline">Offline</LBadge>
      </div>
      <p class="card-helper-ds">
        Forge and A1111 read <code>&lt;model&gt;.json</code> rather than <code>.civitai.info</code>.
        This writes trigger words and descriptions from existing sidecars into that file for the LoRA card's <strong>Activation text</strong> and <strong>Description</strong> boxes. Fast, offline, and preserves custom edits.
      </p>
      <button class="btn-ds secondary full-width" @click="handleBackfill" :disabled="isProcessing">
        <component :is="isProcessing ? Loader2 : Tags" :size="16" :class="{ 'spin-icon': isProcessing }" />
        <span>Backfill Trigger Words &amp; Descriptions</span>
      </button>
    </div>

    <InfoModal v-if="showInfo" title="Metadata Fetcher Info" @close="showInfo = false">
      <p>This tool performs a smart scan of your model library to find files missing Civitai metadata.</p><br>
      <p>It calculates a highly optimized <strong>SHA256 hash</strong> of the model file, queries the Civitai API, and downloads the official metadata JSON and preview image.</p><br>
      <p>Files are updated in-place. No models are moved during this process.</p><br>
      <p><strong>Trigger Words &amp; Descriptions</strong> is a separate, offline pass. Stable Diffusion front-ends
        never read <code>.civitai.info</code> sidecars — that format belongs to the Civitai Helper extension. They
        read <code>&lt;model&gt;.json</code> instead. The backfill copies the <code>trainedWords</code> and model
        description already stored in your sidecars into that file, so they appear in each LoRA's
        <strong>Activation text</strong> and <strong>Description</strong> boxes.</p><br>
      <p>Civitai serves descriptions as HTML, which the WebUI escapes by default, so they are converted to plain
        text first. The model description is preferred; the version's release note is used only when the model
        has none.</p><br>
      <p>Existing metadata is merged, not replaced: notes, preferred weight, and any field you filled in
        yourself are left untouched.</p>
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

.card-helper-ds {
  font-size: var(--text-body-sm, 13px);
  color: var(--color-text-tertiary);
  line-height: var(--text-body-lh, 1.55);
  margin: 0;
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

.btn-ds:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.info-banner-ds {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  background: var(--color-accent-primary-bg);
  border: 1px solid rgba(79, 216, 208, 0.2);
  border-radius: var(--radius-md);
  padding: 12px 16px;
  font-size: var(--text-body-sm, 13px);
  color: var(--color-text-secondary);
}

.info-banner-icon {
  color: var(--color-accent-primary);
  font-size: 1.1rem;
  margin-top: 2px;
  flex-shrink: 0;
}

.info-banner-text p {
  margin: 2px 0;
}

.options-row-ds {
  display: flex;
  gap: 24px;
  padding: 4px 0;
}

.actions-row-ds {
  display: flex;
  gap: 12px;
}

.backfill-section-ds {
  margin-top: 8px;
}
</style>

<script setup>
/**
 * Summarymodal.vue
 *
 * Summary reporting dialog for Latent Model Organizer aligned with the Latent Design System.
 */
import { computed } from 'vue';
import { Eye, CheckCircle2, BarChart3, Inbox, AlertTriangle, XCircle, X } from 'lucide-vue-next';

const props = defineProps({
  report: { type: Object, required: true },
  isDryRun: { type: Boolean, default: false },
});
const emit = defineEmits(['close']);

const title = computed(() => props.isDryRun ? 'Dry Run Summary' : 'Operation Complete');

const sortedSummary = computed(() => {
  if (!props.report.summary) return [];
  return Object.entries(props.report.summary).sort((a, b) => b[1] - a[1]);
});

const hasErrors = computed(() => props.report.errors?.length > 0);
const errorCount = computed(() => props.report.errors?.length ?? 0);

const fmtNum = (n) => (n ?? 0).toLocaleString();
</script>

<template>
  <div class="modal-scrim-ds" @click.self="emit('close')">
    <div class="modal-box-ds" role="dialog" aria-modal="true">

      <div class="modal-header-ds">
        <div class="modal-title-group-ds">
          <component :is="isDryRun ? Eye : CheckCircle2" :size="18"
             :style="{ color: isDryRun ? 'var(--color-warning)' : 'var(--color-success)' }" />
          <h2 class="modal-title-ds">{{ title }}</h2>
          <span v-if="isDryRun" class="badge-ds warning">DRY RUN</span>
        </div>
        <button class="win-btn-ds" @click="emit('close')" title="Close">
          <span>✕</span>
        </button>
      </div>

      <div class="modal-body-ds">

        <div class="stats-grid-ds">
          <div class="stat-card-ds">
            <span class="stat-label-ds">Groups Processed</span>
            <span class="stat-number-ds">{{ fmtNum(report.totalProcessed) }}</span>
          </div>
          <div class="stat-card-ds" :class="{ 'stat-warn': (report.totalUncategorized ?? 0) > 0 }">
            <span class="stat-label-ds">Uncategorized</span>
            <span class="stat-number-ds">{{ fmtNum(report.totalUncategorized) }}</span>
          </div>
          <div class="stat-card-ds" :class="{ 'stat-danger': hasErrors }">
            <span class="stat-label-ds">Errors</span>
            <span class="stat-number-ds">{{ fmtNum(errorCount) }}</span>
          </div>
        </div>

        <div v-if="sortedSummary.length > 0" class="modal-section-ds">
          <h3 class="modal-section-title-ds">
            <BarChart3 :size="14" /> Breakdown by Architecture
          </h3>
          <ul class="summary-list-ds">
            <li v-for="[arch, count] in sortedSummary" :key="arch" class="summary-row-ds">
              <span class="summary-arch-ds">{{ arch }}</span>
              <div class="summary-bar-wrap-ds">
                <div class="summary-bar-ds"
                     :style="{ width: (count / report.totalProcessed * 100).toFixed(1) + '%' }"></div>
              </div>
              <span class="summary-count-ds">{{ fmtNum(count) }}</span>
            </li>
          </ul>
        </div>
        <div v-else class="empty-state-ds">
          <Inbox :size="20" />
          <p>No items were categorized.</p>
        </div>

        <div v-if="hasErrors" class="modal-section-ds error-section-ds">
          <h3 class="modal-section-title-ds error-title-ds">
            <AlertTriangle :size="14" /> Errors ({{ errorCount }})
          </h3>
          <ul class="error-list-ds">
            <li v-for="(err, i) in report.errors" :key="i" class="error-item-ds">
              <XCircle :size="14" class="error-dot-ds" />{{ err }}
            </li>
          </ul>
        </div>

      </div>

      <div class="modal-footer-ds">
        <button class="btn-ds secondary" @click="emit('close')">
          <X :size="16" /> Close
        </button>
      </div>

    </div>
  </div>
</template>

<style scoped>
.modal-scrim-ds {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: var(--color-surface-overlay);
  backdrop-filter: var(--blur-glass);
}

.modal-box-ds {
  width: 100%;
  max-width: 520px;
  max-height: 82vh;
  background: var(--color-surface-1);
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-panel);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.modal-header-ds {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--color-border-subtle);
  background: var(--color-surface-2);
}

.modal-title-group-ds {
  display: flex;
  align-items: center;
  gap: 10px;
}

.modal-title-ds {
  margin: 0;
  font-size: var(--text-h3, 17px);
  font-weight: var(--weight-bold);
  color: var(--color-text-primary);
}

.modal-body-ds {
  padding: 20px;
  overflow-y: auto;
  flex: 1;
}

.stats-grid-ds {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.stat-card-ds {
  background: var(--color-surface-2);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  padding: 12px;
  text-align: center;
}

.stat-card-ds.stat-warn {
  border-color: rgba(245, 184, 78, 0.4);
}

.stat-card-ds.stat-danger {
  border-color: rgba(242, 102, 91, 0.4);
}

.stat-label-ds {
  display: block;
  font-size: var(--text-caption, 11px);
  text-transform: uppercase;
  letter-spacing: var(--tracking-caps);
  color: var(--color-text-tertiary);
  margin-bottom: 4px;
}

.stat-number-ds {
  font-size: 1.4rem;
  font-weight: var(--weight-extrabold);
  color: var(--color-text-primary);
  font-family: var(--font-mono);
}

.stat-card-ds.stat-warn .stat-number-ds {
  color: var(--color-warning);
}

.stat-card-ds.stat-danger .stat-number-ds {
  color: var(--color-danger);
}

.modal-section-ds {
  margin-top: 16px;
}

.modal-section-title-ds {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 10px;
  font-size: var(--text-caption, 11px);
  font-weight: var(--weight-bold);
  text-transform: uppercase;
  letter-spacing: var(--tracking-caps);
  color: var(--color-text-secondary);
}

.summary-list-ds {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.summary-row-ds {
  display: flex;
  align-items: center;
  gap: 10px;
}

.summary-arch-ds {
  width: 140px;
  font-size: var(--text-body-sm, 13px);
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.summary-bar-wrap-ds {
  flex: 1;
  height: 6px;
  background: var(--color-surface-2);
  border-radius: var(--radius-full);
  overflow: hidden;
}

.summary-bar-ds {
  height: 100%;
  background: var(--gradient-brand);
  border-radius: var(--radius-full);
}

.summary-count-ds {
  width: 36px;
  text-align: right;
  font-size: var(--text-mono, 13px);
  font-weight: var(--weight-bold);
  color: var(--color-accent-primary);
  font-family: var(--font-mono);
}

.empty-state-ds {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 24px 0;
  color: var(--color-text-tertiary);
}

.error-list-ds {
  list-style: none;
  padding: 0;
  margin: 0;
  max-height: 120px;
  overflow-y: auto;
}

.error-item-ds {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: var(--text-body-sm, 13px);
  color: var(--color-danger);
  padding: 3px 0;
}

.modal-footer-ds {
  display: flex;
  justify-content: flex-end;
  padding: 12px 20px;
  border-top: 1px solid var(--color-border-subtle);
  background: var(--color-surface-2);
}

.badge-ds.warning {
  background: var(--color-warning-bg);
  color: var(--color-warning);
  border: 1px solid rgba(245, 184, 78, 0.3);
  font-size: var(--text-caption, 11px);
  padding: 2px 8px;
  border-radius: var(--radius-full);
}

.btn-ds {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 8px 16px;
  border-radius: var(--radius-md);
  font-size: var(--text-body-sm, 13px);
  font-weight: var(--weight-semibold);
  font-family: var(--font-sans);
  cursor: pointer;
  border: 1px solid transparent;
}

.btn-ds.secondary {
  background: var(--color-surface-3);
  color: var(--color-text-primary);
  border-color: var(--color-border-subtle);
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
}

.win-btn-ds:hover {
  background: var(--color-surface-3);
  color: var(--color-text-primary);
}
</style>

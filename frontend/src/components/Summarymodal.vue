<script setup>
/**
 * SUMMARYMODAL.VUE
 *
 * A comprehensive reporting component that visualizes the results of a backend operation.
 * It parses the OperationReport payload to provide high-level metrics,
 * architectural breakdowns, and error diagnostics.
 *
 * REPORTING FEATURES:
 * - Key Metrics: Displays total processed items, uncategorized counts, and error tallies in a responsive grid.
 * - Architectural Breakdown: Sorts and visualizes processed models by architecture using relative percentage bars.
 * - Error Diagnostics: Provides a dedicated, scrollable area for reviewing specific operational failures.
 * - Process Context: Includes visual badges and title adjustments to distinguish between "Dry Run" simulations and actual filesystem changes.
 *
 * @see OperationReport.java
 * @see SorterView.vue
 * @see FetcherView.vue
 */
import {computed} from 'vue';

const props = defineProps({
  report: {type: Object, required: true},
  isDryRun: {type: Boolean, default: false},
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
  <div class="modal-overlay" @click.self="emit('close')">
    <div class="glass-panel modal-box" role="dialog" aria-modal="true">

      <div class="modal-header">
        <div class="modal-title-group">
          <i class="pi" :class="isDryRun ? 'pi-eye' : 'pi-check-circle'"
             :style="{ color: isDryRun ? 'var(--status-warning)' : 'var(--status-success)' }"></i>
          <h2 class="modal-title">{{ title }}</h2>
          <span v-if="isDryRun" class="dry-run-badge">DRY RUN</span>
        </div>
        <button class="nav-btn icon-only" @click="emit('close')" title="Close">
          <i class="pi pi-times"></i>
        </button>
      </div>

      <div class="modal-body custom-scrollbar">

        <div class="stats-grid">
          <div class="stat-card">
            <span class="stat-label">Groups Processed</span>
            <span class="stat-number">{{ fmtNum(report.totalProcessed) }}</span>
          </div>
          <div class="stat-card" :class="{ 'stat-warn': (report.totalUncategorized ?? 0) > 0 }">
            <span class="stat-label">Uncategorized</span>
            <span class="stat-number">{{ fmtNum(report.totalUncategorized) }}</span>
          </div>
          <div class="stat-card" :class="{ 'stat-danger': hasErrors }">
            <span class="stat-label">Errors</span>
            <span class="stat-number">{{ fmtNum(errorCount) }}</span>
          </div>
        </div>

        <div v-if="sortedSummary.length > 0" class="section">
          <h3 class="section-title">
            <i class="pi pi-chart-bar"></i> Breakdown by Architecture
          </h3>
          <ul class="summary-list">
            <li v-for="[arch, count] in sortedSummary" :key="arch" class="summary-row">
              <span class="summary-arch">{{ arch }}</span>
              <div class="summary-bar-wrap">
                <div class="summary-bar"
                     :style="{ width: (count / report.totalProcessed * 100).toFixed(1) + '%' }"></div>
              </div>
              <span class="summary-count">{{ fmtNum(count) }}</span>
            </li>
          </ul>
        </div>
        <div v-else class="empty-state">
          <i class="pi pi-inbox"></i>
          <p>No items were categorized.</p>
        </div>

        <div v-if="hasErrors" class="section error-section">
          <h3 class="section-title error-title">
            <i class="pi pi-exclamation-triangle"></i> Errors ({{ errorCount }})
          </h3>
          <ul class="error-list custom-scrollbar">
            <li v-for="(err, i) in report.errors" :key="i" class="error-item">
              <i class="pi pi-times-circle error-dot"></i>{{ err }}
            </li>
          </ul>
        </div>

      </div>

      <div class="modal-footer">
        <button class="secondary-btn" @click="emit('close')">
          <i class="pi pi-times"></i> Close
        </button>
      </div>

    </div>
  </div>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(0, 0, 0, 0.7);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
}

.modal-box {
  width: 100%;
  max-width: 540px;
  max-height: 82vh;
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  overflow: hidden;
  padding: 0;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 20px 14px;
  border-bottom: 1px solid var(--border-input);
  flex-shrink: 0;
}

.modal-title-group {
  display: flex;
  align-items: center;
  gap: 10px;
}

.modal-title {
  margin: 0;
  font-size: 1.1rem;
  font-weight: 700;
  color: var(--text-primary);
}

.dry-run-badge {
  font-size: 0.65rem;
  font-weight: 800;
  letter-spacing: 0.08em;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(234, 179, 8, 0.15);
  color: var(--status-warning);
  border: 1px solid rgba(234, 179, 8, 0.35);
}

.modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  min-height: 0;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 24px;
}

.stat-card {
  background: var(--bg-input);
  border: 1px solid var(--border-input);
  border-radius: 8px;
  padding: 14px 12px;
  text-align: center;
}

.stat-card.stat-warn {
  border-color: rgba(234, 179, 8, 0.4);
}

.stat-card.stat-danger {
  border-color: rgba(255, 77, 77, 0.4);
}

.stat-label {
  display: block;
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--text-secondary);
  margin-bottom: 6px;
}

.stat-number {
  font-size: 1.6rem;
  font-weight: 800;
  color: var(--text-primary);
}

.stat-card.stat-warn .stat-number {
  color: var(--status-warning);
}

.stat-card.stat-danger .stat-number {
  color: var(--status-danger);
}

.section {
  margin-bottom: 20px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 12px;
  font-size: 0.8rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--text-secondary);
}

.error-title {
  color: var(--status-danger);
}

.summary-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.summary-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.summary-arch {
  width: 160px;
  flex-shrink: 0;
  font-size: 0.83rem;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.summary-bar-wrap {
  flex: 1;
  height: 6px;
  background: var(--bg-input);
  border-radius: 3px;
  overflow: hidden;
}

.summary-bar {
  height: 100%;
  background: var(--grad-hover);
  border-radius: 3px;
  min-width: 2px;
  transition: width 0.4s ease;
}

.summary-count {
  width: 36px;
  text-align: right;
  flex-shrink: 0;
  font-size: 0.83rem;
  font-weight: 700;
  color: var(--accent-primary);
  font-variant-numeric: tabular-nums;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 32px 0;
  color: var(--text-muted);
}

.empty-state .pi {
  font-size: 2rem;
  opacity: 0.4;
}

.empty-state p {
  margin: 0;
  font-size: 0.9rem;
  font-style: italic;
}

.error-section {
  border-top: 1px solid var(--border-input);
  padding-top: 16px;
}

.error-list {
  list-style: none;
  padding: 0;
  margin: 0;
  max-height: 140px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.error-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 0.8rem;
  color: var(--status-danger);
  padding: 4px 0;
  line-height: 1.4;
}

.error-dot {
  flex-shrink: 0;
  margin-top: 2px;
}

.modal-footer {
  padding: 14px 20px;
  border-top: 1px solid var(--border-input);
  display: flex;
  justify-content: flex-end;
  flex-shrink: 0;
}
</style>

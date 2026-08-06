# ds/ Component Layer & Backend StatusPill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Latent Model Organizer a `ds/` component layer whose centrepiece is a titlebar StatusPill reporting live backend connection state, and retire the duplicated button/badge/switch CSS spread across eight components.

**Architecture:** A `useBackendStatus` composable owns a 10-second poll of `GET /api/version` and exposes a `status` ref; `StatusPill.vue` is purely presentational and receives that status as a prop. Two further primitives (`LBadge`, `LSwitch`) replace hand-rolled markup that is currently re-declared in every component that uses it. Vitest is introduced here because a composable built on timers and `fetch` is the ideal first unit-test case.

**Tech Stack:** Vue 3.5 (`<script setup>`), Vite 7, Vitest + @vue/test-utils + jsdom, Latent Design System CSS tokens.

**Spec:** `docs/superpowers/specs/2026-08-06-ds-layer-statuspill-design.md`

## Global Constraints

- **Vue style:** Composition API with `<script setup>` only. No Options API, no `this`, no mixins.
- **Props down, emit up.** Never mutate a prop. Derived state is `computed`, never a watcher copying values.
- **No raw hex colours.** Every colour comes from a design-system token via `var()`. Do **not** copy Latent-Library's `var(--token, #hexfallback)` pattern — LMO's tokens are always defined and each fallback adds a `npm run lint:ds` warning.
- **Ported primitives keep Latent-Library's internal class names** (`status-pill`, `l-badge`, `l-switch-track`) so the files stay diffable against the sibling repo. Styles are `<style scoped>`, so these never collide with LMO's `-ds` app classes.
- **Accessibility is not optional:** every interactive element is keyboard-reachable with visible focus; every input has a label; status changes are announced.
- **Tests must be deterministic:** no sleeps, no order dependence. Use `vi.useFakeTimers()`.
- **Never weaken or delete a test** to make a change pass.
- **Commits:** imperative subject ≤72 chars. No AI attribution anywhere — no `Co-Authored-By` naming an AI, no "Generated with" lines.
- **Verification before claiming done:** run the command and show output. Every task ends green on `npm run test`, `npm run lint:ds`, and `npm run build`.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `frontend/vite.config.js` | Add Vitest `test` block (jsdom env) | 1 |
| `frontend/package.json` | Add vitest/@vue/test-utils/jsdom devDeps + `test` script | 1 |
| `frontend/src/components/ds/StatusPill.vue` | Presentational connection indicator | 1 |
| `frontend/src/components/ds/__tests__/StatusPill.spec.js` | Pill rendering per status | 1 |
| `frontend/src/composables/useBackendStatus.js` | Poll `/api/version`, own the state machine | 2 |
| `frontend/src/composables/__tests__/useBackendStatus.spec.js` | State machine + cleanup | 2 |
| `frontend/src/App.vue` | Mount pill in titlebar; drop FATAL branch | 3 |
| `frontend/src/components/ds/LBadge.vue` | Badge primitive incl. spec's `outline` variant | 4 |
| `frontend/src/components/ds/__tests__/LBadge.spec.js` | All six variants | 4 |
| `frontend/src/components/ds/LSwitch.vue` | Switch primitive on a native checkbox | 5 |
| `frontend/src/components/ds/__tests__/LSwitch.spec.js` | Toggle behaviour + a11y | 5 |

Call sites modified in Task 4: `ConsoleWindow.vue`, `Settingsmodal.vue`, `Summarymodal.vue`, `FetcherView.vue`, `SorterView.vue`.
Call sites modified in Task 5: `Settingsmodal.vue`, `Sidebar.vue`, `FetcherView.vue`, `SorterView.vue`.

---

### Task 1: Vitest harness + StatusPill

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/vite.config.js:18-39`
- Create: `frontend/src/components/ds/StatusPill.vue`
- Test: `frontend/src/components/ds/__tests__/StatusPill.spec.js`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `StatusPill.vue` default export. Props: `status: 'online'|'starting'|'offline'` (default `'starting'`), `label: String` (default `'Backend'`). Renders root element with classes `status-pill` + the status value, `role="status"`, `aria-live="polite"`.

- [ ] **Step 1: Install test dependencies**

```bash
cd frontend
npm install -D vitest@^3 @vue/test-utils@^2 jsdom@^25
```

- [ ] **Step 2: Add the test script**

In `frontend/package.json`, replace the `"scripts"` block with:

```json
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest",
    "lint:ds": "node scripts/lint-design-system.mjs"
  },
```

- [ ] **Step 3: Configure Vitest**

In `frontend/vite.config.js`, add a `test` property to the `defineConfig({...})` object, immediately after the `resolve` block (keep everything else unchanged):

```js
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/__tests__/**/*.spec.js'],
  },
})
```

- [ ] **Step 4: Write the failing test**

Create `frontend/src/components/ds/__tests__/StatusPill.spec.js`:

```js
import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import StatusPill from '../StatusPill.vue';

describe('StatusPill', () => {
  it('defaults to starting so it never claims a connection it has not verified', () => {
    const wrapper = mount(StatusPill);
    expect(wrapper.classes()).toContain('starting');
    expect(wrapper.text()).toBe('Backend: Starting...');
  });

  it.each([
    ['online', 'Backend: Online'],
    ['starting', 'Backend: Starting...'],
    ['offline', 'Backend: Offline'],
  ])('renders %s', (status, expected) => {
    const wrapper = mount(StatusPill, { props: { status } });
    expect(wrapper.classes()).toContain(status);
    expect(wrapper.text()).toBe(expected);
  });

  it('uses a custom label', () => {
    const wrapper = mount(StatusPill, { props: { status: 'online', label: 'Engine' } });
    expect(wrapper.text()).toBe('Engine: Online');
  });

  it('announces changes to assistive tech', () => {
    const wrapper = mount(StatusPill);
    expect(wrapper.attributes('role')).toBe('status');
    expect(wrapper.attributes('aria-live')).toBe('polite');
  });

  it('hides the decorative dot from assistive tech', () => {
    const wrapper = mount(StatusPill);
    expect(wrapper.find('.status-dot').attributes('aria-hidden')).toBe('true');
  });
});
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `cd frontend && npm run test`
Expected: FAIL — cannot resolve `../StatusPill.vue`.

- [ ] **Step 6: Write StatusPill.vue**

Create `frontend/src/components/ds/StatusPill.vue`:

```vue
<script setup>
/**
 * StatusPill
 *
 * Presentational connection indicator. Deliberately owns no transport: the
 * design-system contract is props `status` and `label` only. Polling lives in
 * useBackendStatus.
 */
import { computed } from 'vue';

const props = defineProps({
  status: {
    type: String,
    default: 'starting',
    validator: (v) => ['online', 'starting', 'offline'].includes(v)
  },
  label: {
    type: String,
    default: 'Backend'
  }
});

const statusText = computed(() => {
  switch (props.status) {
    case 'online': return `${props.label}: Online`;
    case 'offline': return `${props.label}: Offline`;
    default: return `${props.label}: Starting...`;
  }
});
</script>

<template>
  <div class="status-pill" :class="status" role="status" aria-live="polite">
    <span class="status-dot" aria-hidden="true" />
    <span class="status-label">{{ statusText }}</span>
  </div>
</template>

<style scoped>
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: var(--radius-full);
  border: 1px solid var(--color-border-subtle);
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--color-text-secondary);
  user-select: none;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: var(--radius-full);
  flex-shrink: 0;
}

.status-pill.online {
  background: var(--color-success-bg);
}
.status-pill.online .status-dot {
  background: var(--color-success);
  animation: ds-pulse 2s infinite var(--ease-standard);
}

.status-pill.starting {
  background: var(--color-warning-bg);
}
.status-pill.starting .status-dot {
  background: var(--color-warning);
  animation: ds-pulse 1s infinite var(--ease-standard);
}

.status-pill.offline {
  background: var(--color-danger-bg);
}
.status-pill.offline .status-dot {
  background: var(--color-danger);
}

@media (prefers-reduced-motion: reduce) {
  .status-pill .status-dot {
    animation: none;
  }
}
</style>
```

Note: `ds-pulse` is already defined globally in `assets/css/latent/tokens/effects.css`.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd frontend && npm run test`
Expected: PASS — 7 tests.

- [ ] **Step 8: Verify lint and build stay clean**

Run: `cd frontend && npm run lint:ds && npm run build`
Expected: `lint:ds` exits 0 with **7** hex warnings (unchanged — no new raw hex). Build succeeds.

- [ ] **Step 9: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/vite.config.js frontend/src/components/ds/
git commit -m "Add StatusPill primitive and a Vitest harness"
```

---

### Task 2: useBackendStatus composable

**Files:**
- Create: `frontend/src/composables/useBackendStatus.js`
- Test: `frontend/src/composables/__tests__/useBackendStatus.spec.js`

**Interfaces:**
- Consumes: nothing from Task 1 at runtime.
- Produces: `useBackendStatus(apiBase, apiToken, isBackendReady)` where all three arguments are Vue `Ref`s. Returns `{ status }`, a `Ref<'online'|'starting'|'offline'>`. Must be called from within a component `setup()` because it registers `onUnmounted`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/composables/__tests__/useBackendStatus.spec.js`:

```js
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ref, nextTick } from 'vue';
import { mount } from '@vue/test-utils';
import { useBackendStatus } from '../useBackendStatus';

// Mount inside a component so onUnmounted is registered against a real instance.
function harness(apiBase, apiToken, isBackendReady) {
  let exposed;
  const wrapper = mount({
    template: '<div />',
    setup() {
      exposed = useBackendStatus(apiBase, apiToken, isBackendReady);
      return {};
    }
  });
  return { wrapper, get status() { return exposed.status; } };
}

describe('useBackendStatus', () => {
  let apiBase, apiToken, isBackendReady;

  beforeEach(() => {
    vi.useFakeTimers();
    apiBase = ref('http://127.0.0.1:5000');
    apiToken = ref('tok');
    isBackendReady = ref(false);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('starts in starting before the handshake completes', () => {
    vi.stubGlobal('fetch', vi.fn());
    const h = harness(apiBase, apiToken, isBackendReady);
    expect(h.status.value).toBe('starting');
  });

  it('goes online when /api/version returns 200', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true }));
    const h = harness(apiBase, apiToken, isBackendReady);
    isBackendReady.value = true;
    await nextTick();
    await vi.advanceTimersByTimeAsync(0);
    expect(h.status.value).toBe('online');
  });

  it('sends the bearer token', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true });
    vi.stubGlobal('fetch', fetchMock);
    harness(apiBase, apiToken, isBackendReady);
    isBackendReady.value = true;
    await nextTick();
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledWith(
      'http://127.0.0.1:5000/api/version',
      expect.objectContaining({ headers: { Authorization: 'Bearer tok' } })
    );
  });

  it('treats a non-200 as starting, not offline', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    const h = harness(apiBase, apiToken, isBackendReady);
    isBackendReady.value = true;
    await nextTick();
    await vi.advanceTimersByTimeAsync(0);
    expect(h.status.value).toBe('starting');
  });

  it('goes offline when the request throws', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')));
    const h = harness(apiBase, apiToken, isBackendReady);
    isBackendReady.value = true;
    await nextTick();
    await vi.advanceTimersByTimeAsync(0);
    expect(h.status.value).toBe('offline');
  });

  it('goes offline when the handshake yields no token (fatal or browser dev mode)', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    apiToken.value = '';
    const h = harness(apiBase, apiToken, isBackendReady);
    isBackendReady.value = true;
    await nextTick();
    expect(h.status.value).toBe('offline');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('detects a backend that dies after startup', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true })
      .mockRejectedValue(new Error('ECONNREFUSED'));
    vi.stubGlobal('fetch', fetchMock);
    const h = harness(apiBase, apiToken, isBackendReady);
    isBackendReady.value = true;
    await nextTick();
    await vi.advanceTimersByTimeAsync(0);
    expect(h.status.value).toBe('online');

    await vi.advanceTimersByTimeAsync(10000);
    expect(h.status.value).toBe('offline');
  });

  it('stops polling once unmounted', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true });
    vi.stubGlobal('fetch', fetchMock);
    const h = harness(apiBase, apiToken, isBackendReady);
    isBackendReady.value = true;
    await nextTick();
    await vi.advanceTimersByTimeAsync(0);
    const callsBefore = fetchMock.mock.calls.length;

    h.wrapper.unmount();
    await vi.advanceTimersByTimeAsync(30000);
    expect(fetchMock.mock.calls.length).toBe(callsBefore);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npm run test`
Expected: FAIL — cannot resolve `../useBackendStatus`.

- [ ] **Step 3: Write the composable**

Create `frontend/src/composables/useBackendStatus.js`:

```js
/**
 * useBackendStatus
 *
 * Polls GET /api/version and reports the backend connection state for StatusPill.
 *
 * The backend is a child process on an ephemeral port; if it dies, every action
 * fails while the UI looks fine. Polling is what makes that visible.
 *
 * Deliberate choices:
 * - Initial state is 'starting', never 'online'. Latent-Library's equivalent
 *   initialises to 'online' and so claims a connection before the first check runs.
 * - The interval and any in-flight request are torn down on unmount.
 * - A non-200 (including 401) means reachable-but-not-usable, so it maps to
 *   'starting'. Only a thrown request means 'offline'.
 * - No flap debounce: a failed request to 127.0.0.1 means the process is gone.
 */
import { ref, watch, onUnmounted } from 'vue';

const POLL_INTERVAL_MS = 10_000;
const REQUEST_TIMEOUT_MS = 5_000;

export function useBackendStatus(apiBase, apiToken, isBackendReady) {
  const status = ref('starting');
  let timer = null;
  let controller = null;

  const setStatus = (next) => {
    if (status.value === next) return;          // log transitions only, never per tick
    console.log(`[LMO] Backend ${status.value} -> ${next}`);
    status.value = next;
  };

  const check = async () => {
    if (controller) controller.abort();          // never let ticks stack
    controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
      const res = await fetch(`${apiBase.value}/api/version`, {
        headers: { Authorization: `Bearer ${apiToken.value}` },
        signal: controller.signal
      });
      setStatus(res.ok ? 'online' : 'starting');
    } catch {
      setStatus('offline');
    } finally {
      clearTimeout(timeout);
    }
  };

  const stop = () => {
    if (timer) { clearInterval(timer); timer = null; }
    if (controller) { controller.abort(); controller = null; }
  };

  // Nothing to poll until the IPC handshake has produced a port and token.
  watch(isBackendReady, (ready) => {
    if (!ready) return;
    if (!apiToken.value) {
      setStatus('offline');   // fatal engine start, or a plain browser with no electronAPI
      return;
    }
    check();
    timer = setInterval(check, POLL_INTERVAL_MS);
  }, { immediate: true });

  onUnmounted(stop);

  return { status };
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npm run test`
Expected: PASS — 8 new tests, 15 total.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/composables/
git commit -m "Add useBackendStatus polling composable"
```

---

### Task 3: Mount the pill and give it sole ownership of connection state

**Files:**
- Modify: `frontend/src/App.vue` (script imports, `initializeBackendConnection`, titlebar template)

**Interfaces:**
- Consumes: `StatusPill.vue` (Task 1); `useBackendStatus(apiBase, apiToken, isBackendReady)` returning `{ status }` (Task 2).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Import the pill and the composable**

In `frontend/src/App.vue`, add to the existing import block in `<script setup>`:

```js
import StatusPill from './components/ds/StatusPill.vue';
import { useBackendStatus } from './composables/useBackendStatus';
```

- [ ] **Step 2: Wire the composable**

In `frontend/src/App.vue`, immediately after the line `const apiToken = ref('');` (currently line 55), add:

```js
const { status: backendStatus } = useBackendStatus(apiBase, apiToken, isBackendReady);
```

`isBackendReady`, `apiBase` and `apiToken` are already declared above it (lines 52–55), so no reordering is needed.

- [ ] **Step 3: Remove the FATAL branch so the pill owns connection state**

In `frontend/src/App.vue`, inside `initializeBackendConnection`, replace this block:

```js
        if (backend.port === 8080 && backend.token === null) {
          statusMessage.value = "❌ FATAL: Java engine failed to start.";
        } else {
          console.log(`[LMO] Bound to backend at ${apiBase.value}`);
        }
```

with:

```js
        // Connection state belongs to the StatusPill; the status bar reports
        // operation outcomes only. A null token surfaces there as "Offline".
        console.log(`[LMO] Bound to backend at ${apiBase.value}`);
```

Then, in the same function's `catch` block, replace:

```js
    statusMessage.value = '❌ Failed to connect to backend.';
```

with:

```js
    // Surfaced by the StatusPill, not the status bar.
```

- [ ] **Step 4: Render the pill in the titlebar**

In `frontend/src/App.vue`, replace the brand group (currently lines 220–223):

```html
      <div class="brand-group-ds no-drag">
        <img :src="latentMarkUrl" alt="Latent" class="brand-mark-img" />
        <span class="app-title-ds">Latent Model Organizer</span>
      </div>
```

with:

```html
      <div class="brand-group-ds no-drag">
        <img :src="latentMarkUrl" alt="Latent" class="brand-mark-img" />
        <span class="app-title-ds">Latent Model Organizer</span>
        <StatusPill :status="backendStatus" label="Backend" />
      </div>
```

This matches where both Latent-Library and Latent-Tools place their pill.

- [ ] **Step 5: Verify tests, lint and build**

Run: `cd frontend && npm run test && npm run lint:ds && npm run build`
Expected: 15 tests PASS; `lint:ds` exits 0; build succeeds.

- [ ] **Step 6: Verify in the running app**

```bash
# terminal 1
cd backend && java -jar target/backend.jar
# terminal 2
cd frontend && npm run dev
# terminal 3
cd electron && npm start
```

Expected: pill reads **Backend: Online** within a second of launch. Kill the Java process; within 10 seconds the pill reads **Backend: Offline** and the status bar still reads "Ready.".

- [ ] **Step 7: Commit**

```bash
git add frontend/src/App.vue
git commit -m "Show backend connection state in the titlebar"
```

---

### Task 4: LBadge primitive

**Files:**
- Create: `frontend/src/components/ds/LBadge.vue`
- Test: `frontend/src/components/ds/__tests__/LBadge.spec.js`
- Modify: `frontend/src/components/ConsoleWindow.vue:107`, `frontend/src/components/Settingsmodal.vue:66`, `frontend/src/components/Summarymodal.vue:38`, `frontend/src/views/FetcherView.vue:111`, `frontend/src/views/SorterView.vue:324,359`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `LBadge.vue`. Prop `variant: 'neutral'|'accent'|'success'|'warning'|'danger'|'outline'` (default `'neutral'`), prop `size: 'sm'|'md'` (default `'md'`). Content via default slot.

**Why not a verbatim port:** Latent-Library's `LBadge` has a `secondary` variant and **no `outline`**, but the design system's `_adherence.oxlintrc.json` declares `neutral | accent | success | warning | danger | outline`, and LMO uses `outline` twice. Implement the spec, not Latent-Library.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/components/ds/__tests__/LBadge.spec.js`:

```js
import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import LBadge from '../LBadge.vue';

describe('LBadge', () => {
  it.each(['neutral', 'accent', 'success', 'warning', 'danger', 'outline'])(
    'renders the %s variant declared by the design system',
    (variant) => {
      const wrapper = mount(LBadge, { props: { variant }, slots: { default: 'X' } });
      expect(wrapper.classes()).toContain(variant);
    }
  );

  it('defaults to neutral md', () => {
    const wrapper = mount(LBadge, { slots: { default: 'X' } });
    expect(wrapper.classes()).toEqual(expect.arrayContaining(['l-badge', 'neutral', 'md']));
  });

  it('renders slot content', () => {
    const wrapper = mount(LBadge, { slots: { default: 'DRY RUN' } });
    expect(wrapper.text()).toBe('DRY RUN');
  });

  it('supports the sm size', () => {
    const wrapper = mount(LBadge, { props: { size: 'sm' }, slots: { default: 'X' } });
    expect(wrapper.classes()).toContain('sm');
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npm run test`
Expected: FAIL — cannot resolve `../LBadge.vue`.

- [ ] **Step 3: Write LBadge.vue**

Create `frontend/src/components/ds/LBadge.vue`:

```vue
<script setup>
/**
 * LBadge
 *
 * Variants follow the design system's _adherence.oxlintrc.json contract.
 * Latent-Library's copy substitutes `secondary` for `outline`; that is drift,
 * and this implementation deliberately does not reproduce it.
 */
defineProps({
  variant: {
    type: String,
    default: 'neutral',
    validator: (v) => ['neutral', 'accent', 'success', 'warning', 'danger', 'outline'].includes(v)
  },
  size: {
    type: String,
    default: 'md',
    validator: (v) => ['sm', 'md'].includes(v)
  }
});
</script>

<template>
  <span class="l-badge" :class="[variant, size]">
    <slot />
  </span>
</template>

<style scoped>
.l-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border-radius: var(--radius-full);
  font-family: var(--font-sans);
  font-weight: 600;
  letter-spacing: 0.02em;
  white-space: nowrap;
  user-select: none;
  border: 1px solid transparent;
}

.l-badge.sm { padding: 1px 6px; font-size: 10px; }
.l-badge.md { padding: 2px 8px; font-size: 11px; }

.l-badge.neutral {
  background: var(--color-surface-2);
  color: var(--color-text-secondary);
  border-color: var(--color-border-subtle);
}

.l-badge.accent {
  background: var(--color-accent-primary-bg);
  color: var(--color-accent-primary);
  border-color: var(--color-accent-primary-bg);
}

.l-badge.success {
  background: var(--color-success-bg);
  color: var(--color-success);
  border-color: var(--color-success-bg);
}

.l-badge.warning {
  background: var(--color-warning-bg);
  color: var(--color-warning);
  border-color: var(--color-warning-bg);
}

.l-badge.danger {
  background: var(--color-danger-bg);
  color: var(--color-danger);
  border-color: var(--color-danger-bg);
}

.l-badge.outline {
  background: transparent;
  color: var(--color-text-secondary);
  border-color: var(--color-border-default);
}
</style>
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npm run test`
Expected: PASS — 9 new tests, 24 total.

- [ ] **Step 5: Replace the six call sites**

Add `import LBadge from '@/components/ds/LBadge.vue';` to the `<script setup>` block of each file below, then swap the markup:

`frontend/src/components/ConsoleWindow.vue:107`
```html
<LBadge variant="outline" v-if="logs.length > 0">{{ logs.length }}</LBadge>
```

`frontend/src/components/Settingsmodal.vue:66`
```html
<LBadge variant="accent">Active</LBadge>
```

`frontend/src/components/Summarymodal.vue:38`
```html
<LBadge v-if="isDryRun" variant="warning">DRY RUN</LBadge>
```

`frontend/src/views/FetcherView.vue:111`
```html
<LBadge variant="outline">Offline</LBadge>
```

`frontend/src/views/SorterView.vue:324` — keep the existing `v-if` expression and inner content exactly as they are, changing only the element:
```html
<LBadge variant="accent" v-if="!allSelected && !noneSelected">
```

`frontend/src/views/SorterView.vue:359`
```html
<LBadge v-for="a in selectedArchitectures" :key="a" variant="accent">{{ a }}</LBadge>
```

- [ ] **Step 6: Delete the now-orphaned `.badge-ds` CSS**

In each of the five files above, remove the `.badge-ds` rule and its variant rules (`.badge-ds.accent`, `.badge-ds.outline`, `.badge-ds.warning`) from the `<style scoped>` block. Leave every other rule untouched.

Verify none survive:

```bash
grep -rn "badge-ds" frontend/src/
```
Expected: no output.

- [ ] **Step 7: Verify**

Run: `cd frontend && npm run test && npm run lint:ds && npm run build`
Expected: 24 tests PASS; `lint:ds` exits 0; build succeeds and the CSS bundle shrinks.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/components/ frontend/src/views/
git commit -m "Replace hand-rolled badges with an LBadge primitive"
```

---

### Task 5: LSwitch primitive

**Files:**
- Create: `frontend/src/components/ds/LSwitch.vue`
- Test: `frontend/src/components/ds/__tests__/LSwitch.spec.js`
- Modify: `frontend/src/components/Settingsmodal.vue`, `frontend/src/components/Sidebar.vue`, `frontend/src/views/FetcherView.vue`, `frontend/src/views/SorterView.vue`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `LSwitch.vue`. Props: `modelValue: Boolean` (default `false`), `label: String` (default `''`), `ariaLabel: String` (default `''`), `disabled: Boolean` (default `false`). Emits `update:modelValue` (Boolean) and `change` (Boolean). Supports `v-model`.

**The `ariaLabel` prop exists for a reason:** `Settingsmodal.vue` renders each switch's visible
text ("Deep Scan") in a sibling `<div>` *outside* the `<label>`, so its checkbox currently has
**no accessible name** — a screen reader announces an unlabelled checkbox. Those call sites pass
`aria-label` instead of `label`. Do not drop this and reproduce the existing bug.

**Why not a verbatim port:** Latent-Library's `LSwitch` is a `<label>` with `@click.prevent` and **no `<input>` at all** — it is not keyboard-reachable and is not announced as a switch. LMO's current hand-rolled toggle already wraps a native `<input type="checkbox" class="sr-only">`, which is better and satisfies `AGENTS.md`'s "semantic HTML first" and "keyboard-reachable with visible focus" rules. Keep the native input; take only Latent-Library's visual treatment.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/components/ds/__tests__/LSwitch.spec.js`:

```js
import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import LSwitch from '../LSwitch.vue';

describe('LSwitch', () => {
  it('renders a real checkbox so it is keyboard reachable', () => {
    const wrapper = mount(LSwitch, { props: { label: 'Deep Scan' } });
    const input = wrapper.find('input[type="checkbox"]');
    expect(input.exists()).toBe(true);
  });

  it('reflects modelValue', () => {
    const wrapper = mount(LSwitch, { props: { modelValue: true, label: 'Deep Scan' } });
    expect(wrapper.find('input').element.checked).toBe(true);
    expect(wrapper.find('.l-switch-track').classes()).toContain('active');
  });

  it('emits update:modelValue and change when toggled', async () => {
    const wrapper = mount(LSwitch, { props: { modelValue: false, label: 'Deep Scan' } });
    await wrapper.find('input').setValue(true);
    expect(wrapper.emitted('update:modelValue')[0]).toEqual([true]);
    expect(wrapper.emitted('change')[0]).toEqual([true]);
  });

  it('does not emit when disabled', async () => {
    const wrapper = mount(LSwitch, { props: { modelValue: false, label: 'X', disabled: true } });
    await wrapper.find('input').trigger('change');
    expect(wrapper.emitted('update:modelValue')).toBeUndefined();
  });

  it('associates the label with the input', () => {
    const wrapper = mount(LSwitch, { props: { label: 'Deep Scan' } });
    expect(wrapper.find('label').text()).toBe('Deep Scan');
    expect(wrapper.find('input').element.closest('label')).toBeTruthy();
  });

  it('names the input via aria-label when the visible text lives outside', () => {
    const wrapper = mount(LSwitch, { props: { ariaLabel: 'Deep Scan' } });
    expect(wrapper.find('input').attributes('aria-label')).toBe('Deep Scan');
    expect(wrapper.find('.l-switch-label').exists()).toBe(false);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npm run test`
Expected: FAIL — cannot resolve `../LSwitch.vue`.

- [ ] **Step 3: Write LSwitch.vue**

Create `frontend/src/components/ds/LSwitch.vue`:

```vue
<script setup>
/**
 * LSwitch
 *
 * Visual treatment from Latent-Library, markup from LMO's existing toggles: a
 * real <input type="checkbox"> visually hidden inside its <label>. Latent-Library's
 * version is a label with a click handler and no input, which is neither
 * keyboard-reachable nor announced as a switch.
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  label: { type: String, default: '' },
  // For call sites whose visible text sits outside the control (Settingsmodal).
  ariaLabel: { type: String, default: '' },
  disabled: { type: Boolean, default: false }
});

const emit = defineEmits(['update:modelValue', 'change']);

const onChange = (event) => {
  if (props.disabled) return;
  const next = event.target.checked;
  emit('update:modelValue', next);
  emit('change', next);
};
</script>

<template>
  <label class="l-switch" :class="{ disabled }">
    <input
        type="checkbox"
        class="l-switch-input"
        :checked="modelValue"
        :disabled="disabled"
        :aria-label="ariaLabel || undefined"
        @change="onChange"
    />
    <span class="l-switch-track" :class="{ active: modelValue }">
      <span class="l-switch-thumb" />
    </span>
    <span v-if="label || $slots.default" class="l-switch-label">
      <slot>{{ label }}</slot>
    </span>
  </label>
</template>

<style scoped>
.l-switch {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  user-select: none;
  font-family: var(--font-sans);
  font-size: 13px;
  color: var(--color-text-primary);
}

/* Visually hidden, still focusable and still announced. */
.l-switch-input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}

.l-switch-track {
  position: relative;
  width: 36px;
  height: 20px;
  border-radius: var(--radius-full);
  background: var(--color-surface-2);
  border: 1px solid var(--color-border-default);
  transition: background var(--duration-base) var(--ease-standard),
              border-color var(--duration-base) var(--ease-standard);
  flex-shrink: 0;
}

.l-switch-track.active {
  background: var(--color-accent-primary);
  border-color: var(--color-accent-primary);
}

/* Focus must be visible when tabbing to the hidden input. */
.l-switch-input:focus-visible + .l-switch-track {
  outline: 2px solid var(--color-border-focus);
  outline-offset: 2px;
}

.l-switch-thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--color-text-secondary);
  transition: transform var(--duration-base) var(--ease-standard),
              background var(--duration-base) var(--ease-standard);
}

.l-switch-track.active .l-switch-thumb {
  transform: translateX(16px);
  background: var(--color-text-on-accent);
}

.l-switch:hover:not(.disabled) .l-switch-track {
  border-color: var(--color-border-strong);
}

.l-switch.disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

@media (prefers-reduced-motion: reduce) {
  .l-switch-track,
  .l-switch-thumb {
    transition: none;
  }
}
</style>
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npm run test`
Expected: PASS — 6 new tests, 30 total.

- [ ] **Step 5: Replace the seven call sites**

Add `import LSwitch from '@/components/ds/LSwitch.vue';` to the `<script setup>` of each file, then replace each toggle block.

`frontend/src/views/FetcherView.vue:77-88` — replace the whole `.options-row-ds` contents:
```html
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
```

`frontend/src/views/SorterView.vue:364-376` — replace the whole `.options-row-ds` block:
```html
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
```

`frontend/src/components/Settingsmodal.vue:75-98` — keep the `.toggle-row-ds` / `.toggle-info-ds`
wrappers (they carry the visible name and description); replace only the two `<label
class="toggle-control-ds">` blocks. Note `aria-label`, not `label` — the visible text is in the
sibling `.toggle-info-ds`, so without this the checkbox has no accessible name:
```html
          <div class="toggle-list-ds">
            <div class="toggle-row-ds">
              <div class="toggle-info-ds">
                <span class="toggle-name-ds">Deep Scan</span>
                <span class="toggle-desc-ds">Recursively scan all subfolders</span>
              </div>
              <LSwitch
                  :model-value="isRecursive"
                  aria-label="Deep Scan"
                  @update:model-value="v => emit('update:isRecursive', v)"
              />
            </div>
            <div class="toggle-row-ds">
              <div class="toggle-info-ds">
                <span class="toggle-name-ds">Dry Run</span>
                <span class="toggle-desc-ds">Simulate operations without moving files</span>
              </div>
              <LSwitch
                  :model-value="isDryRun"
                  aria-label="Dry Run"
                  @update:model-value="v => emit('update:isDryRun', v)"
              />
            </div>
          </div>
```

`frontend/src/components/Sidebar.vue:59-71` — replace the `<label class="toggle-control-ds">`
block (keep the surrounding `.sidebar-group.sidebar-bottom` div and the button after it):
```html
        <LSwitch
            :model-value="consoleOpen"
            label="Console"
            @update:model-value="v => emit('update:consoleOpen', v)"
        />
```

- [ ] **Step 6: Delete the now-orphaned toggle CSS**

Remove `.toggle-track-ds`, `.toggle-thumb-ds`, `.toggle-control-ds`, `.toggle-label-text`
(SorterView/FetcherView) and `.toggle-text` (Sidebar) from the `<style scoped>` blocks of the
four files. Also remove each file's `.sr-only` rule **only if** that file no longer uses the
class — after this task, none of the four do, since the hidden input moved inside `LSwitch`.

Keep `.toggle-list-ds`, `.toggle-row-ds`, `.toggle-info-ds`, `.toggle-name-ds` and
`.toggle-desc-ds` in `Settingsmodal.vue` — those style the surrounding rows, not the control.

Verify none survive:

```bash
grep -rn "toggle-track-ds\|toggle-thumb-ds\|toggle-control-ds\|toggle-label-text\|toggle-text" frontend/src/
```
Expected: no output.

Then confirm no `.sr-only` rule is left without a user:

```bash
grep -rn "sr-only" frontend/src/
```
Expected: no output.

- [ ] **Step 7: Verify**

Run: `cd frontend && npm run test && npm run lint:ds && npm run build`
Expected: 30 tests PASS; `lint:ds` exits 0; build succeeds and the CSS bundle shrinks again.

- [ ] **Step 8: Verify keyboard access in the running app**

Launch the app (Task 3 Step 6). Tab to each switch: focus ring must be visible on the track,
Space must toggle it, and the bound behaviour (deep scan, dry run, console) must still work.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/components/ frontend/src/views/
git commit -m "Replace hand-rolled toggles with an LSwitch primitive"
```

---

## Done when

- `npm run test` green (30 tests), `npm run lint:ds` exits 0, `npm run build` clean.
- Pill shows **Backend: Online** in the packaged app and flips to **Backend: Offline** within
  10s of the Java process dying.
- `grep -rn "badge-ds\|toggle-track-ds" frontend/src/` returns nothing.
- CSS bundle smaller than the 46.89 kB baseline.

## Follow-ups (not this plan)

- `LButton` port — needs `full-width` and `icon-only` modifiers Latent-Library lacks, and
  touches 13 call sites across 8 files.
- Input border contrast (open decision; design-system-level, affects all three apps).
- Report upstream to Latent-Library: `LBadge` drift (`secondary` instead of the spec's
  `outline`), the `LSwitch` keyboard-accessibility gap (no `<input>` at all), and `StatusPill`
  initialising to `'online'` with an uncleared `setInterval`.
- `Settingsmodal.vue`'s unlabelled checkboxes are fixed here via `aria-label`; check whether the
  same pattern (visible text outside the control) exists elsewhere in the app.

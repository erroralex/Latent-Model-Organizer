# Design — `ds/` component layer, centred on a backend StatusPill

**Date:** 2026-08-06
**Status:** Approved
**Open issue addressed:** "LMO has no `ds/` component layer" (suite coherency audit, 2026-08-06)

---

## Problem

Latent Model Organizer ships **zero** design-system primitives. Latent-Library ships 16 and
Latent-Tools has ~15 as CSS classes. Two concrete consequences:

1. **A real UX gap.** LMO spawns a Java backend on an ephemeral port and authenticates against
   it with a startup token, and shows the user **no connection state whatsoever**. If the
   backend dies, the UI looks fine and every action fails. Both sibling apps show a StatusPill
   in the titlebar; LMO shows nothing.
2. **433 lines of duplicated CSS.** Button, badge and switch styles are re-declared in eight
   separate component `<style scoped>` blocks:

   | Component | Lines |
   |---|---|
   | `SorterView.vue` | 103 |
   | `FetcherView.vue` | 91 |
   | `Settingsmodal.vue` | 69 |
   | `Summarymodal.vue` | 43 |
   | `InfoModal.vue` | 35 |
   | `App.vue` | 34 |
   | `ConsoleWindow.vue` | 30 |
   | `Sidebar.vue` | 28 |

This is a DRY problem with a suite-coherency symptom, not the reverse.

---

## Scope

Port **only primitives with an immediate consumer**. A wholesale 16-file port would create
roughly twelve files with no consumer — precisely the dead-code class that was just deleted
from this codebase (`layout.css` and `buttons.css`, 113 rules, 607 lines).

**In scope (4 new files):**

| File | Consumers today |
|---|---|
| `composables/useBackendStatus.js` | new capability |
| `components/ds/StatusPill.vue` | new capability — titlebar |
| `components/ds/LBadge.vue` | 6 instances across 5 components |
| `components/ds/LSwitch.vue` | 7 instances across 4 components |

Plus one tooling change: `frontend/package.json` gains a `vitest` devDependency and a `test`
script (see Testing), and a `vitest.config.js` if Vite's config cannot be reused directly.

**Deferred to a second pass:** `LButton.vue`. It needs two new modifiers and touches 13 call
sites across 8 files — a much larger blast radius. Splitting keeps the first change reviewable.

**Explicitly out of scope:** `win-btn-ds` (window controls) and `con-btn-ds` (console buttons)
are bespoke chrome with one call site each. Leave them.

---

## Key constraint: the spec is the contract, not Latent-Library

Latent-Library is the reference implementation but has **drifted from the design system**, so
porting it verbatim would import its drift:

| Primitive | DS spec (`_adherence.oxlintrc.json`) | Latent-Library | Resolution |
|---|---|---|---|
| `Badge.variant` | `neutral \| accent \| success \| warning \| danger \| outline` | has `secondary`, **missing `outline`** | Implement the spec. LMO uses `outline` twice. |
| `Button.variant` | `primary` = `--color-surface-3`; accent fill reserved for `cta` | `primary` fills solid accent | Implement the spec (second pass). |
| `StatusPill` | props `status`, `label` | conformant | Port as-is. |

Also **not** to be copied from LL's implementation:

- `engineStatus` initialises to `'online'` — claims connected before the first check runs.
- `setInterval` is never cleared — timer leak on unmount.

---

## Architecture

```
electron main ──IPC──> App.vue: initializeBackendConnection()
                          │  apiBase, apiToken
                          ▼
              composables/useBackendStatus.js      ← owns the 10s timer
                          │  status: ref<'starting'|'online'|'offline'>
                          ▼
              components/ds/StatusPill.vue         ← presentational; props only
                          │
                          ▼
              App.vue titlebar, inside .brand-group-ds
```

The composable owns polling; the pill stays presentational. Two alternatives were rejected:

- **Pill fetches its own status** — breaks the DS contract (props are `status` and `label`
  only) and couples presentation to transport. Note LL puts the fetch in `Titlebar`, not the pill.
- **Port LL's `Titlebar.vue` wholesale** — LMO's titlebar already works, owns its own window
  controls and drag regions, and LL's hardcodes its own title and logo. Large risky diff, no gain.

`useBackendStatus` follows the existing `useUiZoom` composable pattern.

---

## State machine

| Condition | State |
|---|---|
| Initial; handshake pending | `starting` |
| `GET /api/version` → 200 | `online` |
| `GET /api/version` → non-200 (incl. 401) | `starting` |
| Network error / abort / throw | `offline` |
| Fatal sentinel: port `8080` **and** token `null` | `offline` |
| `isBackendReady` true but no `apiBase` (browser dev mode) | `offline` |

Poll interval **10s**, matching Latent-Library. **Polling does not start until `apiBase` is
set** — there is nothing to poll before the IPC handshake completes, so the composable watches
`apiBase` and starts the timer on first non-empty value. Until then the state is `starting`.

A 401 mapping to `starting` is deliberate: the process is reachable but not yet usable.

### Error handling

| Case | Handling |
|---|---|
| Hung backend | `AbortController`, 5s timeout (half the interval) — otherwise ticks stack |
| Overlapping polls | Abort the in-flight request before the next tick |
| Log noise | Log on state *transition* only, never per tick |
| Unmount | `clearInterval` + abort — explicitly tested |

**No flap debounce.** A failed request to `127.0.0.1` means the process is gone; transient
failure is a remote-service concern. Retry hysteresis here would be speculative.

---

## Status ownership

The pill owns backend-connection state. The status bar owns operation outcomes.

The `FATAL: Java engine failed to start` branch is removed from
`initializeBackendConnection`; that condition now surfaces as `offline` on the pill. After the
change, `statusMessage` reports only Sort/Fetch results.

---

## Accessibility

`AGENTS.md` makes this non-optional, and LL's pill has none of it:

- `role="status"` and `aria-live="polite"` — transitions are announced, not silent.
- `aria-hidden="true"` on the dot. It is decorative; the text label carries the meaning, so
  status is never conveyed by colour alone.
- `ds-pulse` wrapped in `@media (prefers-reduced-motion: reduce)`.

All colours come from design-system tokens (`--color-success`, `--color-warning`,
`--color-danger` and their `-bg` tints), so `npm run lint:ds` stays clean.

---

## Testing

This introduces **Vitest**, which the project does not yet have — closing the standing "no
frontend tests" gap with a concrete first case rather than an abstract one. A composable built
on timers and `fetch` is exactly the unit-test case.

| Target | Coverage |
|---|---|
| `useBackendStatus` | every row of the state-machine table, with `vi.useFakeTimers()` and mocked `fetch` |
| `useBackendStatus` | timer and request cleanup on unmount (the LL leak) |
| `StatusPill` | correct class and label per status, asserted on rendered output |
| `LBadge` | renders all six spec variants, including `outline` |
| `LSwitch` | emits on toggle; reflects `checked` |

Per `AGENTS.md`: deterministic, no sleeps, behaviour through rendered output rather than
internal refs. Backend needs no changes — `/api/version` already serves this and, since the
`52312d9` fix, returns a real version rather than `"dev"`.

---

## Verification

1. `npm run test` — new Vitest suite green.
2. `npm run lint:ds` — exits 0; no new raw hex.
3. `npm run build` — clean.
4. Manual, in Electron with a real backend: pill reads **Backend: Online**; kill the Java
   process and it flips to **Backend: Offline** within 10s.
5. Confirm the 433-line duplication figure drops by the share `LBadge` and `LSwitch` retire.

---

## Out of scope

- `LButton` port (second pass).
- The remaining twelve Latent-Library primitives — no consumers.
- Input border contrast (open decision; DS-token-level, affects all three apps).
- Vendor namespace unification.

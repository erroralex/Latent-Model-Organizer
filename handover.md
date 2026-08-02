# Project Handover - Recent Fixes and Setup

This document summarizes the recent changes made to **Latent Model Organizer** during this session.

---

## 1. AI Guidelines Setup

Following the **Drop-in Brain** guidelines, the project has been fully configured for AI assistants:
- **`AGENTS.md` (Root):** Configured with exact project details (name, purpose, Java 21/Vue 3/Electron stack, build/test/run commands) and target addons: **Frontend Core** and **Vue (3.x)**.
- **`GEMINI.md` (Root):** Created as a byte-for-byte copy of `AGENTS.md` to support Gemini CLI.
- **`.agents/AGENTS.md` (NTFS Hard Link):** Hard-linked to `AGENTS.md` in the root to allow Antigravity (Gemini) to automatically discover project-scoped instructions.
- **`CLAUDE.md` (Root):** Created as the import shim (`@AGENTS.md`) for Claude Code compatibility.
- **`.claude/settings.json`:** Tailored with specific permission rules allowing Git, Maven (`mvn`), and Node (`npm`) operations without prompt noise.
- **`.agents/skills/`:** Created and populated with:
  - Custom skill: `ai-setup-doctor` (for self-diagnostics)
  - Upstream skills: `frontend-design`, `web-design-guidelines`, `vue`, and `vitest` (installed via the `skills` CLI).
- **`.claude/skills` (Junction Link):** Created as an NTFS Junction pointing to `.agents/skills` to expose skills to Claude Code.
- **`.gitignore`:** Appended `.claude/skills/` to prevent committing the linked directory contents twice.

---

## 2. Krea 2 & New Base Models Support

Added sorting and Civitai metadata mapping for several new base models and architectures:
- **Krea 2**
- **Wan Video 2.7** and **Wan Image 2.7**
- **LTXV 2.3**
- **Qwen 2**
- **HiDream-O1**
- **Ideogram 4.0**
- **Grok**

### Technical Details:
- **`ModelAnalyzer.java`:**
  - Added new models to the `SUPPORTED_ARCHITECTURES` list.
  - Implemented token and string checks in `checkFilenameHeuristics` to identify these architectures from filenames.
  - Added mappings in `mapBaseModelToArchitecture` to map incoming baseModel values from the Civitai API to their corresponding architectures.
- **`ModelAnalyzerTest.java`:**
  - Added comprehensive unit tests verifying that all new architectures are correctly detected through filename heuristics, sidecars, and internal headers.

---

## 3. Verification Status

- **Setup Doctor Checklist:** Checked via the `ai-setup-doctor` skill: **10/10 PASS**.
- **Backend Unit Tests:** Ran all 29 unit tests in the `backend/` directory:
  ```powershell
  & "C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.3\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" test
  ```
  (The bundled Maven lives under `plugins\maven-plugin\`, not `plugins\maven\`; the folder
  was renamed in recent IntelliJ builds. There is no standalone `mvn` on the PATH.)
  Result: **BUILD SUCCESS** (29 tests run, 0 failures).

---

## 4. Releases

v1.1.0 was the first release since v1.0.0, covering the Krea 2 and new base model support above.
v1.2.0 followed with the Forge user-metadata work.

### Process (source of truth for future releases)

- Version lives in **`backend/pom.xml`** only. `build.yml` reads it via
  `mvn help:evaluate -Dexpression=project.version` and syncs it into
  `electron/package.json` and `frontend/package.json` at build time via
  `npm version --no-git-tag-version` — no need to hand-edit those files.
- Release flow:
  1. Bump `<version>` in `backend/pom.xml` on `development`, commit, push.
  2. Open a PR from `development` into `main` (repo convention: one PR per merge,
     visible in `git log` as `Merge pull request #N from erroralex/development`).
  3. After merge, tag the merge commit on `main` as `vX.Y.Z` and push the tag:
     `git tag -a vX.Y.Z <commit> -m "..."` then `git push origin vX.Y.Z`.
  4. Pushing the tag triggers `.github/workflows/build.yml`, which builds
     Windows/Linux/macOS artifacts and publishes a GitHub Release automatically.
- `gh` CLI is **not installed** on this machine — PRs must be opened manually via
  the GitHub compare URL (`https://github.com/erroralex/Latent-Model-Organizer/compare/main...development`).

### What shipped in 1.1.0

- Version bump `1.0.0` → `1.1.0` (`backend/pom.xml`).
- Krea 2, Wan Video/Image 2.7, LTXV 2.3, Qwen 2, HiDream-O1, Ideogram 4.0, and
  Grok architecture support (already implemented in section 2 above; this release
  just ships it).
- Tagged and pushed as `v1.1.0` on the `main` merge commit — release build triggered
  via GitHub Actions.

### What shipped in 1.2.0

- Version bump `1.1.0` → `1.2.0` (`backend/pom.xml`).
- Trigger words and Civitai descriptions written into the `<basename>.json` user metadata that
  A1111 / Forge / Forge Neo actually read (section 5).
- `POST /api/backfill-metadata` and a Fetcher UI action to retrofit an existing library from
  sidecars already on disk, without hashing or network calls.
- Preview files now keep the extension the URL serves, so Civitai's `.mp4` animated previews
  stop being written as undecodable `.png` (section 7).
- Shared IntelliJ run configurations under `.run/` (section 6).
- Tagged as `v1.2.0` on the `main` merge commit (`46c287d`) — release build triggered
  via GitHub Actions.

**Verified against a real library after release:** the backfill was run over the reference
Forge Neo install and filled the previously-empty models; the previously broken animated
previews now render. Both features are confirmed working outside the test suite.

### A trap when tagging

`build.yml` reads the version from the pom **on the tagged commit**, so a tag placed on a
`main` that has not yet received the version bump publishes a release whose name and artifacts
disagree. This happened once: a PR was opened before the bump was pushed, so the merge brought
in an older `development` and `main` still read `1.1.0`.

Always confirm the version on the exact commit being tagged, not merely that `main` looks
current:

```bash
git fetch origin
git show origin/main:backend/pom.xml | grep -m1 "<version>"
git log --oneline origin/main..origin/development   # must be empty
```

---

## 5. LoRA Trigger Words & Descriptions

Civitai's `by-hash` response already contains a `trainedWords` array and two description fields,
and the fetcher already wrote the whole response to `<basename>.civitai.info`. Both were
therefore on disk but invisible to the WebUI.

**Why:** A1111 / Forge / Forge Neo never read `.civitai.info` — that format belongs to the
Civitai Helper extension. Verified against a local Forge Neo install: `modules/extra_networks.py`
`get_user_metadata()` reads exactly one file, `<basename>.json`, and
`extensions-builtin/sd_forge_lora/ui_edit_user_metadata.py` populates the "Activation text" box
from its `"activation text"` key and the "Description" box from `"description"`.

### What was added

- **`ForgeUserMetadataWriter`** — joins `trainedWords` with `,, ` (the Civitai section convention
  that the Card Master extension splits on), converts the description to plain text, and
  merge-writes `<basename>.json`. Each field is filled only when blank, so anything the user
  wrote survives; writes go through a temp file + atomic move. Returns a `WriteOutcome` naming
  which fields were added, so callers can tally them separately.
- **`HtmlToPlainText`** — Civitai serves descriptions as HTML, and the WebUI escapes them by
  default (`extra_networks_card_description_is_html` defaults to `false` in
  `modules/shared_options.py`), so raw markup would render as literal `<p>` tags on every card.
  A small dependency-free converter reduces them to text.
- **`UserMetadataBackfillService`** + `POST /api/backfill-metadata` — an offline pass over
  existing `.civitai.info` sidecars. No hashing, no network, idempotent, so it is safe to re-run.
  Needed because `fetchMissingMetadata` skips any model that already has a sidecar, which on a
  mature library is nearly all of them.
- **Fetcher UI** — a "Trigger Words & Descriptions" section with a backfill button, reusing the
  existing Deep Scan / Dry Run toggles.

### Gotchas discovered

- Many Civitai authors leave a **trailing comma** on each `trainedWords` entry. Joining verbatim
  produced `,,,` runs that shift Card Master's section boundaries; entries are now stripped of
  surrounding commas and whitespace (regression tests cover this).
- Do **not** write the `sd version` key. Forge Neo has diverged from upstream: the editor saves
  `"sd version"` but `read_user_metadata` looks for `sd_version_str`.
- There are **two** description fields. `model.description` is the model page text (present in
  1260 of 1764 sidecars in the reference library); the top-level `description` is a short version
  note (592). The model one wins, with the version note as fallback — they are not concatenated.

---

## 6. IntelliJ Run Configurations

Run configurations now live in **`.run/`** as shared, committed files rather than in the
gitignored `.idea/workspace.xml`.

**Root cause of "Configuration is still incorrect":** the backend Application configuration
declared **no `<module>`**, so it had no classpath. That alone invalidates the configuration.
The shared config now declares `<module name="latent-model-organizer-backend" />`.

**The module is named after the artifactId, not the directory.** It is
`latent-model-organizer-backend`, not `backend` — an earlier `<module name="backend" />` was
silently stripped by the IDE on load because no module answers to that name.

### Two things that look like causes but are not

- **The `ALTERNATIVE_JRE_PATH="liberica-full-21"` pin.** That SDK resolves fine. It was removed
  anyway so the config inherits the project SDK and stays portable, but it was never the fault.
- **`.idea/modules.xml` listing only `frontend` and the root module.** External module storage is
  enabled (`ExternalStorageConfigurationManager`), so Maven-derived modules live outside `.idea/`
  and never appear there. The backend module *is* imported; the file simply is not where to look.
  Query the running IDE instead of reading `.idea/` when checking module state.

Note also that the **running IDE uses the `IntelliJIdea2026.2` config directory** even though the
binary is 2025.2.3, so `$APPDATA\JetBrains\IntelliJIdea2025.2\` is the wrong place to inspect
SDK tables for this project.

Verified by launching the configuration: the backend started and logged
`LMO_PORT=…` normally.

---

## 7. Preview Files

`resolvePreviewExtension` recognised only jpg/jpeg/webp and defaulted everything else to
`.preview.png`. Civitai serves animated previews as `.mp4`, so video bytes were written into
files named `.png` — undecodable, and the card rendered "NO PREVIEW". The extension is now taken
from the URL's final path segment, accepting the same media set as the WebUI's
`default_allowed_preview_extensions`.

### Do NOT switch this to `images[0].type`

It looks like the authoritative signal and is not. `type` describes what the **author uploaded**;
the URL describes what the **CDN will return**, and only the latter can determine the filename.

In the reference library 27 sidecars have `type: "video"` but only 6 have a `.mp4` URL. The other
26 carry a `.jpeg` URL with a `width=450` transform, and Civitai returns a **still frame** — the
bytes on disk are genuinely JPEG. Keying the extension off `type` would rename those 26 working
previews to `.mp4`, turning 4 broken previews into 30.

The genuinely authoritative signal, if this ever needs hardening, is the response's
`Content-Type` header — but it requires downloading before naming, and no case has been observed
where the URL suffix disagreed with the actual bytes in a way that broke rendering.

### Pre-existing mislabelling (harmless)

1715 preview files have content that does not match their extension, almost all `jpeg` bytes in
`.preview.png` — the Civitai Helper extension names every preview `.preview.png` regardless of
content. These render correctly because browsers sniff content type. Not worth renaming.

---

## 8. UI Redesign (Latent Design System Integration)

The entire Vue 3 user interface has been reworked to conform to the unified **Latent Design System** (`https://github.com/erroralex/Latent-Design-System.git`). All changes were performed on the dedicated **`feature/ui-redesign-latent-ds`** branch.

### Key Highlights

- **Design System Token Suite:** Copied `styles.css` and token files (`colors.css`, `typography.css`, `spacing.css`, `effects.css`, `fonts.css`) into `frontend/src/assets/css/latent/`. Consolidated the app onto the single unified dark theme canvas (`#0A0A0D`), desaturated Latent Cyan (`#4FD8D0`) primary accent, and Latent Violet (`#9B7EF5`) secondary accent.
- **Official App Mark & Assets:** Imported the official `latent-mark.svg` (cyan-to-violet gradient container with rounded L-glyph) and `latent-lockup.svg` from the upstream design system repo. The 52px frameless titlebar in `App.vue` now renders `latent-mark.svg`.
- **Sidebar & Developer Attribution:** Updated `Sidebar.vue` to use standard `NavItem` styling with active tab highlights, a console toggle, and Alexander Nilsson's signature developer logo (`alx_logo.png`) linking to GitHub.
- **View Refactoring:** `SorterView.vue` and `FetcherView.vue` refactored to use token cards, monospace inputs for folder paths, architecture badges, Deep Scan & Dry Run switches, and CTA buttons.
- **Modals & Console:** `ConsoleWindow.vue`, `Settingsmodal.vue`, `Summarymodal.vue`, and `InfoModal.vue` restyled with tokenized dialog surfaces, desaturated backdrop scrims (`var(--color-surface-overlay)`), and JetBrains Mono monospace formatting.

### Verification Status

- **Frontend Build (`npm run build` in `frontend/`):** Passed with **BUILD SUCCESS** (zero compilation or CSS errors).
- **Backend Unit Tests (`mvn test` in `backend/`):** Passed all **83 unit tests** (0 failures, BUILD SUCCESS).

---

## 9. Icon System Standardization: PrimeIcons → Lucide

This app was the reference standard for a cross-app pass bringing Latent Library and Latent
Tools' chrome (sidebar, titlebar, icons) in line with it — the sidebar/titlebar here were
already correct and untouched. What changed here was the icon system itself: this app used
PrimeIcons (`pi pi-*`) exclusively, while Latent Library's main nav already used
`lucide-vue-next`. Standardized all three apps on Lucide.

- **`package.json`**: removed `primeicons`, added `"lucide-vue-next": "^1.0.0"` (matched to
  the version pinned in Latent Library's `frontend/package.json` for consistency). Note: npm
  flags `lucide-vue-next@1.0.0` as deprecated in favor of `@lucide/vue`, but the exact version
  was kept to match the sibling app.
- **9 files migrated**: `main.js` (dropped the `primeicons/primeicons.css` import),
  `App.vue`, `components/ConsoleWindow.vue`, `components/InfoModal.vue`,
  `components/Settingsmodal.vue`, `components/Sidebar.vue`, `components/Summarymodal.vue`,
  `views/FetcherView.vue`, `views/SorterView.vue`. All icons here are rendered as plain
  `<i class="pi ...">` elements (no PrimeVue Menu/TieredMenu icon-slot usage exists in this
  codebase), so every conversion was a direct template swap to `<IconName :size="16" />` or
  `<component :is="...">` for conditionally-chosen icons — no slot-plumbing needed, unlike
  Library's PrimeVue `<Button icon="...">`/`<Tree>` cases.
- Added a `.spin-icon` utility + `@keyframes spin` in `assets/css/components/base.css` for
  the `Loader2` replacements of `pi-spin pi-spinner` (no spin animation existed previously).
- `App.vue`'s status-bar icon previously combined multiple PrimeIcons classes
  (`pi-check-circle`/`pi-exclamation-triangle`/`pi-times-circle`/`pi-info-circle`/
  `pi-spin pi-spinner`) that could theoretically co-occur; replaced with a `statusIcon`
  computed with an explicit priority order (processing spinner > success > warning > error >
  default info) — a judgment call since the original classes had no documented precedence.
- A handful of icons had no listed mapping and were chosen by best semantic match:
  `pi-terminal`→`Terminal`, `pi-question-circle`→`HelpCircle`, `pi-chart-bar`→`BarChart3`,
  `pi-inbox`→`Inbox`, `pi-check-square`/`pi-stop`→`CheckSquare`/`Square`,
  `pi-stopwatch`→`Timer`, `pi-undo`→`Undo2`.
- **Verification**: `grep -rn "pi pi-\|primeicons\|pi-spin" frontend/src` returns zero
  results; `npm run build` succeeded (1764 modules, no errors). `lucide-vue-next` was
  installed with `--no-save`-equivalent scope limited to itself (not a full reinstall) purely
  to prove the build resolves the new import; `package-lock.json` reflects that one install.
  `primeicons` has not yet been removed from `node_modules` via a full `npm install` —
  `package.json` is the source of truth, a normal install will reconcile the lockfile.

---

## 10. Post-Migration Fixes (found via user screenshots)

Follow-up pass, same session as section 9 — two issues surfaced from a live
screenshot comparison against Latent Library:

- **Dev-credit logo opened in the wrong browser**: `components/Sidebar.vue`'s
  GitHub-profile link was a plain `<a href="..." target="_blank">`. This app's
  `electron/main.js` has an IPC `shell:openExternal` handler
  (`ipcMain.on('shell:openExternal', ...)` → `shell.openExternal`, already used
  correctly by `Settingsmodal.vue`'s Ko-fi link via
  `window.electronAPI.openExternal(...)`) but **no `setWindowOpenHandler`** override
  on the `BrowserWindow`'s `webContents`. Without that, Electron's default handling of
  `target="_blank"` anchors opened the link in a bare, unbranded Chromium window
  instead of the system default browser — reads as "the wrong browser" to a user.
  Fixed by removing `target="_blank"` and routing the click through
  `window.electronAPI.openExternal('https://github.com/erroralex')` on `@click.prevent`,
  matching the pattern the Ko-fi link already used. (Latent Library doesn't have this
  bug — its `electron/main.js` has a global `setWindowOpenHandler` that intercepts all
  external links app-wide, not just ones explicitly wired through IPC.)
- **Dev-credit logo size**: initially left as-is here (`width: 64px`) since Latent
  Library's `.dev-logo-img` was brought down to match it. The user then said the
  64px version read as too tiny and preferred Library's original
  `max-width: 120px; max-height: 44px` sizing — so this repo's `.dev-logo-img` was
  updated to `max-width: 120px; height: auto; max-height: 44px; object-fit:
  contain;` to match instead, flipping which app was the reference value for this
  one property.

**Verification**: `cd frontend && npm run build` clean.


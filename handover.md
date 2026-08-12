# Handover — Latent Model Organizer

Current state of the project, the traps that will bite you, and what is still open.

**This document is not a changelog.** Per-change narrative lives in git history (`git log`) and
in the GitHub releases. What belongs here is only what a newcomer cannot derive from reading the
code: non-obvious domain rules, the reasoning behind them, and open work.

---

## What this is

A desktop utility that organises large AI model libraries (`.safetensors`) by architecture, using
zero-copy safetensors header parsing plus Civitai metadata fetching. Two workflows:

- **Sorter** — classifies models by architecture and relocates them into per-architecture folders,
  with a persistent undo manifest.
- **Fetcher** — fills in missing sidecar metadata and preview images from Civitai, and writes
  trigger words and descriptions into the file the WebUI actually reads.

### Stack

| Layer | Stack | Location |
|---|---|---|
| Backend | Java 21, **raw `com.sun.net.httpserver.HttpServer`** | `backend/` |
| Frontend | Vue 3.5 + Vite 7 + PrimeVue 4.5 | `frontend/` |
| Desktop shell | Electron 28 | `electron/` |

**There is no Spring here.** The backend is a plain `main()` that builds an `HttpServer` on an
ephemeral port with a virtual-thread executor. The only runtime dependencies are Jackson, SLF4J
and Logback; tests add JUnit 5, Mockito and ArchUnit. Do not reach for Spring idioms
(`@RestController`, `@Service`, dependency injection) — nothing will wire them up. `AGENTS.md`
states this correctly and is worth trusting.

Backend version `1.2.0` (`backend/pom.xml` — the single source of truth, see Releases).

### Repository map

- `backend/src/main/java/com/nilsson/lmo/`
  - `LmoApplication.java` — entry point, HTTP routing, and the API surface. All routes are
    registered here via `server.createContext(...)`, each wrapped in `SecurityFilter`.
  - `service/` — the real logic: `ModelAnalyzer` (architecture detection), `OrganizationService`
    (move/undo), `CivitaiApiClient`, `ForgeUserMetadataWriter`, `UserMetadataBackfillService`.
  - `api/` — `SecurityFilter` (startup-token auth), `LogStreamHandler` + `SseLogAppender`
    (Server-Sent Events log streaming to the UI).
  - `domain/` — request/report records. `util/` — `HashUtil`, `HtmlToPlainText`.
- `frontend/src/` — `views/SorterView.vue` and `views/FetcherView.vue` are the two screens;
  `components/` holds the sidebar, console window and three modals. Design System tokens live in
  `assets/css/latent/`.
- `electron/main.js` — spawns the backend jar, discovers its port, owns the window.
- `.run/` — shared IntelliJ run configurations (committed deliberately; see below).

### API surface

`POST /api/organize`, `/api/undo`, `/api/fetch`, `/api/backfill-metadata`, `/api/shutdown`,
`/api/cancel`; `GET /api/logs` (SSE), `/api/progress`, `/api/architectures`, `/api/version`.

---

## How a model gets classified

`ModelAnalyzer.analyze()` resolves an architecture in this order, and **the order is the whole
design**:

1. **Sidecar** (`.civitai.info`) — Civitai's own `baseModel`, mapped through
   `mapBaseModelToArchitecture()`. Ground truth when present.
2. **Safetensors internal header** — `ss_base_model_version` and friends, read zero-copy.
3. **Filename heuristics** — `checkFilenameHeuristics()`, the last resort.

Fifteen architectures are currently supported (`SUPPORTED_ARCHITECTURES`).

### The generic-SDXL override exists for a reason

Illustrious, NoobAI and Pony are all SDXL fine-tunes, and **kohya_ss writes
`ss_base_model_version: "sdxl_base_v1-0"` into the header regardless of which specific checkpoint
was used** — it records the base family, not the fine-tune. That maps to the generic `"SDXL 1.0"`
bucket, which is not `"Unknown"`, so before the fix the header won and the filename was discarded:
`illustriousXL_*` and `noobaiXL_*` LoRAs sorted into `SDXL 1.0`.

`isGenericSdxlBucket()` / `isSpecificSdxlVariant()` implement the narrow override: when the header
resolves to generic SDXL **and** the filename names a more specific SDXL-derived family, the
filename wins. Do not widen this into "filename always beats header" — the header is more reliable
everywhere else.

This only ever surfaced on files that had never been fetched, because a `.civitai.info` sidecar
short-circuits at step 1.

### Fetch-first changes results, and that is correct

Running Fetch before Sort will reclassify some files — notably NoobAI-named models landing under
`Illustrious`. That is Civitai's own data granularity: NoobAI is a fine-tune *of* Illustrious and
uploaders often tag the parent lineage. Fetching first is still the more accurate path because it
uses Civitai's data instead of a local guess. This is not a bug; the UI tells users to fetch first.

---

## Invariants and traps

### The WebUI reads `<basename>.json`, not `.civitai.info`

`.civitai.info` belongs to the Civitai Helper extension. A1111 / Forge / Forge Neo never read it —
verified against a local Forge Neo install, where `modules/extra_networks.py` `get_user_metadata()`
reads exactly one file, `<basename>.json`, and the LoRA metadata editor populates "Activation text"
from its `"activation text"` key and "Description" from `"description"`.

This is why `ForgeUserMetadataWriter` exists: the data was already on disk and simply invisible.
It merge-writes, filling each field only when blank so user edits survive, via temp file + atomic
move.

Three details that are easy to get wrong:

- **Do not write the `sd version` key.** Forge Neo has diverged from upstream: its editor saves
  `"sd version"` but `read_user_metadata` reads `sd_version_str`. Writing it achieves nothing and
  risks confusing the editor.
- **Strip commas from `trainedWords` entries.** Many Civitai authors leave a trailing comma on
  each entry; joining verbatim with the `,, ` section separator produces `,,,` runs that shift the
  Card Master extension's section boundaries. Regression tests cover this.
- **There are two description fields, and they are not concatenated.** `model.description` is the
  model page text; the top-level `description` is a short version note. The model one wins, with
  the version note as fallback. (In the reference library of 1764 sidecars: 1260 vs 592.)

Descriptions arrive as HTML and the WebUI escapes them by default
(`extra_networks_card_description_is_html` defaults to `false`), so `HtmlToPlainText` reduces them
to text — otherwise every card renders literal `<p>` tags.

`POST /api/backfill-metadata` exists because `fetchMissingMetadata` skips any model that already
has a sidecar, which on a mature library is nearly all of them. The backfill is offline, does no
hashing or network calls, and is idempotent — safe to re-run.

### The undo manifest must never scan itself

When source and target directories coincide (organizing in place), `undo-manifest.json` from a
prior run sits right inside the directory being scanned. Before the fix, the candidate-file filter
in `OrganizationService.organizeModels` had no opinion on it, so `ModelAnalyzer` classified the
manifest itself as `Uncategorized` and moved it to `Uncategorized\undo-manifest (3).json` —
destroying the very undo trail a subsequent `/api/undo` depends on, and silently corrupting
history on every in-place sort thereafter.

`isUndoManifest()` filters `UNDO_MANIFEST_FILENAME` out of the scan alongside the existing
`isAlreadyOrganized()` check. Regression test:
`OrganizationServiceTest#organizeModels_shouldNotSortTheUndoManifestItself`.

### Preview extensions come from the URL, never from `images[0].type`

`type` describes what the **author uploaded**; the URL describes what the **CDN will return**, and
only the latter can determine the filename on disk.

In the reference library 27 sidecars have `type: "video"` but only 6 have an `.mp4` URL. The other
26 carry a `.jpeg` URL with a `width=450` transform and Civitai returns a **still frame** — those
bytes genuinely are JPEG. Keying off `type` would rename 26 working previews to `.mp4`, turning
4 broken previews into 30.

The only strictly authoritative signal is the response `Content-Type` header, but that requires
downloading before naming, and no case has been seen where the URL suffix disagreed with the bytes
in a way that broke rendering.

**Pre-existing mislabelling is harmless and should be left alone**: ~1715 preview files hold JPEG
bytes in a `.preview.png` name because the Civitai Helper extension names every preview that way.
Browsers sniff content type, so they render fine. Renaming them buys nothing.

### The CSS has a token layer and a legacy alias shim, and the order matters

`assets/css/latent/` is the vendored Latent Design System: `styles.css` imports the five
`tokens/*.css` files and then `aliases.css`. Everything under `assets/css/components/` and
every component `<style>` block is written against those.

`aliases.css` is a compatibility shim, not a token file. The component stylesheets were
written against the pre-Latent "Deep Neon" vocabulary (`--accent-primary`, `--text-primary`,
`--bg-input`, …) that used to be defined by `assets/css/themes/*.css`. Those theme files
stopped being imported during the design-system migration but were not deleted, so **15
variables across ~135 references resolved to nothing, with no fallback** — text colour,
button gradients, input borders and switch fills silently fell back to browser defaults.
The build succeeds either way, which is why it survived a full code review.

Two consequences worth internalising:

- **A missing CSS variable is invisible to every normal check.** Vite does not resolve
  `var()`, so the build cannot fail on it, and the page still renders — just wrong.
  `npm run lint:ds` exists specifically for this and exits non-zero on any `var(--x)` with
  no definition and no fallback.
- **Do not add names to `aliases.css`.** New CSS should use the `--color-*` / `--gradient-*`
  / `--glow-*` tokens directly. The shim exists to keep old stylesheets working, and it
  should shrink over time, not grow.

Fallbacks like `var(--sidebar-width, 200px)` are worse than useless once the token is always
defined: they are unreachable but advertise a value that is not in effect. Several removed
ones disagreed with the real token (200px vs 224px, Tailwind slate vs Latent borders).

### The global stylesheets were superseded by `-ds` scoped styles, not replaced

The design-system migration re-authored every component against `-ds`-suffixed class names in
its own `<style scoped>` block, but left the pre-migration global stylesheets in place. The
result was two parallel vocabularies where only one was ever matched: `.sidebar` vs
`.sidebar-ds`, `.arch-item` vs `.arch-item-ds`, `.status-bar.is-ok` vs
`.status-bar-ds.status-ok`. `layout.css` and `buttons.css` turned out to be **100 % dead —
113 rules, 76 of 81 class tokens never present in the DOM in any state** — and have been
deleted. `base.css` (resets, scrollbars, body) and `primevue-overrides.css` (PrimeVue's
runtime `.p-*` classes) are still live.

If you go looking for more dead CSS, two traps make it easy to get wrong:

- **`\b` word boundaries lie here.** `\bsidebar\b` matches inside `sidebar-ds`, so a naive
  grep reports dead rules as live. Split `class="…"` on whitespace and compare exact tokens.
- **A rule can look dead only because you never reached its state.** Cross-check the static
  markup scan against the live DOM (click through every view, modal and dropdown, collecting
  `classList` tokens), and remember a compound selector like `.toggle-track.checked` is dead
  if *either* class is absent, even when the other is in use elsewhere.

Before deleting a global rule, check whether the components that use it define it themselves:
`.sr-only` and `.no-drag` looked shared but every consumer already declared them in its own
scoped block, so the global copies were redundant rather than load-bearing.

**The design system's `_adherence.oxlintrc.json` does not apply to this app.** Its plugins are
`react`/`import` and its adherence rules are AST selectors over JS/JSX (`JSXOpeningElement`,
`Literal[value=/#hex/]`). LMO is Vue with plain CSS and has no JSX, so those selectors match
nothing here — wiring it in would produce a green lint that proves nothing. `lint:ds` reads
the `.css` and `.vue` files where the violations actually live instead.

### Electron: external links need the IPC bridge

`electron/main.js` has an `ipcMain.on('shell:openExternal', ...)` handler but **no
`setWindowOpenHandler`** override. A plain `<a target="_blank">` therefore opens in a bare,
unbranded Chromium window rather than the system browser. Route external links through a
script-level function calling `window.electronAPI.openExternal(url)` — see `openDevProfile()` in
`Sidebar.vue`, which also falls back to `window.open()` for when the app runs in a plain browser
via `npm run dev`. (`openKofi()` in `Settingsmodal.vue` follows the same IPC pattern but has no
such fallback, so that link is inert outside Electron.)

Call it from `<script setup>`, not inline in the template: a bare `window.` reference inside a
template expression did not resolve and the link silently did nothing.

### Backend port and token handshake

The backend binds port `0` so the OS assigns a free port, then writes `port:token` to
`.lmo-port` in the system temp directory and also emits it on stdout. Electron reads that file;
`SecurityFilter` rejects any `/api/` request without the token. In dev mode Electron waits for an
externally started backend to produce the file. If you need to drive the API by hand, read the
token from `%TEMP%\.lmo-port`.

### Maven is not on the PATH

There is **no Maven wrapper (`mvnw`)** in this repo — a genuine difference from Latent Library, so
`mvn` in the docs is correct and should not be "fixed" to `./mvnw`. There is also no standalone
`mvn` on the PATH. Use IntelliJ's bundled copy:

```
C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.3\plugins\maven-plugin\lib\maven3\bin\mvn.cmd
```

Note `plugins\maven-plugin\`, not `plugins\maven\` — the folder was renamed in recent builds.

### IntelliJ run configurations

They live in `.run/` as committed, shared files rather than in the gitignored
`.idea/workspace.xml`. If one reports "Configuration is still incorrect", the usual cause is a
missing `<module>` element, which leaves it with no classpath.

**The module is named after the artifactId, not the directory**: `latent-model-organizer-backend`,
not `backend`. A wrong name is silently stripped by the IDE on load.

Two things that look like causes and are not: the `ALTERNATIVE_JRE_PATH` pin resolves fine, and
`.idea/modules.xml` listing only `frontend` proves nothing — external module storage is enabled,
so Maven-derived modules live outside `.idea/`. Query the running IDE rather than reading `.idea/`.
Note also that the IDE uses the `IntelliJIdea2026.2` config directory despite the binary being
2025.2.3.

---

## Releases

Version lives in **`backend/pom.xml` only**. `.github/workflows/build.yml` reads it via
`mvn help:evaluate -Dexpression=project.version` and syncs it into `electron/package.json` and
`frontend/package.json` at build time with `npm version --no-git-tag-version`.

**The committed versions in those two `package.json` files are therefore stale by design**
(`electron/package.json` currently reads `1.0.0` against a pom of `1.2.0`). Do not hand-edit them
to "fix" the mismatch.

Flow:

1. Bump `<version>` in `backend/pom.xml` on `development`, commit, push.
2. Open a PR from `development` into `main` (repo convention: one PR per merge).
3. Tag the merge commit on `main`: `git tag -a vX.Y.Z <commit> -m "..."` and push the tag.
4. The tag triggers `build.yml`, which builds Windows/Linux/macOS artifacts and publishes a
   GitHub Release.

`gh` CLI is **not installed** on this machine, so PRs must be opened through the GitHub compare
URL: `https://github.com/erroralex/Latent-Model-Organizer/compare/main...development`.

### Every API handler must answer the CORS preflight, or the renderer cannot call it

The renderer is always cross-origin to the backend — `http://localhost:5173` in dev, `file://`
in production, against `http://127.0.0.1:<ephemeral>`. Any `fetch` carrying an `Authorization`
header is therefore a non-simple request, and the browser sends an `OPTIONS` preflight first.

So every handler in `LmoApplication` must begin:

```java
addCorsHeaders(exchange);
if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
    exchange.sendResponseHeaders(204, -1);
    return;
}
```

`SecurityFilter` deliberately lets `OPTIONS` through without a token so that preflight can
succeed — that is not a hole, it is what makes the scheme work.

`VersionHandler` was missing this branch and returned 405 to the preflight, so **`/api/version`
was unreachable from the UI entirely** while working perfectly over curl. That is what made the
Settings dialog show "vdev" even after the version-resolution fix below, and it made the
StatusPill report `Offline` against a healthy backend. Both symptoms, one cause.

The trap is that a handler with this bug looks completely healthy from any command-line client,
because curl sends no preflight. **Verify API changes from the running app, not only with curl.**
`LmoApplicationHttpTest` now covers the preflight contract for `/api/version`; extend it when
adding a route.

### The runtime version comes from a filtered resource, not from Maven coordinates

`GET /api/version` reads `version=${project.version}` out of `src/main/resources/version.properties`,
which Maven filters at build time. The `<resources>` block in `pom.xml` splits filtering so that
**only** that file is filtered — `logback.xml` must keep its `${...}` patterns intact.

Do not "simplify" this back to reading `META-INF/maven/<groupId>/<artifactId>/pom.properties`.
That path encodes the Maven coordinates and is exactly how this broke: it was written as
`com.nilsson.lmo/backend`, mixing the *Java package* with the jar's `finalName`, while the real
coordinates are `com.latent/latent-model-organizer-backend`. The resource never resolved, the
handler fell through to its `"dev"` fallback, and the Settings dialog showed "vdev" in every
released build. The filtered resource has no coordinates to get wrong, which also means it
survives the groupId unification proposed in the suite audit.

Note the Java package (`com.nilsson.lmo`) and the Maven groupId (`com.latent`) genuinely differ.
That mismatch is the underlying trap; expect it to bite anything else that assumes they match.

### Tagging trap

`build.yml` reads the version from the pom **on the tagged commit**. Tagging a `main` that has not
yet received the bump publishes a release whose name and artifacts disagree — this has happened
once. Confirm the version on the exact commit being tagged, not merely that `main` looks current:

```bash
git fetch origin
git show origin/main:backend/pom.xml | grep -m1 "<version>"
git log --oneline origin/main..origin/development   # must be empty
```

---

## Open issues

- **The backend port is handed to the renderer once, at startup.** If the backend dies and is
  restarted it comes up on a *new* ephemeral port, but the renderer still holds the old one, so
  the StatusPill correctly stays `Offline` until the app is restarted. Recovering would need a
  re-handshake over IPC; nothing does that today.
- **`lint:ds` only runs at release time.** `build.yml` runs it before the frontend build, so a
  regression cannot be shipped — but that workflow triggers only on `v*` tags, so nothing checks
  ordinary pushes or PRs. A regression stays invisible until someone cuts a release. A small
  push/PR-triggered workflow running `lint:ds` and `mvn test` would close the gap.
- **7 raw hex colours remain outside the token layer**, reported as warnings by `lint:ds`.
  One (`#FF5E5B` in `Settingsmodal.vue`) is the Ko-fi brand colour and legitimately is not a
  Latent token, so it needs an exemption rather than a fix. The warning list is the backlog —
  promote it to an error once it is empty.
- **Input borders fail WCAG non-text contrast.** Measured in the running app: the input
  boundary is **1.31:1** against the panel where 1.4.11 requires 3:1, the input *fill* is
  1.05:1 (it contributes nothing — the border does all the separation), and placeholder text
  is 4.16:1 against a 4.5:1 AA threshold. This is inherent to the DS border tokens:
  `--color-border-default` is `rgba(255,255,255,0.10)` and even `--color-border-strong`
  (0.18) will not reach 3:1 on these surfaces. Fixing it means either an LMO-local override
  or an upstream token change affecting all three apps — an open decision, not an oversight.
- **The `ds/` layer is started, not finished.** `components/ds/` now holds `StatusPill`,
  `LBadge` and `LSwitch`, against Latent-Library's 16. `LButton` is the obvious next one — it
  needs `full-width` and `icon-only` modifiers Latent-Library lacks and touches 13 call sites
  across 8 files, which is why it was split out.
- **Latent-Library has drifted from the design system**; treat the DS contract in
  `_adherence.oxlintrc.json` as authoritative, not the sibling implementation. Known drift:
  its `LBadge` substitutes `secondary` for the spec's `outline`; its `LSwitch` is a `<label>`
  with `@click.prevent` and no `<input>`, so it is not keyboard-reachable; its `StatusPill`
  initialises to `'online'` before the first health check and never clears its `setInterval`.
  LMO's ports fix all four deliberately — do not "resync" them to match the sibling.
- **Windows-only link setup.** `.agents/AGENTS.md` is an NTFS hard link to the root `AGENTS.md`,
  and `.claude/skills` is an NTFS junction to `.agents/skills`. Neither survives a clone on
  another machine or a non-Windows checkout, so a fresh environment needs them recreated before
  assistant instructions and skills resolve.

---

## Build, test, run

```bash
# Backend tests (91, all JUnit) - use the IntelliJ-bundled mvn, see above
cd backend && mvn test

# Frontend tests (35, Vitest + @vue/test-utils, jsdom)
cd frontend && npm run test

# Backend package
cd backend && mvn clean package -DskipTests

# Frontend design-system check (fails on undefined CSS variables; warns on raw hexes)
cd frontend && npm run lint:ds

# Frontend build (outputs to frontend/dist, which electron-builder copies in)
cd frontend && npm run build

# Packaged desktop app
cd electron && npm run dist
```

Running locally:

```bash
cd backend  && java -jar target/backend.jar   # terminal 1
cd frontend && npm run dev                    # terminal 2 (Vite on :5173)
cd electron && npm start                      # terminal 3
```

In dev mode Electron loads `http://localhost:5173`; in production it loads the packaged
`frontend/dist`. Unlike Latent Library, the frontend is **not** bundled into the jar, so there is
no stale-JAR trap for UI changes — but `npm run build` must have run before `npm run dist`, or the
packaged app ships an old renderer.

Before claiming a change works: run the tests and show the output.

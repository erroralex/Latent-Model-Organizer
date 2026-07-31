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

## 4. Release 1.1.0

Cut and published the first release since v1.0.0, covering the Krea 2 and new base model support above.

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

The previous setup was broken: the backend configuration pinned
`ALTERNATIVE_JRE_PATH="liberica-full-21"` and declared no `<module>`, producing
"Configuration is still incorrect" on launch. That SDK name exists only in the jdk.table of
IntelliJ 2025.3+; opening the project in 2025.2.3 leaves it unresolved. The shared configs
declare `<module name="backend" />` and pin no alternative JRE, so they inherit the project SDK
and work across IDE versions.

`.idea/misc.xml` still names `project-jdk-name="liberica-21"`, which has the same problem. If the
project SDK shows as unresolved, add `C:\Users\error\.jdks\liberica-full-21.0.12` under
File → Project Structure → SDKs and name it `liberica-21`.

**Still outstanding:** on opening the shared config, IntelliJ 2025.2.3 stripped the
`<module name="backend" />` element back out. The backend Maven project is **not imported** in
that IDE — `.idea/modules.xml` lists only `frontend` and the root module, there is no
`backend.iml`, and external module storage is empty. The module is therefore unresolvable by any
name, and an Application configuration without a module has no classpath.

To finish the fix: open the Maven tool window and add `backend/pom.xml`, then re-point the
configuration at the resulting module (named after the artifactId,
`latent-model-organizer-backend`, not `backend`). Until then the backend must be launched from
the command line: `java -jar backend/target/backend.jar`.

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

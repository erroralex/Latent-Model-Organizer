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
  & "C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd" test
  ```
  Result: **BUILD SUCCESS** (29 tests run, 0 failures).

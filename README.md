# Latent Model Organizer

![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Vue.js](https://img.shields.io/badge/Vue.js-3-4FC08D?style=for-the-badge&logo=vue.js&logoColor=white)
![Vite](https://img.shields.io/badge/Vite-5-646CFF?style=for-the-badge&logo=vite&logoColor=white)
![Electron](https://img.shields.io/badge/Electron-31-47848F?style=for-the-badge&logo=electron&logoColor=white)

A high-performance, ultra-low-memory desktop utility designed to wrangle massive AI model libraries. It organizes Checkpoints, LoRAs, and embeddings by architecture (SDXL, Pony, Flux, SD 1.5, etc.) using **zero-copy `.safetensors` header parsing** and an **automated Civitai metadata fetcher**—all wrapped in a premium, multi-themed glassmorphism interface.

---

## 📸 Interface

<p align="center">
  <img src="frontend/src/assets/screenshots/Organizer-LMO.png" width="800" alt="Main Organizer Interface">
  <br>
  <i>Sleek, frameless desktop UI with tabbed navigation for Sorting and Fetching.</i>
</p>

<p align="center">
  <img src="frontend/src/assets/screenshots/Fetcher-LMO.png" width="800" alt="CivitAi Fetcher">
  <br>
  <i><b>Fetcher:</b> Fetches metadata and preview images from Civitai.</i>
</p>

---

## 🔐 Fast, Portable & Safe

Engineered from the ground up for power users managing terabytes of models, prioritizing safety and speed.

* **Standalone Desktop App:** Runs as a single `.exe` (Windows), `.AppImage` (Linux), or `.dmg` (macOS). No installer required.
* **Bundled Runtime:** Includes a self-contained Java 21 environment. No system-wide Java installation is required.
* **Dry Run Mode:** Test your organization parameters safely. The app will calculate hashes, read headers, and output exactly what *would* happen to the console without moving a single file.
* **Deep Scanning:** Toggle recursive scanning to organize models buried deep within nested subfolders.
* **Non-Destructive Grouping:** Associated files (e.g., `model_v1.safetensors`, `model_v1.preview.png`, `model_v1.civitai.info`) are safely bundled and moved as atomic units.
* **Smart Collision Handling:** If a model with the same name already exists in the target folder, the app safely renames the incoming model and *all* of its associated sidecars (e.g., `model (1).safetensors`) to guarantee zero data loss.
* **Transactional Restores:** An automated `undo-manifest.json` tracks every move, allowing you to instantly revert thousands of files back to their exact original locations.

---

## ✨ Key Features

* **Zero-Memory Safetensors Parsing:** Reads massive 6GB+ `.safetensors` files without loading them into RAM. It memory-maps only the first 8 bytes to locate the exact length of the JSON header, bypassing heap memory bloat entirely.
* **Advanced Architecture Detection:** Automatically identifies base models (Flux, SDXL, Pony, Illustrious, SD 1.5, SD 3.5, Sana, Noob V, etc.) based on internal metadata tags or intelligent filename heuristics.
* **Civitai Metadata Fetcher (3-Tier Fallback):**
  1. Checks for local `.civitai.info` sidecar files (Instant).
  2. Reads the internal `.safetensors` metadata JSON header (Fast).
  3. If unknown, calculates a highly optimized SHA256 hash, queries the Civitai API asynchronously, saves the JSON locally, and streams the preview thumbnail directly to disk (Zero-Memory).
* **Smart File Grouping:** Employs a descending-length prefix-matching algorithm to ensure that orphaned preview images or text files are never left behind when their parent model is moved.
* **Blistering Performance:** * Powered by Java 21 **Virtual Threads (Project Loom)**.
  * Launches a lightweight virtual thread for *every single model group*, allowing your OS to parallelize massive I/O operations instantly. Organize thousands of models in seconds.
* **Modern UX & Customization:**
  * Premium "Glassmorphism" UI with draggable frameless window controls.
  * Real-time console terminal overlay for developer-level transparency.
  * Post-run Summary Modals detailing exactly how many files were moved to each architecture folder.

---

## 💻 System Requirements

* **OS:** Windows 10/11 (64-bit), Linux (AppImage), or macOS (11+).
* **Memory:** Extremely efficient. Requires < 100MB of RAM during peak operation. Minimum 2GB RAM overall.
* **Storage:** ~150MB for the application.
* **Network:** An active internet connection is only required when using the "Fetcher" mode to download metadata/images from Civitai. "Sorter" mode is 100% offline.

---

## 🛠️ Technical Architecture

Latent Model Organizer bypasses heavy frameworks to deliver raw native performance, combining an ultra-lightweight backend with a modern frontend.

* **Backend (Java 21):**
  * **Raw HttpServer:** Uses `com.sun.net.httpserver` with zero heavy dependencies (No Spring Boot, no external HTTP libraries).
  * **Project Loom:** `VirtualThreadPerTaskExecutor` handles extreme concurrency for file hashing and I/O.
  * **NIO FileChannels:** Byte-level manipulation of large files using `java.nio`.
  * **SSE Telemetry:** Custom Logback Appender pipes SLF4J logs directly to the frontend via Server-Sent Events.

* **Frontend (Vue 3 + Vite):**
  * **Composition API:** Highly reactive state management for UI toggles and API payloads.
  * **Dynamic Theme Engine:** Hot-swappable CSS custom properties injected at runtime.
  * **Glassmorphism CSS:** Centralized, premium design system utilizing `backdrop-filter`.

* **Desktop (Electron):**
  * **ContextBridge IPC:** Secure communication between the Vue renderer and native OS APIs (Folder selection, Window management).
  * **Process Orchestration:** Gracefully spins up and tears down the Java backend as a background child process.

---

## 🚀 Getting Started

[![Download Latest Release](https://img.shields.io/badge/Download-Latest_Release-2ea44f?style=for-the-badge&logo=github&logoColor=white)](https://github.com/erroralex/latent-model-organizer/releases/latest)

1.  **Download** the appropriate file for your OS:
    * **Windows:** `Latent Model Organizer Setup X.X.X.exe`
    * **Linux:** `Latent Model Organizer-X.X.X.AppImage` (mark as executable with `chmod +x`)
    * **macOS:** `Latent Model Organizer-X.X.X.dmg`
2.  **Run** the application. No installation is required.
3.  **Select Folders:** Pick your source directory and your target directory.
4.  *(Optional)* Toggle "Dry Run" on your first attempt to verify where models will be routed.
5.  **Click Start** and watch the virtual threads work their magic.

> **🍎 macOS Users:**
> Because this app is not yet signed with an Apple Developer Certificate, you may see an error saying the app is **"damaged and can't be opened."** This is a standard macOS security message for unsigned apps.
>
> **To fix this:**
> 1. Move the app to your **Applications** folder.
> 2. Open **Terminal**.
> 3. Run the following command to clear the quarantine attribute:
>    ```bash
>    sudo xattr -cr "/Applications/Latent Model Organizer.app"
>    ```
> 4. You can now open the app normally.

---

### 🔄 State Persistence
Latent Model Organizer automatically remembers your selected directories, chosen architectures, and toggle states (Deep Scan / Dry Run) via `localStorage`. You can safely close the app and pick up right where you left off.

---

## 📜 License

Distributed under the **MIT License**. Free for personal use.

---

## 💖 Support the Project

If **Latent Model Organizer** has saved you hours of manual sorting, consider supporting its ongoing development.

[![GitHub Sponsors](https://img.shields.io/badge/Sponsor-GitHub-ea4aaa?style=for-the-badge&logo=github-sponsors)](https://github.com/sponsors/erroralex)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-F16061?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/error_alex)

---

<p align="center">
  <b>Developed by</b><br>
  <img src="frontend/src/assets/alx_logo_neon.png" width="120" alt="Alexander Nilsson Logo"><br>
  Copyright (c) 2026 Alexander Nilsson
</p>
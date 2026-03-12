/**
 * @file preload.js — Latent Model Organizer
 *
 * Secure bridge between the Electron main process and the Vue 3 renderer.
 * Uses contextBridge + contextIsolation so the renderer never has direct
 * Node.js access.
 *
 * Exposed surface must exactly match what the Vue files call:
 *   window.electronAPI.selectFolder()   → dialog:selectFolder  (App.vue)
 *   window.electronAPI.openExternal(url)→ shell:openExternal   (SettingsModal.vue)
 *   window.windowAPI.minimize()         → window:minimize      (App.vue)
 *   window.windowAPI.maximize()         → window:maximize      (App.vue)
 *   window.windowAPI.close()            → window:close         (App.vue)
 */

const { contextBridge, ipcRenderer, shell } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
    selectFolder: () => ipcRenderer.invoke('dialog:selectFolder'),
    openExternal: (url) => ipcRenderer.send('shell:openExternal', url),
});

contextBridge.exposeInMainWorld('windowAPI', {
    minimize: () => ipcRenderer.send('window:minimize'),
    maximize: () => ipcRenderer.send('window:maximize'),
    close:    () => ipcRenderer.send('window:close'),
});
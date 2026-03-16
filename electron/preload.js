/**
 * THE PRELOAD BRIDGE
 *
 * This script serves as the secure, high-integrity bridge between the Electron main
 * process and the Vue 3 renderer. It executes in a privileged context with limited
 * access to Node.js APIs, enabling secure communication between the untrusted web
 * frontend and the trusted desktop environment.
 *
 * It utilizes Electron's contextBridge and contextIsolation to prevent the renderer
 * from having direct access to native system calls, adhering to the principle of
 * least privilege.
 *
 * Exposed APIs:
 * - electronAPI: Orchestrates native OS features including directory selection dialogs,
 *   cross-platform folder exploration via shell.openPath, external URL handling,
 *   dynamic backend port retrieval, and proxied undo operations.
 * - windowAPI: Provides a clean interface for controlling the application window state
 *   (minimize, maximize, close) from the custom glassmorphic title bar.
 */

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
    selectFolder: () => ipcRenderer.invoke('dialog:selectFolder'),
    openFolder: (folderPath) => ipcRenderer.invoke('shell:openFolder', folderPath),
    openExternal: (url) => ipcRenderer.send('shell:openExternal', url),
    closeApp: () => ipcRenderer.send('app:quit'),
    getBackendPort: () => ipcRenderer.invoke('app:getBackendPort'),
    undoLastOrganization: () => ipcRenderer.invoke('api:undoLastOrganization'),
});

contextBridge.exposeInMainWorld('windowAPI', {
    minimize: () => ipcRenderer.send('window:minimize'),
    maximize: () => ipcRenderer.send('window:maximize'),
    close: () => ipcRenderer.send('app:quit'),
});
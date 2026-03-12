/**
 * The secure bridge between the Electron main process and the Vue 3 renderer.
 *
 * This script executes in a privileged context with limited access to Node.js APIs,
 * enabling secure communication between the untrusted web frontend and the trusted
 * desktop environment. It uses contextBridge and contextIsolation to prevent 
 * the renderer from having direct access to native system calls.
 *
 * Exposed APIs include:
 * - electronAPI: Provides methods for native features like directory selection dialogs.
 * - windowAPI: Provides methods for controlling the application window state 
 *   (minimize, maximize, close) from the custom frontend title bar.
 */

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
    selectFolder: () => ipcRenderer.invoke('dialog:selectFolder')
});

contextBridge.exposeInMainWorld('windowAPI', {
    minimize: () => ipcRenderer.send('window:minimize'),
    maximize: () => ipcRenderer.send('window:maximize'),
    close: () => ipcRenderer.send('window:close')
});

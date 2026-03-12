/**
 * The main process controller for the Electron desktop application.
 *
 * This module orchestrates the native desktop environment for the Latent Model
 * Organizer, providing essential operating system integrations that are not
 * accessible to the browser-based frontend. It manages the application's lifecycle,
 * native window creation, and cross-process communication (IPC).
 *
 * Key responsibilities include:
 * - Frameless Window Orchestration: Configures a custom UI by disabling native OS frames
 *   and implementing manual window controls (minimize, maximize, close).
 * - IPC Bridging: Handles asynchronous requests for native directory dialogs and
 *   window state management through IPC listeners.
 * - System Integration: Provides access to the local file system for model scanning and organization.
 * - Graceful Stack Termination: Implements a specialized shutdown sequence that
 *   notifies the Java backend and forcefully terminates the Vite development server.
 */

const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('node:path');
const { exec } = require('child_process');

function createWindow() {
    const mainWindow = new BrowserWindow({
        width: 1400,
        height: 1200,
        title: "Latent Model Organizer",
        frame: false,
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false
        }
    });

    mainWindow.loadURL('http://localhost:5173');
}

app.whenReady().then(() => {
    ipcMain.handle('dialog:selectFolder', async () => {
        const result = await dialog.showOpenDialog({
            properties: ['openDirectory']
        });
        return result.canceled ? null : result.filePaths[0];
    });

    ipcMain.on('window:minimize', (event) => {
        const win = BrowserWindow.fromWebContents(event.sender);
        win?.minimize();
    });

    ipcMain.on('window:maximize', (event) => {
        const win = BrowserWindow.fromWebContents(event.sender);
        if (win?.isMaximized()) {
            win.unmaximize();
        } else {
            win?.maximize();
        }
    });

    ipcMain.on('window:close', (event) => {
        const win = BrowserWindow.fromWebContents(event.sender);
        win?.close();
    });

    createWindow();

    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
});

app.on('window-all-closed', async () => {
    console.log("Window closed. Initiating total stack shutdown...");

    try {
        console.log('Sending shutdown signal to Java backend...');
        await fetch('http://localhost:8080/api/shutdown', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' }
        });
        console.log('Java backend shutdown signal sent successfully.');
    } catch (error) {
        console.log('Java backend already closed or unreachable:', error.message);
    }

    const vitePort = 5173;
    console.log(`Force killing Vite on port ${vitePort}...`);

    if (process.platform === 'win32') {
        exec(`for /f "tokens=5" %a in ('netstat -aon ^| find ":${vitePort}" ^| find "LISTENING"') do taskkill /f /pid %a`, (err) => {
            if (!err) console.log("Vite process terminated.");
            app.quit();
        });
    } else {
        exec(`lsof -ti:${vitePort} | xargs kill -9`, (err) => {
            if (!err) console.log("Vite process terminated.");
            app.quit();
        });
    }

    setTimeout(() => {
        console.log("Fallback quit triggered.");
        app.quit();
    }, 1500);
});

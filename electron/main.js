/**
 * The primary process controller for the Electron application.
 *
 * This module orchestrates the native desktop environment, providing essential
 * services not accessible to the web-based renderer process. It manages the
 * application lifecycle, native window orchestration, and cross-process
 * communication (IPC).
 *
 * Core Responsibilities:
 * - Native Orchestration: Configures and manages the frameless BrowserWindow,
 *   injecting the secure preload bridge for frontend communication.
 * - IPC Gateway: Handles requests for native OS features including folder selection
 *   dialogs and opening external URLs in the default system browser.
 * - Window Management: Implements manual handlers for custom title bar controls
 *   (minimize, maximize, close) consistent with the glassmorphic UI design.
 * - Resource Sanitization: Executes a coordinated shutdown sequence that notifies
 *   the Java backend and forcefully terminates the Vite development server to
 *   ensure a clean system state upon exit.
 */

const {app, BrowserWindow, ipcMain, dialog, shell} = require('electron');
const path = require('node:path');
const {exec} = require('child_process');

function createWindow() {
    const mainWindow = new BrowserWindow({
        width: 1400,
        height: 1200,
        title: 'Latent Model Organizer',
        frame: false,
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false,
        },
    });

    mainWindow.loadURL('http://localhost:5173');
}

app.whenReady().then(() => {

    ipcMain.handle('dialog:selectFolder', async () => {
        const result = await dialog.showOpenDialog({properties: ['openDirectory']});
        return result.canceled ? null : result.filePaths[0];
    });

    ipcMain.on('shell:openExternal', (_event, url) => {
        if (typeof url === 'string' && url.startsWith('https://')) {
            shell.openExternal(url);
        }
    });

    ipcMain.on('window:minimize', (event) => {
        BrowserWindow.fromWebContents(event.sender)?.minimize();
    });

    ipcMain.on('window:maximize', (event) => {
        const win = BrowserWindow.fromWebContents(event.sender);
        if (win?.isMaximized()) win.unmaximize();
        else win?.maximize();
    });

    ipcMain.on('window:close', (event) => {
        BrowserWindow.fromWebContents(event.sender)?.close();
    });

    createWindow();

    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
});

app.on('window-all-closed', async () => {
    console.log('Window closed. Initiating total stack shutdown…');

    try {
        await fetch('http://localhost:8080/api/shutdown', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
        });
        console.log('Java backend shutdown signal sent.');
    } catch (err) {
        console.log('Java backend already closed or unreachable:', err.message);
    }

    const vitePort = 5173;
    const killCmd = process.platform === 'win32'
        ? `for /f "tokens=5" %a in ('netstat -aon ^| find ":${vitePort}" ^| find "LISTENING"') do taskkill /f /pid %a`
        : `lsof -ti:${vitePort} | xargs kill -9`;

    exec(killCmd, (err) => {
        if (!err) console.log('Vite process terminated.');
        app.quit();
    });

    setTimeout(() => {
        app.quit();
    }, 1500);
});

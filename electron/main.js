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
const {exec, spawn} = require('child_process');
const fs = require('fs');

// Reliable check for development mode
const isDev = !app.isPackaged;
let backendProcess = null;

function getBackendPath() {
    if (isDev) {
        // In dev, we assume the backend is either running separately OR
        // we can try to run the jar if it was built.
        // For simplicity in this project's context (per CONTRIBUTING.md),
        // we expect the dev to run the backend in IntelliJ.
        console.log('Development mode detected. Assuming backend is running externally or will be started manually.');
        return null;
    }
    
    // In production, look for the bundled JRE and JAR
    // structure: resources/app.asar/../../runtime/bin/java (or java.exe)
    // jar: resources/app.asar/../../runtime/app/backend.jar
    
    const rootDir = path.join(process.resourcesPath, '..', 'runtime');
    const binName = process.platform === 'win32' ? 'java.exe' : 'java';
    const javaPath = path.join(rootDir, 'bin', binName);
    const jarPath = path.join(rootDir, 'app', 'backend.jar');
    
    return { javaPath, jarPath };
}

function startBackend() {
    const paths = getBackendPath();
    if (!paths) return;

    if (!fs.existsSync(paths.javaPath)) {
        console.error(`JRE not found at: ${paths.javaPath}`);
        return;
    }
    if (!fs.existsSync(paths.jarPath)) {
        console.error(`Backend JAR not found at: ${paths.jarPath}`);
        return;
    }

    console.log('Starting Java backend...');
    backendProcess = spawn(paths.javaPath, ['-jar', paths.jarPath], {
        stdio: 'ignore', // Detach stdio or redirect to log file if needed
        windowsHide: true // Hide console window on Windows
    });

    backendProcess.on('error', (err) => {
        console.error('Failed to start backend:', err);
    });
    
    backendProcess.on('exit', (code) => {
        console.log(`Backend exited with code ${code}`);
    });
}

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

    if (isDev) {
        // In dev, wait a sec for Vite to be ready if running concurrently,
        // or just load localhost.
        console.log('Loading development URL...');
        mainWindow.loadURL('http://localhost:5173');
        // mainWindow.webContents.openDevTools(); // Optional: auto-open devtools
    } else {
        // In production, load the built Vue app
        const indexPath = path.join(__dirname, '../frontend/dist/index.html');
        mainWindow.loadFile(indexPath).catch(e => {
             console.error('Failed to load index.html:', e);
        });
    }
}

app.whenReady().then(() => {
    startBackend();

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
    console.log('Window closed. Initiating shutdown…');

    // 1. Kill backend gracefully if running (Production only)
    if (backendProcess) {
        try {
            await fetch('http://localhost:8080/api/shutdown', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
            });
        } catch (e) {
            backendProcess.kill(); 
        }
    } else if (isDev) {
        // In dev, strictly optional to try killing the external backend
        // We usually leave it running for faster dev cycles, but here we try to be clean.
        try {
            await fetch('http://localhost:8080/api/shutdown', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
            });
        } catch (ignored) {}
    }

    // 2. Kill Vite (Dev only)
    if (isDev) {
        // Just quit the app, Vite is usually managed by the terminal that ran `npm run dev`
        app.quit();
    } else {
        app.quit();
    }
});

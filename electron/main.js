/**
 * main.js
 *
 * The primary process controller for the Electron application.
 * It orchestrates the native desktop environment, lifecycle management,
 * and cross-process communication (IPC).
 *
 * Key Capabilities:
 * - Backend Lifecycle: Spawns and manages the Java backend process with ephemeral port discovery.
 * - Native Integration: Provides access to file dialogs and shell operations (openPath).
 * - IPC Bridge: Handles requests from the renderer for backend port resolution and window controls.
 * - Resource Management: Implements a clean shutdown sequence, terminating the backend
 *   and cleaning up temporary port-hint files on exit.
 */

const { app, BrowserWindow, ipcMain, dialog, shell } = require('electron');
const path = require('node:path');
const os = require('node:os');
const { spawn } = require('child_process');
const fs = require('fs');

const isDev = !app.isPackaged;
let backendProcess = null;
let backendPort = null;
let backendToken = null;
let isQuitting = false;
let splashWindow = null;
const PORT_FILE_PATH = path.join(os.tmpdir(), '.lmo-port');

function getBackendPaths() {
    if (isDev) {
        return null;
    }

    const rootDir = path.join(process.resourcesPath, '..', 'runtime');
    const binName = process.platform === 'win32' ? 'java.exe' : 'java';
    const javaDir = process.platform === 'darwin' ? path.join('Contents', 'Home', 'bin') : 'bin';

    return {
        javaPath: path.join(rootDir, javaDir, binName),
        jarPath: path.join(rootDir, 'app', 'backend.jar'),
    };
}

function startBackend() {
    const PORT_RESOLUTION_TIMEOUT_MS = 15_000;
    const PORT_POLL_INTERVAL_MS = 200;

    return new Promise((resolve, reject) => {
        let resolved = false;

        const finish = (port, token) => {
            if (resolved) return;
            resolved = true;
            backendPort = port;
            backendToken = token;
            console.log(`[main] Backend port resolved: ${port} with token ${token}`);
            resolve({ port, token });
        };

        const fail = (reason) => {
            if (resolved) return;
            resolved = true;
            console.error(`[main] Port resolution failed: ${reason}. Falling back to 8080.`);
            backendPort = 8080;
            resolve({ port: 8080, token: null });
        };

        const timeoutHandle = setTimeout(
            () => fail(`timeout after ${PORT_RESOLUTION_TIMEOUT_MS}ms`),
            PORT_RESOLUTION_TIMEOUT_MS
        );

        const startFilePoll = () => {
            const pollHandle = setInterval(() => {
                try {
                    const raw = fs.readFileSync(PORT_FILE_PATH, 'utf8').trim();
                    const parts = raw.split(':');
                    if (parts.length >= 2) {
                        const port = parseInt(parts[0], 10);
                        const token = parts[1];
                        if (!isNaN(port) && port > 0 && token) {
                            clearInterval(pollHandle);
                            clearTimeout(timeoutHandle);
                            finish(port, token);
                        }
                    }
                } catch {
                }
            }, PORT_POLL_INTERVAL_MS);
        };

        if (isDev) {
            try {
                if (fs.existsSync(PORT_FILE_PATH)) {
                    fs.unlinkSync(PORT_FILE_PATH);
                    console.log('[main] Stale port file deleted.');
                }
            } catch (err) {
                console.warn('[main] Could not delete stale port file:', err.message);
            }
            console.log('[main] Dev mode - waiting for external backend port file...');
            startFilePoll();
            return;
        }

        const paths = getBackendPaths();

        if (!fs.existsSync(paths.javaPath)) {
            clearTimeout(timeoutHandle);
            fail(`JRE not found at: ${paths.javaPath}`);
            return;
        }
        if (!fs.existsSync(paths.jarPath)) {
            clearTimeout(timeoutHandle);
            fail(`Backend JAR not found at: ${paths.jarPath}`);
            return;
        }

        console.log('[main] Spawning Java backend...');
        backendProcess = spawn(paths.javaPath, [
            '-Djava.net.preferIPv4Stack=true', // <-- ADD THIS FLAG
            '-jar',
            paths.jarPath
        ], {
            stdio: ['ignore', 'pipe', 'ignore'],
            windowsHide: true,
        });

        backendProcess.stdout.on('data', (data) => {
            const text = data.toString();
            const match = text.match(/LMO_PORT=(\d+):([a-f0-9\-]+)/);
            if (match) {
                const port = parseInt(match[1], 10);
                const token = match[2];
                clearTimeout(timeoutHandle);
                finish(port, token);
            }
        });

        backendProcess.on('error', (err) => {
            console.error('[main] Failed to start backend:', err);
        });

        backendProcess.on('exit', (code) => {
            console.log(`[main] Backend exited with code ${code}`);
        });

        startFilePoll();
    });
}

function createSplashWindow() {
    splashWindow = new BrowserWindow({
        width: 400, height: 500, transparent: true, frame: false, alwaysOnTop: true,
        icon: path.join(__dirname, '../frontend/src/assets/lmo_icon.png')
    });
    splashWindow.loadFile(path.join(__dirname, 'splash.html'));
}

function createWindow() {
    const mainWindow = new BrowserWindow({
        width: 1400,
        height: 1200,
        title: 'Latent Model Organizer',
        icon: path.join(__dirname, '../frontend/src/assets/lmo_icon.png'),
        frame: false,
        show: false,
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false,
        },
    });

    if (isDev) {
        mainWindow.loadURL('http://localhost:5173');
    } else {
        const indexPath = path.join(__dirname, 'dist', 'index.html');
        mainWindow.loadFile(indexPath).catch((e) => {
            console.error('[main] Failed to load index.html:', e);
        });
    }

    mainWindow.once('ready-to-show', () => {
        if (splashWindow && !splashWindow.isDestroyed()) {
            splashWindow.close();
        }
        mainWindow.show();
    });
}

app.whenReady().then(async () => {
    createSplashWindow();
    await startBackend();

    ipcMain.handle('dialog:selectFolder', async () => {
        const result = await dialog.showOpenDialog({ properties: ['openDirectory'] });
        return result.canceled ? null : result.filePaths[0];
    });

    ipcMain.handle('shell:openFolder', (_event, folderPath) => {
        if (typeof folderPath === 'string' && folderPath.length > 0) {
            return shell.openPath(folderPath);
        }
        return Promise.resolve('Invalid path');
    });

    ipcMain.handle('app:getBackendPort', () => ({ port: backendPort, token: backendToken }));

    ipcMain.handle('api:undoLastOrganization', async () => {
        const res = await fetch(`http://127.0.0.1:${backendPort}/api/undo`, {
            method: 'POST',
            headers: { 'Authorization': 'Bearer ' + backendToken }
        });
        if (!res.ok) throw new Error(`Undo failed with status ${res.status}`);
        return res.json();
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

    ipcMain.on('window:close', () => app.quit());
    ipcMain.on('app:quit', () => app.quit());

    createWindow();

    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
});

app.on('before-quit', async (event) => {
    if (isQuitting) return;

    event.preventDefault();
    isQuitting = true;

    console.log('[main] App quitting - initiating cleanup...');

    if (backendPort && backendToken) {
        const shutdownUrl = `http://127.0.0.1:${backendPort}/api/shutdown`;
        try {
            await fetch(shutdownUrl, {
                method: 'POST',
                headers: { 
                    'Content-Type': 'application/json',
                    'Authorization': 'Bearer ' + backendToken
                },
            });
            console.log('[main] Backend shutdown signal sent.');
        } catch (err) {
            console.warn('[main] Could not send shutdown signal to backend:', err.message);
            if (backendProcess) backendProcess.kill();
        }
    } else {
        console.log('[main] No backend connected, skipping backend shutdown signal.');
        if (backendProcess) backendProcess.kill();
    }

    try {
        if (fs.existsSync(PORT_FILE_PATH)) {
            fs.unlinkSync(PORT_FILE_PATH);
            console.log('[main] Port file deleted.');
        }
    } catch (err) {
        console.warn('[main] Could not delete port file:', err.message);
    }

    if (isDev) {
        try {
            const killPort = require('kill-port');
            await killPort(5173);
            console.log('[main] Vite dev server on port 5173 terminated.');
        } catch (err) {
            console.warn('[main] Could not kill Vite process:', err.message);
        }
    }

    console.log('[main] Cleanup complete. Finalizing quit.');
    app.quit();
});

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
        app.quit();
    }
});

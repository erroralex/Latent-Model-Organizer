/**
 * The primary process controller for the Electron application.
 *
 * This module orchestrates the native desktop environment, providing essential
 * services not accessible to the web-based renderer process. It manages the
 * application lifecycle, native window orchestration, and cross-process
 * communication (IPC).
 */

const { app, BrowserWindow, ipcMain, dialog, shell } = require('electron');
const path = require('node:path');
const os = require('node:os');
const { spawn } = require('child_process');
const fs = require('fs');

const isDev = !app.isPackaged;
let backendProcess = null;
let backendPort = null;
let isQuitting = false;
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

        const finish = (port) => {
            if (resolved) return;
            resolved = true;
            backendPort = port;
            console.log(`[main] Backend port resolved: ${port}`);
            resolve(port);
        };

        const fail = (reason) => {
            if (resolved) return;
            resolved = true;
            console.error(`[main] Port resolution failed: ${reason}. Falling back to 8080.`);
            backendPort = 8080;
            resolve(8080);
        };

        const timeoutHandle = setTimeout(
            () => fail(`timeout after ${PORT_RESOLUTION_TIMEOUT_MS}ms`),
            PORT_RESOLUTION_TIMEOUT_MS
        );

        const startFilePoll = () => {
            const pollHandle = setInterval(() => {
                try {
                    const raw = fs.readFileSync(PORT_FILE_PATH, 'utf8').trim();
                    const port = parseInt(raw, 10);
                    if (!isNaN(port) && port > 0) {
                        clearInterval(pollHandle);
                        clearTimeout(timeoutHandle);
                        finish(port);
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
        backendProcess = spawn(paths.javaPath, ['-jar', paths.jarPath], {
            stdio: ['ignore', 'pipe', 'ignore'],
            windowsHide: true,
        });

        backendProcess.stdout.on('data', (data) => {
            const text = data.toString();
            const match = text.match(/LMO_PORT=(\d+)/);
            if (match) {
                const port = parseInt(match[1], 10);
                clearTimeout(timeoutHandle);
                finish(port);
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
        mainWindow.loadURL('http://localhost:5173');
    } else {
        const indexPath = path.join(__dirname, '../frontend/dist/index.html');
        mainWindow.loadFile(indexPath).catch((e) => {
            console.error('[main] Failed to load index.html:', e);
        });
    }
}

app.whenReady().then(async () => {
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

    ipcMain.handle('app:getBackendPort', () => backendPort);

    ipcMain.handle('api:undoLastOrganization', async () => {
        const res = await fetch(`http://localhost:${backendPort}/api/undo`, {
            method: 'POST',
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

    const shutdownUrl = `http://localhost:${backendPort}/api/shutdown`;

    try {
        await fetch(shutdownUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
        });
        console.log('[main] Backend shutdown signal sent.');
    } catch (err) {
        console.warn('[main] Could not send shutdown signal to backend:', err.message);
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

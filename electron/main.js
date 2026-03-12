const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('node:path');
const { exec } = require('child_process');

function createWindow() {
    const mainWindow = new BrowserWindow({
        width: 1200,
        height: 800,
        title: "Latent Model Organizer",
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true, // Crucial for security
            nodeIntegration: false
        }
    });

    // In dev mode, Vite runs on port 5173.
    // In production, you would load the static index.html built by Vite.
    mainWindow.loadURL('http://localhost:5173');
}

app.whenReady().then(() => {
    // Register IPC handler for native directory selection
    ipcMain.handle('dialog:selectFolder', async () => {
        const result = await dialog.showOpenDialog({
            properties: ['openDirectory']
        });
        return result.canceled ? null : result.filePaths[0];
    });

    createWindow();

    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
});

app.on('window-all-closed', async () => {
    console.log("Red X clicked. Initiating total stack shutdown...");

    // 1. Send shutdown signal to the Java 21 Backend
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

    // 2. Forcefully kill the Vite Development Server (Port 5173)
    const vitePort = 5173;
    console.log(`Force killing Vite on port ${vitePort}...`);

    if (process.platform === 'win32') {
        // Windows: Find the PID listening on 5173 and taskkill it
        exec(`for /f "tokens=5" %a in ('netstat -aon ^| find ":${vitePort}" ^| find "LISTENING"') do taskkill /f /pid %a`, (err) => {
            if (!err) console.log("Vite process terminated.");
            app.quit();
        });
    } else {
        // macOS/Linux: Find the PID using lsof and kill it
        exec(`lsof -ti:${vitePort} | xargs kill -9`, (err) => {
            if (!err) console.log("Vite process terminated.");
            app.quit();
        });
    }

    // Fallback safety net: Ensure Electron quits even if the port kill command hangs
    setTimeout(() => {
        console.log("Fallback quit triggered.");
        app.quit();
    }, 1500);
});
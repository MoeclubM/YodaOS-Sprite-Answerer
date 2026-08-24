const { app, BrowserWindow, ipcMain, powerSaveBlocker } = require('electron');
const path = require('path');

let displayBlockerId = null;
let suspendBlockerId = null;

function createWindow() {
  const win = new BrowserWindow({
    width: 480,
    height: 640,
    backgroundColor: '#000000',
    titleBarStyle: 'hiddenInset',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    },
    autoHideMenuBar: true
  });

  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.setAspectRatio(480/640);
  // 保持前台可见
  win.setAlwaysOnTop(false);
  if (process.argv.includes('--dev')) win.webContents.openDevTools({ mode: 'detach' });
}

app.whenReady().then(() => {
  try {
    displayBlockerId = powerSaveBlocker.start('prevent-display-sleep');
    suspendBlockerId = powerSaveBlocker.start('prevent-app-suspension');
    console.log('[keepalive] display', displayBlockerId, 'suspend', suspendBlockerId);
  } catch (e) { console.warn('powerSaveBlocker failed', e); }
  try { app.commandLine.appendSwitch('disable-renderer-backgrounding'); } catch {}

  createWindow();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (powerSaveBlocker.isStarted(displayBlockerId)) powerSaveBlocker.stop(displayBlockerId);
  if (powerSaveBlocker.isStarted(suspendBlockerId)) powerSaveBlocker.stop(suspendBlockerId);
  if (process.platform !== 'darwin') app.quit();
});

ipcMain.handle('solve', async (event, dataUrl) => {
  const { runPipeline } = require('./src/agent/pipeline');
  const win = BrowserWindow.getFocusedWindow();
  const onProgress = (stage, data) => {
    if (win) win.webContents.send('progress', { stage, data });
  };
  try {
    const result = await runPipeline(dataUrl, onProgress);
    return result;
  } catch (e) {
    return { error: e.message || String(e) };
  }
});

ipcMain.handle('pick-image', async () => {
  const { dialog } = require('electron');
  const res = await dialog.showOpenDialog({
    properties: ['openFile'],
    filters: [{ name: 'Images', extensions: ['png','jpg','jpeg','webp'] }]
  });
  if (res.canceled || !res.filePaths.length) return null;
  const fs = require('fs');
  const buf = fs.readFileSync(res.filePaths[0]);
  const ext = path.extname(res.filePaths[0]).slice(1) || 'png';
  return `data:image/${ext};base64,${buf.toString('base64')}`;
});

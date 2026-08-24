const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  solve: (dataUrl) => ipcRenderer.invoke('solve', dataUrl),
  pickImage: () => ipcRenderer.invoke('pick-image'),
  onProgress: (cb) => {
    const handler = (_, data) => cb(data);
    ipcRenderer.on('progress', handler);
    return () => ipcRenderer.removeListener('progress', handler);
  }
});

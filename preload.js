// preload.js - 在渲染进程加载之前运行
// 用于安全地暴露 Node.js API 给渲染进程

const { contextBridge, ipcRenderer } = require('electron');

// 暴露视频相关的API给渲染进程
contextBridge.exposeInMainWorld('videoAPI', {
  // 扫描指定目录的视频文件
  scanVideos: (dirPath) => ipcRenderer.invoke('scan-videos', dirPath)
});


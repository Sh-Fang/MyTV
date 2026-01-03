// preload.js - 在渲染进程加载之前运行
// 用于安全地暴露 Node.js API 给渲染进程

const { contextBridge, ipcRenderer } = require('electron');

// 暴露视频相关的API给渲染进程
contextBridge.exposeInMainWorld('videoAPI', {
  // 选择目录
  selectDirectory: () => ipcRenderer.invoke('select-directory'),
  // 获取保存的目录
  getSavedDirectory: () => ipcRenderer.invoke('get-saved-directory'),
  // 获取用户主目录的 Videos 文件夹路径
  getVideosPath: () => ipcRenderer.invoke('get-videos-path'),
  // 扫描指定目录的视频文件
  scanVideos: (dirPath) => ipcRenderer.invoke('scan-videos', dirPath)
});


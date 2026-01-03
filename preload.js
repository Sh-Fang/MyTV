// preload.js - 在渲染进程加载之前运行
// 用于安全地暴露 Node.js API 给渲染进程
// 注意：preload 脚本必须使用 CommonJS，即使主进程使用 ESM

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
  scanVideos: (dirPath) => ipcRenderer.invoke('scan-videos', dirPath),
  
  // 新增：频道相关 API
  // 扫描频道（扫描子文件夹）
  scanChannels: (rootPath) => ipcRenderer.invoke('scan-channels', rootPath),
  // 保存频道数据到数据库（单个频道）
  saveChannelData: (channelData) => ipcRenderer.invoke('save-channel-data', channelData),
  // 保存所有频道数据（全量覆盖）
  saveAllChannels: (allChannels) => ipcRenderer.invoke('save-all-channels', allChannels),
  // 从数据库获取所有频道
  getChannels: () => ipcRenderer.invoke('get-channels'),
  // 搜索视频
  searchVideos: (keyword) => ipcRenderer.invoke('search-videos', keyword),
  // 搜索历史相关
  getSearchHistory: () => ipcRenderer.invoke('get-search-history'),
  addSearchHistory: (keyword) => ipcRenderer.invoke('add-search-history', keyword),
  clearSearchHistory: () => ipcRenderer.invoke('clear-search-history')
});


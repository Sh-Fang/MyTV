import { app, BrowserWindow, ipcMain, dialog } from 'electron';
import path from 'path';
import fs from 'fs';
import os from 'os';
import Store from 'electron-store';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

let mainWindow;
const store = new Store();

// 获取所有频道数据
function getChannelsData() {
  return store.get('channels', []);
}

// 保存所有频道数据
function saveChannelsData(channels) {
  store.set('channels', channels);
}

// 获取配置
function getConfig(key, defaultValue = null) {
  return store.get(`config.${key}`, defaultValue);
}

// 保存配置
function setConfig(key, value) {
  store.set(`config.${key}`, value);
}

function createWindow() {
  // 创建浏览器窗口
  mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      nodeIntegration: false,
      contextIsolation: true
    }
  });

  // 加载 index.html
  mainWindow.loadFile('index.html');

  // 打开开发者工具（开发时使用）
  mainWindow.webContents.openDevTools();
}

// 获取用户主目录的 Videos 文件夹路径
function getVideosPath() {
  const homeDir = os.homedir();
  let videosPath;
  
  if (process.platform === 'win32') {
    videosPath = path.join(homeDir, 'Videos');
  } else if (process.platform === 'darwin') {
    videosPath = path.join(homeDir, 'Movies');
  } else {
    videosPath = path.join(homeDir, 'Videos');
  }
  
  return videosPath;
}

// 扫描视频文件
function scanVideoFiles(dirPath) {
  const videoExtensions = ['.mp4', '.avi', '.mkv', '.mov', '.wmv', '.flv', '.webm', '.m4v'];
  
  try {
    if (!fs.existsSync(dirPath)) {
      return [];
    }

    const files = fs.readdirSync(dirPath);
    const videoFiles = [];

    for (const file of files) {
      const filePath = path.join(dirPath, file);
      const stat = fs.statSync(filePath);
      
      if (stat.isFile()) {
        const ext = path.extname(file).toLowerCase();
        if (videoExtensions.includes(ext)) {
          videoFiles.push({
            name: file,
            path: filePath,
            size: stat.size,
            modified: stat.mtime
          });
        }
      }
    }

    return videoFiles;
  } catch (error) {
    console.error('Error scanning directory:', error);
    return [];
  }
}

// 扫描频道（扫描子文件夹）
function scanChannels(rootPath) {
  const channels = [];
  
  try {
    if (!fs.existsSync(rootPath)) {
      return [];
    }
    
    const items = fs.readdirSync(rootPath);
    
    for (const item of items) {
      const itemPath = path.join(rootPath, item);
      const stat = fs.statSync(itemPath);
      
      if (stat.isDirectory()) {
        const videos = scanVideoFiles(itemPath);
        
        if (videos.length > 0) {
          channels.push({
            name: item,
            path: itemPath,
            videos: videos
          });
        }
      }
    }
    
    console.log(`扫描到 ${channels.length} 个频道`);
    return channels;
  } catch (error) {
    console.error('扫描频道错误:', error);
    return [];
  }
}

// IPC 处理程序
ipcMain.handle('select-directory', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ['openDirectory'],
    title: '选择视频文件夹'
  });
  
  if (result.canceled) {
    return null;
  }
  
  const selectedPath = result.filePaths[0];
  setConfig('videoDirectory', selectedPath);
  
  return selectedPath;
});

ipcMain.handle('get-saved-directory', async () => {
  return getConfig('videoDirectory');
});

ipcMain.handle('get-videos-path', async () => {
  return getVideosPath();
});

ipcMain.handle('scan-videos', async (event, dirPath) => {
  return scanVideoFiles(dirPath);
});

// 扫描频道（返回频道结构，不包含时长）
ipcMain.handle('scan-channels', async (event, rootPath) => {
  return scanChannels(rootPath);
});

// 保存频道数据
ipcMain.handle('save-channel-data', async (event, channelData) => {
  try {
    const channels = getChannelsData();
    
    // 查找是否已存在该频道（按 path 匹配）
    const existingIndex = channels.findIndex(c => c.path === channelData.path);
    
    // 计算总时长
    const totalDuration = channelData.videos.reduce((sum, v) => sum + (v.duration || 0), 0);
    
    // 构造频道对象
    const channel = {
      id: existingIndex >= 0 ? channels[existingIndex].id : Date.now(),
      name: channelData.name,
      path: channelData.path,
      video_count: channelData.videos.length,
      total_duration: totalDuration,
      last_scan_time: new Date().toISOString(),
      videos: channelData.videos.map((video, index) => ({
        name: video.name,
        path: video.path,
        duration: video.duration || 0,
        file_size: video.size,
        position_in_channel: index
      }))
    };
    
    // 更新或添加频道
    if (existingIndex >= 0) {
      channels[existingIndex] = channel;
    } else {
      channels.push(channel);
    }
    
    saveChannelsData(channels);
    console.log(`频道 ${channelData.name} 保存成功，包含 ${channelData.videos.length} 个视频`);
    return { success: true };
  } catch (error) {
    console.error('保存频道数据失败:', error);
    throw error;
  }
});

// 读取所有频道
ipcMain.handle('get-channels', async () => {
  try {
    const channels = getChannelsData();
    console.log(`读取 ${channels.length} 个频道`);
    return channels;
  } catch (error) {
    console.error('读取频道数据失败:', error);
    return [];
  }
});

// 不再需要这个处理程序，因为在同一窗口播放
// ipcMain.handle('play-video', ...) 已移除

// 当 Electron 完成初始化并准备创建浏览器窗口时调用此方法
app.whenReady().then(() => {
  createWindow();

  app.on('activate', function () {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

// 当所有窗口都关闭时退出应用
app.on('window-all-closed', function () {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});


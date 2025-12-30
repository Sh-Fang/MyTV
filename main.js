const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const fs = require('fs');

let mainWindow;

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

// IPC 处理程序
ipcMain.handle('scan-videos', async (event, dirPath) => {
  return scanVideoFiles(dirPath);
});

// 不再需要这个处理程序，因为在同一窗口播放
// ipcMain.handle('play-video', ...) 已移除

// 当 Electron 完成初始化并准备创建浏览器窗口时调用此方法
app.whenReady().then(() => {
  createWindow();

  app.on('activate', function () {
    // 在 macOS 上，当点击 dock 图标并且没有其他窗口打开时，
    // 通常会在应用程序中重新创建一个窗口
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

// 当所有窗口都关闭时退出应用
app.on('window-all-closed', function () {
  // 在 macOS 上，应用程序和菜单栏通常会保持活动状态，
  // 直到用户使用 Cmd + Q 明确退出
  if (process.platform !== 'darwin') app.quit();
});


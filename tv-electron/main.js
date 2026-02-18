const { app, BrowserWindow } = require('electron');

function createWindow() {
    const win = new BrowserWindow({
        fullscreen: true,
        backgroundColor: '#000',
        webPreferences: {
            autoplayPolicy: 'no-user-gesture-required',
        },
    });

    win.loadURL('http://localhost:8080/tv.html');
}

app.whenReady().then(createWindow);

app.on('window-all-closed', () => app.quit());

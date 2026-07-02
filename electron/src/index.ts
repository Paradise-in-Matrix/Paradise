import type { CapacitorElectronConfig } from "@capacitor-community/electron";
import {
    getCapacitorElectronConfig,
    setupElectronDeepLinking,
} from "@capacitor-community/electron";
import type { MenuItemConstructorOptions } from "electron";
import { app, MenuItem, Tray, Menu, nativeImage, dialog } from "electron";
import electronIsDev from "electron-is-dev";
import unhandled from "electron-unhandled";
import { autoUpdater } from "electron-updater";
import { join } from "path";

import {
    ElectronCapacitorApp,
    setupContentSecurityPolicy,
    setupReloadWatcher,
} from "./setup";

// Graceful handling of unhandled errors.
unhandled();

// Define our menu templates (these are optional)
const trayMenuTemplate: (MenuItemConstructorOptions | MenuItem)[] = [
    new MenuItem({ label: "Quit App", role: "quit" }),
];
const appMenuBarMenuTemplate: (MenuItemConstructorOptions | MenuItem)[] = [
    { role: process.platform === "darwin" ? "appMenu" : "fileMenu" },
    { role: "viewMenu" },
];

// Get Config options from capacitor.config
const capacitorFileConfig: CapacitorElectronConfig =
    getCapacitorElectronConfig();

const myCapacitorApp = new ElectronCapacitorApp(
    capacitorFileConfig,
    null,
    appMenuBarMenuTemplate
);

// If deeplinking is enabled then we will set it up here.
if (capacitorFileConfig.electron?.deepLinkingEnabled) {
    setupElectronDeepLinking(myCapacitorApp, {
        customProtocol:
            capacitorFileConfig.electron.deepLinkingCustomProtocol ??
            "mycapacitorapp",
    });
}

// If we are in Dev mode, use the file watcher components.
if (electronIsDev) {
    setupReloadWatcher(myCapacitorApp);
}

// Run Application
(async () => {
    await app.whenReady();
    setupContentSecurityPolicy(myCapacitorApp.getCustomURLScheme());
    await myCapacitorApp.init();

    const mainWindow = myCapacitorApp.getMainWindow();
    if (mainWindow) {
        mainWindow.on("close", (event) => {
            if (!isQuitting) {
                event.preventDefault();
                mainWindow.hide();
                if (process.platform === "darwin") {
                    app.hide();
                }
            }
        });
    }

    autoUpdater.checkForUpdatesAndNotify();
})();

// When the dock icon is clicked.
app.on('activate', async function () {
  // On OS X it's common to re-create a window in the app when the
  // dock icon is clicked and there are no other windows open.
  if (myCapacitorApp.getMainWindow().isDestroyed()) {
    await myCapacitorApp.init();
  }
});

// Place all ipc or other electron api calls and custom functionality under this line

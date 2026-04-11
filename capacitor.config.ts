import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
    appId: "com.gigiaj.paradise",
    appName: "Paradise",
    webDir: "dist",
    server: {
        // url: "http://192.168.3.33:8000",
        cleartext: true,
        //        allowNavigation: ["192.168.3.33"],
        // androidScheme: "https",
    },
    plugins: {
        StatusBar: {
            overlaysWebView: true,
        },
        SplashScreen: {
            launchShowDuration: 3000,
            launchAutoHide: false,
            backgroundColor: "#313338",
            showSpinner: true,
            androidSpinnerStyle: "large",
            iosSpinnerStyle: "small",
            spinnerColor: "#dbdee1",
        },
        PushNotifications: {
            presentationOptions: ["badge", "sound", "alert"],
        },
    },
};

export default config;

import { defineConfig, loadEnv } from "vite";
import wasm from "vite-plugin-wasm";
import topLevelAwait from "vite-plugin-top-level-await";
import { viteStaticCopy } from "vite-plugin-static-copy";
import { VitePWA } from "vite-plugin-pwa";
import commonjs from "vite-plugin-commonjs";
import path from "path";
import fs from "node:fs";
import * as babel from "@babel/core";

const copyFiles = {
    targets: [
        {
            src: path.resolve(
                __dirname,
                "node_modules/@element-hq/element-call-embedded/dist/*"
            ),
            dest: "element-call",
        },

        {
            src: path.resolve(
                __dirname,
                "node_modules/ffi-bindings/src/generated-compat/wasm-bindgen"
            ),
            dest: "generated-compat",
        },
        {
            src: "config.edn",
            dest: ".",
        },
        {
            src: "i18n.edn",
            dest: ".",
        },
        {
            src: "css/*",
            dest: "css",
        },
    ],
};

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, process.cwd());

    return {
        publicDir: "../public",
        root: "./build",
        plugins: [
            wasm(),
            viteStaticCopy(copyFiles),
            VitePWA({
                strategies: "injectManifest",
                srcDir: ".",
                filename: "sw.js",
                injectRegister: "inline",
                injectManifest: {
                    injectionPoint: "self.__WB_MANIFEST",
                    minify: false,
                    swSrc: "./sw.js",
                    swDest: "./dist/sw.js",
                    maximumFileSizeToCacheInBytes: 70428800,
                },
                devOptions: {
                    enabled: false,
                    type: "module",
                },
                manifest: {
                    name: "Paradise",
                    short_name: "Paradise",
                    start_url: "/",
                    display: "standalone",
                    icons: [
                        {
                            src: "icon-192x192.png",
                            sizes: "192x192",
                            type: "image/png",
                        },
                        {
                            src: "icon-512x512.png",
                            sizes: "512x512",
                            type: "image/png",
                        },
                    ],
                },
            }),
        ],

        define: {
            "process.env.VAPID_KEY": JSON.stringify(env.VITE_VAPID_KEY),
            "process.env.PUSH_NOTIFY_URL": JSON.stringify(
                env.VITE_PUSH_NOTIFY_URL
            ),
            "process.env.WEB_PUSH_APP_ID": JSON.stringify(
                env.VITE_WEB_PUSH_APP_ID
            ),
            "process.env.MATRIX_HOMESERVER": JSON.stringify(
                env.VITE_MATRIX_HOMESERVER || "https://matrix.org"
            ),
            global: "globalThis",
        },

        optimizeDeps: {
            include: ["react", "react-dom"],
            esbuildOptions: {
                target: "es2022",
            },
        },
        build: {
            target: "es2022",
            outDir: "../dist",
            terserOptions: {
                keep_classnames: true,
                keep_fnames: true,
            },
            minify: false,
            minifyHtml: false,
            rollupOptions: {
                input: {
                    main: path.resolve(__dirname, "build/index.html"),
                    engine: path.resolve(__dirname, "build/engine.js"),
                },
                output: {
                    entryFileNames: (chunkInfo) => {
                        if (chunkInfo.name === "engine") {
                            return "engine.js";
                        }
                        return "assets/[name]-[hash].js";
                    },
                    assetFileNames: "assets/[name].[ext]",
                },
            },
        },
        resolve: {
            alias: {
                "generated-compat": path.resolve(
                    __dirname,
                    "./node_modules/ffi-bindings/src/index.web.js"
                ),
                react: path.resolve(__dirname, "./node_modules/react"),
                "react-dom": path.resolve(
                    __dirname,
                    "./node_modules/react-dom"
                ),
                "react/jsx-runtime": path.resolve(
                    __dirname,
                    "./node_modules/react/jsx-runtime"
                ),
                "react/jsx-dev-runtime": path.resolve(
                    __dirname,
                    "./node_modules/react/jsx-dev-runtime"
                ),
                //      '/element-call': path.resolve(__dirname, './node_modules/@element-hq/element-call-embedded')
            },
        },

        server: {
            port: 8000,
            host: true,
            allowedHosts: true,
            headers: {
                "Cross-Origin-Opener-Policy": "same-origin",
                "Cross-Origin-Embedder-Policy": "credentialless",
            },
            fs: {
                allow: [
                    path.resolve(__dirname, ".."),
                    path.resolve(__dirname, "build"),
                    path.resolve(__dirname, "node_modules"),
                ],
            },
        },
    };
});

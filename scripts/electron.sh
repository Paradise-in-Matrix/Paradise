cd "$(dirname "$0")/.."

npx cap add @capacitor-community/electron
jq '.compilerOptions += {"skipLibCheck": true}' electron/tsconfig.json > electron/tsconfig.json.tmp && mv electron/tsconfig.json.tmp electron/tsconfig.json
echo "
id: com.gigiaj.paradise
runtime: org.freedesktop.Platform
runtime-version: '23.08'
sdk: org.freedesktop.Sdk
base: org.electronjs.Electron2.BaseApp
command: paradise
finish-args:
  - --share=network
  - --share=ipc
  - --socket=x11
  - --socket=wayland
  - --device=dri
  - --filesystem=xdg-documents:ro
modules:
  - name: paradise
    buildsystem: simple
    sources:
      - type: dir
        path: ./electron/dist/linux-unpacked
      - type: file
        path: wrapper.sh
    build-commands:
      - mkdir -p /app/main
      - cp -r . /app/main
      - install -D wrapper.sh /app/bin/paradise
" >  electron/com.gigiaj.paradise.yml
npm run electron:make

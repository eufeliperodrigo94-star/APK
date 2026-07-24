# SorteOuroPOS

APK Android para PDV de jogo do bicho — Positivo L500 (Android 11)

## Arquivos
- `SistemaTerminal.apk` — APK pronto para instalar
- `android/` — código-fonte
  - `src/com/sistema/terminal/MainActivity.java` — WebView + bridge impressora
  - `AndroidManifest.xml` — manifest
  - `assets/index.html` — frontend completo (HTML/CSS/JS)
  - `res/values/` — strings e estilos

## Impressão
- Tenta CloudPOS → ESC/POS serial (`/dev/ttyS1`, `/dev/ttyS0`) → Intents OEM
- Diagnóstico disponível dentro do app (botão 🔍)

## Build
```bash
javac -source 8 -target 8 -cp android.jar -d build/classes android/src/com/sistema/terminal/MainActivity.java
d8 --output build/ build/classes/**/*.class
aapt2 compile --dir android/res -o build/compiled.zip
aapt2 link --proto-format -o build/linked.apk -I android.jar --manifest android/AndroidManifest.xml -A android/assets build/compiled.zip
```

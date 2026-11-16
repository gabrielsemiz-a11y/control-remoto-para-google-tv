# Google TV Remote Pro — Compilación en la Nube con GitHub Actions

Este proyecto ya incluye el flujo automático `.github/workflows/build-apk.yml` para compilar el archivo `app-debug.apk` en la nube de GitHub sin instalar Android Studio en tu PC.

## Pasos para obtener tu APK en 2 minutos:
1. Descomprimí este archivo `.zip` en una carpeta de tu computadora.
2. Entrá a [github.com/new](https://github.com/new) y creá un repositorio nuevo (puede ser privado o público).
3. Tocá en **"uploading an existing file"** (subir un archivo existente), arrastrá todas las carpetas y archivos de este proyecto (incluyendo la carpeta `.github`) y presioná **"Commit changes"**.
4. Entrá a la pestaña **"Actions"** arriba en tu repositorio de GitHub.
5. Vas a ver corriendo el flujo **"Compilar APK Google TV Remote Pro"**. Cuando se ponga en tilde verde (~2 minutos), entrá y abajo en la sección **Artifacts** descargá **`GoogleTvRemotePro-APK`** (adentro está tu `app-debug.apk` listo para instalar en el celular).

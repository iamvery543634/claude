#!/usr/bin/env bash
# Builds the signed release APK into dist/GhostHub.apk
set -e
cd "$(dirname "$0")"
export JAVA_HOME="/c/Users/iamve/AndroidDev/jdk17"
export ANDROID_HOME="/c/Users/iamve/AndroidDev/sdk"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleRelease --console=plain "$@"
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/GhostHub.apk
ls -la dist/GhostHub.apk

# Publish the new APK to Ghost Hub
python "C:/Users/iamve/AndroidDev/GhostHubServer/publish.py" || true

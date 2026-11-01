#!/usr/bin/env bash
# Build no GitHub Actions (Android SDK do runner): aapt2 + javac + d8, assinado sempre com a mesma chave (atualiza por cima).
set -euo pipefail
cd "$(dirname "$0")/../app"
VC=${VC:?}; VN=${VN:?}
BT=$ANDROID_HOME/build-tools/34.0.0
AJ=$ANDROID_HOME/platforms/android-34/android.jar
rm -rf build && mkdir -p build/gen build/classes build/dex
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -o build/base.apk -I $AJ --manifest AndroidManifest.xml -R build/res.zip -A assets \
  --java build/gen --auto-add-overlay --min-sdk-version 26 --target-sdk-version 30 --version-code $VC --version-name $VN
javac -nowarn --release 8 -encoding UTF-8 -classpath "$AJ" -d build/classes $(find src build/gen -name '*.java')
$BT/d8 --release --min-api 26 --lib $AJ --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q -j ../base.apk classes*.dex)
$BT/zipalign -f -p 4 build/base.apk build/aligned.apk
$BT/apksigner sign --ks ../libs/facebox.keystore --ks-pass pass:tubebox --ks-key-alias tubebox \
  --key-pass pass:tubebox --out build/Amp.apk build/aligned.apk
$BT/apksigner verify -v build/Amp.apk | head -5
$BT/aapt2 dump badging build/Amp.apk | head -12
ls -la build/Amp.apk

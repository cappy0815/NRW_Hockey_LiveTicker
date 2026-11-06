#!/usr/bin/env bash
# Baut EHV-Live.apk ohne Gradle: aapt2 -> javac -> dex -> signieren
set -euo pipefail

# >>> Version hier pflegen: landet in App und Dateiname <<<
VERSION_NAME="2.6"
VERSION_CODE=28

cd "$(dirname "$0")"
SDK=/tmp/claude-0/sdk
AJ=$SDK/android.jar
OUT=build
rm -rf $OUT && mkdir -p $OUT/gen $OUT/classes

echo "== Ressourcen kompilieren"
$SDK/aapt2_64 compile --dir res -o $OUT/res.zip

echo "== Manifest + Ressourcen linken"
$SDK/aapt2_64 link -o $OUT/base.apk -I $AJ \
  --manifest AndroidManifest.xml --java $OUT/gen \
  --min-sdk-version 26 --target-sdk-version 33 \
  --version-code $VERSION_CODE --version-name $VERSION_NAME \
  $OUT/res.zip

echo "== Java kompilieren"
find src $OUT/gen -name '*.java' > $OUT/sources.txt
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath $AJ -classpath $AJ -d $OUT/classes @$OUT/sources.txt

echo "== DEX erzeugen"
(cd $OUT/classes && jar cf ../classes.jar .)
bash $SDK/d2j/d2j-jar2dex.sh -f -o $OUT/classes.dex $OUT/classes.jar

echo "== APK zusammensetzen"
cp $OUT/base.apk $OUT/unsigned.apk
(cd $OUT && zip -q unsigned.apk classes.dex)

echo "== Signieren (zipalign + v1/v2/v3)"
java -jar $SDK/ubs.jar -a $OUT/unsigned.apk -o $OUT/signed --allowResign
ls -la $OUT/signed
cp $OUT/signed/unsigned-aligned-debugSigned.apk "$OUT/EHV-Live-v$VERSION_NAME.apk"
echo "== Fertig: $OUT/EHV-Live-v$VERSION_NAME.apk"

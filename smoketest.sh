#!/usr/bin/env bash
# 1.20.4 smoke test: a dedicated 1.20.4 Fabric server runs the built jar (build/libs) with the 1.20.4 Alloy Syncmatica
# port, then the dev client (src/smoketest) joins it as op and drives the menus: GUI backup, live chunk restore,
# full-restore countdown, area selector + preview (shared through Syncmatica), settings. Run it through ./headless.sh
# so no window shows. Logs: build/run/smokeserver/console.log and build/run/smoke/logs/latest.log.
set -uo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/PrismLauncher/java/java-runtime-delta}"
MC=1.20.4 LOADER=0.19.5 INSTALLER=1.1.0 PORT=25574 PLAYER=BackupsSmoke
FABRIC_API=$(find ~/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api/0.97.3+1.20.4 -name 'fabric-api-0.97.3+1.20.4.jar' | head -1)
SYNCMATICA=../Crytra-Syncmatica/build/libs/alloy-syncmatica-1.5.0+1.20.4.jar

./gradlew remapJar --no-daemon -q || exit 1
JAR=$(ls build/libs/alloy-backups-*+1.20.4.jar | grep -v sources | head -1)

S=build/run/smokeserver
rm -rf "$S/mods" "$S/world" "$S/logs" "$S/backups" "$S/config"
mkdir -p "$S/mods"
[ -f "$S/fabric-server-launch.jar" ] || curl -sSfL -o "$S/fabric-server-launch.jar" \
    "https://meta.fabricmc.net/v2/versions/loader/$MC/$LOADER/$INSTALLER/server/jar" || exit 1
cp "$JAR" "$FABRIC_API" "$S/mods/"
[ -f "$SYNCMATICA" ] && cp "$SYNCMATICA" "$S/mods/"
echo "eula=true" > "$S/eula.txt"
cat > "$S/server.properties" <<PROPS
online-mode=false
server-port=$PORT
level-type=minecraft\:flat
spawn-protection=0
view-distance=4
simulation-distance=4
spawn-monsters=false
PROPS
# the test player is op (level 4); offline-mode UUID
UUID=$(python3 -c "import hashlib,uuid;h=bytearray(hashlib.md5(b'OfflinePlayer:$PLAYER').digest());h[6]=h[6]&0x0f|0x30;h[8]=h[8]&0x3f|0x80;print(uuid.UUID(bytes=bytes(h)))")
echo "[{\"uuid\":\"$UUID\",\"name\":\"$PLAYER\",\"level\":4,\"bypassesPlayerLimit\":false}]" > "$S/ops.json"

rm -f "$S/stdin"; mkfifo "$S/stdin"
(cd "$S" && exec "$JAVA_HOME/bin/java" -Xmx1G -jar fabric-server-launch.jar nogui < stdin > console.log 2>&1) &
SERVER=$!
exec 3> "$S/stdin"   # keep the fifo open so the server doesn't see EOF
for _ in $(seq 1 180); do
    grep -q 'Done (' "$S/console.log" 2>/dev/null && break
    kill -0 $SERVER 2>/dev/null || break
    sleep 1
done
if ! grep -q 'Done (' "$S/console.log"; then
    echo "server did not start"; tail -40 "$S/console.log"; kill $SERVER 2>/dev/null; exit 1
fi
echo "server started"

./gradlew runSmokeTest --no-daemon -q
echo stop >&3
exec 3>&-
wait $SERVER

echo "--- server"
grep -E 'Alloy Backups|cytrabackups|ERROR|Exception|Mixin' "$S/console.log" | head -40
echo "--- client"
grep -E 'BackupsSmoke' build/run/smoke/logs/latest.log
grep -q '\[BackupsSmoke\] PASS' build/run/smoke/logs/latest.log

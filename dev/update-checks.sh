#!/bin/bash
# Tandem updating itself, in the real game as players get it (the signed release jar), against dev/FakeModrinth.java.
#   bash dev/update-checks.sh [folder]   (dev/checks.sh updates runs it into its run folder)
# Each case writes "Update check: OK|FAILED <what>" to logs/updates.log. Game windows open while it runs.
set -u
T=$(cd "$(dirname "$0")/.." && pwd)
OUT=${1:-$T/devcloud/runs/updates-$(date +%Y%m%d-%H%M%S)}
U=$OUT/updates
mkdir -p "$U" "$OUT/logs"
LOG=$OUT/logs/updates.log
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
FAPI=$(find "$HOME/.gradle/caches/modules-2" -name "fabric-api-0.116.17+1.21.1.jar" | head -1)
PORT=47123
V=$(sed -n 's/^version=//p' "$T/gradle.properties" | tr -d '\r')
NEXT=$(echo "$V" | awk -F. '{print $1"."$2"."$3+1}')
N=0
WORLD=$T/fabric/run-a/saves/tandem-round-plain

say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
ok() { if [ "$1" = 0 ]; then say "Update check: OK $2"; else say "Update check: FAILED $2"; fi; }
win() { cygpath -m "$1"; }
version_of() { unzip -p "$1" fabric.mod.json 2> /dev/null | grep -o '"version":"[^"]*"' | cut -d'"' -f4; }
kill_tagged() {
	powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name like 'java%'\" | Where-Object { \$_.CommandLine -like '*$1*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" > /dev/null 2>&1
}
wait_for() { # wait_for <file> <pattern> <seconds>
	local end=$((SECONDS + $3))
	while [ $SECONDS -lt $end ]; do grep -qE "$2" "$1" 2> /dev/null && return 0; sleep 2; done
	return 1
}

# The init script that points runProduction at one case: its own game folder, only the jars given, extra arguments.
cat > "$U/case.gradle" <<'EOF'
allprojects {
	tasks.matching { it.name == 'runProduction' }.configureEach {
		runDir = file(System.getenv('UC_RUN'))
		mods.setFrom(System.getenv('UC_MODS').split(';').collect { file(it) })
		jvmArgs.addAll(System.getenv('UC_ARGS').split(' ').findAll { it })
		programArgs.addAll(System.getenv('UC_PROG').split(' ').findAll { it })
	}
}
EOF

# play <case> <tandem jar> <jvm args> [program args] [more gradle args]: starts it in the background.
play() {
	local c=$1 jar=$2 args=$3 prog=${4:-} more=${5:-}
	# Each launch its own log, so a line from the last launch never answers for this one.
	N=$((N + 1)); L=$OUT/logs/update-$c-$N.log
	mkdir -p "$U/$c/saves"
	[ -f "$U/$c/options.txt" ] || printf 'onboardAccessibility:false\npauseOnLostFocus:false\ntutorialStep:none\nsoundCategory_master:0.0\n' > "$U/$c/options.txt"
	[ -d "$U/$c/saves/check" ] || cp -r "$WORLD" "$U/$c/saves/check"
	(cd "$T" && UC_RUN=$(win "$U/$c") UC_MODS="$(win "$jar");$(win "$FAPI")" UC_ARGS="-Dtandem.case=$c-$$ -Dtandem.server=http://127.0.0.1:9 $args" UC_PROG="$prog" \
		./gradlew :fabric:runProduction -x remapJar -x signJar -I "$(win "$U/case.gradle")" $more --console=plain > "$L" 2>&1) &
}
stop() { kill_tagged "tandem.case=$1-$$"; sleep 8; }
modrinth() { # modrinth <jar> <version> [ms per 64 KB]
	kill_tagged FakeModrinth; sleep 1
	("$JAVA" "$T/dev/FakeModrinth.java" $PORT "$(win "$1")" "$2" ${3:-} >> "$U/modrinth.log" 2>&1 &)
	for _ in $(seq 1 30); do curl -s "http://127.0.0.1:$PORT/v2/project/tandem/version" > /dev/null && return 0; sleep 1; done
}
FROM_MODRINTH="-Dtandem.updates.api=http://127.0.0.1:$PORT -Dtandem.updates.files=http://127.0.0.1:$PORT/"

say "building the signed jars: $NEXT to serve, $V to start from"
(cd "$T" && ./gradlew clean build -Pversion=$NEXT -q --console=plain > "$U/build.log" 2>&1 && cp fabric/build/libs/tandem-fabric-$NEXT.jar "$U/serve-$NEXT.jar" \
	&& ./gradlew clean build -q --console=plain >> "$U/build.log" 2>&1 && cp fabric/build/libs/tandem-fabric-$V.jar "$U/start-$V.jar") \
	|| { ok 1 "the jars build (see updates/build.log)"; exit 1; }
fresh() { mkdir -p "$U/$1/mods"; cp "$U/start-$V.jar" "$U/$1/mods/tandem-fabric-$V.jar"; echo "$U/$1/mods/tandem-fabric-$V.jar"; }

# 1. The oldest Fabric Loader it accepts, straight into a world.
say "== the oldest Fabric Loader"
J=$(fresh loader)
play loader "$J" "-Dtandem.updates=false" "--quickPlaySingleplayer check" "-Ploader_version=0.15.11"
wait_for "$L" "joined the game" 240; ok $? "starts on Fabric Loader 0.15.11 and joins a world"
grep -qE "Mixin apply failed|InvalidInjectionException|turned off" "$L"; [ $? = 1 ]; ok $? "no mixin errors or pieces turned off on 0.15.11"
stop loader

# 2. The whole update: found, downloaded, checked, waiting while the game runs, in after it's killed, then cleaned up.
say "== update, then the game killed"
modrinth "$U/serve-$NEXT.jar" $NEXT
J=$(fresh happy)
play happy "$J" "$FROM_MODRINTH -Dtandem.updates.install=true"
wait_for "$L" "goes in when Minecraft closes|couldn't update" 240
grep -q "goes in when Minecraft closes" "$L"; ok $? "finds, downloads and checks $NEXT"
sleep 6; [ "$(version_of "$J")" = $V ]; ok $? "nothing changes while the game runs"
stop happy
[ "$(version_of "$J")" = $NEXT ]; ok $? "the jar is $NEXT once the game is gone, same file name"
[ "$(ls "$U/happy/mods" | wc -l)" = 1 ]; ok $? "nothing else left in mods"
play happy "$J" "$FROM_MODRINTH"
wait_for "$L" "$NEXT is the newest" 240; ok $? "the next launch runs $NEXT and finds nothing newer"
grep -q "so the update is done" "$L"; ok $? "the next launch says the update went in"
sleep 3; [ -z "$(ls -A "$U/happy/.tandem/update" 2> /dev/null)" ]; ok $? "its update folder is cleaned up"
stop happy

# 3. Killed halfway through the download: still the old Tandem, offered again, no half file kept.
say "== killed mid-download"
modrinth "$U/serve-$NEXT.jar" $NEXT 400
J=$(fresh cut)
play cut "$J" "$FROM_MODRINTH -Dtandem.updates.install=true"
wait_for "$L" "Tandem update: downloading" 240; sleep 4
stop cut
[ "$(version_of "$J")" = $V ]; ok $? "a cut download leaves $V in place"
modrinth "$U/serve-$NEXT.jar" $NEXT
play cut "$J" "$FROM_MODRINTH"
wait_for "$L" "Tandem $NEXT is out" 240; ok $? "the next launch offers $NEXT again"
sleep 3; [ -z "$(ls "$U/cut/.tandem/update" 2> /dev/null)" ]; ok $? "the half download is cleaned up"
stop cut

# 4. No Modrinth at all.
say "== Modrinth unreachable"
kill_tagged FakeModrinth
J=$(fresh offline)
play offline "$J" "-Dtandem.updates.api=http://127.0.0.1:47199 -Dtandem.updates.files=http://127.0.0.1:47199/"
wait_for "$L" "couldn't check for updates" 240; ok $? "says it couldn't check, and goes on"
sleep 10; grep -qE "Crash report|has crashed" "$L"; [ $? = 1 ]; ok $? "no crash"
stop offline

# 5. Pressed twice: one download, one helper.
say "== Update pressed twice"
modrinth "$U/serve-$NEXT.jar" $NEXT 100
J=$(fresh twice)
play twice "$J" "$FROM_MODRINTH -Dtandem.updates.install=true -Dtandem.updates.installTwice=true"
wait_for "$L" "goes in when Minecraft closes|couldn't update" 240
[ "$(grep -c "Tandem update: downloading" "$L")" = 1 ]; ok $? "a second press during the download starts nothing new"
stop twice
[ "$(version_of "$J")" = $NEXT ]; ok $? "and the update still goes in"

kill_tagged FakeModrinth
say "update checks done: $(grep -c ": OK" "$LOG") OK, $(grep -c ": FAILED" "$LOG") failed"

#!/bin/bash
# Tandem next to other mods: the release jar with each mod in devcloud/mods/<name>, then all of them, then each pack in
# devcloud/packs/<name> (its own mods and configs), straight into a world. Passes when the world loads with no mixin
# error, crash or Tandem piece turned off.
#   bash dev/mod-checks.sh [folder]
set -u
T=$(cd "$(dirname "$0")/.." && pwd)
OUT=${1:-$T/devcloud/runs/mods-$(date +%Y%m%d-%H%M%S)}
U=$OUT/mods
mkdir -p "$U" "$OUT/logs"
LOG=$OUT/logs/mods.log
FAPI=$(find "$HOME/.gradle/caches/modules-2" -name "fabric-api-0.116.17+1.21.1.jar" | head -1)
WORLD=$T/fabric/run-a/saves/tandem-round-plain
JAR=$U/tandem.jar
V=$(sed -n 's/^version=//p' "$T/gradle.properties" | tr -d '\r')
cp "$T/fabric/build/libs/tandem-fabric-$V.jar" "$JAR"

say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
win() { cygpath -m "$1"; }
kill_tagged() {
	powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name like 'java%'\" | Where-Object { \$_.CommandLine -like '*$1*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" > /dev/null 2>&1
}
cat > "$U/case.gradle" <<'EOF'
allprojects {
	tasks.matching { it.name == 'runProduction' }.configureEach {
		runDir = file(System.getenv('UC_RUN'))
		mods.setFrom(System.getenv('UC_MODS').split(';').findAll { it }.collect { file(it) })
		jvmArgs.addAll(System.getenv('UC_ARGS').split(' ').findAll { it })
		programArgs.addAll(System.getenv('UC_PROG').split(' ').findAll { it })
	}
}
EOF

# one <name> <game folder to copy configs from, or ""> <jar> [jar ...]
one() {
	local name=$1 base=$2; shift 2
	local dir=$U/$name log=$OUT/logs/mods-$name.log
	rm -rf "$dir"; mkdir -p "$dir/saves"
	[ -n "$base" ] && cp -r "$base"/. "$dir"/ 2> /dev/null && rm -rf "$dir/mods"
	cp -r "$WORLD" "$dir/saves/check"
	printf 'onboardAccessibility:false\npauseOnLostFocus:false\ntutorialStep:none\nsoundCategory_master:0.0\n' >> "$dir/options.txt"
	local list
	list=$(for j in "$@"; do win "$j"; done | paste -sd';')
	say "== $name ($# jars)"
	(cd "$T" && UC_RUN=$(win "$dir") UC_MODS="$list" UC_ARGS="-Dtandem.case=mods-$name-$$ -Dtandem.server=http://127.0.0.1:9 -Dtandem.updates=false" \
		UC_PROG="--quickPlaySingleplayer check" ./gradlew :fabric:runProduction -x remapJar -x signJar -I "$(win "$U/case.gradle")" --console=plain > "$log" 2>&1) &
	local end=$((SECONDS + 420))
	until grep -qE "joined the game|Crash report saved|has crashed|Incompatible mods|BUILD FAILED" "$log" 2> /dev/null || [ $SECONDS -ge $end ]; do sleep 3; done
	sleep 15
	local why=""
	grep -q "joined the game" "$log" || why="never got into the world"
	grep -qE "Incompatible mods|Mod resolution failed" "$log" && why="Fabric refused the mod set"
	grep -qE "Crash report saved|has crashed" "$log" && why="crashed"
	grep -qE "Mixin apply failed|InvalidInjectionException|injection failure" "$log" && why="a mixin failed${why:+; $why}"
	grep -q "Tandem turned off" "$log" && why="Tandem turned a piece off${why:+; $why}"
	grep -q "Tandem syncs worlds\|Tandem update\|Signed in\|couldn't sign in" "$log" || why="Tandem didn't start${why:+; $why}"
	if [ -z "$why" ]; then say "Mod check, Runner: OK $name: into a world, no errors"; else say "Mod check, Runner: FAILED $name: $why (logs/mods-$name.log)"; fi
	kill_tagged "mods-$name-$$"; sleep 6
}

jars() { find "$1" -maxdepth 2 -name "*.jar" | sort; }
for m in "$T"/devcloud/mods/*/; do
	n=$(basename "$m")
	mapfile -t J < <(jars "$m")
	one "$n" "" "$JAR" "$FAPI" "${J[@]}"
done
# Not Chunky: Distant Horizons asks for it before it has loaded and crashes, with or without Tandem.
mapfile -t ALL < <(find "$T"/devcloud/mods -name "*.jar" -not -path "*/chunky/*" | awk -F/ '{print $NF"\t"$0}' | sort -u -k1,1 | cut -f2)
one together "" "$JAR" "$FAPI" "${ALL[@]}"
for p in "$T"/devcloud/packs/*/; do
	n=$(basename "$p")
	mapfile -t J < <(jars "$p/mods")
	one "pack-$n" "$p" "$JAR" "${J[@]}"
done
say "mod checks done: $(grep -c ": OK" "$LOG") OK, $(grep -c ": FAILED" "$LOG") failed"

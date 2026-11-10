#!/bin/bash
# Tandem's in-game checks, two games at a time, against a local KeplerSync on this PC.
#   bash dev/checks.sh                    every suite, in order
#   bash dev/checks.sh friends sync lease  just these (friends first: the others need Alex and Sam to be friends)
# Suites: friends sync lease play handoff rescue history own round walk old updates mods
# Each run gets a fresh database and fresh game folders in devcloud/runs/<time>; the summary is report.md there.
# Needs Docker (server/docker-compose.yml: postgres and storage), cargo and JDK 21. The games' windows open while it runs.
set -u
T=$(cd "$(dirname "$0")/.." && pwd)
K=$(dirname "$T")
ID=$(date +%Y%m%d-%H%M%S)
OUT=$T/devcloud/runs/$ID
W=$(cygpath -m "$OUT")
mkdir -p "$OUT/check" "$OUT/logs"
MARKS=$OUT/check
# A fresh game folder opens on Minecraft's first-launch screens; the checks start from the title screen, quietly.
for d in run-a run-a2 run-b run-c run-x; do
	mkdir -p "$OUT/$d"
	printf 'onboardAccessibility:false\npauseOnLostFocus:false\ntutorialStep:none\njoinedFirstServer:true\nskipMultiplayerWarning:true\nsoundCategory_master:0.0\n' > "$OUT/$d/options.txt"
done
SUITES=${*:-friends sync mistakes lease play handoff rescue history own round walk old updates mods}
DATA_KEY=dGFuZGVtLWNoZWNrLWRhdGEta2V5LWZvci10ZXN0cyE=
SECRET=tandem-check-relay-secret-tandem-check-relay
MIN_TANDEM=
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java

say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/run.log"; }
pid_on() { netstat -ano | grep -E "(127\.0\.0\.1|0\.0\.0\.0):$1 .*LISTENING" | awk '{print $NF}' | head -1; }
# netstat doesn't always list a listening socket as LISTENING here, so ask Windows for every process bound to the port.
free_port() { powershell.exe -NoProfile -Command "Get-NetTCPConnection -LocalPort $1 -ErrorAction SilentlyContinue | Where-Object OwningProcess -gt 0 | Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object { Stop-Process -Id \$_ -Force -ErrorAction SilentlyContinue }" > /dev/null 2>&1; true; }
up() { for _ in $(seq 1 90); do curl -s "http://127.0.0.1:$1/healthz" > /dev/null && return 0; sleep 1; done; return 1; }
mark() { : > "$MARKS/$1"; }
marked() { [ -e "$MARKS/$1" ]; }
# Every game of this run, by the folder that's on its command line.
kill_games() {
	powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name like 'java%'\" | Where-Object { \$_.CommandLine -like '*$ID*' -and \$_.CommandLine -notlike '*FakeModrinth*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" > /dev/null 2>&1
}

server_start() {
	(cd "$K/server" && BIND_ADDR=127.0.0.1:8080 STORAGE_ENDPOINT=http://127.0.0.1:9000 RELAY_ADDRESS=127.0.0.1:25600 \
		RELAY_SECRET=$SECRET DATA_KEY=$DATA_KEY DATABASE_URL=postgres://atlas:atlas@localhost:5432/tandem_check \
		MOJANG_SESSION_URL=http://127.0.0.1:8090/ RATE_LIMITS=off STORAGE_BUCKET=${BUCKET:-tandem-check} \
		OWN_STORAGE_ENDPOINT=http://127.0.0.1:9000 MIN_TANDEM_VERSION=$MIN_TANDEM \
		./target/debug/atlas-server.exe >> "$OUT/logs/server.log" 2>&1 &)
	up 8080 || { say "the server didn't start: logs/server.log"; exit 1; }
}
server_stop() { free_port 8080; }

stack_start() {
	say "starting the local KeplerSync: database tandem_check (fresh), storage, fake Mojang, relay, server"
	free_port 8080; free_port 8090; free_port 25600
	(cd "$K/server" && docker compose up -d postgres storage > /dev/null 2>&1)
	for _ in $(seq 1 30); do (cd "$K/server" && docker compose exec -T postgres pg_isready -U atlas > /dev/null 2>&1) && break; sleep 1; done
	(cd "$K/server" && docker compose exec -T postgres psql -U atlas -d atlas -qc "DROP DATABASE IF EXISTS tandem_check WITH (FORCE)" -c "CREATE DATABASE tandem_check" > /dev/null) || { say "couldn't reset the database"; exit 1; }
	(cd "$K/server" && cargo build -q --bin atlas-server --bin relay) || { say "the server didn't build"; exit 1; }
	("$JAVA" "$T/dev/FakeMojang.java" 8090 127.0.0.1 >> "$OUT/logs/mojang.log" 2>&1 &)
	(cd "$K/server" && RELAY_SECRET=$SECRET RELAY_REPORT_URL=http://127.0.0.1:8080/v1/relay/usage ./target/debug/relay.exe >> "$OUT/logs/relay.log" 2>&1 &)
	server_start
	say "KeplerSync is up on 127.0.0.1:8080"
}
stack_stop() { kill_games; free_port 8080; free_port 8090; free_port 25600; }
trap stack_stop EXIT

# A game in the background: game <log name> <gradle run task> [more gradle arguments]; its pid in $!.
game() {
	local name=$1 task=$2; shift 2
	say "  starting $name ($task)"
	(cd "$T" && ./gradlew ":fabric:$task" -Ptandem.runs="$W" --console=plain "$@" > "$OUT/logs/$name.log" 2>&1) &
}
# Waits for the games' pids, at most [seconds]; then stops every game of this run. Choreography runs meanwhile.
finish() {
	local limit=$1; shift
	local end=$((SECONDS + limit))
	while [ $SECONDS -lt $end ]; do
		local alive=0
		for p in "$@"; do kill -0 "$p" 2> /dev/null && alive=1; done
		[ $alive = 0 ] && return 0
		[ -n "${CHOREO:-}" ] && $CHOREO
		sleep 2
	done
	say "  still running after $limit s: stopped"
	kill_games
	echo "TIMEOUT after $limit s" >> "$OUT/logs/timeouts.log"
}

suite_friends() {
	game friends-1-sam runFriendB; finish 300 $!
	game friends-2-alex runFriendA; finish 300 $!
	# Sam accepts and opens his world; then Alex starts, so Sam gets the toast; then the server restarts under them.
	game friends-3-sam runFriendB; local sam=$!
	for _ in $(seq 1 150); do marked sam-in-world && break; sleep 2; done
	game friends-3-alex runFriendA; local alex=$!
	restarted() { if marked alex-playing && ! marked restarted; then sleep 40; server_stop; sleep 3; server_start; mark restarted; say "  restarted the server"; fi; }
	CHOREO=restarted finish 900 $sam $alex
	rm -f "$MARKS"/sam-* "$MARKS"/alex-* "$MARKS/restarted"
	# Friends now: what changes behind an open dialog shows once it closes.
	game friends-4-sam runFriendB; sam=$!
	for _ in $(seq 1 150); do marked sam-in-world && break; sleep 2; done
	game friends-4-alex runFriendA; alex=$!
	finish 600 $sam $alex
}

suite_sync() {
	# The friends check plays in worlds by the same names (folder and title); the sync check starts from none.
	rm -rf "$OUT/run-a/saves/tandem-sync" "$OUT/run-b/saves/tandem-sync" "$OUT/run-b/saves/Shared Base"
	game sync-1-alex runCheckA; finish 600 $!
	game sync-2-sam runCheckB; finish 600 $!
	game sync-3-alex runCheckA; finish 600 $!
}

suite_lease() {
	rm -f "$MARKS"/*
	lease_steps() {
		if marked alex-in-world && ! marked stopped-1; then server_stop; mark stopped-1; say "  server stopped while Alex plays"; fi
		if marked alex-offline && ! marked server-back; then server_start; mark server-back; say "  server back"; fi
		if marked alex-left && ! marked server-down-again; then server_stop; mark server-down-again; say "  server stopped again"; fi
		if marked alex-done && ! marked started-2; then server_start; mark started-2; fi
	}
	game lease-sam runLeaseB; local sam=$!
	game lease-alex runLeaseA; local alex=$!
	CHOREO=lease_steps finish 1200 $sam $alex
	marked started-2 || server_start
}

pair() { # pair <suite> <Sam's task> <Alex's task> [seconds]
	rm -f "$MARKS"/*
	game "$1-sam" "$2"; local sam=$!
	sleep 20
	game "$1-alex" "$3"; local alex=$!
	finish "${4:-1200}" $sam $alex
}
suite_play() { pair play runPlayB runPlayA; pair play-relay runPlayBRelay runPlayARelay; pair play-punch runPlayBPunch runPlayAPunch; }
suite_handoff() { pair handoff runHandB runHandA; }
# Hosting for each other (a slow download, a hand-off said no to, a quit mid-upload), then a host's game stopping.
suite_rescue() { pair rescue runRescueB runRescueA; pair crash runCrashB runCrashA; }
suite_history() { pair history runHistB runHistA; }
suite_own() {
	# Own storage stands in for R2 with a second local bucket.
	# The server makes its bucket as it starts: once under the second bucket's name, then as usual.
	server_stop; BUCKET=tandem-check-own server_start; server_stop; server_start
	pair own runOwnB runOwnA
}
suite_round() { pair round runRoundB runRoundA; }

psql_check() { (cd "$K/server" && docker compose exec -T postgres psql -U atlas -d tandem_check -qAtc "$1"); }
suite_walk() {
	rm -f "$MARKS"/*
	# Once Riley's part is done, Alex's storage shrinks to 1 KB, so his next Sync this world can't fit.
	full() { if marked riley-done && ! marked space-small; then psql_check "UPDATE users SET space_bytes = 1024 WHERE display_name = 'Alex'" > /dev/null; mark space-small; say "  Alex's storage is now 1 KB"; fi; }
	game walk-riley runWalkC; local riley=$!
	sleep 20
	game walk-alex runWalkA; local alex=$!
	CHOREO=full finish 900 $riley $alex
	psql_check "UPDATE users SET space_bytes = DEFAULT WHERE display_name = 'Alex'" > /dev/null
}
suite_mods() { bash "$T/dev/mod-checks.sh" "$OUT"; }

# What players get wrong, seen from their side in real screenshots (each game's screenshots folder). After sync.
suite_mistakes() {
	result() { echo "[$(date +%H:%M:%S)] Mistakes check, Runner: $1" | tee -a "$OUT/logs/mistakes-runner.log"; }
	# A cracked or offline account: Mojang never confirms it, so Tandem can't sign in to KeplerSync.
	game mistakes-cracked runShootX -Ptandem.shoot="wait:600;play;wait:100;shot:mistake-cracked-play;play:settings;wait:20;shot:mistake-cracked-settings"
	finish 300 $!
	# The same account in two games (a second PC, a brother on the same account): the second can't host it too.
	if [ -d "$OUT/run-a/saves/tandem-sync" ]; then
		game mistakes-host runShootA -Ptandem.shoot="signed;play;listed:Shared+Base;click:button-Shared+Base;wait:3000"; local host=$!
		local f=$OUT/logs/mistakes-host.log end=$((SECONDS + 300))
		until grep -q "Alex joined the game" "$f" 2> /dev/null || [ $SECONDS -ge $end ]; do sleep 2; done
		game mistakes-second runShootA2 -Ptandem.shoot="signed;play;listed:Shared+Base;wait:40;shot:mistake-two-games-list;click:button-Shared+Base;wait:60;shot:mistake-two-games"
		finish 300 $!
		local g=$OUT/logs/mistakes-second.log
		grep -q "is open in another game on this account" "$g" && result "OK a second game on the same account is told the world is open in the other" \
			|| result "FAILED a second game on the same account is told the world is open in the other"
		finish 300 $host
		sleep 40
	fi
	# A synced world changed outside Tandem: opened in an instance without it, edited by a tool, a backup put back.
	# First a launch that only finishes the session the last game closed on (its next launch would take the new file
	# in as part of it, which is right); then the file, then Play.
	if [ -d "$OUT/run-a/saves/tandem-sync/data" ]; then
		game mistakes-settle runShootA -Ptandem.shoot="signed;play;listed:Shared+Base;wait:600"
		finish 300 $!
		head -c 4096 /dev/urandom > "$OUT/run-a/saves/tandem-sync/data/outside-tandem.dat"
		game mistakes-outside runShootA -Ptandem.shoot="signed;play;listed:Shared+Base;wait:40;shot:mistake-outside-list;click:button-Shared+Base;wait:80;shot:mistake-outside-dialog"
		finish 300 $!
		rm -f "$OUT/run-a/saves/tandem-sync/data/outside-tandem.dat"
	else
		say "  no synced world from the sync suite: skipped the outside-changes shot"
	fi
	# Too old for KeplerSync: the banner sends players to the Discord for the new jar.
	server_stop; MIN_TANDEM=9.9.9 server_start
	game mistakes-old runShootA -Ptandem.shoot="wait:400;play;wait:100;shot:mistake-old-play"
	finish 300 $!
	server_stop; MIN_TANDEM= server_start
}

# A big world (not in "every suite": make it first with gradlew :fabric:runBigWorld and Chunky, see build.gradle).
suite_big() {
	local world=$T/devcloud/bigworld-server/tandem-big
	[ -f "$world/level.dat" ] || { say "  no big world in devcloud/bigworld-server: skipped"; return; }
	say "  copying the big world ($(du -sm "$world" | cut -f1) MB) into Alex's saves"
	rm -rf "$OUT/run-a/saves/tandem-big"; mkdir -p "$OUT/run-a/saves"; cp -r "$world" "$OUT/run-a/saves/tandem-big"
	rm -f "$OUT/run-a/saves/tandem-big/session.lock"
	# Bigger than the default space: Alex gets 10 GB for this check.
	psql_check "UPDATE users SET space_bytes = 10737418240 WHERE display_name = 'Alex'" > /dev/null
	rm -f "$MARKS"/*
	# Part way through the first upload, storage goes away for a while: the upload waits, then carries on by itself.
	storage_steps() {
		if marked storage-stop && ! marked storage-stopped; then (cd "$K/server" && docker compose stop storage > /dev/null 2>&1); mark storage-stopped; say "  storage stopped mid-upload"; fi
		if marked storage-start && ! marked storage-started; then (cd "$K/server" && docker compose start storage > /dev/null 2>&1); mark storage-started; say "  storage back"; fi
	}
	game big-sam runBigB; local sam=$!
	sleep 20
	game big-alex runBigA; local alex=$!
	CHOREO=storage_steps finish 3600 $sam $alex
	(cd "$K/server" && docker compose start storage > /dev/null 2>&1)
}

suite_updates() { bash "$T/dev/update-checks.sh" "$OUT"; }

# KeplerSync only takes a newer Tandem: Tandem says so once and stops asking.
suite_old() {
	result() { echo "[$(date +%H:%M:%S)] Old check, Runner: $1" | tee -a "$OUT/logs/old-runner.log"; }
	server_stop; MIN_TANDEM=9.9.9 server_start
	game old-alex runCloudA; local p=$!
	local f=$OUT/logs/old-alex.log end=$((SECONDS + 300))
	until grep -q "KeplerSync needs a newer Tandem" "$f" 2> /dev/null || [ $SECONDS -ge $end ]; do sleep 2; done
	grep -q "KeplerSync needs a newer Tandem" "$f" && result "OK told KeplerSync needs a newer Tandem" || result "FAILED told KeplerSync needs a newer Tandem"
	sleep 75
	[ "$(grep -cE "needs a newer Tandem|couldn't sign in" "$f")" = 1 ] && result "OK asked once, not again" || result "FAILED asked once, not again ($(grep -cE "needs a newer Tandem|couldn't sign in" "$f") tries)"
	grep -qE "Crash report|has crashed" "$f" && result "FAILED no crash" || result "OK no crash"
	kill_games; sleep 3
	server_stop; MIN_TANDEM= server_start
}

say "Tandem checks $ID: $SUITES"
(cd "$T" && ./gradlew :fabric:classes -q --console=plain > "$OUT/logs/build.log" 2>&1) || { say "Tandem didn't build: logs/build.log"; exit 1; }
needs_stack=0
for s in $SUITES; do [ "$s" != updates ] && needs_stack=1; done
[ $needs_stack = 1 ] && stack_start
for s in $SUITES; do
	say "== $s"
	"suite_$s"
done
stack_stop
trap - EXIT

# The report: every check line, failures first, and anything that went wrong besides.
{
	echo "# Tandem checks $ID"
	echo
	echo "Suites: $SUITES"
	echo
	fails=$(grep -hE "check, [A-Za-z]+: FAILED|Update check: FAILED" "$OUT"/logs/*.log 2> /dev/null | sed 's/^.*\]: //' | sort -u)
	oks=$(cat "$OUT"/logs/*.log 2> /dev/null | grep -cE "check, [A-Za-z]+: OK|Update check: OK")
	echo "## Result: ${oks:-0} OK, $(echo -n "$fails" | grep -c .) failed"
	echo
	if [ -n "$fails" ]; then echo "### Failed"; echo; echo "$fails" | sed 's/^/- /'; echo; fi
	echo "### Each game"
	echo
	for f in "$OUT"/logs/*.log; do
		n=$(basename "$f" .log)
		case $n in server|mojang|relay|build|timeouts) continue;; esac
		ok=$(grep -cE ": OK " "$f"); bad=$(grep -cE ": FAILED " "$f")
		crash=$(grep -cE "Crash report saved|has crashed|Exception in" "$f")
		echo "- $n: $ok OK, $bad failed$( [ "$crash" -gt 0 ] && echo ", $crash crash lines")"
	done
	echo
	[ -s "$OUT/logs/timeouts.log" ] && { echo "### Timeouts"; echo; sed 's/^/- /' "$OUT/logs/timeouts.log"; echo; }
	echo "### Tandem's warnings and errors (each once)"
	echo
	grep -hE "\[[^]]*/(WARN|ERROR)\]: Tandem|Tandem turned off|closed its screen" "$OUT"/logs/*.log 2> /dev/null | sed 's/^.*\]: //' | sort | uniq -c | sort -rn | head -60 | sed 's/^/    /'
} > "$OUT/report.md"
say "report: $OUT/report.md"
grep -E "^## Result" "$OUT/report.md"

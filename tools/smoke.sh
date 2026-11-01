#!/usr/bin/env bash
# The atom is thin on purpose: everything hard about running a script lives in the SDK's Shell.Script, so these cases
# are about the mapping (params -> script) and about the atom telling the truth about what happened.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

os="$(uname -s 2>/dev/null || echo Windows)"
ws="$(mktemp -d)"
input() { printf '%s' "$1" >"$ws/input.json"; }

# --- 1. a plain script, exit 0
input '{"script": "echo hello-from-atom"}'
run "$ws" >"$ws/log.txt" 2>&1 || fail "echo: $(cat "$ws/output.json")"
[ "$(output "$ws" exitCode)" = "0" ] || fail "exitCode: $(cat "$ws/output.json")"
grep -q "hello-from-atom" "$ws/output.json" || fail "outputTail missing the echo: $(cat "$ws/output.json")"

# --- 2. a non-zero exit fails the step and says which code
input '{"script": "echo about-to-fail; exit 3"}'
run "$ws" >"$ws/log.txt" 2>&1 && fail "a non-zero exit must fail the step by default"
grep -q '"errorCode":40007' "$ws/output.json" || fail "expected 40007: $(cat "$ws/output.json")"
grep -q "exited with 3" "$ws/output.json" || fail "the failure must name the code: $(cat "$ws/output.json")"

# --- 3. ...unless the caller asked for the code instead
input '{"script": "exit 3", "failOnError": "false"}'
run "$ws" >"$ws/log.txt" 2>&1 || fail "failOnError=false: $(cat "$ws/output.json")"
[ "$(output "$ws" exitCode)" = "3" ] || fail "exitCode should be 3: $(cat "$ws/output.json")"

# --- 4. environment and arguments reach the script
input '{"script": "echo \"env=$SCOPED arg1=$1 arg2=$2\"", "env": "SCOPED=42", "args": "alpha\nbeta"}'
run "$ws" >"$ws/log.txt" 2>&1 || fail "env/args: $(cat "$ws/output.json")"
grep -q "env=42 arg1=alpha arg2=beta" "$ws/output.json" || fail "env/args not passed: $(cat "$ws/output.json")"

# --- 5. quotes and non-ASCII survive (they would not if the script went through sh -c)
input '{"script": "echo '\''single $notexpanded'\''; echo \"中文 🚀\""}'
run "$ws" >"$ws/log.txt" 2>&1 || fail "quoting: $(cat "$ws/output.json")"
grep -q 'single $notexpanded' "$ws/output.json" || fail "quoting was rewritten: $(cat "$ws/output.json")"
grep -q '中文' "$ws/output.json" || fail "unicode was mangled: $(cat "$ws/output.json")"

# --- 6. a script file in the workspace
printf 'echo from-the-file\n' >"$ws/task.sh"
input '{"scriptFile": "task.sh"}'
run "$ws" >"$ws/log.txt" 2>&1 || fail "scriptFile: $(cat "$ws/output.json")"
grep -q "from-the-file" "$ws/output.json" || fail "scriptFile was not run: $(cat "$ws/output.json")"

# --- 7. workdir
mkdir -p "$ws/sub"
input '{"script": "echo marker > here.txt", "workdir": "sub"}'
run "$ws" >"$ws/log.txt" 2>&1 || fail "workdir: $(cat "$ws/output.json")"
[ -f "$ws/sub/here.txt" ] || fail "the script did not run in the workdir"

# --- 8. a timeout is reported as a timeout, not as exit 143
input '{"script": "sleep 5", "timeoutSeconds": "1"}'
run "$ws" >"$ws/log.txt" 2>&1 && fail "a hung script must fail the step"
grep -q '"errorCode":40006' "$ws/output.json" || fail "expected 40006: $(cat "$ws/output.json")"
grep -q "timed out" "$ws/output.json" || fail "the failure must say it timed out: $(cat "$ws/output.json")"

# --- 9. bad input is named
input '{"interpreter": "brainfuck", "script": "+++"}'
run "$ws" >"$ws/log.txt" 2>&1 && fail "an unknown interpreter must be refused"
grep -q '"errorCode":40001' "$ws/output.json" || fail "expected 40001: $(cat "$ws/output.json")"
input '{"interpreter": "python", "interpreterPath": "/nonexistent/python3", "script": "print(1)"}'
run "$ws" >"$ws/log.txt" 2>&1 && fail "a bad interpreterPath must be refused"
grep -q "not found" "$ws/output.json" || fail "the failure must say not found: $(cat "$ws/output.json")"
input '{"scriptFile": "nope.sh"}'
run "$ws" >"$ws/log.txt" 2>&1 && fail "a missing script file must be refused"
grep -q '"errorCode":40003' "$ws/output.json" || fail "expected 40003: $(cat "$ws/output.json")"

# --- 10. python and node when they exist (this is where a CI matrix earns its keep)
if command -v python3 >/dev/null 2>&1; then
  input '{"interpreter": "python", "script": "import os\nprint(\"py\", os.environ.get(\"WHO\"))\n", "env": "WHO=atom"}'
  run "$ws" >"$ws/log.txt" 2>&1 || fail "python: $(cat "$ws/output.json")"
  grep -q "py atom" "$ws/output.json" || fail "python output: $(cat "$ws/output.json")"
fi
if command -v node >/dev/null 2>&1; then
  input '{"interpreter": "node", "script": "console.log(\"node ok\")"}'
  run "$ws" >"$ws/log.txt" 2>&1 || fail "node: $(cat "$ws/output.json")"
  grep -q "node ok" "$ws/output.json" || fail "node output: $(cat "$ws/output.json")"
fi

# --- 11. Windows-only interpreters: only meaningful on a Windows runner, and the CI matrix provides one
case "$os" in
  MINGW*|MSYS*|CYGWIN*|Windows*)
    input '{"interpreter": "bat", "script": "echo bat-works"}'
    run "$ws" >"$ws/log.txt" 2>&1 || fail "bat: $(cat "$ws/output.json")"
    grep -q "bat-works" "$ws/output.json" || fail "bat output: $(cat "$ws/output.json")"
    input '{"interpreter": "powershell", "script": "Write-Output pwsh-works"}'
    run "$ws" >"$ws/log.txt" 2>&1 || fail "powershell: $(cat "$ws/output.json")"
    grep -q "pwsh-works" "$ws/output.json" || fail "powershell output: $(cat "$ws/output.json")"
    extra="bat, powershell"
    ;;
  *) extra="(bat/powershell skipped: not a Windows host; CI covers them)" ;;
esac

echo "smoke ok: script/exit code/failOnError/env/args/quoting/unicode/scriptFile/workdir/timeout/bad input$extra"

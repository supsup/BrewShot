#!/bin/sh
set -eu

image=${1:-brewshot:local}
tmp_root=$(mktemp -d "${TMPDIR:-/tmp}/brewshot-docker-smoke.XXXXXX")
# TRAVERSAL, not just writability. `mktemp -d` creates 0700 owned by the invoking user, and
# the image runs as the fixed non-root UID 10001. Every bind mount below lives UNDER this
# directory, so a container UID that cannot TRAVERSE it cannot reach a child no matter how
# permissive that child is: `chmod 0777 child` beneath a 0700 parent still denies the write.
#
# HARDENING, not the repair for 741ba49 (brewshot/360) -- I checked, and it is not. The
# mechanism is real and reproduced in a Linux container: parent 0700 + child 0777 -> permission
# denied; parent 0755 + child 0777 -> writes. But with and without this chmod the suite failed
# at the SAME phase for the same reason (the worker-temp settle race handled below), so this
# fixes a latent trap rather than the failure that was actually observed. Docker Desktop
# translates ownership through its file-sharing layer, which is why the trap is invisible on
# macOS at all -- keep it closed, just do not credit it with the CI repair.
#
# 0755 rather than 0777 on purpose: traversal is all a container UID needs from the PARENT,
# and the individual mount dirs below already set the modes for the writes they take.
chmod 0755 "$tmp_root"
name_suffix=$$
watch_one="brewshot-smoke-one-$name_suffix"
watch_recovery="brewshot-smoke-recovery-$name_suffix"
watch_race_a="brewshot-smoke-race-a-$name_suffix"
watch_race_b="brewshot-smoke-race-b-$name_suffix"
watch_foreign="brewshot-smoke-foreign-$name_suffix"
foreign_input_volume="brewshot-smoke-foreign-input-$name_suffix"
foreign_output_volume="brewshot-smoke-foreign-output-$name_suffix"
watch_unreadable_a="brewshot-smoke-unreadable-a-$name_suffix"
watch_unreadable_b="brewshot-smoke-unreadable-b-$name_suffix"
watch_unreadable_c="brewshot-smoke-unreadable-c-$name_suffix"
watch_unreadable_d="brewshot-smoke-unreadable-d-$name_suffix"

# THE PHASE TRACKER (plan 4124aab6). `set -eu` aborts on the first failing command and prints
# NOTHING identifying it, so a red run named no cause at all — three separate diagnostic rounds
# on 2026-08-06 were spent re-deriving WHERE it died, twice by re-running under `sh -x` because
# the script itself would not say. `step` costs one line per section and turns a silent exit 1
# into the name of the phase that failed.
#
# It reports the PHASE, not the assertion. That is the deliberate 90% — annotating all 34 bare
# assertions would be a rewrite of a concurrency harness, and knowing the phase is what actually
# collapses the search.
current_step="startup"
step() {
    current_step="$1"
}

# An assertion that names itself. Used where the bare `test`/`grep` gave no clue.
fail() {
    echo "brewshot Docker smoke: FAILED — $1" >&2
    exit 1
}

cleanup() {
    # MUST BE THE FIRST LINE: every command below clobbers $?, so capture the real exit status
    # before doing anything else.
    smoke_status=$?
    if [ "$smoke_status" -ne 0 ]; then
        echo "brewshot Docker smoke: FAILED during phase: ${current_step}" >&2
        echo "  (exit ${smoke_status}; re-run with \`sh -x docker/smoke-test.sh <image>\` to get" \
             "the exact command)" >&2
    fi
    docker rm -f "$watch_one" "$watch_recovery" "$watch_race_a" "$watch_race_b" \
        "$watch_foreign" \
        "$watch_unreadable_a" "$watch_unreadable_b" \
        "$watch_unreadable_c" "$watch_unreadable_d" >/dev/null 2>&1 || true
    docker volume rm -f "$foreign_input_volume" "$foreign_output_volume" \
        >/dev/null 2>&1 || true
    if [ -n "$tmp_root" ] && [ "$tmp_root" != "/" ]; then
        # The worker writes artifacts owner-only as ITS uid, so a host that is not that uid can
        # neither chmod nor unlink them — `rm -rf` left the tree behind on a real CI runner and
        # printed permission-denied per file. Empty it from inside a container that can, then let
        # the host remove the now-empty directory it owns. Best-effort: a cleanup that fails must
        # never turn a PASSING smoke into a failure, which is why the container hop is `|| true`.
        docker run --rm --user 0:0 --volume "$tmp_root:/cleanup" --entrypoint /bin/sh "$image" \
            -c 'rm -rf /cleanup/..?* /cleanup/.[!.]* /cleanup/* 2>/dev/null || true' \
            >/dev/null 2>&1 || true
        rm -rf -- "$tmp_root" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

write_html() {
    target=$1
    title=$2
    color=$3
    printf '%s\n' \
        '<!doctype html>' \
        '<html lang="en"><meta charset="utf-8">' \
        "<title>$title</title>" \
        "<body style=\"margin:0;background:$color;font-family:sans-serif\">" \
        "<h1>$title</h1><p>BrewShot folder worker</p></body></html>" \
        > "$target"
}

wait_for_path() {
    target=$1
    container=$2
    attempt=0
    while [ ! -e "$target" ] && [ "$attempt" -lt 400 ]; do
        sleep 0.05
        attempt=$((attempt + 1))
    done
    if [ ! -e "$target" ]; then
        echo "timed out waiting for worker artifact: $target" >&2
        docker logs "$container" >&2 || true
        return 1
    fi
}

wait_for_container_path() {
    target=$1
    container=$2
    attempt=0
    while ! docker exec "$container" test -e "$target" 2>/dev/null \
            && [ "$attempt" -lt 400 ]; do
        sleep 0.05
        attempt=$((attempt + 1))
    done
    if ! docker exec "$container" test -e "$target" 2>/dev/null; then
        echo "timed out waiting for worker artifact: $target" >&2
        docker logs "$container" >&2 || true
        return 1
    fi
}

# Wait until no worker temp remains in the given output dirs. A losing worker
# publishes nothing but still renders to its own temp, so it holds that temp for
# a window AFTER the winner's output appears -- measured at ~4.8s on a Linux
# runner with four racing workers. Callers that inspect an output dir must let
# that window close first, or they assert against another worker's in-flight
# state rather than against a terminal one. Bounded, not a fixed sleep: it
# returns the moment the dirs are clean, and a temp that never goes away still
# fails (verified with an injected permanent orphan).
wait_for_no_worker_temps() {
    attempt=0
    while [ "$attempt" -lt 400 ]; do
        leftover=$(find "$@" -maxdepth 1 -type f \
            -name '.brewshot-watch-*' -print -quit)
        [ -z "$leftover" ] && return 0
        sleep 0.05
        attempt=$((attempt + 1))
    done
    echo "worker temp never cleaned up: $leftover" >&2
    return 1
}

wait_for_log() {
    container=$1
    pattern=$2
    attempt=0
    while ! docker logs "$container" 2>&1 | grep -q "$pattern"; do
        if [ "$attempt" -ge 400 ]; then
            echo "timed out waiting for worker log: $pattern" >&2
            docker logs "$container" >&2 || true
            return 1
        fi
        sleep 0.05
        attempt=$((attempt + 1))
    done
}

# mkdir -p a path whose PARENT may already be worker-owned, and set its mode, in one hop.
#
# The host cannot create a subdirectory inside a directory the worker made: by the time the
# restart-recovery phase runs, `processing/` exists and is owned by UID 10001, so a plain
# `mkdir -p` fails with permission denied BEFORE any chmod could help. Ordering a chmod first
# does not fix it either — the chmod has the same problem for the same reason. Doing both from
# inside a container removes the host's need for any privilege over the tree at all.
container_mkdir() {
    _mk_mode=$1
    _mk_path=$2
    _mk_parent=$(dirname "$_mk_path")
    docker run --rm --user 0:0 --volume "$_mk_parent:/target" --entrypoint /bin/sh "$image" \
        -c "mkdir -p '/target/$(basename "$_mk_path")' \
            && chmod $_mk_mode '/target' '/target/$(basename "$_mk_path")'" >/dev/null 2>&1 \
        || fail "could not create $_mk_path with mode $_mk_mode (container-side)"
}

# chmod a path that may ALREADY be worker-owned, from inside a container that can.
#
# The host creates most of these directories, but a worker may have created them first — it
# makes `processing/` the moment it claims a file — and then the host cannot chmod them at all.
# That is what stopped the restart-recovery phase on a non-root runner, one line after a comment
# explaining a DIFFERENT Linux permission fix on the same statement. Doing it container-side
# works whether the host or the worker got there first, which removes the ordering dependency
# rather than papering over one instance of it.
container_chmod() {
    _ch_mode=$1
    shift
    for _ch_path in "$@"; do
        docker run --rm --user 0:0 --volume "$(dirname "$_ch_path"):/target" \
            --entrypoint /bin/sh "$image" \
            -c "chmod $_ch_mode '/target/$(basename "$_ch_path")'" >/dev/null 2>&1 \
            || fail "could not chmod $_ch_mode $_ch_path (container-side)"
    done
}

# Assert an artifact is OWNER-ONLY (0600) — the property that makes every host-side read fail
# and therefore the property the container-side readers above quietly depend on.
#
# It had NO test. The permission behaviour turned main red once and was still only implicit
# afterwards, which means a future change could relax outputs to 0644 and every check here would
# keep passing while the security posture silently weakened. Reading as root is fine for a
# verifier BECAUSE this exists: the privilege that lets the reader work is exactly the privilege
# that would hide a regression, so the regression gets its own guard.
assert_owner_only() {
    mode=$(docker run --rm --user 0:0 --volume "$(dirname "$1"):/verify:ro" \
        --entrypoint /bin/sh "$image" -c "stat -c '%a' '/verify/$(basename "$1")'" 2>/dev/null)
    test "$mode" = "600" \
        || fail "expected owner-only 0600 on $1, got mode '$mode' — outputs must not be world-readable"
}

# Read a worker artifact's BYTES from inside the image under test, on stdout.
#
# Same reason as assert_png: outputs are owner-only to UID 10001, so ANY host-side content read
# fails on a runner whose uid differs — and it fails with "permission denied" on a file that is
# present and correct. Fixing assert_png alone was not enough; the next host-side reader (`cmp`)
# failed identically one line later, which is why this is a shared helper.
#
# An earlier version of this comment claimed the content reads had all been enumerated. They had
# not: four host-side `sha256sum` calls survived, and the first non-root run found them. The
# enumeration that missed them searched for readers that LOOK like readers (`cat`, `cmp`); a
# hashing command reads every byte of a file without resembling one. See container_sha256.
#
# DIRECTORY listings are deliberately NOT routed through this: the smoke's mount points are
# 0777, so `find`/`wc -l` over a directory works from the host and needs no container hop.
container_cat() {
    _cc_dir=$(dirname "$1")
    _cc_base=$(basename "$1")
    docker run --rm --user 0:0 --volume "$_cc_dir:/verify:ro" --entrypoint /bin/sh "$image" \
        -c "cat '/verify/$_cc_base'"
}

# Hash $1 FROM INSIDE A CONTAINER, and refuse to return anything that is not a hash.
#
# A host-side `sha256sum` on an owner-only output is worse than a plain read failure. It writes
# its complaint to stderr and NOTHING to stdout, so the caller's variable is set to the EMPTY
# STRING rather than left unset. Two such reads then compare equal to each other, and an
# immutability assertion written as `test "$before" = "$(...)"` passes while proving nothing —
# a green line whose subject was never read. That is the exact shape this script exists to close,
# and it survived a review and a self-audit before a non-root run exposed it.
#
# The hash is computed container-side rather than by piping container_cat into a host sha256sum,
# and the difference matters: a failed pipe would produce the hash OF ZERO BYTES — a well-formed
# 64-character hash that still compares equal to its twin. That is the same vacuity wearing a
# convincing disguise, and the length check below would not catch it.
container_sha256() {
    _cs_dir=$(dirname "$1")
    _cs_base=$(basename "$1")
    _cs_out=$(docker run --rm --user 0:0 --volume "$_cs_dir:/verify:ro" --entrypoint /bin/sh \
        "$image" -c "sha256sum '/verify/$_cs_base'" 2>/dev/null | cut -d ' ' -f 1)
    test ${#_cs_out} -eq 64 \
        || fail "container-side sha256 of $1 returned '$_cs_out' (expected 64 hex chars) — a read that yields no hash must fail here, not silently compare equal to another empty read"
    printf '%s\n' "$_cs_out"
}

# Assert a real PNG at $target, READ FROM INSIDE A CONTAINER rather than from this host.
#
# THE HOST CANNOT READ THE ARTIFACT, and that is by design. BrewShot writes outputs owner-only
# as its fixed UID 10001; a host whose uid differs gets permission-denied on a SUCCESSFUL
# capture. That is exactly what turned main red on the first real ubuntu-latest run:
#   "expected a PNG at .../legacy.png — file(1) reports: regular file, no read permission"
# The file was there and correct. The reader was wrong.
#
# This is a KNOWN class in this repo, already solved once: plan 2f42a765 fixed the capture gate
# the same way, and ci.yml still carries that shape — a read-only second invocation of the same
# pinned image. The smoke script simply never adopted it, because every environment it had been
# run in read as root, where mode bits never bite.
#
# Reads via the image under test, mounted READ-ONLY, so the assertion cannot mutate what it
# checks. `od` on the first 8 bytes is the PNG signature — the same check ci.yml uses — rather
# than file(1), which the runtime image does not carry.
assert_png() {
    target=$1
    dir=$(dirname "$target")
    base=$(basename "$target")
    if ! docker run --rm --user 0:0 --volume "$dir:/verify:ro" --entrypoint /bin/sh "$image" -eu -c "
            test -s '/verify/$base'
            test \"\$(od -An -t x1 -N 8 '/verify/$base' | tr -d ' \n')\" = '89504e470d0a1a0a'
        " >/dev/null 2>&1; then
        fail "expected a PNG at $target — container-side read found it missing, empty, or not PNG-signed"
    fi
}

assert_running() {
    container=$1
    if [ "$(docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null)" != true ]; then
        echo "container $container is not running; its logs follow" >&2
        docker logs "$container" >&2 || true
        fail "expected container $container to still be running"
    fi
}

step "runtime contract: fixed non-root identity + immutable artifacts"
# Runtime contract: fixed non-root identity, immutable artifacts, and fixed
# mount roots. The real captures below also prove Chromium and fonts work.
docker run --rm --entrypoint sh "$image" -c '
    test "$(id -u)" = 10001
    test -r /opt/brewshot/brewshot.jar
    test ! -w /opt/brewshot/brewshot.jar
    test -r /opt/brewshot/brewshot-worker.jar
    test ! -w /opt/brewshot/brewshot-worker.jar
    test -d /brewshot/input
    test -d /brewshot/output
'

input="$tmp_root/Input"
output="$tmp_root/Output"
mkdir -p "$input" "$output"
chmod 0777 "$input" "$output"

step 'no-mode image shape and explicit `cli` drive real Chromium'
# Old no-mode image shape and explicit `cli` both drive real Chromium.
write_html "$input/cli.html" 'CLI parity' '#f8fafc'
docker run --rm \
    -v "$input:/brewshot/input:ro" \
    -v "$output:/brewshot/output" \
    "$image" /brewshot/input/cli.html \
    -o /brewshot/output/legacy.png
docker run --rm \
    -v "$input:/brewshot/input:ro" \
    -v "$output:/brewshot/output" \
    "$image" cli /brewshot/input/cli.html \
    -o /brewshot/output/explicit.png
assert_png "$output/legacy.png"
assert_owner_only "$output/legacy.png"
assert_png "$output/explicit.png"
# POSIX sh has no process substitution, so the comparison happens INSIDE one container that
# can read both files, rather than by staging copies on a host that cannot read either.
docker run --rm --user 0:0 --volume "$output:/verify:ro" --entrypoint /bin/sh "$image" \
    -c 'cmp /verify/legacy.png /verify/explicit.png' \
    || fail "legacy.png and explicit.png differ"
docker run --rm "$image" cli --version | grep -q '^brewshot 0\.9\.0$'

step "page diagnostics publish private evidence before the exit gate"
cat > "$input/diagnostics.html" <<'HTML'
<!doctype html><meta charset="utf-8"><p>diagnostics</p><script>
console.log('before-error');
setTimeout(function () { throw new Error('docker-page-kaput'); }, 0);
</script>
HTML
set +e
docker run --rm \
    -v "$input:/brewshot/input:ro" \
    -v "$output:/brewshot/output" \
    "$image" cli /brewshot/input/diagnostics.html \
    -o /brewshot/output/diagnostics.png \
    --json /brewshot/output/diagnostics.json \
    --page-diagnostics --fail-page-errors --settle 100
diagnostics_status=$?
set -e
test "$diagnostics_status" -eq 4 \
    || fail "expected observed page exception exit 4, got $diagnostics_status"
assert_png "$output/diagnostics.png"
assert_owner_only "$output/diagnostics.json"
# The consumer contract is identity-sensitive: a different non-root UID cannot read the
# 0600 sidecar, while a deliberate container-side privileged reader can consume it.
docker run --rm --user 10002:10002 --volume "$output:/verify:ro" \
    --entrypoint /bin/sh "$image" \
    -c "test ! -r /verify/diagnostics.json" \
    || fail "a non-owner container identity could read the private diagnostics sidecar"
docker run --rm --user 0:0 --volume "$output:/verify:ro" \
    --entrypoint /bin/sh "$image" -eu -c '
        grep -q "\"outcome\": \"failed\"" /verify/diagnostics.json
        grep -q "\"page-errors\"" /verify/diagnostics.json
        grep -q docker-page-kaput /verify/diagnostics.json
    ' \
    || fail "container-side diagnostics receipt did not explain the observed gate"

step "pre-worker /work contract: relative output and default brewshot.png"
# The pre-worker image contract uses /work as its working directory. Preserve
# both explicit relative output and the default brewshot.png destination.
legacy_work="$tmp_root/LegacyWork"
mkdir -p "$legacy_work"
chmod 0777 "$legacy_work"
write_html "$legacy_work/relative.html" 'Relative CLI' '#fef9c3'
docker run --rm -v "$legacy_work:/work" \
    "$image" relative.html -o relative-output.png
assert_png "$legacy_work/relative-output.png"
write_html "$legacy_work/default-relative.html" 'Default CLI' '#e0f2fe'
docker run --rm -v "$legacy_work:/work" "$image" default-relative.html
assert_png "$legacy_work/brewshot.png"

step "Linux bind-mount shape overriding the fixed image UID"
# The documented Linux bind-mount shape may override the fixed image user.
# That UID still receives a private writable Chromium home.
docker run --rm --user 12345:12345 \
    -v "$input:/brewshot/input:ro" \
    -v "$output:/brewshot/output" \
    "$image" cli /brewshot/input/cli.html \
    -o /brewshot/output/arbitrary-uid.png
assert_png "$output/arbitrary-uid.png"

step "startup backlog, content-free failure, ignore rules, later progress"
# Startup backlog, one content-free failure, ignore rules, and later progress.
write_html "$input/startup page.html" 'Startup' '#dbeafe'
: > "$input/empty.htm"
long_stem=$(printf '%0250s' '' | tr ' ' x)
long_name="${long_stem}.html"
write_html "$input/$long_name" 'Maximum source name' '#fae8ff'
write_html "$input/.upload.html.tmp" 'Partial' '#fee2e2'
printf '%s\n' '{"ignored":true}' > "$input/ignored.json"
ln -s 'startup page.html' "$input/symlink.html"

docker run -d --name "$watch_one" \
    -e BREWSHOT_WATCH_POLL_MS=20 \
    -v "$input:/brewshot/input" \
    -v "$output:/brewshot/output" \
    "$image" watch >/dev/null

wait_for_path "$output/startup page.html.png" "$watch_one"
wait_for_path "$output/empty.htm.error.txt" "$watch_one"
wait_for_path "$input/finished/startup page.html" "$watch_one"
wait_for_path "$input/failed/empty.htm" "$watch_one"
wait_for_path "$input/finished/$long_name" "$watch_one"
assert_png "$output/startup page.html.png"
long_output=$(find "$output" -maxdepth 1 -type f -name 'job-*.png' -print -quit)
test -n "$long_output"
test "$(find "$output" -maxdepth 1 -type f -name 'job-*.png' | wc -l | tr -d ' ')" -eq 1
assert_png "$long_output"
test -e "$input/.upload.html.tmp"
test -e "$input/ignored.json"
test -L "$input/symlink.html"
test ! -e "$output/upload.html.tmp.png"
test ! -e "$output/ignored.json.png"
test ! -e "$output/symlink.html.png"
test "$(container_cat "$output/empty.htm.error.txt" | wc -c)" -le 600
container_cat "$output/empty.htm.error.txt" | grep -q 'empty-input'
assert_running "$watch_one"

write_html "$input/.after-failure.html.tmp" 'After failure' '#dcfce7'
mv "$input/.after-failure.html.tmp" "$input/after-failure.html"
wait_for_path "$output/after-failure.html.png" "$watch_one"
wait_for_path "$input/finished/after-failure.html" "$watch_one"
assert_png "$output/after-failure.html.png"
assert_running "$watch_one"

step "output/archive bytes immutable under a same-name resend"
# Existing output and archive bytes are immutable. A second same-name source
# with different pixels fails closed and is retained under the failed bucket.
startup_output_before=$(container_sha256 "$output/startup page.html.png")
startup_source_before=$(container_sha256 "$input/finished/startup page.html")
write_html "$input/.startup page.html.tmp" 'Collision' '#fef3c7'
mv "$input/.startup page.html.tmp" "$input/startup page.html"
wait_for_path "$output/startup page.html.error.txt" "$watch_one"
wait_for_path "$input/failed/startup page.html" "$watch_one"
test "$startup_output_before" = "$(container_sha256 "$output/startup page.html.png")"
test "$startup_source_before" = "$(container_sha256 "$input/finished/startup page.html")"
container_cat "$output/startup page.html.error.txt" | grep -q 'output-collision'
assert_running "$watch_one"
docker stop -t 3 "$watch_one" >/dev/null
docker rm "$watch_one" >/dev/null

step "restart recovery of a UUID claim directory"
# Restart recovery consumes a valid UUID claim directory and leaves unrelated
# processing entries alone.
recovery_id=0123456789abcdef0123456789abcdef
container_mkdir 0777 "$input/processing/$recovery_id"
# MEASURED ON LINUX, NOT ASSUMED (plan 4124aab6). `mkdir -p` creates the INTERMEDIATE and
# INNERMOST directories at the caller's UMASK -- 0755 under the default 0022 -- and only the
# path we chmod explicitly gets 0777. The image runs as USER 10001:10001, so on Linux, where a
# bind mount preserves host ownership, the worker can traverse these but cannot WRITE: archiving
# the claim fails with AccessDeniedException. On macOS Docker Desktop the bind mount translates
# UIDs, so the same script passes and the defect is invisible. Every other directory in this
# file is already chmod'd for exactly this reason; these two were created inside a phase rather
# than in the setup block and were missed.
# (modes set by container_mkdir above — the host never needed privilege here)
write_html "$input/processing/$recovery_id/recovered.html" 'Recovered' '#ede9fe'
printf '%s\n' 'not a claim' > "$input/processing/keep-me.txt"
docker run -d --name "$watch_recovery" \
    -e BREWSHOT_WATCH_POLL_MS=20 \
    -v "$input:/brewshot/input" \
    -v "$output:/brewshot/output" \
    "$image" watch >/dev/null
wait_for_path "$output/recovered.html.png" "$watch_recovery"
wait_for_path "$input/finished/recovered.html" "$watch_recovery"
assert_png "$output/recovered.html.png"
test -e "$input/processing/keep-me.txt"
assert_running "$watch_recovery"
docker stop -t 3 "$watch_recovery" >/dev/null
docker rm "$watch_recovery" >/dev/null

step "two workers sharing one mount converge on one success"
# Two workers sharing one mount converge on one terminal success.
race_input="$tmp_root/RaceInput"
race_output="$tmp_root/RaceOutput"
mkdir -p "$race_input" "$race_output"
chmod 0777 "$race_input" "$race_output"
docker run -d --name "$watch_race_a" \
    -e BREWSHOT_WATCH_POLL_MS=20 \
    -v "$race_input:/brewshot/input" \
    -v "$race_output:/brewshot/output" \
    "$image" watch >/dev/null
docker run -d --name "$watch_race_b" \
    -e BREWSHOT_WATCH_POLL_MS=20 \
    -v "$race_input:/brewshot/input" \
    -v "$race_output:/brewshot/output" \
    "$image" watch >/dev/null
wait_for_log "$watch_race_a" 'brewshot-watch: ready'
wait_for_log "$watch_race_b" 'brewshot-watch: ready'
write_html "$race_input/.race.html.tmp" 'Race' '#cffafe'
mv "$race_input/.race.html.tmp" "$race_input/race.html"
wait_for_path "$race_output/race.html.png" "$watch_race_a"
wait_for_path "$race_input/finished/race.html" "$watch_race_a"
sleep 0.2
assert_png "$race_output/race.html.png"
test ! -e "$race_output/race.html.error.txt"
test ! -e "$race_input/failed/race.html"
assert_running "$watch_race_a"
assert_running "$watch_race_b"
finished_count=$(
    { docker logs "$watch_race_a"; docker logs "$watch_race_b"; } 2>&1 \
        | grep -c ' finished$'
)
test "$finished_count" -eq 1
# Let the loser release its temp BEFORE the workers are killed. A SIGKILLed
# worker cannot clean up, so stopping first would strand a temp here and charge
# it to the final terminal-path assertion below, which is about what the worker
# does on its OWN exit paths -- not about what survives an abrupt kill.
wait_for_no_worker_temps "$race_output"
docker stop -t 3 "$watch_race_a" "$watch_race_b" >/dev/null
docker rm "$watch_race_a" "$watch_race_b" >/dev/null

step "producer-owned readable file without hard-link permission"
# A producer-owned readable file must not require hard-link permission from
# the fixed 10001 worker. Linux protects a root-owned mode-0444 inode from that
# link, while writable directories still permit the atomic claim/move state
# machine. The same watcher must remain live for a later job.
docker volume create "$foreign_input_volume" >/dev/null
docker volume create "$foreign_output_volume" >/dev/null
docker run --rm --user 0:0 \
    -v "$foreign_input_volume:/brewshot/input" \
    --entrypoint sh "$image" -c '
        chmod 0777 /brewshot/input
        printf "%s\n" \
            "<!doctype html>" \
            "<html lang=\"en\"><meta charset=\"utf-8\">" \
            "<title>Foreign readable</title>" \
            "<body><h1>Foreign readable</h1></body></html>" \
            > /brewshot/input/foreign-readable.html
        chmod 0444 /brewshot/input/foreign-readable.html
        test "$(stat -c "%u:%g" /brewshot/input/foreign-readable.html)" = "0:0"
    '
docker run --rm --user 0:0 \
    -v "$foreign_output_volume:/brewshot/output" \
    --entrypoint sh "$image" -c 'chmod 0777 /brewshot/output'
docker run -d --name "$watch_foreign" \
    -e BREWSHOT_WATCH_POLL_MS=20 \
    -v "$foreign_input_volume:/brewshot/input" \
    -v "$foreign_output_volume:/brewshot/output" \
    "$image" watch >/dev/null
wait_for_container_path \
    /brewshot/output/foreign-readable.html.png "$watch_foreign"
wait_for_container_path \
    /brewshot/input/finished/foreign-readable.html "$watch_foreign"
assert_running "$watch_foreign"
docker run --rm --user 0:0 \
    -v "$foreign_input_volume:/brewshot/input" \
    --entrypoint sh "$image" -c '
        printf "%s\n" \
            "<!doctype html>" \
            "<html><title>After foreign</title><body>After foreign</body></html>" \
            > /brewshot/input/.after-foreign.html.tmp
        chmod 0444 /brewshot/input/.after-foreign.html.tmp
        mv /brewshot/input/.after-foreign.html.tmp \
            /brewshot/input/after-foreign.html
    '
wait_for_container_path \
    /brewshot/output/after-foreign.html.png "$watch_foreign"
wait_for_container_path \
    /brewshot/input/finished/after-foreign.html "$watch_foreign"
assert_running "$watch_foreign"
foreign_inspect="$tmp_root/ForeignInspect"
mkdir -p "$foreign_inspect"
docker run --rm --user 0:0 \
    -v "$foreign_output_volume:/brewshot/output:ro" \
    -v "$foreign_inspect:/inspect" \
    --entrypoint sh "$image" -c '
        cp /brewshot/output/foreign-readable.html.png /inspect/
        cp /brewshot/output/after-foreign.html.png /inspect/
        chmod 0644 /inspect/foreign-readable.html.png \
            /inspect/after-foreign.html.png
    '
assert_png "$foreign_inspect/foreign-readable.html.png"
assert_png "$foreign_inspect/after-foreign.html.png"
test -z "$(docker exec "$watch_foreign" find /brewshot/output \
    -maxdepth 1 -type f -name '.brewshot-watch-*' -print -quit)"
docker stop -t 3 "$watch_foreign" >/dev/null
docker rm "$watch_foreign" >/dev/null

step "four workers race one unreadable processing claim"
# Four recovery workers race one unreadable processing claim. Direct archive
# and diagnostic collisions are unrelated sentinels: only physical file
# identity may prove disposition, never the same pathname.
unreadable_input="$tmp_root/UnreadableInput"
unreadable_output="$tmp_root/UnreadableOutput"
unreadable_id=fedcba9876543210fedcba9876543210
mkdir -p "$unreadable_input/processing/$unreadable_id" \
    "$unreadable_input/failed" "$unreadable_output"
# The innermost UUID directory needs it too, for the reason spelled out at the recovery phase
# above -- listing only its PARENT leaves the leaf at umask, which is the same defect one level
# down and is why this line names the claim directory explicitly.
chmod 0777 "$unreadable_input" "$unreadable_input/processing" \
    "$unreadable_input/processing/$unreadable_id" \
    "$unreadable_input/failed" "$unreadable_output"
write_html "$unreadable_input/processing/$unreadable_id/unreadable.html" \
    'Unreadable secret' '#fecaca'
printf '%s\n' 'ARCHIVE-SENTINEL' > "$unreadable_input/failed/unreadable.html"
printf '%s\n' 'DIAGNOSTIC-SENTINEL' > "$unreadable_output/unreadable.html.error.txt"
chmod 000 "$unreadable_input/processing/$unreadable_id/unreadable.html"
for worker in "$watch_unreadable_a" "$watch_unreadable_b" \
        "$watch_unreadable_c" "$watch_unreadable_d"; do
    docker run -d --name "$worker" \
        -e BREWSHOT_WATCH_POLL_MS=20 \
        -v "$unreadable_input:/brewshot/input" \
        -v "$unreadable_output:/brewshot/output" \
        "$image" watch >/dev/null
done
wait_for_path "$unreadable_input/failed/collisions/$unreadable_id/unreadable.html" \
    "$watch_unreadable_a"
wait_for_path "$unreadable_output/job-$unreadable_id-$unreadable_id.error.txt" \
    "$watch_unreadable_a"
test "$(container_cat "$unreadable_input/failed/unreadable.html")" = 'ARCHIVE-SENTINEL'
test "$(container_cat "$unreadable_output/unreadable.html.error.txt")" = 'DIAGNOSTIC-SENTINEL'
test "$(find "$unreadable_output" -maxdepth 1 -type f \
    -name "job-$unreadable_id-*.error.txt" | wc -l | tr -d ' ')" -eq 1
test -z "$(find "$unreadable_input/processing" "$unreadable_input/failed/pending" \
    -type f -name 'unreadable.html' -print -quit)"
for worker in "$watch_unreadable_a" "$watch_unreadable_b" \
        "$watch_unreadable_c" "$watch_unreadable_d"; do
    assert_running "$worker"
done
write_html "$unreadable_input/.after-unreadable.html.tmp" \
    'After unreadable race' '#e0e7ff'
mv "$unreadable_input/.after-unreadable.html.tmp" \
    "$unreadable_input/after-unreadable.html"
wait_for_path "$unreadable_output/after-unreadable.html.png" "$watch_unreadable_a"
assert_png "$unreadable_output/after-unreadable.html.png"

step "no complete-looking hidden artifacts survive a terminal path"
# No complete-looking hidden artifacts survive any terminal path. The preceding
# phase waits only on the WINNER's output, so three losing workers are still
# inside their own write-then-delete window when this runs; polling for the
# window to close is what makes this an assertion about terminal state instead
# of a race against it.
wait_for_no_worker_temps "$output" "$race_output" "$unreadable_output"

echo 'brewshot Docker smoke: PASS'

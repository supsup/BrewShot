#!/bin/sh
set -eu

image=${1:-brewshot:local}
tmp_root=$(mktemp -d "${TMPDIR:-/tmp}/brewshot-docker-smoke.XXXXXX")
name_suffix=$$
watch_one="brewshot-smoke-one-$name_suffix"
watch_recovery="brewshot-smoke-recovery-$name_suffix"
watch_race_a="brewshot-smoke-race-a-$name_suffix"
watch_race_b="brewshot-smoke-race-b-$name_suffix"
watch_unreadable_a="brewshot-smoke-unreadable-a-$name_suffix"
watch_unreadable_b="brewshot-smoke-unreadable-b-$name_suffix"
watch_unreadable_c="brewshot-smoke-unreadable-c-$name_suffix"
watch_unreadable_d="brewshot-smoke-unreadable-d-$name_suffix"

cleanup() {
    docker rm -f "$watch_one" "$watch_recovery" "$watch_race_a" "$watch_race_b" \
        "$watch_unreadable_a" "$watch_unreadable_b" \
        "$watch_unreadable_c" "$watch_unreadable_d" >/dev/null 2>&1 || true
    if [ -n "$tmp_root" ] && [ "$tmp_root" != "/" ]; then
        chmod -R u+rwx "$tmp_root" >/dev/null 2>&1 || true
        rm -rf -- "$tmp_root"
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

assert_png() {
    target=$1
    test -s "$target"
    file "$target" | grep -q 'PNG image data'
}

assert_running() {
    container=$1
    test "$(docker inspect --format '{{.State.Running}}' "$container")" = true
}

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
assert_png "$output/explicit.png"
cmp "$output/legacy.png" "$output/explicit.png"
docker run --rm "$image" cli --version | grep -q '^brewshot 0\.9\.0$'

# The documented Linux bind-mount shape may override the fixed image user.
# That UID still receives a private writable Chromium home.
docker run --rm --user 12345:12345 \
    -v "$input:/brewshot/input:ro" \
    -v "$output:/brewshot/output" \
    "$image" cli /brewshot/input/cli.html \
    -o /brewshot/output/arbitrary-uid.png
assert_png "$output/arbitrary-uid.png"

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
test "$(wc -c < "$output/empty.htm.error.txt")" -le 600
grep -q 'empty-input' "$output/empty.htm.error.txt"
assert_running "$watch_one"

write_html "$input/.after-failure.html.tmp" 'After failure' '#dcfce7'
mv "$input/.after-failure.html.tmp" "$input/after-failure.html"
wait_for_path "$output/after-failure.html.png" "$watch_one"
wait_for_path "$input/finished/after-failure.html" "$watch_one"
assert_png "$output/after-failure.html.png"
assert_running "$watch_one"

# Existing output and archive bytes are immutable. A second same-name source
# with different pixels fails closed and is retained under the failed bucket.
startup_output_before=$(sha256sum "$output/startup page.html.png" | cut -d ' ' -f 1)
startup_source_before=$(sha256sum "$input/finished/startup page.html" | cut -d ' ' -f 1)
write_html "$input/.startup page.html.tmp" 'Collision' '#fef3c7'
mv "$input/.startup page.html.tmp" "$input/startup page.html"
wait_for_path "$output/startup page.html.error.txt" "$watch_one"
wait_for_path "$input/failed/startup page.html" "$watch_one"
test "$startup_output_before" = \
    "$(sha256sum "$output/startup page.html.png" | cut -d ' ' -f 1)"
test "$startup_source_before" = \
    "$(sha256sum "$input/finished/startup page.html" | cut -d ' ' -f 1)"
grep -q 'output-collision' "$output/startup page.html.error.txt"
assert_running "$watch_one"
docker stop -t 3 "$watch_one" >/dev/null
docker rm "$watch_one" >/dev/null

# Restart recovery consumes a valid UUID claim directory and leaves unrelated
# processing entries alone.
recovery_id=0123456789abcdef0123456789abcdef
mkdir -p "$input/processing/$recovery_id"
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
docker stop -t 3 "$watch_race_a" "$watch_race_b" >/dev/null
docker rm "$watch_race_a" "$watch_race_b" >/dev/null

# Four recovery workers race one unreadable processing claim. Direct archive
# and diagnostic collisions are unrelated sentinels: only physical file
# identity may prove disposition, never the same pathname.
unreadable_input="$tmp_root/UnreadableInput"
unreadable_output="$tmp_root/UnreadableOutput"
unreadable_id=fedcba9876543210fedcba9876543210
mkdir -p "$unreadable_input/processing/$unreadable_id" \
    "$unreadable_input/failed" "$unreadable_output"
chmod 0777 "$unreadable_input" "$unreadable_input/processing" \
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
test "$(cat "$unreadable_input/failed/unreadable.html")" = 'ARCHIVE-SENTINEL'
test "$(cat "$unreadable_output/unreadable.html.error.txt")" = 'DIAGNOSTIC-SENTINEL'
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

# No complete-looking hidden artifacts survive any terminal path.
test -z "$(find "$output" "$race_output" "$unreadable_output" \
    -maxdepth 1 -type f -name '.brewshot-watch-*' -print -quit)"

echo 'brewshot Docker smoke: PASS'

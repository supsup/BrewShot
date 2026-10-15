#!/usr/bin/env python3
"""Real watched job integration smoke. Python stdlib only; pass the built image."""
import hashlib
import http.server
import io
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import zipfile


def docker(*args, check=True):
    return subprocess.run(["docker", *map(str, args)], check=check,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def gif_info(data):
    """Independent structural reader; distinct encoded images, not a pixel verdict."""
    assert data[:6] in (b"GIF87a", b"GIF89a")
    width, height, packed = struct.unpack_from("<HHB", data, 6)
    pos = 13
    global_table = b""
    if packed & 128:
        length = 3 * (2 << (packed & 7))
        global_table, pos = data[pos:pos + length], pos + length
    frames, delays, payloads = 0, [], set()
    delay = 0

    def blocks(offset):
        content = bytearray()
        while True:
            count = data[offset]
            offset += 1
            if not count:
                return bytes(content), offset
            content.extend(data[offset:offset + count])
            assert len(data[offset:offset + count]) == count
            offset += count

    while pos < len(data):
        kind, pos = data[pos], pos + 1
        if kind == 0x3B:
            assert pos == len(data), "trailing bytes"
            return width, height, frames, delays, len(payloads)
        if kind == 0x21:
            label, pos = data[pos], pos + 1
            extension, pos = blocks(pos)
            if label == 0xF9:
                assert len(extension) == 4
                delay = int.from_bytes(extension[1:3], "little") * 10
        elif kind == 0x2C:
            descriptor = data[pos:pos + 9]
            assert len(descriptor) == 9
            local_packed = descriptor[8]
            pos += 9
            table = global_table
            if local_packed & 128:
                length = 3 * (2 << (local_packed & 7))
                table, pos = data[pos:pos + length], pos + length
            lzw, pos = data[pos], pos + 1
            image, pos = blocks(pos)
            payloads.add(hashlib.sha256(table + bytes([lzw]) + image).digest())
            frames += 1
            delays.append(delay)
        else:
            raise AssertionError(f"invalid GIF block {kind}")
    raise AssertionError("missing GIF trailer")


def main():
    image = sys.argv[1] if len(sys.argv) > 1 else "brewshot:local"
    root = Path(tempfile.mkdtemp(prefix="brewshot-job-smoke-"))
    root.chmod(0o755)
    input_dir, output_dir, site = (root / name for name in ("in", "out", "site"))
    for path in (input_dir, output_dir, site):
        path.mkdir(mode=0o777)
        path.chmod(0o777)
    identity = uuid.uuid4().hex[:12]
    names = [f"brewshot-jobs-{identity}-{index}" for index in range(2)]
    step = "setup"
    started = []
    handler = lambda *args, **kwargs: http.server.SimpleHTTPRequestHandler(
        *args, directory=str(site), **kwargs)
    server = http.server.ThreadingHTTPServer(("0.0.0.0", 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    def start(name):
        docker("run", "-d", "--name", name,
               "--add-host", "host.docker.internal:host-gateway",
               "-e", "BREWSHOT_WATCH_POLL_MS=20",
               "-e", "BREWSHOT_WATCH_JOB_TIMEOUT_MS=15000",
               "-v", f"{input_dir}:/brewshot/input",
               "-v", f"{output_dir}:/brewshot/output", image, "watch")
        started.append(name)

    def wait(relative, timeout=60):
        limit = time.monotonic() + timeout
        while time.monotonic() < limit:
            if (root / relative).exists():
                return
            time.sleep(0.05)
        raise AssertionError(f"missing {relative}")

    def read_output(name):
        return docker("exec", names[0], "cat", f"/brewshot/output/{name}")

    def submit(name, content):
        hidden = input_dir / ("." + name + ".upload")
        hidden.write_bytes(content)
        hidden.chmod(0o644)
        hidden.rename(input_dir / name)

    def manifest(source, fmt="gif", **capture):
        rules = {"format": fmt, "viewport": {"width": 320, "height": 240},
                 "dpr": 1, "settleMs": 50, "selector": ".stage"}
        if fmt == "gif":
            rules.update(frames=4, delayMs=100)
        rules.update(capture)
        return {"version": 1, "source": source, "capture": rules}

    html = b'''<!doctype html><link rel="stylesheet" href="style.css">
        <div class="stage"><img src="button.svg"><span id="tick"></span></div>
        <script src="animation.js"></script>'''
    css = b"body{margin:0}.stage{width:160px;height:100px;background:#123456}"
    js = b'''let n=0; setInterval(()=>{
        document.querySelector('.stage').style.backgroundColor=
            `rgb(${(++n*47)%255},${(n*83)%255},${(n*131)%255})`;
        document.querySelector('#tick').textContent=n;
    },40);'''
    svg = b'<svg xmlns="http://www.w3.org/2000/svg" width="12" height="12"><rect width="12" height="12" fill="white"/></svg>'
    for name, data in (("index.html", html), ("style.css", css),
                       ("animation.js", js), ("button.svg", svg)):
        (site / name).write_bytes(data)
        (site / name).chmod(0o644)

    def bundle(job, extra=None):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("job.json", json.dumps(job))
            for name in ("index.html", "style.css", "animation.js", "button.svg"):
                archive.writestr(name, (site / name).read_bytes())
            archive.writestr("prepare.js", "document.querySelector('.stage').style.width='128px';")
            if extra:
                archive.writestr(*extra)
        return data.getvalue()

    try:
        step = "URL GIF and ignored non-job JSON"
        start(names[0])
        (input_dir / "notes.json").write_text('{"ignored":true}')
        url = f"http://host.docker.internal:{server.server_port}/index.html"
        submit("url.brewshot.json", json.dumps(manifest({"url": url})).encode())
        wait("in/finished/url.brewshot.json")
        info = gif_info(read_output("url.brewshot.json.gif"))
        assert info[:3] == (160, 100, 4) and info[3] == [100] * 4 and info[4] >= 2, info
        assert (input_dir / "notes.json").is_file()

        step = "ZIP assets, preparation script, selector and scale"
        job = manifest({"file": "index.html"}, scale=0.5)
        job["prepareScript"] = "prepare.js"
        package = bundle(job)
        submit("bundle.brewshot.zip", package)
        wait("in/finished/bundle.brewshot.zip")
        gif = read_output("bundle.brewshot.zip.gif")
        info = gif_info(gif)
        assert info[:3] == (64, 50, 4) and info[3] == [100] * 4 and info[4] >= 2, info
        # Preserve artifacts outside owner-only mount for independent local decoding.
        (root / "bundle-decoded-input.gif").write_bytes(gif)

        step = "format PNG and validation failures followed by later progress"
        submit("still.brewshot.zip", bundle(manifest({"file": "index.html"}, "png")))
        wait("in/finished/still.brewshot.zip")
        png = read_output("still.brewshot.zip.png")
        assert png[:8] == b"\x89PNG\r\n\x1a\n"
        assert struct.unpack_from(">II", png, 16) == (160, 100)
        failures = {
            "scheme.brewshot.json": json.dumps(manifest({"url": "file:///etc/passwd"})).encode(),
            "range.brewshot.json": json.dumps(manifest({"url": url}, dpr=5)).encode(),
            "unknown.brewshot.json": b'{"version":1,"unexpected":true}',
            "selector.brewshot.json": json.dumps(manifest({"url": url}, selector=".absent")).encode(),
            "unreachable.brewshot.json": json.dumps(manifest({"url": "http://127.0.0.1:1/"})).encode(),
            "broken.brewshot.zip": b"not a ZIP",
            "traversal.brewshot.zip": bundle(job, ("../escape", "bad")),
        }
        for name, data in failures.items():
            submit(name, data)
            wait("in/failed/" + name)
            error = read_output(name + ".error.txt")
            assert 0 < len(error) <= 600
            assert not (output_dir / (name + ".gif")).exists()

        step = "outer deadline and worker progress afterwards"
        submit("deadline.brewshot.json", json.dumps(manifest({"url": url}, settleMs=60000)).encode())
        wait("in/failed/deadline.brewshot.json", timeout=40)
        assert b"job-deadline" in read_output("deadline.brewshot.json.error.txt")
        submit("after.html", b"<!doctype html><h1>Still alive</h1>")
        wait("in/finished/after.html")
        assert read_output("after.html.png")[:8] == b"\x89PNG\r\n\x1a\n"

        step = "no replacement on same-name GIF resend"
        before = hashlib.sha256(gif).hexdigest()
        submit("bundle.brewshot.zip", package)
        wait("in/failed/bundle.brewshot.zip")
        assert hashlib.sha256(read_output("bundle.brewshot.zip.gif")).hexdigest() == before

        step = "restart recovery and two-worker single-job convergence"
        docker("stop", "-t", "15", names[0])
        docker("rm", names[0])
        started.remove(names[0])
        claim_id = uuid.uuid4().hex
        processing = input_dir / "processing" / claim_id
        # Directories are worker-owned; create the synthetic recovered claim
        # using the worker uid through its own image rather than host root assumptions.
        docker("run", "--rm", "-v", f"{input_dir}:/brewshot/input",
               "--entrypoint", "/bin/sh", image, "-c",
               f"mkdir -p /brewshot/input/processing/{claim_id}")
        staged = input_dir / ".recovered.upload"
        staged.write_bytes(package)
        staged.chmod(0o644)
        docker("run", "--rm", "--user", "0:0", "-v", f"{input_dir}:/brewshot/input",
               "--entrypoint", "/bin/sh", image, "-c",
               f"cp /brewshot/input/.recovered.upload /brewshot/input/processing/{claim_id}/recovered.brewshot.zip")
        start(names[0])
        start(names[1])
        wait("in/finished/recovered.brewshot.zip")
        assert gif_info(read_output("recovered.brewshot.zip.gif"))[:3] == (64, 50, 4)
        assert not processing.exists()
        step = "restart after publication reuses the durable selected GIF"
        for name in list(started):
            docker("stop", "-t", "15", name)
            docker("rm", name)
            started.remove(name)
        published_id = uuid.uuid4().hex
        source_hash = hashlib.sha256(package).hexdigest()
        # Model the exact durable filesystem state between publish and archive:
        # immutable claim, committed image and its public hard link already exist.
        docker("run", "--rm", "--user", "0:0",
               "-v", f"{input_dir}:/brewshot/input", "-v", f"{output_dir}:/brewshot/output",
               "--entrypoint", "/bin/sh", image, "-ec",
               f"mkdir -p /brewshot/input/processing/{published_id} "
               f"/brewshot/output/.brewshot-state/{published_id}; "
               f"chown 10001:10001 /brewshot/input/processing/{published_id} "
               f"/brewshot/output/.brewshot-state/{published_id}; "
               f"cp /brewshot/input/.recovered.upload /brewshot/input/processing/{published_id}/published.brewshot.zip; "
               f"cp /brewshot/output/bundle.brewshot.zip.gif /brewshot/output/.brewshot-state/{published_id}/{source_hash}.decision; "
               f"chown 10001:10001 /brewshot/output/.brewshot-state/{published_id}/{source_hash}.decision; "
               f"chmod 600 /brewshot/output/.brewshot-state/{published_id}/{source_hash}.decision; "
               f"ln /brewshot/output/.brewshot-state/{published_id}/{source_hash}.decision /brewshot/output/published.brewshot.zip.gif")
        start(names[0])
        start(names[1])
        wait("in/finished/published.brewshot.zip")
        assert read_output("published.brewshot.zip.gif") == gif
        assert not (input_dir / "failed/published.brewshot.zip").exists()
        for name in started:
            assert docker("inspect", "-f", "{{.State.Running}}", name).strip() == b"true"
        print("watch job smoke: PASS — URL/ZIP GIFs, PNG, refusals, deadline, collision, recovery and two workers")
        print(json.dumps({"bundleGif": info, "sha256": before, "image": image}))
        preserve = os.environ.get("BREWSHOT_SMOKE_EVIDENCE_DIR")
        if preserve:
            destination = Path(preserve)
            destination.mkdir(parents=True, exist_ok=True)
            (destination / "watch-bundle.gif").write_bytes(gif)
            (destination / "watch-bundle.png").write_bytes(png)
    except Exception:
        print(f"watch job smoke: FAILED during {step}", file=sys.stderr)
        for name in started:
            print(docker("logs", name, check=False).decode(errors="replace"), file=sys.stderr)
        raise
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
        for name in started:
            docker("rm", "-f", name, check=False)
        # Fixed uid outputs are owner-only. Match the existing smoke's
        # deliberate container-side reader/cleanup instead of weakening modes.
        docker("run", "--rm", "--user", "0:0", "-v", f"{root}:/cleanup",
               "--entrypoint", "/bin/sh", image, "-c",
               "rm -rf /cleanup/in /cleanup/out", check=False)
        shutil.rmtree(root)


if __name__ == "__main__":
    main()

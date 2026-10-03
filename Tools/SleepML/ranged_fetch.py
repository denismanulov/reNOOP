#!/usr/bin/env python3
"""ranged_fetch.py: download one large file over several ranged connections, resumably.

PhysioNet serves an open-access archive at about 100 kB/s per client, which puts a 6 GB ZIP at most
of a day, far longer than one connection can be expected to survive. The server honours Range, so
the file is fetched as fixed-size chunks, each written at its own offset in the output. Finished
chunks are recorded beside the output, so a rerun continues where the last one stopped, and a
dropped connection continues inside its chunk instead of starting the chunk again. Several
connections help only a little there (the cap is per client), so keep the count small.

USAGE:  ranged_fetch.py <url> <output> [connections]
"""
import concurrent.futures
import os
import subprocess
import sys
import time

CHUNK = 4 * 1024 * 1024
STALLS = 8


def probe(url):
    """Follow any redirect once and read the full length, so the workers hit the file directly."""
    out = subprocess.run(
        ["curl", "--fail", "--silent", "--show-error", "--location", "--range", "0-0",
         "--retry", "6", "--retry-delay", "5", "--retry-all-errors",
         "--output", os.devnull, "--dump-header", "-", "--write-out", "\n%{url_effective}", url],
        check=True, capture_output=True, text=True).stdout
    sizes = [line.rsplit("/", 1)[1] for line in out.splitlines()
             if line.lower().startswith("content-range:")]
    if not sizes:
        raise RuntimeError("server did not answer a ranged request")
    return out.splitlines()[-1], int(sizes[-1])


def fetch_chunk(url, fd, index, size):
    lo = index * CHUNK
    hi = min(lo + CHUNK, size) - 1
    off = lo
    stalls = 0
    while stalls < STALLS:
        # curl carries the transfer: it uses the system trust store and gives up on a stalled link.
        proc = subprocess.Popen(
            ["curl", "--fail", "--silent", "--range", f"{off}-{hi}",
             "--speed-limit", "1000", "--speed-time", "60", url],
            stdout=subprocess.PIPE)
        before = off
        while off <= hi:
            block = proc.stdout.read(min(1 << 16, hi + 1 - off))
            if not block:
                break
            os.pwrite(fd, block, off)
            off += len(block)
        overrun = bool(proc.stdout.read(1))
        proc.stdout.close()
        proc.wait()
        if overrun:
            raise RuntimeError(f"chunk {index}: server sent more than the requested range")
        if off == hi + 1:
            return index
        # Only an attempt that moved nothing counts toward giving up.
        stalls = 0 if off > before else stalls + 1
        time.sleep(5 * (stalls + 1))
    raise RuntimeError(f"chunk {index} made no progress in {STALLS} attempts")


def main(url, out, connections):
    url, size = probe(url)
    total = (size + CHUNK - 1) // CHUNK

    # The ledger is only valid for the chunking and length it was written under.
    ledger = out + ".chunks"
    header = f"# chunk={CHUNK} size={size}"
    done = set()
    if os.path.exists(ledger) and os.path.exists(out) and os.path.getsize(out) == size:
        with open(ledger) as fh:
            lines = fh.read().split("\n")
        if lines and lines[0] == header:
            done = {int(line) for line in lines[1:] if line.strip()}
    if not done:
        with open(ledger, "w") as fh:
            fh.write(header + "\n")
    fd = os.open(out, os.O_RDWR | os.O_CREAT, 0o644)
    os.ftruncate(fd, size)

    todo = [i for i in range(total) if i not in done]
    print(f"{size / 1e9:.2f} GB in {total} chunks, {len(done)} already present, "
          f"{connections} connections", flush=True)
    started = time.time()
    fetched = 0
    with open(ledger, "a") as log, concurrent.futures.ThreadPoolExecutor(connections) as pool:
        for future in concurrent.futures.as_completed(
                [pool.submit(fetch_chunk, url, fd, i, size) for i in todo]):
            log.write(f"{future.result()}\n")
            log.flush()
            fetched += 1
            if fetched % 16 == 0 or fetched == len(todo):
                rate = fetched * CHUNK / (time.time() - started) / 1e6
                print(f"{len(done) + fetched}/{total} chunks, {rate:.2f} MB/s", flush=True)
    os.close(fd)
    os.remove(ledger)
    print(f"complete: {out}", flush=True)


if __name__ == "__main__":
    if len(sys.argv) not in (3, 4):
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) == 4 else 3)

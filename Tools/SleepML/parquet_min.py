"""parquet_min.py — just enough Parquet to read the Wearanize+ OA synchronised recordings.

No Parquet library is assumed. This reads the footer, GZIP and Snappy pages, PLAIN and dictionary
encodings and one level of list nesting, which is all those files use, from a local file or, through
`RangeFile`, straight off a web server by byte range, so one column of a 400 MB file costs that column.
"""
import re
import struct
import subprocess
import time
import zlib

import numpy as np


class RangeFile:
    """A read-only, seekable view of a remote file, fetched by HTTP byte range with curl.

    curl rather than urllib: the python.org build here ships without root certificates. The last
    `tail` bytes are fetched once at open and kept, since the Parquet footer and the small columns
    beside it are all read from there.
    """

    def __init__(self, url, tail=262144):
        self.url, self.pos, self.fetched = url, 0, 0
        head = self._curl(["--range", "0-0", "--dump-header", "-", "--output", "/dev/null", url])
        m = re.search(rb"(?im)^content-range:\s*bytes\s+0-0/(\d+)", head)
        if not m:
            raise IOError("the server did not answer a byte-range request: " + url)
        self.size = int(m.group(1))
        self.tail_start = max(0, self.size - tail)
        self.tail = self._get(self.tail_start, self.size - self.tail_start)

    @staticmethod
    def _curl(args, attempts=6):
        """One curl call per range, so one TLS handshake each; a refused handshake is retried here
        because curl's own --retry does not cover it."""
        for i in range(attempts):
            r = subprocess.run(["curl", "--silent", "--show-error", "--fail", "--location", "--retry", "5",
                                "--retry-delay", "3", "--retry-all-errors"] + args, capture_output=True)
            if r.returncode == 0:
                return r.stdout
            time.sleep(2 * (i + 1))
        raise IOError("curl failed %d times (exit %d): %s" % (attempts, r.returncode,
                                                             r.stderr.decode(errors="replace").strip()))

    def _get(self, start, n):
        out = self._curl(["--max-filesize", str(n + 4096), "--range", "%d-%d" % (start, start + n - 1),
                          self.url])
        if len(out) != n:
            raise IOError("asked for %d bytes at %d, got %d: %s" % (n, start, len(out), self.url))
        self.fetched += n
        return out

    def seek(self, off, whence=0):
        self.pos = off if whence == 0 else self.pos + off if whence == 1 else self.size + off
        return self.pos

    def read(self, n):
        n = min(n, self.size - self.pos)
        if self.pos >= self.tail_start:
            a = self.pos - self.tail_start
            out = self.tail[a:a + n]
        else:
            out = self._get(self.pos, n)
        self.pos += n
        return out

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class Thrift:
    """Thrift compact protocol, decoded into {field id: value} dicts."""

    def __init__(self, b, p=0):
        self.b, self.p = b, p

    def byte(self):
        v = self.b[self.p]
        self.p += 1
        return v

    def varint(self):
        r = s = 0
        while True:
            c = self.byte()
            r |= (c & 0x7F) << s
            s += 7
            if not c & 0x80:
                return r

    def zigzag(self):
        v = self.varint()
        return (v >> 1) ^ -(v & 1)

    def value(self, t):
        if t in (1, 2):
            return t == 1
        if t == 3:
            return self.byte()
        if t in (4, 5, 6):
            return self.zigzag()
        if t == 7:
            v = struct.unpack_from("<d", self.b, self.p)[0]
            self.p += 8
            return v
        if t == 8:
            n = self.varint()
            v = bytes(self.b[self.p:self.p + n])
            self.p += n
            return v
        if t in (9, 10):
            h = self.byte()
            n, et = h >> 4, h & 0xF
            if n == 15:
                n = self.varint()
            if et in (1, 2):
                return [self.byte() == 1 for _ in range(n)]
            return [self.value(et) for _ in range(n)]
        if t == 12:
            return self.struct()
        raise ValueError("thrift type %d" % t)

    def struct(self):
        out, fid = {}, 0
        while True:
            h = self.byte()
            if h == 0:
                return out
            d, t = h >> 4, h & 0xF
            fid = fid + d if d else self.zigzag()
            out[fid] = self.value(t)


def snappy(src):
    r = Thrift(src)
    n = r.varint()
    out, o, p = bytearray(n), 0, r.p
    while p < len(src):
        tag = src[p]
        p += 1
        kind = tag & 3
        if kind == 0:
            ln = tag >> 2
            if ln >= 60:
                nb = ln - 59
                ln = int.from_bytes(src[p:p + nb], "little")
                p += nb
            ln += 1
            out[o:o + ln] = src[p:p + ln]
            p += ln
            o += ln
            continue
        if kind == 1:
            ln, off = ((tag >> 2) & 7) + 4, ((tag >> 5) << 8) | src[p]
            p += 1
        elif kind == 2:
            ln, off = (tag >> 2) + 1, src[p] | (src[p + 1] << 8)
            p += 2
        else:
            ln, off = (tag >> 2) + 1, int.from_bytes(src[p:p + 4], "little")
            p += 4
        for i in range(ln):                    # overlapping copies are legal, so byte by byte
            out[o + i] = out[o - off + i]
        o += ln
    return bytes(out)


def decompress(codec, raw):
    if codec == 0:
        return raw
    if codec == 1:
        return snappy(raw)
    if codec == 2:
        return zlib.decompress(raw, 31)
    raise ValueError("parquet codec %d is not handled" % codec)


PHYSICAL = {1: "<i4", 2: "<i8", 4: "<f4", 5: "<f8"}


def hybrid(buf, p, end, width, count):
    """Parquet's RLE / bit-packed hybrid, as `count` unsigned values."""
    out = np.zeros(count, dtype=np.uint32)
    if width == 0:
        return out
    o, nbytes = 0, (width + 7) // 8
    while o < count and p < end:
        r = Thrift(buf, p)
        h = r.varint()
        p = r.p
        if h & 1:
            n = (h >> 1) * 8
            nb = min((h >> 1) * width, end - p)
            bits = np.unpackbits(np.frombuffer(buf, np.uint8, nb, p), bitorder="little")
            bits = bits[:(len(bits) // width) * width].reshape(-1, width).astype(np.uint32)
            vals = (bits << np.arange(width, dtype=np.uint32)).sum(axis=1, dtype=np.uint32)
            take = min(n, count - o, len(vals))
            out[o:o + take] = vals[:take]
            o += take
            p += nb
        else:
            v = int.from_bytes(buf[p:p + nbytes], "little")
            p += nbytes
            take = min(h >> 1, count - o)
            out[o:o + take] = v
            o += take
    if o != count:
        raise ValueError("level/index run ended early: %d of %d" % (o, count))
    return out


class Parquet:
    def __init__(self, path, opener=None):
        """`opener` returns a seekable binary reader; by default the local file at `path`."""
        self.path = path
        self._open = opener or (lambda: open(path, "rb"))
        with self._open() as f:
            f.seek(-8, 2)
            n = struct.unpack("<I", f.read(4))[0]
            if f.read(4) != b"PAR1":
                raise ValueError("not a Parquet file: " + path)
            f.seek(-8 - n, 2)
            self.meta = Thrift(f.read(n)).struct()
        self.leaves, self.chunks = {}, {}
        schema, i = self.meta[2], 1

        def walk(prefix, d, r, count):
            nonlocal i
            for _ in range(count):
                e = schema[i]
                i += 1
                rep = e.get(3, 0)
                dd, rr = d + (1 if rep in (1, 2) else 0), r + (1 if rep == 2 else 0)
                name = prefix + [e[4].decode()]
                if e.get(5):
                    walk(name, dd, rr, e[5])
                else:
                    self.leaves[".".join(name)] = (dd, rr, e.get(1))
        walk([], 0, 0, schema[0][5])
        for rg in self.meta[4]:
            for cc in rg[1]:
                md = cc[3]
                self.chunks.setdefault(b".".join(md[3]).decode(), []).append(md)

    @staticmethod
    def _strings(page, q, n):
        out = []
        for _ in range(n):
            ln = struct.unpack_from("<I", page, q)[0]
            q += 4
            out.append(bytes(page[q:q + ln]).decode("utf-8", "replace"))
            q += ln
        return out

    def rows(self, leaf):
        """One entry per top-level row: that row's non-null values of `leaf` (array, or list of str)."""
        max_def, max_rep, typ = self.leaves[leaf]
        vals_all, defs_all, reps_all = [], [], []
        with self._open() as f:
            for md in self.chunks[leaf]:
                codec, total = md[4], md[5]
                f.seek(min(md[9], md[11]) if md.get(11) else md[9])
                blob, p, got, dic = f.read(md[7]), 0, 0, None
                while got < total:
                    r = Thrift(blob, p)
                    ph = r.struct()
                    raw = blob[r.p:r.p + ph[3]]
                    p = r.p + ph[3]
                    if ph[1] == 2:
                        d = decompress(codec, raw)
                        dic = self._strings(d, 0, ph[7][1]) if typ == 6 else np.frombuffer(
                            d, PHYSICAL[typ], ph[7][1])
                        continue
                    if ph[1] != 0:
                        raise ValueError("only v1 data pages are handled")
                    page, n, enc, q = decompress(codec, raw), ph[5][1], ph[5][2], 0
                    reps = np.zeros(n, np.uint32)
                    defs = np.full(n, max_def, np.uint32)
                    if max_rep:
                        ln = struct.unpack_from("<I", page, q)[0]
                        reps = hybrid(page, q + 4, q + 4 + ln, max_rep.bit_length(), n)
                        q += 4 + ln
                    if max_def:
                        ln = struct.unpack_from("<I", page, q)[0]
                        defs = hybrid(page, q + 4, q + 4 + ln, max_def.bit_length(), n)
                        q += 4 + ln
                    nn = int((defs == max_def).sum())
                    if enc == 0:
                        vals = self._strings(page, q, nn) if typ == 6 else np.frombuffer(
                            page, PHYSICAL[typ], nn, q)
                    elif enc in (2, 8):
                        idx = hybrid(page, q + 1, len(page), page[q], nn)
                        vals = [dic[k] for k in idx] if typ == 6 else dic[idx]
                    else:
                        raise ValueError("parquet encoding %d is not handled" % enc)
                    vals_all.append(vals)
                    defs_all.append(defs)
                    reps_all.append(reps)
                    got += n
        defs, reps = np.concatenate(defs_all), np.concatenate(reps_all)
        vals = [v for part in vals_all for v in part] if typ == 6 else np.concatenate(vals_all)
        row_of = np.cumsum(reps == 0) - 1
        counts = np.bincount(row_of[defs == max_def], minlength=int(row_of[-1]) + 1)
        out, o = [], 0
        for c in counts:
            out.append(vals[o:o + c])
            o += c
        return out

    def device_rows(self):
        return [d[0] for d in self.rows("Device")]

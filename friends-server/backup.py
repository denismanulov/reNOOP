#!/usr/bin/env python3
"""Nightly consistent copy of the friends database; keeps the newest seven."""
import glob, os, sqlite3, time
SRC = "/var/lib/private/renoop-friends/friends.db"
DST = "/var/backups/renoop-friends"
os.makedirs(DST, mode=0o700, exist_ok=True)
out = os.path.join(DST, "friends-%s.db" % time.strftime("%Y%m%d-%H%M%S"))
src = sqlite3.connect("file:%s?mode=ro" % SRC, uri=True)
dst = sqlite3.connect(out)
with dst:
    src.backup(dst)
dst.close(); src.close()
os.chmod(out, 0o600)
for old in sorted(glob.glob(os.path.join(DST, "friends-*.db")))[:-7]:
    os.remove(old)
print("backup written:", out, os.path.getsize(out), "bytes")

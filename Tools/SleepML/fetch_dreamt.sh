#!/usr/bin/env bash
# fetch_dreamt.sh — pull the part of PhysioNet DREAMT that sleep-model work needs.
#
# DREAMT is restricted-access. Its files stay outside this repository and are never committed, and the
# password is typed by the person who signed the data use agreement. Only `data_64Hz` is wanted: the
# wrist signals with the 30 s PSG stage labels. `data_100Hz` carries the raw PSG channels and is most
# of the dataset's 113.7 GB.
#
# USAGE:  Tools/SleepML/fetch_dreamt.sh probe [dest]
#
#   probe   fetch the four small top-level files and the first participant's `data_64Hz` file, verify
#           its checksum, and describe its columns in aggregate (see dreamt_probe.py).
#   dest    where the dataset lives. Defaults to ~/datasets/dreamt.
#
# PHYSIONET_USER overrides the account name.
set -euo pipefail

mode="${1:-}"
dest="${2:-$HOME/datasets/dreamt}"
base="https://physionet.org/files/dreamt/2.2.0"
user="${PHYSIONET_USER:-athoros}"
here="$(cd "$(dirname "$0")" && pwd)"

if [[ "$mode" != "probe" ]]; then
    echo "usage: $0 probe [dest]" >&2
    exit 2
fi

mkdir -p "$dest/raw"
read -r -s -p "PhysioNet password for $user: " pw
echo
# The password reaches curl through a config stream, so it is never an argument `ps` can show and
# never a file on disk. Backslashes and quotes are escaped for curl's quoted config syntax.
esc=${pw//\\/\\\\}
esc=${esc//\"/\\\"}

get() {   # get <remote path> <local path>
    curl --fail --location --silent --show-error \
         --config <(printf 'user = "%s:%s"\n' "$user" "$esc") \
         --output "$2" "$base/$1"
}

for f in LICENSE.txt Metadata.txt SHA256SUMS.txt participant_info.csv; do
    get "$f" "$dest/$f"
    echo "fetched $f"
done

echo "files listed: data_64Hz $(grep -c ' data_64Hz/' "$dest/SHA256SUMS.txt" || true)," \
     "data_100Hz $(grep -c ' data_100Hz/' "$dest/SHA256SUMS.txt" || true)"

first=$(awk '$2 ~ /^data_64Hz\// { print $2; exit }' "$dest/SHA256SUMS.txt")
want=$(awk -v p="$first" '$2 == p { print $1; exit }' "$dest/SHA256SUMS.txt")
if [[ -z "$first" ]]; then
    echo "no data_64Hz entry in SHA256SUMS.txt" >&2
    exit 1
fi

local_file="$dest/raw/$(basename "$first")"
echo "fetching $first"
get "$first" "$local_file"
got=$(shasum -a 256 "$local_file" | awk '{ print $1 }')
if [[ "$got" != "$want" ]]; then
    echo "checksum mismatch for $first" >&2
    exit 1
fi
echo "checksum ok"

python3 "$here/dreamt_probe.py" "$local_file"

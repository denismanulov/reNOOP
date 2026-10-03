#!/usr/bin/env bash
# fetch_open.sh — pull the open-access sleep datasets the stage model trains on.
#
# No dataset is committed; all of them live outside this repository and are always an argument.
#
#   bidsleep    PhysioNet BIDSleep 1.0.1 as one ZIP (6.4 GB). Left packed: unpacked it is 27.9 GB, and
#               the reducers read members straight out of the archive.
#   wearanize   Radboud Wearanize+ OA v1.1, wristband part only: the raw Empatica E4 archives, every
#               sleep-score file, the manual synchronisation sheet and the demographics (about 2 GB of
#               the collection's 129 GB), plus three of the authors' synchronised PlugNPlay files, from
#               participants who also have a raw wristband archive, to check an alignment against.
#               Each file is verified against the collection's MANIFEST.
#   sleepaccel  PhysioNet sleep-accel 1.0.0 (2.35 GB unpacked), the dataset `Tools/SleepPSG` scores the
#               shipped stager against. Taken file by file from PhysioNet's Amazon mirror, which is not
#               rate-capped the way physionet.org is, and verified against SHA256SUMS.txt.
#
# USAGE:  Tools/SleepML/fetch_open.sh bidsleep|wearanize|sleepaccel [dest]      dest defaults to ~/datasets
#
# ATTRIBUTION, required by every licence here:
#   BIDSleep        Song T, Zhang Y, Zhou Z, Dutta J. A Multi-Night Instantaneous Heart Rate and
#                   Accelerometry Dataset with EEG Sleep Stage Labels (1.0.1). PhysioNet, 2026.
#                   https://doi.org/10.13026/rees-1092. Open Data Commons Attribution License v1.0.
#   Wearanize+ OA   Sikder NS et al. Wearanize+ OA (version 1). Radboud University, 2026.
#                   https://doi.org/10.34973/xrmf-5726. CC BY 4.0. Reference paper:
#                   https://doi.org/10.1093/sleepadvances/zpaf094. "Data were provided (in part) by the
#                   Radboud University, Nijmegen, The Netherlands".
#   sleep-accel     Walch O, Huang Y, Forger D, Goldstein C. Sleep stage prediction with raw
#                   acceleration and photoplethysmography heart rate data derived from a consumer
#                   wearable device. SLEEP 42(12), zsz180 (2019). PhysioNet sleep-accel 1.0.0,
#                   Open Data Commons Attribution License v1.0.
set -euo pipefail

mode="${1:-}"
root="${2:-$HOME/datasets}"
here="$(cd "$(dirname "$0")" && pwd)"

case "$mode" in
bidsleep)
    dest="$root/bidsleep"
    mkdir -p "$dest"
    # Measured 2026-10-03: PhysioNet delivers about 80 kB/s on one connection and about 120 kB/s over
    # four, so the cap is per client and more connections only stall each other (eight fell to
    # 8 kB/s in total). Three is the useful ceiling; the point of the ranged fetch is that a rerun
    # continues a partial archive instead of starting over.
    python3 "$here/ranged_fetch.py" "https://physionet.org/content/bidsleep-dataset/get-zip/1.0.1/" \
            "$dest/bidsleep-1.0.1.zip" 3
    echo "bidsleep: $(du -h "$dest/bidsleep-1.0.1.zip" | cut -f1)"
    ;;
wearanize)
    dest="$root/wearanize-oa"
    base="https://webdav.data.ru.nl/dcmn/DSC_wrnzpoa_t0000925a_195_v1"
    mkdir -p "$dest"
    for f in MANIFEST.txt LICENSE.txt ABOUT.txt README.txt; do
        curl --fail --location --silent --show-error --retry 5 --output "$dest/$f" "$base/$f"
    done

    # The wristband part of the raw release, and three synchronised files spread across the cohort.
    wanted=$(awk '{ print $2 }' "$dest/MANIFEST.txt" | grep -E \
        '/3\.Empatica/|/2\.Sleep_scores/|/3\.Manual_synchronization/|/4\.Demographic_info/|_raw_v1\.1/README\.txt$' || true)
    # A synchronised file is only a usable check for a participant whose raw wristband archive exists.
    checks=$(awk '{ print $2 }' "$dest/MANIFEST.txt" | grep -E '_Parquet_v1\.1/.*\.parquet$' \
        | while IFS= read -r p; do
              if grep -q "/$(basename "$p" .parquet)/3\.Empatica/" "$dest/MANIFEST.txt"; then echo "$p"; fi
          done | awk '{ a[NR] = $0 } END { print a[1]; print a[int(NR / 2)]; print a[NR] }')

    ok=0; bad=0
    while IFS= read -r path; do
        [[ -z "$path" ]] && continue
        want=$(awk -v p="$path" '$2 == p { print $1; exit }' "$dest/MANIFEST.txt")
        out="$dest/$path"
        if [[ -f "$out" ]] && [[ "$(shasum -a 256 "$out" | cut -d' ' -f1)" == "$want" ]]; then
            ok=$((ok + 1)); continue
        fi
        mkdir -p "$(dirname "$out")"
        # '+' in the collection's folder names must travel percent-encoded.
        if curl --fail --location --silent --show-error --retry 5 --retry-delay 3 \
                --output "$out.part" "$base/${path//+/%2B}" \
           && [[ "$(shasum -a 256 "$out.part" | cut -d' ' -f1)" == "$want" ]]; then
            mv "$out.part" "$out"; ok=$((ok + 1))
        else
            rm -f "$out.part"; bad=$((bad + 1)); echo "FAILED $path" >&2
        fi
    done <<< "$wanted"$'\n'"$checks"
    echo "wearanize: $ok verified, $bad failed, $(du -sh "$dest" | cut -f1) on disk"
    [[ "$bad" -eq 0 ]]
    ;;
sleepaccel)
    dest="$root/sleep-accel"
    base="https://physionet-open.s3.amazonaws.com/sleep-accel/1.0.0"
    mkdir -p "$dest"
    for f in SHA256SUMS.txt LICENSE.txt; do
        curl --fail --silent --show-error --retry 5 --output "$dest/$f" "$base/$f"
    done

    verified() {   # verified <path>: present and matching its listed checksum
        [[ -f "$dest/$1" ]] && [[ "$(shasum -a 256 "$dest/$1" | cut -d' ' -f1)" == \
            "$(awk -v p="$1" '$2 == p { print $1; exit }' "$dest/SHA256SUMS.txt")" ]]
    }
    # Whatever is missing or damaged goes three at a time; then every listed file is checked.
    awk '{ print $2 }' "$dest/SHA256SUMS.txt" | while IFS= read -r path; do
        verified "$path" || echo "$path"
    done | xargs -P 3 -I{} curl --fail --silent --show-error --retry 5 --retry-delay 3 \
                  --retry-all-errors --create-dirs --output "$dest/{}" "$base/{}" || true

    ok=0; bad=0
    while IFS= read -r path; do
        if verified "$path"; then ok=$((ok + 1)); else bad=$((bad + 1)); echo "FAILED $path" >&2; fi
    done < <(awk '{ print $2 }' "$dest/SHA256SUMS.txt")
    echo "sleepaccel: $ok verified, $bad failed, $(du -sh "$dest" | cut -f1) on disk"
    [[ "$bad" -eq 0 ]]
    ;;
*)
    echo "usage: $0 bidsleep|wearanize|sleepaccel [dest]" >&2
    exit 2
    ;;
esac

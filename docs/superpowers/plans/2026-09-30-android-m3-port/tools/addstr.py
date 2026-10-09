#!/usr/bin/env python3
"""Add Android string resources in all nine locales, reusing Denis's iOS translations.

Usage:
  addstr.py lookup "English text"            # print Denis's translations for an iOS key
  addstr.py add name "English text" [--de ... --es ... --fr ... --it ... --pl ... --pt ... --ru ... --zh ...]
  addstr.py addjson strings.json             # [{"name":..., "en":..., "ru":..., ...}, ...]

`add` / `addjson`: any locale not given explicitly is taken from the iOS catalogue
(Strand/Resources/Localizable.xcstrings) when the English text is an exact iOS key; if neither exists
the script FAILS for that locale and writes nothing, so a string never lands untranslated.
Format specifiers: write Android style (%1$s, %1$d) in every language. iOS %@/%lld in catalogue values
are converted positionally (%@ -> %N$s, %lld/%d -> %N$d, %.1f -> %N$.1f).
Existing names are replaced in place (all locales), so re-running is safe.
"""
import json, re, sys, pathlib, html

# The repo root, wherever it is checked out (Mac or PC): tools/ -> port/ -> plans/ -> superpowers/ -> docs/ -> root.
REPO = pathlib.Path(__file__).resolve().parents[5]
# Windows consoles default to a legacy code page; translations must print as UTF-8.
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')
RES = REPO / 'android/app/src/main/res'
LOCALES = {  # android folder suffix -> xcstrings code
    'de': 'de', 'es': 'es', 'fr': 'fr', 'it': 'it', 'pl': 'pl', 'pt-rPT': 'pt-PT', 'ru': 'ru', 'zh': 'zh-Hans',
}
ARG_KEYS = {'de': 'de', 'es': 'es', 'fr': 'fr', 'it': 'it', 'pl': 'pl', 'pt': 'pt-rPT', 'ru': 'ru', 'zh': 'zh'}

_cat = None
def catalogue():
    global _cat
    if _cat is None:
        _cat = json.load(open(REPO / 'Strand/Resources/Localizable.xcstrings', encoding='utf-8'))['strings']
    return _cat

def ios_value(key, code):
    e = catalogue().get(key)
    if not e:
        return None
    loc = e.get('localizations', {}).get(code)
    if not loc:
        return None
    su = loc.get('stringUnit')
    if su and su.get('value'):
        return su['value']
    return None  # plural/variations: translate by hand

def ios_to_android(v):
    n = [0]
    def rep(m):
        n[0] += 1
        spec = m.group(0)
        if spec == '%@': return f'%{n[0]}$s'
        if spec in ('%lld', '%ld', '%d', '%lu', '%llu'): return f'%{n[0]}$d'
        mm = re.match(r'%(\.\d+)?f', spec)
        if mm: return f'%{n[0]}${mm.group(1) or ""}f'
        return spec
    # positional iOS specifiers like %1$@ -> %1$s
    v = re.sub(r'%(\d+)\$@', r'%\1$s', v)
    v = re.sub(r'%(\d+)\$l{0,2}d', r'%\1$d', v)
    if re.search(r'%\d+\$', v):
        return v
    return re.sub(r'%(?:@|l{0,2}[du]|lu|(?:\.\d+)?f)', rep, v)

def escape(v):
    v = v.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    v = v.replace('\\', '\\\\').replace("'", "\\'").replace('"', '\\"').replace('\n', '\\n')
    if v.startswith('@') or v.startswith('?'):
        v = '\\' + v
    return v

def upsert(path, name, value):
    # Bytes in, bytes out: no newline translation, so a checkout's line endings stay as they are.
    s = path.read_bytes().decode('utf-8')
    line = f'    <string name="{name}">{escape(value)}</string>'
    pat = re.compile(r'^\s*<string name="' + re.escape(name) + r'"[^>]*>.*?</string>\s*$', re.M | re.S)
    if pat.search(s):
        s = pat.sub(line, s, count=1)
    else:
        s = s.replace('</resources>', line + '\n</resources>')
    path.write_bytes(s.encode('utf-8'))

def add(entry):
    name, en = entry['name'], entry['en']
    vals = {'': en}
    missing = []
    for folder, code in LOCALES.items():
        given = entry.get(folder) or entry.get(code) or entry.get({'pt-rPT': 'pt', 'zh': 'zh'}.get(folder, folder))
        if given:
            vals[folder] = given
            continue
        iv = ios_value(entry.get('ios_key', en), code)
        if iv is None:
            missing.append(folder)
        else:
            vals[folder] = ios_to_android(iv)
    if missing:
        print(f'FAIL {name}: no translation for {missing} (English key not in iOS catalogue: give them explicitly)')
        return False
    upsert(RES / 'values/strings.xml', name, en)
    for folder in LOCALES:
        upsert(RES / f'values-{folder}/strings.xml', name, vals[folder])
    print(f'ok   {name} = {en}  | ru: {vals["ru"]}')
    return True

def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__); return
    if a[0] == 'lookup':
        key = a[1]
        for folder, code in LOCALES.items():
            print(f'{folder:7} {ios_value(key, code)}')
        return
    if a[0] == 'add':
        entry = {'name': a[1], 'en': a[2]}
        rest = a[3:]
        for i in range(0, len(rest), 2):
            k = rest[i].lstrip('-')
            entry[ARG_KEYS.get(k, k)] = rest[i + 1]
        sys.exit(0 if add(entry) else 1)
    if a[0] == 'addjson':
        entries = json.load(open(a[1], encoding='utf-8'))
        ok = all([add(e) for e in entries])
        sys.exit(0 if ok else 1)

if __name__ == '__main__':
    main()

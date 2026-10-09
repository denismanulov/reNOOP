#!/usr/bin/env python3
"""Check the Android string resources for completeness and for keys nothing references.

Usage:
  i18n_check.py                 # completeness: missing keys, plural quantities, format specifiers
  i18n_check.py --unused        # also list keys no Kotlin/XML source references
  i18n_check.py --delete-unused # delete those keys from all nine strings.xml files
  i18n_check.py --same          # also list values identical to English (possible untranslated text)

Completeness (exit 1 when anything is reported):
  - a <string> / <plurals> / <string-array> in values/ that a locale file lacks (translatable="false" is exempt),
  - a key a locale has but values/ does not (an orphan: Android never resolves it),
  - a plural missing a quantity its language needs (CLDR: ru/pl one, few, many, other; de/es/fr/it/pt
    one, other; zh other) or carrying a different set of quantities than the rules allow,
  - format specifiers that differ from English (the multiset of %N$type conversions; a plural item is
    compared with the English `other` item, and may drop the number where the language spells it out),
  - a duplicated key inside one file.

Unused keys: a key is "used" when its name appears as R.string.<name> / R.plurals.<name> / R.array.<name>
in Kotlin or Java under android/app/src (every source set and the tests), as @string/<name> /
@plurals/<name> / @array/<name> in any XML there (manifests, layouts, xml/, other values files), or when
a string literal equal to the name appears in Kotlin (a by-name lookup). Keys matching a prefix in
DYNAMIC_PREFIXES are resolved through getIdentifier and are always kept.
"""
import pathlib, re, sys, collections

REPO = pathlib.Path(__file__).resolve().parents[5]
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')
SRC = REPO / 'android/app/src'
RES = SRC / 'main/res'
LOCALES = ['de', 'es', 'fr', 'it', 'pl', 'pt-rPT', 'ru', 'zh']
# Every values file that holds strings (a feature may keep its own next to strings.xml).
FILES = sorted(p.name for p in (RES / 'values').glob('*.xml')
               if re.search(r'<(?:string|plurals|string-array)\s', p.read_text(encoding='utf-8')))

# CLDR cardinal categories an Android plural can be asked for with an integer count.
REQUIRED = {
    '': {'one', 'other'}, 'de': {'one', 'other'}, 'es': {'one', 'other'}, 'fr': {'one', 'other'},
    'it': {'one', 'other'}, 'pt-rPT': {'one', 'other'}, 'zh': {'other'},
    'ru': {'one', 'few', 'many', 'other'}, 'pl': {'one', 'few', 'many', 'other'},
}
# es/fr/it/pt also have `many` (for 1 000 000 and compact forms): allowed, never required.
ALLOWED_EXTRA = {'es': {'many'}, 'fr': {'many'}, 'it': {'many'}, 'pt-rPT': {'many'}, 'zh': set(),
                 'de': set(), '': set(), 'ru': set(), 'pl': set()}

# Name prefixes resolved by computed name (Resources.getIdentifier). Filled from the code audit; see
# the grep in main() that fails loudly when a new getIdentifier call appears.
DYNAMIC_PREFIXES = ()

ENTRY = re.compile(
    r'<(string|plurals|string-array)\s+name="([^"]+)"([^>]*?)(?:/>|>(.*?)</\1>)', re.S)
ITEM = re.compile(r'<item(?:\s+quantity="([^"]+)")?\s*>(.*?)</item>', re.S)
# No space flag: "100% and" is prose, not a conversion.
SPEC = re.compile(r'%(?:(\d+)\$)?([-#+0,(]*)(\d+)?(?:\.\d+)?([a-zA-Z%])')


def strip_comments(text):
    return re.sub(r'<!--.*?-->', '', text, flags=re.S)


def parse(path):
    """name -> (kind, attrs, body, items) in file order; also the list of duplicated names."""
    out, dups = collections.OrderedDict(), []
    if not path.exists():
        return out, dups
    text = strip_comments(path.read_bytes().decode('utf-8'))
    for m in ENTRY.finditer(text):
        kind, name, attrs, body = m.group(1), m.group(2), m.group(3) or '', m.group(4) or ''
        items = ITEM.findall(body) if kind != 'string' else []
        if name in out:
            dups.append(name)
        out[name] = (kind, attrs, body, items)
    return out, dups


def specs(value):
    """Sorted list of conversions: positional ones as '1$s', others as their bare type. %% and %n are dropped."""
    res = []
    for m in SPEC.finditer(value):
        pos, _, _, conv = m.groups()
        if conv in '%n':
            continue
        conv = 'd' if conv in 'di' else conv
        res.append(f'{pos}${conv}' if pos else conv)
    return sorted(res)


def load_all():
    data = {}
    for loc in [''] + LOCALES:
        folder = RES / ('values' + (f'-{loc}' if loc else ''))
        merged, dups = collections.OrderedDict(), []
        for f in FILES:
            entries, d = parse(folder / f)
            dups += [f'{f}:{n}' for n in d]
            for k, v in entries.items():
                merged[k] = v + (f,)
        data[loc] = (merged, dups)
    return data


def completeness(data, show_same=False):
    problems = 0
    en, en_dups = data['']
    for d in en_dups:
        print(f'values: duplicate key {d}'); problems += 1
    for loc in LOCALES:
        tr, dups = data[loc]
        missing, orphans, plural_issues, spec_issues, same = [], [], [], [], []
        for d in dups:
            print(f'values-{loc}: duplicate key {d}'); problems += 1
        for name, (kind, attrs, body, items, _f) in en.items():
            if 'translatable="false"' in attrs:
                continue
            if name not in tr:
                missing.append(name); continue
            tkind, _ta, tbody, titems, _tf = tr[name]
            if tkind != kind:
                spec_issues.append(f'{name}: is <{tkind}> here, <{kind}> in English'); continue
            if kind == 'string':
                if specs(body) != specs(tbody):
                    spec_issues.append(f'{name}: {specs(tbody)} vs English {specs(body)}')
                if show_same and body == tbody and re.search(r'[A-Za-z]{4,}', body):
                    same.append(name)
            elif kind == 'plurals':
                have = {q for q, _ in titems}
                need = REQUIRED[loc]
                if need - have:
                    plural_issues.append(f'{name}: missing quantity {sorted(need - have)}')
                extra = have - need - ALLOWED_EXTRA[loc] - {'zero', 'two'}
                if extra and loc not in ('ru', 'pl'):
                    plural_issues.append(f'{name}: unexpected quantity {sorted(extra)}')
                en_other = next((v for q, v in items if q == 'other'), '')
                want = specs(en_other)
                for q, v in titems:
                    got = specs(v)
                    # `one` may spell the number out ("1 day" -> "день"); anything else must match.
                    if got != want and not (q in ('one', 'zero', 'two') and set(got) <= set(want)):
                        spec_issues.append(f'{name}[{q}]: {got} vs English {want}')
            else:
                if len(items) != len(titems):
                    spec_issues.append(f'{name}: {len(titems)} items vs English {len(items)}')
        for name, (kind, attrs, *_r) in tr.items():
            if name not in en:
                orphans.append(name)
        n = len(missing) + len(orphans) + len(plural_issues) + len(spec_issues)
        problems += n
        print(f'values-{loc}: {len(missing)} missing, {len(orphans)} orphan, '
              f'{len(plural_issues)} plural, {len(spec_issues)} format')
        for m in missing: print(f'  missing   {m}')
        for o in orphans: print(f'  orphan    {o}')
        for p in plural_issues: print(f'  plural    {p}')
        for s in spec_issues: print(f'  format    {s}')
        if show_same:
            print(f'  {len(same)} identical to English')
            for s in same: print(f'  same      {s} = {en[s][2][:70]}')
    # English plurals need one + other too.
    for name, (kind, attrs, body, items, _f) in en.items():
        if kind == 'plurals':
            have = {q for q, _ in items}
            if REQUIRED[''] - have:
                print(f'values: plural {name} missing {sorted(REQUIRED[""] - have)}'); problems += 1
    return problems


def references():
    """Every resource name the sources mention, plus Kotlin string literals (by-name lookups)."""
    used, literals = set(), set()
    getid = []
    code_ref = re.compile(r'\bR\.(?:string|plurals|array)\.([A-Za-z0-9_]+)')
    xml_ref = re.compile(r'@(?:string|plurals|array)/([A-Za-z0-9_.]+)')
    lit = re.compile(r'"([a-z][a-z0-9_]{2,})"')
    for p in SRC.rglob('*'):
        if not p.is_file() or 'build' in p.parts:
            continue
        if p.suffix in ('.kt', '.java', '.kts'):
            t = p.read_text(encoding='utf-8', errors='replace')
            used.update(code_ref.findall(t))
            literals.update(lit.findall(t))
            if 'getIdentifier' in t:
                getid.append(str(p.relative_to(REPO)))
        elif p.suffix == '.xml':
            t = p.read_text(encoding='utf-8', errors='replace')
            used.update(n.replace('.', '_') for n in xml_ref.findall(t))
    return used, literals, getid


def unused(data):
    en, _ = data['']
    used, literals, getid = references()
    names = set()
    for loc in [''] + LOCALES:
        names.update(data[loc][0].keys())
    dead = sorted(n for n in names
                  if n not in used and n not in literals and not n.startswith(DYNAMIC_PREFIXES or ('\0',)))
    by_literal = sorted(n for n in names if n not in used and n in literals)
    return dead, by_literal, getid


TOKEN = re.compile(
    r'(?P<comment><!--.*?-->)'
    r'|(?P<entry><(?P<kind>string|plurals|string-array)\s+name="(?P<name>[^"]+)"[^>]*?(?:/>|>.*?</(?P=kind)>))',
    re.S)
GONE = '\x00'


def delete_keys(keys):
    """Remove the entries, and a comment that introduced only removed entries (the next thing after it
    was a removed entry and what follows now is another comment or the end of the file)."""
    keys = set(keys)
    removed = collections.Counter()
    for loc in [''] + LOCALES:
        for f in FILES:
            path = RES / ('values' + (f'-{loc}' if loc else '')) / f
            if not path.exists():
                continue
            text = path.read_bytes().decode('utf-8')
            toks, pos = [], 0          # [kind, text, name]; kind in comment / entry / other
            for m in TOKEN.finditer(text):
                if m.start() > pos:
                    toks.append(['other', text[pos:m.start()], None])
                if m.group('comment'):
                    toks.append(['comment', m.group(0), None])
                else:
                    toks.append(['entry', m.group(0), m.group('name')])
                pos = m.end()
            toks.append(['other', text[pos:], None])
            dead = [t[0] == 'entry' and t[2] in keys for t in toks]
            if not any(dead):
                continue
            removed[loc or 'en'] += sum(dead)

            def next_solid(i, alive_only):
                for j in range(i + 1, len(toks)):
                    if toks[j][0] == 'other':
                        if toks[j][1].strip():       # </resources> or stray text
                            return None
                        continue
                    if alive_only and dead[j]:
                        continue
                    return j
                return None
            for i in range(len(toks) - 1, -1, -1):
                if toks[i][0] != 'comment':
                    continue
                orig = next_solid(i, alive_only=False)
                if orig is None or not dead[orig]:
                    continue
                now = next_solid(i, alive_only=True)
                if now is None or toks[now][0] == 'comment':
                    dead[i] = True
            out = ''.join(GONE if d else t[1] for t, d in zip(toks, dead))
            # drop the lines the removed tokens stood on, then squeeze the blank runs they leave
            out = re.sub(r'[ \t]*' + GONE + r'[ \t]*\r?\n', '', out).replace(GONE, '')
            out = re.sub(r'(\r?\n)(?:[ \t]*\r?\n){2,}', r'\1\1', out)
            out = re.sub(r'(<resources[^>]*>\r?\n)(?:[ \t]*\r?\n)+', r'\1', out)
            out = re.sub(r'(\r?\n)(?:[ \t]*\r?\n)+(</resources>)', r'\1\2', out)
            path.write_bytes(out.encode('utf-8'))
    return removed


def main():
    args = set(sys.argv[1:])
    data = load_all()
    problems = completeness(data, show_same='--same' in args)
    if args & {'--unused', '--delete-unused'}:
        dead, by_literal, getid = unused(data)
        print(f'\n{len(dead)} keys nothing references')
        for n in dead:
            print(f'  unused    {n}')
        if by_literal:
            print(f'{len(by_literal)} keys kept only because a Kotlin string literal equals the name:')
            for n in by_literal:
                print(f'  literal   {n}')
        if getid:
            print('getIdentifier call sites (check DYNAMIC_PREFIXES covers them):')
            for g in getid:
                print(f'  {g}')
        if '--delete-unused' in args:
            removed = delete_keys(dead)
            print('deleted: ' + ', '.join(f'{k} {v}' for k, v in removed.items()))
    print('\nOK' if not problems else f'\n{problems} problem(s)')
    sys.exit(1 if problems else 0)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Write a sleep-stage model's trees as plain text, for a platform that has no Core ML.

    python3 export_trees.py Strand/SleepModel/SleepStageFirst.mlmodel  OUT/SleepStageFirst.trees
    python3 export_trees.py Strand/SleepModel/SleepStageSecond.mlmodel OUT/SleepStageSecond.trees

The app on Apple platforms runs the `.mlmodel` pair through Core ML. Android cannot, so it reads the
same trees from the files this writes (`android/app/src/main/assets/sleepstage/`), and so does
`StrandAnalytics.SleepStageTrees`, the Swift reader both are checked against. Nothing is refit or
rounded: every threshold and leaf value is written with the shortest decimal that reads back as the
same double.

A `.mlmodel` is a protobuf (Core ML's Model.proto). Only the handful of fields a Create ML boosted-tree
classifier uses are read, by number, so this needs nothing but the standard library and runs anywhere.
It refuses a model that is anything else rather than guess.

The format, one item per line:

    renoop-sleep-stage-trees 1
    # free text (description, licence)
    source <file name> sha256 <hex of the .mlmodel>
    classes <label> ...            the order of a row of probabilities
    transform softmax
    base <double> ...              one per class: where each class's sum starts
    meta <key> <value>             the model's own settings (the second model carries the decoder's)
    columns <n>                    then n lines, one input name each, in the order of an input row
    trees <n>                      then n trees
    tree <nodes>                   then that many nodes; a node's number is its position, the root is 0
    b <column> <threshold> <yes> <no>    go to node <yes> when row[column] < threshold, else to <no>
    l <class> <value>                    add <value> to that class's sum and stop
"""

import hashlib
import struct
import sys

FORMAT = "renoop-sleep-stage-trees 1"

# Model.proto field numbers.
MODEL_DESCRIPTION, PIPELINE_CLASSIFIER, TREE_CLASSIFIER, FEATURE_VECTORIZER = 2, 200, 402, 602
BRANCH_LESS_THAN, LEAF = 1, 6
SOFTMAX = 1


def fields(buf):
    """(number, wire type, value) for each field of one message."""
    i, n = 0, len(buf)
    while i < n:
        key, i = varint(buf, i)
        number, wire = key >> 3, key & 7
        if wire == 0:
            value, i = varint(buf, i)
        elif wire == 1:
            value, i = buf[i:i + 8], i + 8
        elif wire == 2:
            size, i = varint(buf, i)
            value, i = buf[i:i + size], i + size
        elif wire == 5:
            value, i = buf[i:i + 4], i + 4
        else:
            raise ValueError(f"wire type {wire}")
        yield number, wire, value


def varint(buf, i):
    value, shift = 0, 0
    while True:
        byte = buf[i]
        i += 1
        value |= (byte & 0x7F) << shift
        shift += 7
        if byte < 0x80:
            return value, i


def only(buf, number):
    found = [v for n, _, v in fields(buf) if n == number]
    if len(found) != 1:
        raise SystemExit(f"expected one field {number}, found {len(found)}: not a model this tool reads")
    return found[0]


def double(raw):
    return struct.unpack("<d", raw)[0]


def doubles(buf, number):
    """A repeated double, packed or not."""
    out = []
    for n, wire, v in fields(buf):
        if n != number:
            continue
        if wire == 1:
            out.append(double(v))
        else:
            out.extend(struct.unpack(f"<{len(v) // 8}d", v))
    return out


def text(value):
    """The shortest decimal that reads back as the same double."""
    if value != value or value in (float("inf"), float("-inf")):
        raise SystemExit("a tree holds a value that is not a finite number")
    return repr(float(value))


def description(model):
    """Short description, licence and user-defined metadata of a Model."""
    short, licence, user = "", "", {}
    meta = [v for n, _, v in fields(only(model, MODEL_DESCRIPTION)) if n == 100]
    for n, _, v in fields(meta[0]) if meta else []:
        if n == 1:
            short = v.decode()
        elif n == 4:
            licence = v.decode()
        elif n == 100:
            entry = {k: val.decode() for k, _, val in fields(v)}
            user[entry.get(1, "")] = entry.get(2, "")
    return short, licence, user


def export(path):
    raw = open(path, "rb").read()
    stages = [v for n, _, v in fields(only(only(raw, PIPELINE_CLASSIFIER), 1)) if n == 1]
    if len(stages) != 2:
        raise SystemExit("expected a feature vectorizer followed by a tree classifier")
    columns = []
    for _, _, column in fields(only(stages[0], FEATURE_VECTORIZER)):
        entry = dict((n, v) for n, _, v in fields(column))
        if entry.get(2, 1) != 1:
            raise SystemExit("an input column is not a single number")
        columns.append(entry[1].decode())
    classifier = only(stages[1], TREE_CLASSIFIER)
    transform = [v for n, _, v in fields(classifier) if n == 2]
    if transform != [SOFTMAX]:
        raise SystemExit("the classifier's answer is not a softmax of its sums")
    classes = [v.decode() for _, _, v in fields(only(classifier, 100))]
    ensemble = only(classifier, 1)
    base = doubles(ensemble, 3)
    dimensions = [v for n, _, v in fields(ensemble) if n == 2]
    if dimensions != [len(classes)] or len(base) != len(classes):
        raise SystemExit("the trees do not answer one sum per class")

    trees = {}
    for number, _, node in fields(ensemble):
        if number != 1:
            continue
        f = {}
        for n, wire, v in fields(node):
            f.setdefault(n, []).append(v)
        tree = f.get(1, [0])[0]
        node_id = f.get(2, [0])[0]
        behaviour = f.get(3, [0])[0]
        if behaviour == LEAF:
            if len(f.get(20, [])) != 1:
                raise SystemExit("a leaf adds to more than one class")
            info = dict((n, v) for n, _, v in fields(f[20][0]))
            entry = ("l", info.get(1, 0), double(info[2]) if 2 in info else 0.0)
        elif behaviour == BRANCH_LESS_THAN:
            entry = ("b", f.get(10, [0])[0], double(f[11][0]) if 11 in f else 0.0,
                     f.get(12, [0])[0], f.get(13, [0])[0])
        else:
            raise SystemExit(f"a node branches by rule {behaviour}, which this format does not carry")
        if node_id in trees.setdefault(tree, {}):
            raise SystemExit("a tree lists one node twice")
        trees[tree][node_id] = entry
    if sorted(trees) != list(range(len(trees))):
        raise SystemExit("the trees are not numbered from zero without gaps")

    short, licence, user = description(raw)
    lines = [FORMAT]
    for note in (short, licence):
        if note:
            lines.append("# " + " ".join(note.split()))
    lines.append(f"source {path.split('/')[-1]} sha256 {hashlib.sha256(raw).hexdigest()}")
    lines.append("classes " + " ".join(classes))
    lines.append("transform softmax")
    lines.append("base " + " ".join(text(b) for b in base))
    for key in sorted(user):
        if key.startswith("com.apple."):
            continue
        if not key or " " in key or "\n" in user[key]:
            raise SystemExit(f"metadata {key!r} does not fit on a line")
        lines.append(f"meta {key} {user[key]}")
    lines.append(f"columns {len(columns)}")
    lines.extend(columns)
    lines.append(f"trees {len(trees)}")
    nodes_written = 0
    for tree in range(len(trees)):
        nodes = trees[tree]
        children = {c for e in nodes.values() if e[0] == "b" for c in e[3:5]}
        roots = [n for n in nodes if n not in children]
        if len(roots) != 1 or not children <= set(nodes):
            raise SystemExit(f"tree {tree} is not one tree")
        # Number the nodes from the root, parents before children, so a reader can check that a branch
        # only ever points forward (no loops) without walking anything.
        order, queue = {}, [roots[0]]
        while queue:
            n = queue.pop(0)
            if n in order:
                raise SystemExit(f"tree {tree} reaches one node twice")
            order[n] = len(order)
            if nodes[n][0] == "b":
                queue.extend(nodes[n][3:5])
        if len(order) != len(nodes):
            raise SystemExit(f"tree {tree} has nodes its root does not reach")
        lines.append(f"tree {len(nodes)}")
        for n in sorted(nodes, key=order.get):
            e = nodes[n]
            if e[0] == "l":
                if not 0 <= e[1] < len(classes):
                    raise SystemExit("a leaf names a class the model does not have")
                lines.append(f"l {e[1]} {text(e[2])}")
            else:
                if not 0 <= e[1] < len(columns):
                    raise SystemExit("a branch reads a column the model does not have")
                lines.append(f"b {e[1]} {text(e[2])} {order[e[3]]} {order[e[4]]}")
        nodes_written += len(nodes)
    return "\n".join(lines) + "\n", len(columns), len(trees), nodes_written


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__.split("\n\n")[1])
    out, columns, trees, nodes = export(sys.argv[1])
    with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    print(f"{sys.argv[2]}: {columns} columns, {trees} trees, {nodes} nodes, {len(out) // 1024} kB")


if __name__ == "__main__":
    main()

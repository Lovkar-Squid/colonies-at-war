"""Does what the server writes match what the client reads?

A building module sends itself to the window as a flat stream of bytes: the module writes a
varint, a string, a block position, and the view reads a varint, a string, a block position, in
that order and with nothing in between. Nothing checks that. Add a field to the module and forget
the view and there is no compile error, no warning and no exception - the view simply reads the
next field's bytes as its own, and the window fills with nonsense, or the connection drops.

It is the one bug in this mod that can be found by reading, so it gets read. For every module and
its view this pairs the writes with the reads in source order, in three parts, because that is the
shape every one of them has:

  the head    - what is written before the loop
  the row     - what is written once per thing, inside the for
  the tail    - what is written after it

and it checks that the three line up, type for type. The early-return path a module takes when it
has nothing to send is checked too, against the head and tail alone: that path is the one nobody
tests, because it only happens in the first tick after a hut is placed.

  python3 tools/check_packets.py [repo ...]
"""
import pathlib
import re
import sys

# what a write and a read have to agree on. Two calls match when they name the same thing here.
SAME = {
    "writeVarInt": "varint", "readVarInt": "varint",
    "writeInt": "int", "readInt": "int",
    "writeVarLong": "varlong", "readVarLong": "varlong",
    "writeLong": "long", "readLong": "long",
    "writeUtf": "utf", "readUtf": "utf",
    "writeBoolean": "bool", "readBoolean": "bool",
    "writeBlockPos": "pos", "readBlockPos": "pos",
    "writeFloat": "float", "readFloat": "float",
    "writeDouble": "double", "readDouble": "double",
    "writeByte": "byte", "readByte": "byte",
}

CALL = re.compile(r'\b(?:buf|byteBuf|buffer)\.(' + "|".join(SAME) + r')\s*\(')
FOR = re.compile(r'\bfor\s*\(')
RETURN = re.compile(r'\breturn\s*;')


def body_of(text, method):
    """The text between the braces of one method."""
    start = text.find(method)
    if start < 0:
        return None
    open_at = text.find("{", start)
    depth = 0
    for i in range(open_at, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[open_at + 1:i]
    return None


def block_after(text, at):
    """The text of the braced block that starts at or after `at`, and where it ends."""
    open_at = text.find("{", at)
    if open_at < 0:
        return "", len(text)
    depth = 0
    for i in range(open_at, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[open_at + 1:i], i + 1
    return text[open_at + 1:], len(text)


def calls(text):
    """Every stream call in this text, in order, as the thing it puts on the wire."""
    return [SAME[m.group(1)] for m in CALL.finditer(text)]


def parts(text, method):
    """Split one method's stream calls into the short packet and an ordered list of segments.

    The shape every one of these has is a guard that writes an empty packet and returns, then a
    count, then a loop over the things, sometimes more than one of each. So it is read as a run of
    segments - a plain stretch, a loop body, a plain stretch, a loop body - and a segment that puts
    nothing on the wire is dropped, because a loop that only counts is not part of the packet.
    """
    body = body_of(text, method)
    if body is None:
        return None
    empty = None
    guard = re.search(r"\bif\s*\(", body)
    if guard:
        inner, after = block_after(body, guard.end())
        if re.search(r"\breturn\s*;", inner):
            empty = calls(inner)
            body = body[:guard.start()] + body[after:]
    segments = []
    while True:
        loop = re.search(r"\b(?:for|while)\s*\(", body)
        if loop is None:
            segments.append(("plain", calls(body)))
            break
        inner, after = block_after(body, loop.end())
        segments.append(("plain", calls(body[:loop.start()])))
        segments.append(("loop", calls(inner)))
        body = body[after:]
    return {"segments": [seg for seg in segments if seg[1]], "empty": empty}


def check(repo):
    repo = pathlib.Path(repo)
    bad = 0
    pairs = []
    for module in sorted(repo.rglob("src/**/*Module.java")):
        view = module.with_name(module.stem + "View.java")
        if view.exists():
            pairs.append((module, view))
    if not pairs:
        print(f"  {repo.name}: no module/view pairs")
        return 0
    for module, view in pairs:
        wrote = parts(module.read_text(), "serializeToView")
        read = parts(view.read_text(), "deserialize")
        if wrote is None or read is None:
            print(f"  ?  {module.stem}: could not find both halves")
            continue
        if wrote["segments"] != read["segments"]:
            bad += 1
            print(f"  !! {module.stem}: the stream does not read back")
            for i in range(max(len(wrote["segments"]), len(read["segments"]))):
                mine = wrote["segments"][i] if i < len(wrote["segments"]) else None
                theirs = read["segments"][i] if i < len(read["segments"]) else None
                flag = "  " if mine == theirs else "<<"
                print(f"     {flag} server {mine}")
                print(f"     {flag} client {theirs}")
        # the empty packet has to be readable too: every plain stretch, and no rows at all
        if wrote["empty"] is not None:
            expect = [c for kind, seg in read["segments"] if kind == "plain" for c in seg]
            if wrote["empty"] != expect:
                bad += 1
                print(f"  !! {module.stem}: the nothing-to-send packet does not read back")
                print(f"       server writes {wrote['empty']}")
                print(f"       client reads  {expect} (the counts and the trailer, no rows)")
        fields = sum(len(seg) for _, seg in wrote["segments"])
        shape = "  ".join(f"{kind} {seg}" for kind, seg in wrote["segments"])
        print(f"  {module.stem}: {fields} field(s) - {shape}")
    return bad


if __name__ == "__main__":
    here = pathlib.Path.cwd()
    default = here if (here / "src").is_dir() else pathlib.Path(__file__).resolve().parent.parent
    print("packets:")
    bad = sum(check(r) for r in (sys.argv[1:] or [default]))
    print("  every module reads back" if bad == 0 else f"  {bad} mismatch(es)")
    sys.exit(1 if bad else 0)

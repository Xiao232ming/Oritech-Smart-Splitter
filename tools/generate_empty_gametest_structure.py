#!/usr/bin/env python3
"""Generate the empty structure template used by this mod's game tests.

NeoForge game tests run inside a structure template, and no "empty" template ships with the game,
so one is generated here. The result is a plain air box; tests build whatever they need inside it.

Usage:
    python3 tools/generate_empty_gametest_structure.py [output-root]

``output-root`` defaults to ``src/main/resources``, producing
``<output-root>/data/oritechsplitter/structure/empty.nbt``.
"""

from __future__ import annotations

import gzip
import struct
import sys
from pathlib import Path

MOD_ID = "oritechsplitter"

# Structure templates are tagged with the data version that wrote them.
DATA_VERSION = 3955  # Minecraft 1.21.1

# Bounding box of the test area. Tests address blocks with 0-based relative coordinates.
SIZE = (5, 5, 5)

TAG_END = 0
TAG_INT = 3
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10


def _encoded_name(name: str) -> bytes:
    raw = name.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw


def tag_int(name: str, value: int) -> bytes:
    return bytes([TAG_INT]) + _encoded_name(name) + struct.pack(">i", value)


def tag_string(name: str, value: str) -> bytes:
    raw = value.encode("utf-8")
    return bytes([TAG_STRING]) + _encoded_name(name) + struct.pack(">H", len(raw)) + raw


def tag_list(name: str, element_type: int, payload: bytes, count: int) -> bytes:
    return (
        bytes([TAG_LIST])
        + _encoded_name(name)
        + bytes([element_type])
        + struct.pack(">i", count)
        + payload
    )


def compound_payload(*tags: bytes) -> bytes:
    """Payload of a compound, including its terminating TAG_End."""
    return b"".join(tags) + bytes([TAG_END])


def build_structure() -> bytes:
    # A palette with a single air entry means every block in the box is air.
    palette_entry = compound_payload(tag_string("Name", "minecraft:air"))

    root = compound_payload(
        tag_int("DataVersion", DATA_VERSION),
        tag_list("size", TAG_INT, struct.pack(">iii", *SIZE), len(SIZE)),
        tag_list("palette", TAG_COMPOUND, palette_entry, 1),
        tag_list("blocks", TAG_COMPOUND, b"", 0),
        tag_list("entities", TAG_COMPOUND, b"", 0),
    )

    # Root compound carries an empty name.
    return bytes([TAG_COMPOUND]) + _encoded_name("") + root


def main() -> int:
    out_root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("src/main/resources")
    target = out_root / "data" / MOD_ID / "structure" / "empty.nbt"
    target.parent.mkdir(parents=True, exist_ok=True)

    with gzip.GzipFile(filename="", mode="wb", fileobj=target.open("wb"), mtime=0) as handle:
        handle.write(build_structure())

    print(f"wrote {target} ({target.stat().st_size} bytes, size={SIZE})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

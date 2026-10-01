#!/usr/bin/env python3
"""Import the Smart Splitter assets from an Oritech jar and adapt them for Minecraft 1.21.1.

The Smart Splitter originates in Oritech 2.0.0 for Minecraft 26.1.2, whose model format is newer
than 1.21.1 understands. This script:

  * drops fields 1.21.1 does not know about (``format_version``, ``credit``, ``groups`` and the
    Blockbench ``name`` on each element),
  * **bakes element rotations into the geometry** (see below),
  * rewrites texture references into this mod's namespace,
  * copies the referenced textures,
  * emits the multipart blockstate.

Why rotations have to be baked
------------------------------
1.21.1 accepts only a single-axis element rotation whose angle is one of 0, +/-22.5 or +/-45::

    "rotation": {"origin": [...], "axis": "y", "angle": -45, "rescale": false}

The upstream models use the newer multi-axis form ``{"x": 0, "y": -90, "z": 0, "origin": [...]}``
and rely on quarter turns (90/180 degrees), which 1.21.1 cannot express at all. Feeding them
straight to the game fails with ``Missing axis, expected to find a string`` and the block renders as
the purple/black missing model.

Every rotation used here is a quarter turn about a single axis, so a rotated box is still
axis-aligned. The rotation is therefore applied to the box corners and the result is collapsed back
to a ``from``/``to`` pair. Face UVs move to whichever face the rotated normal points at.

For a face whose normal is unchanged by the rotation (an up/down face under a Y rotation, or a
north/south face under a Z rotation) the texture should also spin within the face. 1.21.1 has no
per-face UV rotation, so a 180 degree case is handled by flipping the UV rectangle and a 90 degree
case is left as-is; those are small sliver faces on the chute caps.

Usage:
    python3 tools/import_upstream_assets.py <oritech.jar> [output-root]

``output-root`` defaults to ``src/main/resources``.
"""

from __future__ import annotations

import itertools
import json
import math
import shutil
import sys
import zipfile
from pathlib import Path

MOD_ID = "oritechsplitter"
UPSTREAM_ID = "oritech"

# Asset paths inside the Oritech jar that make up the Smart Splitter.
BLOCK_MODELS = [
    "smart_splitter_base",
    "smart_splitter_closed",
    "smart_splitter_input",
    "smart_splitter_output",
]
TEXTURES = [
    "smart_splitter",
    "machine_particle_texture",
]

# Element keys that only newer model formats use.
ELEMENT_KEYS_TO_DROP = {"name"}

# Outward normal of each model face, in Minecraft's axes (X east, Y up, Z south).
FACE_NORMALS = {
    "north": (0, 0, -1),
    "south": (0, 0, 1),
    "east": (1, 0, 0),
    "west": (-1, 0, 0),
    "up": (0, 1, 0),
    "down": (0, -1, 0),
}
NORMAL_TO_FACE = {normal: face for face, normal in FACE_NORMALS.items()}


def parse_rotation(rotation: dict | None) -> tuple[str, float] | None:
    """Normalise a rotation to ``(axis, degrees)``, or ``None`` when there is nothing to do."""
    if not rotation:
        return None

    if "axis" in rotation:
        angle = float(rotation.get("angle", 0.0))
        return None if angle == 0.0 else (str(rotation["axis"]).lower(), angle)

    # Blockbench's multi-axis form: {"x": 0, "y": -90, "z": 0, "origin": [...]}.
    non_zero = [(axis, float(rotation[axis])) for axis in ("x", "y", "z")
                if float(rotation.get(axis, 0.0)) != 0.0]
    if not non_zero:
        return None
    if len(non_zero) > 1:
        raise ValueError(f"cannot bake a multi-axis element rotation: {rotation}")
    return non_zero[0]


def rotate_point(point, origin, axis: str, degrees: float):
    """Rotate ``point`` about ``origin`` around ``axis``. Exact for multiples of 90 degrees."""
    radians = math.radians(degrees)
    # Rounded because cos/sin only ever need to be 0, 1 or -1 here.
    cos = round(math.cos(radians))
    sin = round(math.sin(radians))

    x = point[0] - origin[0]
    y = point[1] - origin[1]
    z = point[2] - origin[2]

    if axis == "x":
        nx, ny, nz = x, y * cos - z * sin, y * sin + z * cos
    elif axis == "y":
        nx, ny, nz = x * cos + z * sin, y, -x * sin + z * cos
    elif axis == "z":
        nx, ny, nz = x * cos - y * sin, x * sin + y * cos, z
    else:
        raise ValueError(f"unknown rotation axis: {axis}")

    return (nx + origin[0], ny + origin[1], nz + origin[2])


def rotate_normal(normal, axis: str, degrees: float):
    return rotate_point(normal, (0.0, 0.0, 0.0), axis, degrees)


def bake_rotation(element: dict, origin) -> None:
    """Replace an element's rotation by rotated geometry and remapped face UVs."""
    rotation = parse_rotation(element.get("rotation"))
    element.pop("rotation", None)
    if rotation is None:
        return

    axis, degrees = rotation

    # A rotated axis-aligned box is still axis-aligned, so the corners collapse back to from/to.
    corners = list(itertools.product(*zip(element["from"], element["to"])))
    rotated = [rotate_point(corner, origin, axis, degrees) for corner in corners]
    element["from"] = [min(c[i] for c in rotated) for i in range(3)]
    element["to"] = [max(c[i] for c in rotated) for i in range(3)]

    # Move every face's UV to whichever face the rotated normal ends up pointing at.
    remapped: dict[str, dict] = {}
    for face, data in element.get("faces", {}).items():
        normal = FACE_NORMALS[face]
        new_normal = tuple(round(v) for v in rotate_normal(normal, axis, degrees))
        target = NORMAL_TO_FACE[new_normal]
        face_data = dict(data)

        if target == face and abs(degrees) == 180.0 and "uv" in face_data:
            # The face still points the same way, so the texture spins within it. A half turn is
            # expressible by flipping the UV rectangle; a quarter turn is not, and is left alone.
            u1, v1, u2, v2 = face_data["uv"]
            face_data["uv"] = [u2, v2, u1, v1]

        remapped[target] = face_data

    element["faces"] = remapped


def adapt_model(model: dict) -> dict:
    """Strip newer-format fields, bake rotations and retarget textures to this mod's namespace."""
    for key in ("format_version", "credit", "groups"):
        model.pop(key, None)

    for element in model.get("elements", []):
        for key in ELEMENT_KEYS_TO_DROP:
            element.pop(key, None)

        rotation = element.get("rotation") or {}
        origin = [float(v) for v in rotation.get("origin", (0.0, 0.0, 0.0))]
        bake_rotation(element, origin)

    textures = model.get("textures")
    if isinstance(textures, dict):
        model["textures"] = {
            name: value.replace(f"{UPSTREAM_ID}:", f"{MOD_ID}:", 1)
            if isinstance(value, str) and value.startswith(f"{UPSTREAM_ID}:")
            else value
            for name, value in textures.items()
        }
    return model


def build_blockstate() -> dict:
    """Multipart blockstate: a base frame plus one overlay per horizontal side."""
    multipart = [{"apply": {"model": f"{MOD_ID}:block/smart_splitter_base"}}]

    # The overlay models are authored for the north face, so each side reuses them with a Y rotation.
    for side, rotation in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        for mode in ("closed", "input", "output"):
            apply = {"model": f"{MOD_ID}:block/smart_splitter_{mode}"}
            if rotation:
                apply["y"] = rotation
            multipart.append({"when": {side: mode}, "apply": apply})

    return {"multipart": multipart}


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2

    jar_path = Path(sys.argv[1])
    out_root = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("src/main/resources")
    if not jar_path.is_file():
        print(f"error: no such jar: {jar_path}", file=sys.stderr)
        return 1

    assets = out_root / "assets" / MOD_ID
    models_dir = assets / "models" / "block"
    textures_dir = assets / "textures" / "block"
    blockstates_dir = assets / "blockstates"
    for directory in (models_dir, textures_dir, blockstates_dir):
        directory.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(jar_path) as zf:
        for name in BLOCK_MODELS:
            member = f"assets/{UPSTREAM_ID}/models/block/{name}.json"
            with zf.open(member) as handle:
                model = adapt_model(json.load(handle))
            target = models_dir / f"{name}.json"
            target.write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")
            print(f"wrote {target}")

        for name in TEXTURES:
            member = f"assets/{UPSTREAM_ID}/textures/block/{name}.png"
            target = textures_dir / f"{name}.png"
            with zf.open(member) as src, target.open("wb") as dst:
                shutil.copyfileobj(src, dst)
            print(f"wrote {target}")

    blockstate_target = blockstates_dir / "smart_splitter.json"
    blockstate_target.write_text(json.dumps(build_blockstate(), indent=2) + "\n", encoding="utf-8")
    print(f"wrote {blockstate_target}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

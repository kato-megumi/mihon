"""Shader compile and image scaling test using moderngl.

Usage examples:
    python tools/test_shader.py --in input.png --out out.png --method lanczos3 --scale 2.0
    python tools/test_shader.py --in input.png --out out.png --method area --width 800 --height 600
    python tools/test_shader.py --make-sample sample.png --out out.png --method bilinear --scale 1.5 --compare

Requires:
    pip install moderngl Pillow numpy
"""

import argparse
import sys
from pathlib import Path
from typing import Dict, Tuple

import moderngl
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SHADER_DIR = ROOT / "app" / "src" / "main" / "res" / "raw"

def load_glsl(filename: str) -> str:
    path = SHADER_DIR / filename
    if not path.exists():
        raise FileNotFoundError(f"Shader not found: {path}")
    return "#version 100\n" + path.read_text(encoding="utf-8")


VERTEX_SHADER = load_glsl("gl_interpolator_vertex.glsl")
NEAREST_SHADER = load_glsl("gl_interpolator_nearest.glsl")
BILINEAR_SHADER = load_glsl("gl_interpolator_bilinear.glsl")
LANCZOS3_SHADER = load_glsl("gl_interpolator_lanczos3.glsl")
LANCZOS4_SHADER = load_glsl("gl_interpolator_lanczos4.glsl")
BICUBIC_SHADER = load_glsl("gl_interpolator_bicubic.glsl")
AREA_SHADER = load_glsl("gl_interpolator_area.glsl")

SHADERS: Dict[str, str] = {
    "nearest": NEAREST_SHADER,
    "bilinear": BILINEAR_SHADER,
    "lanczos3": LANCZOS3_SHADER,
    "lanczos4": LANCZOS4_SHADER,
    "bicubic": BICUBIC_SHADER,
    "area": AREA_SHADER,
}

PIL_RESAMPLE = {
    "nearest": Image.NEAREST,
    "bilinear": Image.BILINEAR,
    "lanczos3": Image.LANCZOS,
    "lanczos4": Image.LANCZOS,
    "bicubic": Image.BICUBIC,
    "area": Image.BOX,
}


def make_sample(path: Path, size: Tuple[int, int] = (256, 256), pattern: str = "gradient") -> None:
    w, h = size
    arr = np.zeros((h, w, 4), dtype=np.uint8)
    if pattern == "checker":
        tiles = 8
        tile_w = max(1, w // tiles)
        tile_h = max(1, h // tiles)
        for y in range(h):
            for x in range(w):
                is_dark = ((x // tile_w) + (y // tile_h)) % 2 == 0
                val = 32 if is_dark else 224
                arr[y, x, :3] = val
        arr[:, :, 3] = 255
    else:
        y_grid, x_grid = np.meshgrid(np.linspace(0, 1, h), np.linspace(0, 1, w), indexing="ij")
        arr[:, :, 0] = (x_grid * 255).astype(np.uint8)
        arr[:, :, 1] = (y_grid * 255).astype(np.uint8)
        arr[:, :, 2] = ((1 - x_grid) * 255).astype(np.uint8)
        arr[:, :, 3] = 255
    Image.fromarray(arr, mode="RGBA").save(path)


def compare_and_save(out_img: Image.Image, pillow_img: Image.Image, diff_out: Path | None) -> Tuple[float, float, int]:
    a = np.array(out_img, dtype=np.int16)
    b = np.array(pillow_img, dtype=np.int16)
    diff = np.abs(a - b)
    mae = float(diff.mean())
    mse = float((diff * diff).mean())
    maxv = int(diff.max())
    if diff_out:
        Image.fromarray(np.clip(diff, 0, 255).astype(np.uint8), mode="RGBA").save(diff_out)
    return mae, mse, maxv


def main() -> int:
    parser = argparse.ArgumentParser(description="Test GL shaders and scale an image")
    parser.add_argument("--in", dest="inp", help="Input image path")
    parser.add_argument("--make-sample", dest="make_sample", help="Generate a sample image to this path and use it as input")
    parser.add_argument("--sample-pattern", choices=["gradient", "checker"], default="gradient", help="Pattern for generated sample")
    parser.add_argument("--out", dest="out", required=True, help="Output image path")
    parser.add_argument("--method", choices=list(SHADERS.keys()), default="lanczos3", help="Interpolation shader to use")
    parser.add_argument("--scale", type=float, default=None, help="Uniform scale factor")
    parser.add_argument("--width", type=int, default=None, help="Target width (overrides scale)")
    parser.add_argument("--height", type=int, default=None, help="Target height (overrides scale)")
    parser.add_argument("--compare", action="store_true", help="Also resize with Pillow and report metrics")
    parser.add_argument("--pillow-out", dest="pillow_out", help="Where to save Pillow resize (default next to out)")
    parser.add_argument("--diff-out", dest="diff_out", help="Optional diff image path (absolute per-channel diff)")
    args = parser.parse_args()

    if not args.inp and not args.make_sample:
        print("Either --in or --make-sample is required", file=sys.stderr)
        return 1

    if args.make_sample:
        sample_path = Path(args.make_sample).expanduser()
        make_sample(sample_path, pattern=args.sample_pattern)
        args.inp = str(sample_path)

    inp_path = Path(args.inp).expanduser()
    out_path = Path(args.out).expanduser()
    if not inp_path.exists():
        print(f"Input not found: {inp_path}", file=sys.stderr)
        return 1

    # Load image
    img = Image.open(inp_path).convert("RGBA")
    src_w, src_h = img.size

    # Determine target size
    if args.width and args.height:
        tgt_w, tgt_h = args.width, args.height
    elif args.width:
        tgt_w = args.width
        tgt_h = int(round(src_h * (tgt_w / src_w)))
    elif args.height:
        tgt_h = args.height
        tgt_w = int(round(src_w * (tgt_h / src_h)))
    elif args.scale:
        tgt_w = int(round(src_w * args.scale))
        tgt_h = int(round(src_h * args.scale))
    else:
        tgt_w, tgt_h = src_w, src_h

    # Create GL context
    ctx = moderngl.create_standalone_context()

    # Compile selected shader
    frag_src = SHADERS[args.method]
    prog = ctx.program(vertex_shader=VERTEX_SHADER, fragment_shader=frag_src)

    # Create geometry (quad)
    vertices = np.array(
        [
            # x, y, s, t (triangle strip)
            -1.0, -1.0, 0.0, 1.0,
            1.0, -1.0, 1.0, 1.0,
            -1.0, 1.0, 0.0, 0.0,
            1.0, 1.0, 1.0, 0.0,
        ],
        dtype="f4",
    )
    vbo = ctx.buffer(vertices.tobytes())
    vao = ctx.vertex_array(
        prog,
        [
            (vbo, "2f 2f", "aPosition", "aTexCoord"),
        ],
    )

    # Create textures and FBO
    tex = ctx.texture((src_w, src_h), 4, img.tobytes())
    tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
    tex.repeat_x = False
    tex.repeat_y = False

    fbo = ctx.framebuffer(color_attachments=[ctx.texture((tgt_w, tgt_h), 4)])
    fbo.use()
    ctx.viewport = (0, 0, tgt_w, tgt_h)
    ctx.clear(0.0, 0.0, 0.0, 0.0)

    # Bind texture
    tex.use(location=0)

    # Set uniforms if present
    if "uTexture" in prog:
        prog["uTexture"].value = 0
    if "uTextureSize" in prog:
        prog["uTextureSize"].value = (float(src_w), float(src_h))
    if "uOutputSize" in prog:
        prog["uOutputSize"].value = (float(tgt_w), float(tgt_h))

    # Draw
    vao.render(mode=moderngl.TRIANGLE_STRIP, vertices=4)

    # Read pixels and flip vertically
    data = fbo.read(components=4, alignment=1)
    arr = np.frombuffer(data, dtype=np.uint8).reshape((tgt_h, tgt_w, 4))
    arr = np.flipud(arr)

    out_img = Image.fromarray(arr, mode="RGBA")
    out_img.save(out_path)
    print(f"Wrote {out_path} using {args.method} ({src_w}x{src_h} -> {tgt_w}x{tgt_h})")

    if args.compare:
        pillow_resample = PIL_RESAMPLE[args.method]
        pillow_img = img.resize((tgt_w, tgt_h), resample=pillow_resample)
        pillow_out = Path(args.pillow_out or (str(out_path.with_stem(out_path.stem + "_pillow"))))
        pillow_img.save(pillow_out)
        diff_out_path = Path(args.diff_out).expanduser() if args.diff_out else None
        mae, mse, maxv = compare_and_save(out_img, pillow_img, diff_out_path)
        print(f"Compare -> Pillow saved: {pillow_out}; MAE={mae:.3f} MSE={mse:.3f} MAX={maxv}")
        if diff_out_path:
            print(f"Diff saved to {diff_out_path}")

    return 0


if __name__ == "__main__":
    sys.exit(main())

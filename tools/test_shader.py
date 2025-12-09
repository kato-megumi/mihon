"""Shader compile and image scaling test using moderngl.

Usage examples:
    python tools/test_shader.py --in input.png --out out.png --method lanczos3 --scale 2.0
    python tools/test_shader.py --in input.png --out out.png --method area --width 800 --height 600

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

# Vertex shader (pass-through)
VERTEX_SHADER = """
#version 100
attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = aTexCoord;
}
"""

NEAREST_SHADER = """
#version 100
precision highp float;
uniform sampler2D uTexture;
varying vec2 vTexCoord;
void main() {
    gl_FragColor = texture2D(uTexture, vTexCoord);
}
"""

BILINEAR_SHADER = NEAREST_SHADER

LANCZOS3_SHADER = """
#version 100
precision highp float;
uniform sampler2D uTexture;
uniform vec2 uTextureSize;
uniform vec2 uOutputSize;
varying vec2 vTexCoord;

const float PI = 3.14159265358979323846;
const float a = 3.0;

float sinc(float x) {
    if (abs(x) < 0.0001) return 1.0;
    float pix = PI * x;
    return sin(pix) / pix;
}

float lanczos(float x) {
    if (abs(x) >= a) return 0.0;
    return sinc(x) * sinc(x / a);
}

void main() {
    vec2 srcPos = vTexCoord * uTextureSize - 0.5;
    vec2 srcPosFloor = floor(srcPos);
    vec2 f = srcPos - srcPosFloor;

    vec4 color = vec4(0.0);
    float weightSum = 0.0;

    for (int y = -2; y <= 3; y++) {
        for (int x = -2; x <= 3; x++) {
            vec2 samplePos = srcPosFloor + vec2(float(x), float(y));
            vec2 sampleCoord = (samplePos + 0.5) / uTextureSize;
            sampleCoord = clamp(sampleCoord, vec2(0.0), vec2(1.0));

            float wx = lanczos(float(x) - f.x);
            float wy = lanczos(float(y) - f.y);
            float weight = wx * wy;

            color += texture2D(uTexture, sampleCoord) * weight;
            weightSum += weight;
        }
    }

    if (weightSum > 0.0) {
        color /= weightSum;
    }

    gl_FragColor = clamp(color, 0.0, 1.0);
}
"""

LANCZOS4_SHADER = """
#version 100
precision highp float;
uniform sampler2D uTexture;
uniform vec2 uTextureSize;
uniform vec2 uOutputSize;
varying vec2 vTexCoord;

const float PI = 3.14159265358979323846;
const float a = 4.0;

float sinc(float x) {
    if (abs(x) < 0.0001) return 1.0;
    float pix = PI * x;
    return sin(pix) / pix;
}

float lanczos(float x) {
    if (abs(x) >= a) return 0.0;
    return sinc(x) * sinc(x / a);
}

void main() {
    vec2 srcPos = vTexCoord * uTextureSize - 0.5;
    vec2 srcPosFloor = floor(srcPos);
    vec2 f = srcPos - srcPosFloor;

    vec4 color = vec4(0.0);
    float weightSum = 0.0;

    for (int y = -3; y <= 4; y++) {
        for (int x = -3; x <= 4; x++) {
            vec2 samplePos = srcPosFloor + vec2(float(x), float(y));
            vec2 sampleCoord = (samplePos + 0.5) / uTextureSize;
            sampleCoord = clamp(sampleCoord, vec2(0.0), vec2(1.0));

            float wx = lanczos(float(x) - f.x);
            float wy = lanczos(float(y) - f.y);
            float weight = wx * wy;

            color += texture2D(uTexture, sampleCoord) * weight;
            weightSum += weight;
        }
    }

    if (weightSum > 0.0) {
        color /= weightSum;
    }

    gl_FragColor = clamp(color, 0.0, 1.0);
}
"""

BICUBIC_SHADER = """
#version 100
precision highp float;
uniform sampler2D uTexture;
uniform vec2 uTextureSize;
uniform vec2 uOutputSize;
varying vec2 vTexCoord;

const float B = 0.333333;
const float C = 0.333333;

float mitchell(float x) {
    float ax = abs(x);
    if (ax < 1.0) {
        return ((12.0 - 9.0 * B - 6.0 * C) * ax * ax * ax +
                (-18.0 + 12.0 * B + 6.0 * C) * ax * ax +
                (6.0 - 2.0 * B)) / 6.0;
    } else if (ax < 2.0) {
        return ((-B - 6.0 * C) * ax * ax * ax +
                (6.0 * B + 30.0 * C) * ax * ax +
                (-12.0 * B - 48.0 * C) * ax +
                (8.0 * B + 24.0 * C)) / 6.0;
    }
    return 0.0;
}

void main() {
    vec2 srcPos = vTexCoord * uTextureSize - 0.5;
    vec2 srcPosFloor = floor(srcPos);
    vec2 f = srcPos - srcPosFloor;

    vec4 color = vec4(0.0);
    float weightSum = 0.0;

    for (int y = -1; y <= 2; y++) {
        for (int x = -1; x <= 2; x++) {
            vec2 samplePos = srcPosFloor + vec2(float(x), float(y));
            vec2 sampleCoord = (samplePos + 0.5) / uTextureSize;
            sampleCoord = clamp(sampleCoord, vec2(0.0), vec2(1.0));

            float wx = mitchell(float(x) - f.x);
            float wy = mitchell(float(y) - f.y);
            float weight = wx * wy;

            color += texture2D(uTexture, sampleCoord) * weight;
            weightSum += weight;
        }
    }

    if (weightSum > 0.0) {
        color /= weightSum;
    }

    gl_FragColor = clamp(color, 0.0, 1.0);
}
"""

AREA_SHADER = """
#version 100
precision highp float;
uniform sampler2D uTexture;
uniform vec2 uTextureSize;
uniform vec2 uOutputSize;
varying vec2 vTexCoord;

void main() {
    vec2 scale = uTextureSize / uOutputSize;

    if (scale.x > 1.0 || scale.y > 1.0) {
        vec2 srcStart = vTexCoord * uTextureSize - scale * 0.5;
        vec2 srcEnd = srcStart + scale;

        vec4 color = vec4(0.0);
        float samples = 0.0;

        int samplesX = int(ceil(scale.x));
        int samplesY = int(ceil(scale.y));
        if (samplesX > 8) samplesX = 8;
        if (samplesY > 8) samplesY = 8;

        for (int y = 0; y < 8; y++) {
            if (y >= samplesY) break;
            for (int x = 0; x < 8; x++) {
                if (x >= samplesX) break;
                vec2 offset = vec2(float(x) + 0.5, float(y) + 0.5) / vec2(float(samplesX), float(samplesY));
                vec2 sampleCoord = (srcStart + offset * scale) / uTextureSize;
                sampleCoord = clamp(sampleCoord, vec2(0.0), vec2(1.0));
                color += texture2D(uTexture, sampleCoord);
                samples += 1.0;
            }
        }

        gl_FragColor = color / samples;
    } else {
        gl_FragColor = texture2D(uTexture, vTexCoord);
    }
}
"""

SHADERS: Dict[str, str] = {
    "nearest": NEAREST_SHADER,
    "bilinear": BILINEAR_SHADER,
    "lanczos3": LANCZOS3_SHADER,
    "lanczos4": LANCZOS4_SHADER,
    "bicubic": BICUBIC_SHADER,
    "area": AREA_SHADER,
}


def compile_shader(ctx: moderngl.Context, frag_src: str) -> Tuple[bool, str]:
    try:
        prog = ctx.program(vertex_shader=VERTEX_SHADER, fragment_shader=frag_src)
    except Exception as exc:  # moderngl.Error or generic
        return False, str(exc)
    return True, "OK"


def main() -> int:
    parser = argparse.ArgumentParser(description="Test GL shaders and scale an image")
    parser.add_argument("--in", dest="inp", required=True, help="Input image path")
    parser.add_argument("--out", dest="out", required=True, help="Output image path")
    parser.add_argument(
        "--method",
        choices=list(SHADERS.keys()),
        default="lanczos3",
        help="Interpolation shader to use",
    )
    parser.add_argument("--scale", type=float, default=None, help="Uniform scale factor")
    parser.add_argument("--width", type=int, default=None, help="Target width (overrides scale)")
    parser.add_argument("--height", type=int, default=None, help="Target height (overrides scale)")
    args = parser.parse_args()

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
            -1.0,
            -1.0,
            0.0,
            1.0,
            1.0,
            -1.0,
            1.0,
            1.0,
            -1.0,
            1.0,
            0.0,
            0.0,
            1.0,
            1.0,
            1.0,
            0.0,
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
    return 0


if __name__ == "__main__":
    sys.exit(main())

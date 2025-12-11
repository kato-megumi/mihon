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

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

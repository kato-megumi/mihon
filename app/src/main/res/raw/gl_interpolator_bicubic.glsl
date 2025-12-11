precision highp float;
uniform sampler2D uTexture;
uniform vec2 uTextureSize;
uniform vec2 uOutputSize;
varying vec2 vTexCoord;

const float B = 0.333333;
const float C = 0.333333;
const float BASE_SUPPORT = 2.0;
const int MAX_RADIUS = 24;

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
    vec2 scale = uTextureSize / uOutputSize;
    float scaleFactor = max(scale.x, scale.y);
    int radiusX = int(clamp(ceil(BASE_SUPPORT * scale.x), 2.0, float(MAX_RADIUS)));
    int radiusY = int(clamp(ceil(BASE_SUPPORT * scale.y), 2.0, float(MAX_RADIUS)));

    vec2 srcPos = vTexCoord * uTextureSize - 0.5;
    vec2 srcPosFloor = floor(srcPos);
    vec2 f = srcPos - srcPosFloor;

    vec4 color = vec4(0.0);
    float weightSum = 0.0;

    for (int y = -MAX_RADIUS; y <= MAX_RADIUS; y++) {
        if (y < -radiusY || y >= radiusY + 1) continue;
        for (int x = -MAX_RADIUS; x <= MAX_RADIUS; x++) {
            if (x < -radiusX || x >= radiusX + 1) continue;

            vec2 samplePos = srcPosFloor + vec2(float(x), float(y));
            vec2 sampleCoord = (samplePos + 0.5) / uTextureSize;
            sampleCoord = clamp(sampleCoord, vec2(0.0), vec2(1.0));

            float wx = mitchell((float(x) - f.x) / scale.x);
            float wy = mitchell((float(y) - f.y) / scale.y);
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

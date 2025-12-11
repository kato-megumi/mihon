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

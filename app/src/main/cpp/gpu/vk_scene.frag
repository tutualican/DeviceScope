#version 450
// Deliberately ALU-heavy fragment shader: 64 sin/cos iterations per pixel.
// This measures real fragment shading throughput, not a peak spec number.
layout(location = 0) out vec4 outColor;
layout(push_constant) uniform PC {
    float t;
} pc;

void main() {
    vec2 p = gl_FragCoord.xy / 512.0;
    float acc = 0.0;
    for (int i = 0; i < 64; i++) {
        float fi = float(i) + 1.0;
        acc += sin(p.x * fi + pc.t) * cos(p.y * fi - pc.t) * 0.5;
        acc = acc * 0.999 + 0.001;
    }
    outColor = vec4(vec3(acc * 0.5 + 0.5), 1.0);
}

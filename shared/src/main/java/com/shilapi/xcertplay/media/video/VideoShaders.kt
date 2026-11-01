package com.shilapi.xcertplay.media.video

/**
 * 视频渲染 Shader 源码常量与着色器定义。
 */
object VideoShaders {

    const val VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = aTexCoord;
}
"""

    const val FRAGMENT_SHADER_3_PLANES = """
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D uTextureY;
uniform sampler2D uTextureU;
uniform sampler2D uTextureV;
uniform float uYOffset;
uniform float uYCoeff;
uniform float uCOffset;
uniform float uCCoeff;
uniform mat3 uColorMatrix;
void main() {
    float y = texture2D(uTextureY, vTexCoord).r;
    float u = texture2D(uTextureU, vTexCoord).r;
    float v = texture2D(uTextureV, vTexCoord).r;
    float yNorm = (y - uYOffset) * uYCoeff;
    float uNorm = (u - uCOffset) * uCCoeff;
    float vNorm = (v - uCOffset) * uCCoeff;
    vec3 rgb = uColorMatrix * vec3(yNorm, uNorm, vNorm);
    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
"""
}

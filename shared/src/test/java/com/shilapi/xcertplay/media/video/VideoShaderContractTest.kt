package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertTrue
import org.junit.Test

class VideoShaderContractTest {

    @Test
    fun vertexShaderDeclaresMandatoryAttributesAndVarying() {
        val vs = VideoShaders.VERTEX_SHADER
        assertTrue(vs.contains("attribute vec4 aPosition;"))
        assertTrue(vs.contains("attribute vec2 aTexCoord;"))
        assertTrue(vs.contains("varying vec2 vTexCoord;"))
        assertTrue(vs.contains("gl_Position = aPosition;"))
    }

    @Test
    fun fragmentShaderDeclaresUniformsMatchingColorTransformModel() {
        val fs = VideoShaders.FRAGMENT_SHADER_3_PLANES
        assertTrue(fs.contains("uniform sampler2D uTextureY;"))
        assertTrue(fs.contains("uniform sampler2D uTextureU;"))
        assertTrue(fs.contains("uniform sampler2D uTextureV;"))
        assertTrue(fs.contains("uniform float uYOffset;"))
        assertTrue(fs.contains("uniform float uYCoeff;"))
        assertTrue(fs.contains("uniform float uCOffset;"))
        assertTrue(fs.contains("uniform float uCCoeff;"))
        assertTrue(fs.contains("uniform mat3 uColorMatrix;"))
        assertTrue(fs.contains("clamp(rgb, 0.0, 1.0)"))
    }
}

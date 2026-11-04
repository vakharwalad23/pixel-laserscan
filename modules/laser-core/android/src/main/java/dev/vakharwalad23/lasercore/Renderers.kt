package dev.vakharwalad23.lasercore

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Draws the ARCore camera image (external OES texture) as a full-screen quad.
internal class CameraBackground {
  var textureId = 0
    private set
  private var program = 0
  private var uDim = 0
  private val ndc = GlUtil.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
  private val uv = GlUtil.floatBuffer(8)

  fun create() {
    val t = IntArray(1)
    GLES30.glGenTextures(1, t, 0)
    textureId = t[0]
    val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
    GLES30.glBindTexture(target, textureId)
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    program = GlUtil.program(VS, FS)
    uDim = GLES30.glGetUniformLocation(program, "u_Dim")
  }

  fun draw(frame: Frame, dim: Float) {
    if (frame.hasDisplayGeometryChanged()) {
      ndc.position(0)
      uv.position(0)
      frame.transformCoordinates2d(
        Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndc,
        Coordinates2d.TEXTURE_NORMALIZED, uv
      )
      ndc.position(0)
      uv.position(0)
    }
    if (frame.timestamp == 0L) return
    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthMask(false)
    GLES30.glUseProgram(program)
    GLES30.glUniform1f(uDim, dim)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, ndc)
    GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, uv)
    GLES30.glEnableVertexAttribArray(0)
    GLES30.glEnableVertexAttribArray(1)
    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    GLES30.glDisableVertexAttribArray(0)
    GLES30.glDisableVertexAttribArray(1)
    GLES30.glDepthMask(true)
  }

  companion object {
    private const val VS = """#version 300 es
layout(location = 0) in vec2 a_Pos;
layout(location = 1) in vec2 a_Tex;
out vec2 v_Tex;
void main() { v_Tex = a_Tex; gl_Position = vec4(a_Pos, 0.0, 1.0); }
"""
    private const val FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES u_Tex;
uniform float u_Dim;
in vec2 v_Tex;
out vec4 o_Color;
void main() { o_Color = vec4(texture(u_Tex, v_Tex).rgb * u_Dim, 1.0); }
"""
  }
}

// Point layout (16 B): float x,y,z; ubyte r,g,b,kind (kind 1 = laser hit).
internal class PointCloudRenderer {
  private var program = 0
  private var uMvp = 0
  private var uSize = 0
  private var vbo = 0
  private var capacity = 0 // points
  var count = 0
    private set
  private val chunkPoints = 32768
  private val chunk: ByteBuffer =
    ByteBuffer.allocateDirect(chunkPoints * NativeFusion.PREVIEW_POINT_BYTES).order(ByteOrder.nativeOrder())
  private val mvp = FloatArray(16)

  fun create() {
    program = GlUtil.program(VS, FS)
    uMvp = GLES30.glGetUniformLocation(program, "u_MVP")
    uSize = GLES30.glGetUniformLocation(program, "u_Size")
    vbo = 0
    capacity = 0
    count = 0
  }

  fun clear() {
    count = 0
  }

  // Pulls any new points; safe to call every frame.
  fun sync(handle: Long) {
    if (handle == 0L) return
    var guard = 0
    while (guard++ < 8 && count < MAX_POINTS) {
      chunk.position(0)
      val n = NativeFusion.nativePreviewSince(handle, count, chunk, chunkPoints)
      if (n <= 0) break
      ensureCapacity(count + n)
      GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
      chunk.position(0)
      GLES30.glBufferSubData(
        GLES30.GL_ARRAY_BUFFER, count * NativeFusion.PREVIEW_POINT_BYTES,
        n * NativeFusion.PREVIEW_POINT_BYTES, chunk
      )
      count += n
      if (n < chunkPoints) break
    }
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
  }

  private fun ensureCapacity(needed: Int) {
    if (needed <= capacity) return
    var newCap = maxOf(capacity * 2, 262144)
    while (newCap < needed) newCap *= 2
    val newVbo = GlUtil.genBuffer()
    GLES30.glBindBuffer(GLES30.GL_COPY_WRITE_BUFFER, newVbo)
    GLES30.glBufferData(GLES30.GL_COPY_WRITE_BUFFER, newCap * NativeFusion.PREVIEW_POINT_BYTES, null, GLES30.GL_DYNAMIC_DRAW)
    if (vbo != 0 && count > 0) {
      GLES30.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, vbo)
      GLES30.glCopyBufferSubData(
        GLES30.GL_COPY_READ_BUFFER, GLES30.GL_COPY_WRITE_BUFFER, 0, 0,
        count * NativeFusion.PREVIEW_POINT_BYTES
      )
      GLES30.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, 0)
    }
    GLES30.glBindBuffer(GLES30.GL_COPY_WRITE_BUFFER, 0)
    GlUtil.deleteBuffer(vbo)
    vbo = newVbo
    capacity = newCap
  }

  fun draw(view: FloatArray, proj: FloatArray, size: Float) {
    if (count == 0 || vbo == 0) return
    Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
    GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    GLES30.glUseProgram(program)
    GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
    GLES30.glUniform1f(uSize, size)
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
    GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, NativeFusion.PREVIEW_POINT_BYTES, 0)
    GLES30.glVertexAttribPointer(1, 4, GLES30.GL_UNSIGNED_BYTE, true, NativeFusion.PREVIEW_POINT_BYTES, 12)
    GLES30.glEnableVertexAttribArray(0)
    GLES30.glEnableVertexAttribArray(1)
    GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count)
    GLES30.glDisableVertexAttribArray(0)
    GLES30.glDisableVertexAttribArray(1)
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
  }

  companion object {
    const val MAX_POINTS = 3_000_000
    private const val VS = """#version 300 es
uniform mat4 u_MVP;
uniform float u_Size;
layout(location = 0) in vec3 a_Pos;
layout(location = 1) in vec4 a_Color;
out vec4 v_Color;
void main() {
  gl_Position = u_MVP * vec4(a_Pos, 1.0);
  bool laser = a_Color.a > 0.002;
  v_Color = laser ? vec4(1.0, 0.18, 0.12, 1.0) : vec4(a_Color.rgb, 1.0);
  float d = max(gl_Position.w, 0.15);
  gl_PointSize = (laser ? u_Size * 2.5 : u_Size) / d;
}
"""
    private const val FS = """#version 300 es
precision mediump float;
in vec4 v_Color;
out vec4 o_Color;
void main() {
  vec2 c = gl_PointCoord - vec2(0.5);
  if (dot(c, c) > 0.25) discard;
  o_Color = v_Color;
}
"""
  }
}

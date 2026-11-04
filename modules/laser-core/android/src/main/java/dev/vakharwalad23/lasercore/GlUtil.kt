package dev.vakharwalad23.lasercore

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal object GlUtil {
  private const val TAG = "LaserScanGL"

  fun program(vs: String, fs: String): Int {
    val v = shader(GLES30.GL_VERTEX_SHADER, vs)
    val f = shader(GLES30.GL_FRAGMENT_SHADER, fs)
    val p = GLES30.glCreateProgram()
    GLES30.glAttachShader(p, v)
    GLES30.glAttachShader(p, f)
    GLES30.glLinkProgram(p)
    val ok = IntArray(1)
    GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
    if (ok[0] == 0) {
      Log.e(TAG, "link failed: " + GLES30.glGetProgramInfoLog(p))
    }
    GLES30.glDeleteShader(v)
    GLES30.glDeleteShader(f)
    return p
  }

  private fun shader(type: Int, src: String): Int {
    val s = GLES30.glCreateShader(type)
    GLES30.glShaderSource(s, src)
    GLES30.glCompileShader(s)
    val ok = IntArray(1)
    GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
    if (ok[0] == 0) {
      Log.e(TAG, "compile failed: " + GLES30.glGetShaderInfoLog(s))
    }
    return s
  }

  fun floatBuffer(n: Int): FloatBuffer =
    ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

  fun floatBuffer(data: FloatArray): FloatBuffer =
    floatBuffer(data.size).apply { put(data); position(0) }

  fun genBuffer(): Int {
    val ids = IntArray(1)
    GLES30.glGenBuffers(1, ids, 0)
    return ids[0]
  }

  fun deleteBuffer(id: Int) {
    if (id != 0) GLES30.glDeleteBuffers(1, intArrayOf(id), 0)
  }
}

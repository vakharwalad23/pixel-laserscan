package dev.vakharwalad23.lasercore

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// Orbit viewer for exported PLY meshes / point clouds.
@SuppressLint("ViewConstructor")
class ModelView(context: Context, appContext: AppContext) : ExpoView(context, appContext), GLSurfaceView.Renderer {
  override val shouldUseAndroidLayout = true

  private val onModelLoad by EventDispatcher()
  private val main = Handler(Looper.getMainLooper())

  private val glView = GLSurfaceView(context).apply {
    setEGLContextClientVersion(3)
    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
    setRenderer(this@ModelView)
    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
  }

  private class Pending(val pos: FloatArray, val nrm: FloatArray, val col: ByteArray, val idx: IntArray)

  @Volatile private var pending: Pending? = null
  @Volatile private var loadGen = 0
  private var source = ""
  private var program = 0
  private var uMvp = 0
  private var uLight = 0
  private var uLit = 0
  private var uSize = 0
  private var vboPos = 0
  private var vboNrm = 0
  private var vboCol = 0
  private var ibo = 0
  private var vertexCount = 0
  private var indexCount = 0
  private var hasNormals = false
  private var pointSize = 3f

  private val center = FloatArray(3)
  private var radius = 1f
  private var yaw = 0.6f
  private var pitch = 0.35f
  private var distance = 2.5f
  private var vpW = 1
  private var vpH = 1
  private val projM = FloatArray(16)
  private val viewM = FloatArray(16)
  private val mvp = FloatArray(16)

  private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
    override fun onScale(d: ScaleGestureDetector): Boolean {
      distance = (distance / d.scaleFactor).coerceIn(radius * 0.3f, radius * 12f)
      glView.requestRender()
      return true
    }
  })
  private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
      if (e2.pointerCount > 1) return false
      yaw += dx * 0.008f
      pitch = (pitch - dy * 0.008f).coerceIn(-1.5f, 1.5f)
      glView.requestRender()
      return true
    }
    override fun onDoubleTap(e: MotionEvent): Boolean {
      resetCamera()
      glView.requestRender()
      return true
    }
  })

  init {
    addView(glView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    glView.setOnTouchListener { v, ev ->
      v.parent?.requestDisallowInterceptTouchEvent(true)
      scaleDetector.onTouchEvent(ev)
      gestureDetector.onTouchEvent(ev)
      true
    }
  }

  fun setPointSize(v: Float) {
    pointSize = v.coerceIn(1f, 20f)
    glView.requestRender()
  }

  fun setSource(path: String) {
    if (path == source) return
    source = path
    val gen = ++loadGen
    if (path.isEmpty()) return
    Thread {
      val arr = try { NativeFusion.nativeLoadPly(path) } catch (e: Throwable) { null }
      if (gen != loadGen) return@Thread // a newer source was set meanwhile
      if (arr == null) {
        main.post { onModelLoad(mapOf("ok" to false, "error" to "could not read $path")) }
        return@Thread
      }
      val p = Pending(arr[0] as FloatArray, arr[1] as FloatArray, arr[2] as ByteArray, arr[3] as IntArray)
      pending = p
      glView.requestRender()
      main.post {
        onModelLoad(mapOf("ok" to true, "vertices" to p.pos.size / 3, "triangles" to p.idx.size / 3))
      }
    }.start()
  }

  fun release() {
    glView.onPause()
  }

  private fun resetCamera() {
    yaw = 0.6f
    pitch = 0.35f
    distance = radius * 2.6f
  }

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    GLES30.glClearColor(0.043f, 0.051f, 0.063f, 1f)
    program = GlUtil.program(VS, FS)
    uMvp = GLES30.glGetUniformLocation(program, "u_MVP")
    uLight = GLES30.glGetUniformLocation(program, "u_Light")
    uLit = GLES30.glGetUniformLocation(program, "u_Lit")
    uSize = GLES30.glGetUniformLocation(program, "u_Size")
    vboPos = 0; vboNrm = 0; vboCol = 0; ibo = 0
    vertexCount = 0
    // Context may have been recreated: re-upload the current model.
    if (source.isNotEmpty() && pending == null) {
      val s = source
      source = ""
      main.post { setSource(s) }
    }
  }

  override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
    vpW = max(1, w)
    vpH = max(1, h)
    GLES30.glViewport(0, 0, vpW, vpH)
  }

  override fun onDrawFrame(gl: GL10?) {
    pending?.let { upload(it); pending = null }
    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
    if (vertexCount == 0) return
    GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    val aspect = vpW.toFloat() / vpH
    Matrix.perspectiveM(projM, 0, 45f, aspect, max(0.001f, distance - radius * 2f).coerceAtLeast(radius * 0.01f), distance + radius * 4f)
    val ex = center[0] + distance * cos(pitch) * sin(yaw)
    val ey = center[1] + distance * sin(pitch)
    val ez = center[2] + distance * cos(pitch) * cos(yaw)
    Matrix.setLookAtM(viewM, 0, ex, ey, ez, center[0], center[1], center[2], 0f, 1f, 0f)
    Matrix.multiplyMM(mvp, 0, projM, 0, viewM, 0)
    val lx = ex - center[0]
    val ly = ey - center[1] + radius
    val lz = ez - center[2]
    val ll = sqrt(lx * lx + ly * ly + lz * lz).coerceAtLeast(1e-6f)

    GLES30.glUseProgram(program)
    GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
    GLES30.glUniform3f(uLight, lx / ll, ly / ll, lz / ll)
    GLES30.glUniform1f(uLit, if (hasNormals && indexCount > 0) 1f else 0f)
    GLES30.glUniform1f(uSize, pointSize)

    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboPos)
    GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
    GLES30.glEnableVertexAttribArray(0)
    if (hasNormals) {
      GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboNrm)
      GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 0, 0)
      GLES30.glEnableVertexAttribArray(1)
    } else {
      GLES30.glDisableVertexAttribArray(1)
      GLES30.glVertexAttrib3f(1, 0f, 1f, 0f)
    }
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboCol)
    GLES30.glVertexAttribPointer(2, 3, GLES30.GL_UNSIGNED_BYTE, true, 0, 0)
    GLES30.glEnableVertexAttribArray(2)

    if (indexCount > 0) {
      GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
      GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
      GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    } else {
      GLES30.glDrawArrays(GLES30.GL_POINTS, 0, vertexCount)
    }
    GLES30.glDisableVertexAttribArray(0)
    GLES30.glDisableVertexAttribArray(1)
    GLES30.glDisableVertexAttribArray(2)
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
  }

  private fun upload(p: Pending) {
    for (id in intArrayOf(vboPos, vboNrm, vboCol, ibo)) GlUtil.deleteBuffer(id)
    vertexCount = p.pos.size / 3
    indexCount = p.idx.size
    hasNormals = p.nrm.size == p.pos.size && p.nrm.isNotEmpty()

    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    for (i in 0 until vertexCount) {
      val x = p.pos[3 * i]; val y = p.pos[3 * i + 1]; val z = p.pos[3 * i + 2]
      if (x < minX) minX = x; if (x > maxX) maxX = x
      if (y < minY) minY = y; if (y > maxY) maxY = y
      if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
    }
    if (vertexCount > 0) {
      center[0] = (minX + maxX) / 2; center[1] = (minY + maxY) / 2; center[2] = (minZ + maxZ) / 2
      val dx = maxX - minX; val dy = maxY - minY; val dz = maxZ - minZ
      radius = max(0.05f, sqrt(dx * dx + dy * dy + dz * dz) / 2)
    }
    resetCamera()

    vboPos = GlUtil.genBuffer()
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboPos)
    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, p.pos.size * 4, GlUtil.floatBuffer(p.pos), GLES30.GL_STATIC_DRAW)
    if (hasNormals) {
      vboNrm = GlUtil.genBuffer()
      GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboNrm)
      GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, p.nrm.size * 4, GlUtil.floatBuffer(p.nrm), GLES30.GL_STATIC_DRAW)
    } else vboNrm = 0
    val colors = if (p.col.size == vertexCount * 3) p.col else ByteArray(vertexCount * 3) { 0xC0.toByte() }
    vboCol = GlUtil.genBuffer()
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboCol)
    val cb = ByteBuffer.allocateDirect(colors.size).order(ByteOrder.nativeOrder()).put(colors)
    cb.position(0)
    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, colors.size, cb, GLES30.GL_STATIC_DRAW)
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    if (indexCount > 0) {
      ibo = GlUtil.genBuffer()
      val ib = ByteBuffer.allocateDirect(indexCount * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(p.idx)
      ib.position(0)
      GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
      GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexCount * 4, ib, GLES30.GL_STATIC_DRAW)
      GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    } else ibo = 0
  }

  companion object {
    private const val VS = """#version 300 es
uniform mat4 u_MVP;
uniform float u_Size;
layout(location = 0) in vec3 a_Pos;
layout(location = 1) in vec3 a_Nrm;
layout(location = 2) in vec3 a_Col;
out vec3 v_Nrm;
out vec3 v_Col;
void main() {
  gl_Position = u_MVP * vec4(a_Pos, 1.0);
  gl_PointSize = u_Size;
  v_Nrm = a_Nrm;
  v_Col = a_Col;
}
"""
    private const val FS = """#version 300 es
precision mediump float;
uniform vec3 u_Light;
uniform float u_Lit;
in vec3 v_Nrm;
in vec3 v_Col;
out vec4 o_Color;
void main() {
  float d = abs(dot(normalize(v_Nrm), u_Light));
  float l = mix(1.0, 0.32 + 0.78 * d, u_Lit);
  o_Color = vec4(v_Col * l, 1.0);
}
"""
  }
}

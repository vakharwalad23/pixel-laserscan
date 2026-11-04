package dev.vakharwalad23.lasercore

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.ImageMetadata
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableException
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.Promise
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

// handleLock only pins the handle (read = use, write = create/destroy); GL never waits on fusion.
class ScanView(context: Context, appContext: AppContext) : ExpoView(context, appContext), GLSurfaceView.Renderer {
  override val shouldUseAndroidLayout = true

  private val onStatus by EventDispatcher()
  private val onScanError by EventDispatcher()

  private val main = Handler(Looper.getMainLooper())
  private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "laserscan-fusion") }
  private val workerBusy = AtomicBoolean(false)
  private val handleLock = ReentrantReadWriteLock()

  private val glView = GLSurfaceView(context).apply {
    preserveEGLContextOnPause = true
    setEGLContextClientVersion(3)
    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
    setRenderer(this@ScanView)
    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
  }

  @Volatile var scanning = false
  @Volatile private var saving = false
  @Volatile private var mode = "fused" // fused | laser | depth
  @Volatile private var voxelSize = 0.01f
  @Volatile private var maxDepth = 2.5f
  @Volatile var maxLaserRange = 10f // optional cap; the laser profile sets the real limit
  @Volatile var pointSize = 7f
  @Volatile private var applyLaserScale = false
  @Volatile private var lockedScale = 1f // laser / ARCore, frozen once trusted

  private val fusing get() = scanning && !saving

  @Volatile private var profileOverride: LaserKind? = null
  @Volatile private var profile: Profile = LaserProfiles.detect(context)
  @Volatile private var tuning: LaserTuning = profile.tuning

  @Volatile private var session: Session? = null
  @Volatile private var textureSet = false
  private var installRequested = false
  private var resumed = false
  private var released = false
  @Volatile private var depthSupported = false
  @Volatile private var focusCalibration = "unknown"
  @Volatile private var minFocusDiopters = 0f
  @Volatile private var hyperfocalDiopters = 0f
  @Volatile private var cameraId = ""
  @Volatile private var cameraLost = false

  @Volatile private var handle = 0L
  @Volatile private var pendingRendererClear = false

  private val background = CameraBackground()
  private val points = PointCloudRenderer()
  private val view = FloatArray(16)
  private val proj = FloatArray(16)
  private var viewW = 1
  private var viewH = 1
  @Volatile private var displayChanged = true

  // GL thread only.
  private val pose = FloatArray(16)
  private var settledPose: FloatArray? = null
  private var lensWasMoving = true
  private var lastAccepted: FloatArray? = null
  private var lastAcceptedZ = -1f
  private var laserState = "idle"
  private var liveLaserZ = -1f
  private var lastDiopters = -1f
  private var lastAfState = -1
  private var lastLensState = -1
  private var lastLaserWeight = 0f

  // GL thread unless noted.
  private var lastDepthTs = 0L
  private val poseTs = LongArray(64)
  private val poseRing = Array(64) { FloatArray(16) }
  private var poseHead = 0
  private var centerDepthZ = -1f
  private var coneSpread = 0f
  private var centerDepthAt = 0L // SystemClock.uptimeMillis
  private var aDepth: ByteBuffer? = null // analysis copies, GL thread only
  private var aConf: ByteBuffer? = null
  private var wDepth: ByteBuffer? = null // worker copies, written only while the worker is idle
  private var wConf: ByteBuffer? = null
  private var wY: ByteBuffer? = null
  private var wU: ByteBuffer? = null
  private var wV: ByteBuffer? = null

  private var tracking = "STOPPED"
  private var trackingFailure = "NONE"
  private var lastStatusMs = 0L
  private var frameCount = 0
  private var fpsWindowStart = 0L
  private var fps = 0f

  init {
    addView(glView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    createFusion()
  }

  private inline fun <T> withHandle(block: (Long) -> T): T? =
    handleLock.read { if (handle != 0L) block(handle) else null }

  fun setMode(m: String) {
    mode = if (m == "laser" || m == "depth") m else "fused"
    applyModes()
  }

  fun setVoxelSize(v: Float) {
    val clamped = v.coerceIn(0.004f, 0.05f)
    if (abs(clamped - voxelSize) < 1e-5f) return
    voxelSize = clamped
    recreateFusion()
  }

  fun setMaxDepth(v: Float) {
    val clamped = v.coerceIn(0.3f, 8f)
    if (abs(clamped - maxDepth) < 1e-4f) return
    maxDepth = clamped
    recreateFusion()
  }

  fun setApplyLaserScale(on: Boolean) {
    applyLaserScale = on
  }

  fun setLaserProfile(id: String) {
    profileOverride = if (id == "auto") null else LaserKind.from(id)
    refreshProfile()
  }

  private fun refreshProfile() {
    val detected = LaserProfiles.detect(context)
    val o = profileOverride
    profile = if (o != null) detected.copy(kind = o, source = "manual") else detected
    tuning = profile.tuning
  }

  private fun applyModes() {
    // Fusion stays in ARCore units: laser hits are always converted with the locked scale.
    withHandle { NativeFusion.nativeSetModes(it, mode != "laser", mode != "depth", 1f) }
  }

  private fun createFusion() {
    if (released) return
    handleLock.write {
      handle = NativeFusion.nativeCreate(voxelSize, voxelSize * 4f, maxDepth, 160, 1)
    }
    applyModes()
  }

  private fun recreateFusion() {
    if (released) return
    handleLock.write {
      if (handle != 0L) NativeFusion.nativeDestroy(handle)
      handle = 0L
    }
    createFusion()
    lockedScale = 1f
    pendingRendererClear = true
  }

  fun resetScan() {
    withHandle { NativeFusion.nativeReset(it) }
    lockedScale = 1f
    lastAccepted = null
    pendingRendererClear = true
    applyModes()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    ScanView.active = WeakReference(this)
    main.post { hostResume() }
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    hostPause()
  }

  fun release() {
    if (released) return
    released = true
    hostPause()
    session?.close()
    session = null
    try {
      worker.execute {
        handleLock.write {
          if (handle != 0L) NativeFusion.nativeDestroy(handle)
          handle = 0L
        }
      }
    } catch (_: RejectedExecutionException) {
    }
    worker.shutdown()
    if (ScanView.active?.get() === this) ScanView.active = null
  }

  fun hostResume() {
    if (released || resumed || !isAttachedToWindow) return
    refreshProfile()
    val activity = appContext.currentActivity ?: return
    var s = session
    if (s == null) {
      try {
        when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
          ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
            installRequested = true
            return
          }
          else -> Unit
        }
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
          emitError("camera_permission", "Camera permission not granted")
          return
        }
        s = Session(context)
        val cfg = Config(s)
        depthSupported = s.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
        cfg.depthMode = if (depthSupported) Config.DepthMode.RAW_DEPTH_ONLY else Config.DepthMode.DISABLED
        cfg.focusMode = Config.FocusMode.AUTO
        cfg.planeFindingMode = Config.PlaneFindingMode.DISABLED
        cfg.lightEstimationMode = Config.LightEstimationMode.DISABLED
        cfg.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        s.configure(cfg)
        readCharacteristics(s)
      } catch (e: UnavailableException) {
        s?.close()
        emitError("arcore_unavailable", e.javaClass.simpleName + ": " + (e.message ?: ""))
        return
      } catch (e: Exception) {
        s?.close()
        emitError("session_failed", e.toString())
        return
      }
    }
    try {
      s.resume()
    } catch (e: Exception) {
      s.close()
      session = null
      emitError(
        "camera_unavailable",
        if (e is CameraNotAvailableException) "Camera is in use by another app" else e.toString()
      )
      return
    }
    // Publish only after resume so the GL thread never touches a half-started session.
    if (session !== s) textureSet = false
    session = s
    cameraLost = false
    glView.onResume()
    displayChanged = true
    resumed = true
  }

  fun hostPause() {
    if (!resumed) return
    glView.onPause()
    session?.pause()
    resumed = false
  }

  private fun readCharacteristics(s: Session) {
    try {
      cameraId = s.cameraConfig.cameraId
      val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
      val ch = cm.getCameraCharacteristics(cameraId)
      minFocusDiopters = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
      hyperfocalDiopters = ch.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f
      focusCalibration = when (ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)) {
        CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "calibrated"
        CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "approximate"
        CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED -> "uncalibrated"
        else -> "unknown"
      }
    } catch (e: Exception) {
      Log.w(TAG, "characteristics: $e")
    }
  }

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    GLES30.glClearColor(0f, 0f, 0f, 1f)
    background.create()
    points.create()
    textureSet = false
  }

  override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
    GLES30.glViewport(0, 0, width, height)
    viewW = width
    viewH = height
    displayChanged = true
  }

  override fun onDrawFrame(gl: GL10?) {
    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
    val s = session
    if (pendingRendererClear) {
      points.clear()
      pendingRendererClear = false
    }
    if (s != null && !cameraLost) {
      try {
        if (!textureSet) {
          s.setCameraTextureName(background.textureId)
          textureSet = true
        }
        if (displayChanged) {
          s.setDisplayGeometry(displayRotation(), viewW, viewH)
          displayChanged = false
        }
        val frame = s.update()
        val cam = frame.camera
        background.draw(frame, if (fusing) 0.6f else 1f)
        tracking = cam.trackingState.name
        trackingFailure = cam.trackingFailureReason.name
        if (cam.trackingState == TrackingState.TRACKING) {
          cam.pose.toMatrix(pose, 0)
          rememberPose(frame.timestamp, pose)
          handleDepth(frame, cam, integrate = fusing && mode != "laser")
          handleLaser(frame, cam)
          cam.getProjectionMatrix(proj, 0, 0.03f, 20f)
          cam.getViewMatrix(view, 0)
          withHandle { points.sync(it) }
          points.draw(view, proj, pointSize)
        }
      } catch (e: CameraNotAvailableException) {
        cameraLost = true
        tracking = "STOPPED"
        emitError("camera_unavailable", "Camera was taken by another app. Leave and reopen the Scan tab.")
      } catch (e: Exception) {
        Log.w(TAG, "frame: $e")
      }
    }
    tickStatus()
  }

  @Suppress("DEPRECATION")
  private fun displayRotation(): Int =
    display?.rotation
      ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation

  private fun rememberPose(ts: Long, m: FloatArray) {
    poseTs[poseHead] = ts
    System.arraycopy(m, 0, poseRing[poseHead], 0, 16)
    poseHead = (poseHead + 1) % poseTs.size
  }

  private fun poseAt(ts: Long): FloatArray? {
    var best = -1
    var bestDt = Long.MAX_VALUE
    for (i in poseTs.indices) {
      val dt = abs(poseTs[i] - ts)
      if (poseTs[i] != 0L && dt < bestDt) { bestDt = dt; best = i }
    }
    // Depth timestamps usually match a frame; allow one frame interval for offset clocks.
    return if (best >= 0 && bestDt <= 34_000_000L) poseRing[best].copyOf() else null
  }

  // Multi-zone reads are local; a single-zone ~25 deg cone blurs depth edges, so it is gated harder.
  private fun handleLaser(frame: Frame, cam: Camera) {
    val meta = try { frame.imageMetadata } catch (e: Exception) { laserState = "no_metadata"; return }
    val diop = try { meta.getFloat(ImageMetadata.LENS_FOCUS_DISTANCE) } catch (e: Exception) {
      laserState = "no_focus_key"; return
    }
    val lensState = try { meta.getByte(ImageMetadata.LENS_STATE).toInt() } catch (e: Exception) { 0 }
    val afState = try { meta.getByte(ImageMetadata.CONTROL_AF_STATE).toInt() } catch (e: Exception) { -1 }
    lastDiopters = diop
    lastAfState = afState
    lastLensState = lensState
    liveLaserZ = if (diop > 0f) 1f / diop else -1f
    val t = tuning

    if (lensState != 0) { // MOVING
      lensWasMoving = true
      laserState = "focusing"
      return
    }
    if (lensWasMoving || settledPose == null) {
      settledPose = pose.copyOf()
      lensWasMoving = false
    }
    if (diop <= 0f) { laserState = "infinity"; return }
    val z = 1f / diop
    if (hyperfocalDiopters > 0f && diop <= hyperfocalDiopters * 1.05f) { laserState = "beyond_hyperfocal"; return }
    if (minFocusDiopters > 0f && diop >= minFocusDiopters * 0.98f) { laserState = "too_close"; return }
    if (z > minOf(maxLaserRange, t.maxRange)) { laserState = "out_of_range"; return }
    if (afState != AF_PASSIVE_FOCUSED && afState != AF_FOCUSED_LOCKED) { laserState = "unfocused"; return }
    // Camera2: uncalibrated focus distances are in no physical unit, so 1/diopters isn't a range.
    if (focusCalibration == "uncalibrated") { laserState = "uncalibrated"; return }

    // Continuous AF holds the lens while in focus; large motion without a refocus means stale.
    val sp = settledPose!!
    val moved = translation(sp, pose)
    val turned = rotationDeg(sp, pose)
    val alongRay = abs((pose[12] - sp[12]) * sp[8] + (pose[13] - sp[13]) * sp[9] + (pose[14] - sp[14]) * sp[10])
    // Moving along the AF ray shifts the true range one-for-one while the lens still holds the old one.
    if (moved > 0.08f || turned > 6f || alongRay > maxOf(0.01f, 0.03f * z)) { laserState = "stale"; return }
    val justSettled = moved < 0.02f && turned < 2f

    val depthFresh = centerDepthZ > 0f && SystemClock.uptimeMillis() - centerDepthAt < 150
    var weight = t.weight
    if (depthFresh) {
      // Compare in ARCore units, so ARCore's known depth bias neither fails nor passes a reading.
      val r = z / (centerDepthZ * lockedScale)
      if (r < t.agreeLo || r > t.agreeHi) { laserState = "disagrees"; return }
      if (coneSpread > t.edgeSpread) {
        // Cone straddles a depth edge: keep only if it matches the AF-ray surface, at lower weight.
        if (abs(r - 1f) > t.edgeAgree) { laserState = "edge"; return }
        weight *= (1f - coneSpread / (2f * t.edgeSpread)).coerceIn(0.4f, 1f)
      }
    } else if (t.strictWithoutDepth && !justSettled) {
      // Nothing to cross-check: trust wide-cone or PDAF-only reads only when just settled.
      laserState = "unverified"
      return
    }
    laserState = "ok"
    lastLaserWeight = weight
    if (!fusing || mode == "depth") return

    val last = lastAccepted
    if (last != null && translation(last, pose) < 0.003f && rotationDeg(last, pose) < 0.3f &&
      abs(z - lastAcceptedZ) < 0.002f) return

    val k = cam.imageIntrinsics
    val f = k.focalLength
    val c = k.principalPoint
    val dims = k.imageDimensions
    val intr = floatArrayOf(f[0], f[1], c[0], c[1])
    // The volume is in ARCore units: convert the metric laser z with the locked scale.
    val zArcore = z / lockedScale
    withHandle { h ->
      NativeFusion.nativeAddLaser(h, pose, intr, dims[0] / 2f, dims[1] / 2f, zArcore, weight, t.spotHalfAngle)
      // Calibration samples: lens settled here, a real laser, and no depth edge in the cone.
      if (depthFresh && justSettled && profile.kind != LaserKind.NONE && coneSpread <= t.edgeSpread) {
        NativeFusion.nativeAddScaleSample(h, z, centerDepthZ)
      }
    }
    lastAccepted = pose.copyOf()
    lastAcceptedZ = z
  }

  private fun translation(a: FloatArray, b: FloatArray): Float {
    val dx = a[12] - b[12]
    val dy = a[13] - b[13]
    val dz = a[14] - b[14]
    return sqrt(dx * dx + dy * dy + dz * dz)
  }

  // Angle between the cameras' forward (-Z) axes, degrees.
  private fun rotationDeg(a: FloatArray, b: FloatArray): Float {
    val d = (a[8] * b[8] + a[9] * b[9] + a[10] * b[10]).coerceIn(-1f, 1f)
    return Math.toDegrees(acos(d).toDouble()).toFloat()
  }

  // Cone stats update every depth frame; fusing only when integrate is set and the worker is idle.
  private fun handleDepth(frame: Frame, cam: Camera, integrate: Boolean) {
    if (!depthSupported) return
    val depthImg = try { frame.acquireRawDepthImage16Bits() } catch (e: NotYetAvailableException) { return } catch (e: Exception) { return }
    var confImg: Image? = null
    var camImg: Image? = null
    try {
      val ts = depthImg.timestamp
      if (ts == lastDepthTs) return
      lastDepthTs = ts
      confImg = try { frame.acquireRawDepthConfidenceImage() } catch (e: Exception) { null }

      val dw = depthImg.width
      val dh = depthImg.height
      val dPlane = depthImg.planes[0]
      val dRow = dPlane.rowStride
      var cRow = 0
      var cPix = 1
      val ad = copyInto(aDepth, dPlane.buffer).also { aDepth = it }
      var ac: ByteBuffer? = null
      if (confImg != null) {
        val cp = confImg.planes[0]
        cRow = cp.rowStride
        cPix = cp.pixelStride
        ac = copyInto(aConf, cp.buffer).also { aConf = it }
      }

      val tex = cam.textureIntrinsics
      val tf = tex.focalLength
      val tc = tex.principalPoint
      val td = tex.imageDimensions
      val sx = dw.toFloat() / td[0]
      val sy = dh.toFloat() / td[1]
      val dIntr = floatArrayOf(tf[0] * sx, tf[1] * sy, tc[0] * sx, tc[1] * sy)

      // AF ray = CPU image centre, in normalized camera coordinates.
      val ik = cam.imageIntrinsics
      val idims = ik.imageDimensions
      val rx = (idims[0] / 2f - ik.principalPoint[0]) / ik.focalLength[0]
      val ry = (idims[1] / 2f - ik.principalPoint[1]) / ik.focalLength[1]
      val cone = NativeFusion.nativeConeStats(
        ad, dRow, ac, cRow, cPix, dw, dh, dIntr, rx, ry,
        Math.toRadians(tuning.coneHalfDeg.toDouble()).toFloat(), 200
      )
      centerDepthZ = cone[0]
      coneSpread = if (cone[2] > 0f) (cone[3] - cone[1]) / cone[2] else 0f
      centerDepthAt = SystemClock.uptimeMillis()

      if (!integrate || workerBusy.get()) return
      val p = poseAt(ts) ?: return

      val db = copyInto(wDepth, dPlane.buffer).also { wDepth = it }
      val cb = if (confImg != null) copyInto(wConf, confImg.planes[0].buffer).also { wConf = it } else null

      // Colour from the current CPU image with the current pose; raw depth is usually older.
      camImg = try { frame.acquireCameraImage() } catch (e: Exception) { null }
      var cw = 0
      var ch = 0
      var yRow = 0
      var uvRow = 0
      var uvPix = 1
      var cIntr: FloatArray? = null
      var cPose: FloatArray? = null
      var yb: ByteBuffer? = null
      var ub: ByteBuffer? = null
      var vb: ByteBuffer? = null
      if (camImg != null) {
        val pl = camImg.planes
        cw = camImg.width
        ch = camImg.height
        yRow = pl[0].rowStride
        uvRow = pl[1].rowStride
        uvPix = pl[1].pixelStride
        yb = copyInto(wY, pl[0].buffer).also { wY = it }
        ub = copyInto(wU, pl[1].buffer).also { wU = it }
        vb = copyInto(wV, pl[2].buffer).also { wV = it }
        cIntr = floatArrayOf(ik.focalLength[0], ik.focalLength[1], ik.principalPoint[0], ik.principalPoint[1])
        cPose = pose.copyOf()
      }

      workerBusy.set(true)
      try {
        worker.execute {
          try {
            withHandle { h ->
              NativeFusion.nativeIntegrateDepth(
                h, db, dRow, cb, cRow, cPix, dw, dh, dIntr, p,
                yb, ub, vb, yRow, uvRow, uvPix, cw, ch, cIntr, cPose
              )
            }
          } finally {
            workerBusy.set(false)
          }
        }
      } catch (e: RejectedExecutionException) {
        workerBusy.set(false)
      }
    } finally {
      depthImg.close()
      confImg?.close()
      camImg?.close()
    }
  }

  private fun copyInto(dst: ByteBuffer?, src: ByteBuffer): ByteBuffer {
    val n = src.remaining()
    val out = if (dst == null || dst.capacity() < n) ByteBuffer.allocateDirect(n).order(ByteOrder.LITTLE_ENDIAN) else dst
    out.clear()
    out.put(src.duplicate())
    out.flip()
    return out
  }

  private fun tickStatus() {
    val now = SystemClock.uptimeMillis()
    frameCount++
    if (fpsWindowStart == 0L) fpsWindowStart = now
    if (now - fpsWindowStart >= 1000) {
      fps = frameCount * 1000f / (now - fpsWindowStart)
      frameCount = 0
      fpsWindowStart = now
    }
    if (now - lastStatusMs < 200) return
    lastStatusMs = now
    // Both calls are lock-free / short-lock in the native core.
    val st = withHandle { NativeFusion.nativeStats(it) } ?: return
    val sc = withHandle { NativeFusion.nativeScaleStats(it) } ?: return
    // Freeze the scale the first time it becomes trustworthy; never drift mid-scan.
    if (lockedScale == 1f && sc[0] >= 20f && sc[2] < 0.05f) lockedScale = sc[1]
    val payload = mapOf(
      "tracking" to tracking,
      "trackingFailure" to trackingFailure,
      "depthSupported" to depthSupported,
      "scanning" to fusing,
      "saving" to saving,
      "mode" to mode,
      "laserState" to laserState,
      "laserMm" to if (liveLaserZ > 0) (liveLaserZ * 1000f).toDouble() else -1.0,
      "diopters" to lastDiopters.toDouble(),
      "afState" to lastAfState,
      "lensState" to lastLensState,
      "arcoreCenterMm" to if (centerDepthZ > 0) (centerDepthZ * 1000f).toDouble() else -1.0,
      "focusCalibration" to focusCalibration,
      "minFocusM" to if (minFocusDiopters > 0) (1.0 / minFocusDiopters) else -1.0,
      "hyperfocalM" to if (hyperfocalDiopters > 0) (1.0 / hyperfocalDiopters) else -1.0,
      "cameraId" to cameraId,
      "blocks" to st[0].toInt(),
      "points" to st[1].toInt(),
      "laserPoints" to st[2].toInt(),
      "depthFrames" to st[3].toInt(),
      "integrateMs" to st[5].toDouble(),
      "blockLimit" to (st[6] > 0f),
      "scaleCount" to sc[0].toInt(),
      "scaleMedian" to sc[1].toDouble(),
      "scaleMad" to sc[2].toDouble(),
      "lockedScale" to lockedScale.toDouble(),
      "fps" to fps.toDouble(),
      "laserKind" to profile.kind.id,
      "laserSource" to profile.source,
      "laserFamily" to profile.family,
      "laserRangeM" to minOf(maxLaserRange, tuning.maxRange).toDouble(),
      "laserWeight" to lastLaserWeight.toDouble(),
      "coneSpread" to coneSpread.toDouble()
    )
    main.post { onStatus(payload) }
  }

  fun save(name: String?, promise: Promise) {
    if (released) {
      promise.reject("SAVE_FAILED", "view released", null)
      return
    }
    // Pause fusion without touching the scanning prop (JS owns it).
    saving = true
    val scale = if (applyLaserScale) lockedScale else 1f
    try {
      worker.execute {
        try {
          val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
          val id = "scan-$stamp"
          val dir = File(File(context.filesDir, "scans"), id)
          dir.mkdirs()
          var res: IntArray? = null
          var st: FloatArray? = null
          var sc: FloatArray? = null
          // Read lock only pins the handle; the GL thread keeps rendering meanwhile.
          withHandle { h ->
            res = NativeFusion.nativeExport(h, dir.absolutePath, 3f, scale)
            st = NativeFusion.nativeStats(h)
            sc = NativeFusion.nativeScaleStats(h)
          }
          val r = res ?: throw IllegalStateException("export failed")
          val s = st!!
          val c = sc!!
          val meta = JSONObject()
            .put("id", id)
            .put("name", name ?: id)
            .put("kind", "scan")
            .put("createdAt", System.currentTimeMillis())
            .put("mode", mode)
            .put("voxelSize", voxelSize.toDouble())
            .put("vertices", r[0]).put("triangles", r[1])
            .put("cloudPoints", r[2]).put("laserPoints", r[3])
            .put("depthFrames", s[3].toInt())
            .put("scaleCount", c[0].toInt()).put("scaleMedian", c[1].toDouble()).put("scaleMad", c[2].toDouble())
            .put("appliedScale", scale.toDouble())
            .put("focusCalibration", focusCalibration)
            .put("device", Build.MANUFACTURER + " " + Build.MODEL)
            .put("laserKind", profile.kind.id)
            .put("laserSource", profile.source)
          File(dir, "meta.json").writeText(meta.toString(2))
          promise.resolve(
            mapOf(
              "id" to id, "path" to dir.absolutePath,
              "vertices" to r[0], "triangles" to r[1], "cloudPoints" to r[2], "laserPoints" to r[3]
            )
          )
        } catch (e: Exception) {
          promise.reject("SAVE_FAILED", e.message ?: e.toString(), e)
        } finally {
          saving = false
        }
      }
    } catch (e: RejectedExecutionException) {
      saving = false
      promise.reject("SAVE_FAILED", "scanner is shutting down", e)
    }
  }

  private fun emitError(code: String, message: String) {
    main.post { onScanError(mapOf("code" to code, "message" to message)) }
  }

  companion object {
    private const val TAG = "LaserScan"
    private const val AF_PASSIVE_FOCUSED = 2
    private const val AF_FOCUSED_LOCKED = 4
    @Volatile var active: WeakReference<ScanView>? = null
  }
}

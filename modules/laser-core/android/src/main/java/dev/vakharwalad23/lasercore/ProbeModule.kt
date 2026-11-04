package dev.vakharwalad23.lasercore

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.google.ar.core.ArCoreApk
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// sweep() steers the AF region over a grid; multi-zone LDAF may use a different ToF zone per cell.
class ProbeModule : Module() {
  private val flagRe = Regex("(tof|laser|ldaf|depth|distance|range|zone|spad|vl53|flight|pdaf|focus)", RegexOption.IGNORE_CASE)
  @Volatile private var lastSweep: Map<String, Any?>? = null
  @Volatile private var sweeping = false
  @Volatile private var meanLuma = -1.0

  private val ctx: Context
    get() = appContext.reactContext ?: throw IllegalStateException("no context")

  override fun definition() = ModuleDefinition {
    Name("LaserProbe")
    Events("onSweepProgress")

    AsyncFunction("capabilities") { promise: Promise ->
      Thread {
        try { promise.resolve(capabilities()) } catch (e: Exception) { promise.reject("PROBE_FAILED", e.toString(), e) }
      }.start()
    }

    Function("laserProfile") { LaserProfiles.detect(ctx).toMap() }

    Function("setObservedLaser") { kind: String? ->
      LaserProfiles.saveObserved(ctx, LaserKind.from(kind))
      LaserProfiles.detect(ctx).toMap()
    }

    AsyncFunction("sweep") { cameraId: String, grid: Int, settleMs: Int, dark: Boolean, promise: Promise ->
      if (sweeping) {
        promise.reject("BUSY", "sweep already running", null)
        return@AsyncFunction
      }
      sweeping = true
      Thread {
        try {
          val r = sweep(cameraId, grid.coerceIn(1, 12), settleMs.coerceIn(0, 2000), dark)
          lastSweep = r
          promise.resolve(r)
        } catch (e: Exception) {
          promise.reject("SWEEP_FAILED", e.message ?: e.toString(), e)
        } finally {
          sweeping = false
        }
      }.start()
    }

    AsyncFunction("saveLastSweep") { promise: Promise ->
      val s = lastSweep
      if (s == null) {
        promise.reject("NO_SWEEP", "run a sweep first", null)
        return@AsyncFunction
      }
      try { promise.resolve(saveSweep(s)) } catch (e: Exception) { promise.reject("SAVE_FAILED", e.toString(), e) }
    }
  }

  private fun capabilities(): Map<String, Any?> {
    val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val ids = LinkedHashSet<String>()
    for (id in cm.cameraIdList) {
      ids.add(id)
      try {
        if (Build.VERSION.SDK_INT >= 28) ids.addAll(cm.getCameraCharacteristics(id).physicalCameraIds)
      } catch (_: Exception) {}
    }
    val logical = cm.cameraIdList.toSet()
    val cameras = ids.mapNotNull { id ->
      try {
        describeCamera(id, cm.getCameraCharacteristics(id)) + ("physical" to !logical.contains(id))
      } catch (e: Exception) {
        mapOf("id" to id, "error" to e.toString(), "physical" to !logical.contains(id))
      }
    }
    val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val sensors = sm.getSensorList(Sensor.TYPE_ALL).map { s ->
      val flagged = Regex("(tof|laser|vl53|range|distance|proximity|flight)", RegexOption.IGNORE_CASE)
        .containsMatchIn(s.name + " " + s.stringType)
      mapOf(
        "name" to s.name, "vendor" to s.vendor, "type" to s.type, "stringType" to s.stringType,
        "maxRange" to s.maximumRange.toDouble(), "flagged" to flagged
      )
    }
    val arcore = try { ArCoreApk.getInstance().checkAvailability(ctx).name } catch (e: Exception) { "ERROR" }
    return mapOf(
      "device" to mapOf(
        "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL, "device" to Build.DEVICE,
        "sdk" to Build.VERSION.SDK_INT, "soc" to if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""
      ),
      "arcore" to arcore,
      "cameras" to cameras,
      "sensors" to sensors
    )
  }

  private fun describeCamera(id: String, ch: CameraCharacteristics): Map<String, Any?> {
    val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
    val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val formats = map?.outputFormats?.toList() ?: emptyList()
    val depthFormats = formats.mapNotNull {
      when (it) {
        ImageFormat.DEPTH16 -> "DEPTH16"
        ImageFormat.DEPTH_POINT_CLOUD -> "DEPTH_POINT_CLOUD"
        ImageFormat.DEPTH_JPEG -> "DEPTH_JPEG"
        else -> null
      }
    }
    val charKeys = ch.keys.map { it.name }
    val resultKeys = ch.availableCaptureResultKeys.map { it.name }
    val vendorChar = charKeys.filter { !it.startsWith("android.") }
    val vendorResult = resultKeys.filter { !it.startsWith("android.") }
    val flaggedChar = LinkedHashMap<String, String>()
    for (k in ch.keys) {
      if (k.name.startsWith("android.") || !flagRe.containsMatchIn(k.name)) continue
      flaggedChar[k.name] = try { stringify(ch.get(k)) } catch (e: Exception) { "<$e>" }
    }
    val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    val hyper = ch.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f
    return mapOf(
      "id" to id,
      "facing" to when (ch.get(CameraCharacteristics.LENS_FACING)) {
        CameraCharacteristics.LENS_FACING_BACK -> "back"
        CameraCharacteristics.LENS_FACING_FRONT -> "front"
        else -> "external"
      },
      "hardwareLevel" to (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1),
      "depthOutput" to caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT),
      "logicalMulti" to caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA),
      "manualSensor" to caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
      "depthFormats" to depthFormats,
      "focusCalibration" to when (ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)) {
        CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "calibrated"
        CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "approximate"
        else -> "uncalibrated"
      },
      "minFocusM" to if (minFocus > 0) 1.0 / minFocus else -1.0,
      "hyperfocalM" to if (hyper > 0) 1.0 / hyper else -1.0,
      "afRegionsMax" to (ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0),
      "focalLengths" to (ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.map { it.toDouble() } ?: emptyList()),
      "intrinsics" to (ch.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.map { it.toDouble() } ?: emptyList()),
      "vendorCharacteristicKeys" to vendorChar,
      "vendorResultKeys" to vendorResult,
      "flaggedCharacteristics" to flaggedChar
    )
  }

  @SuppressLint("MissingPermission")
  private fun sweep(cameraId: String, n: Int, settleMs: Int, dark: Boolean): Map<String, Any?> {
    val c = ctx
    if (c.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
      throw IllegalStateException("camera permission not granted")
    val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val ch = cm.getCameraCharacteristics(cameraId)
    val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
      ?: throw IllegalStateException("no active array")
    if ((ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) < 1)
      throw IllegalStateException("camera $cameraId does not support AF regions")

    val thread = HandlerThread("laser-probe").apply { start() }
    val handler = Handler(thread.looper)
    val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
      ?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
    val size = sizes.minByOrNull { abs(it.width * it.height - 640 * 480) }
      ?: throw IllegalStateException("camera $cameraId has no YUV output")
    val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
    // Mean luma of the preview stream: proves the dark test really starved PDAF of light.
    meanLuma = -1.0
    reader.setOnImageAvailableListener({ r ->
      r.acquireLatestImage()?.let { img ->
        try {
          val pl = img.planes[0]
          val buf = pl.buffer
          var sum = 0L
          var cnt = 0
          var y = 0
          while (y < img.height) {
            var x = 0
            while (x < img.width) {
              sum += buf.get(y * pl.rowStride + x).toInt() and 0xff
              cnt++
              x += 16
            }
            y += 16
          }
          if (cnt > 0) meanLuma = sum.toDouble() / cnt
        } catch (_: Exception) {
        } finally {
          img.close()
        }
      }
    }, handler)
    val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
    val manual = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
    val canResetLens = manual && (ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f
    if (dark && !manual) throw IllegalStateException("camera $cameraId has no manual exposure, so the dark test can't run")
    val hyper = ch.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f
    var device: CameraDevice? = null
    var session: CameraCaptureSession? = null
    val results = LinkedBlockingQueue<TotalCaptureResult>()
    val cb = object : CameraCaptureSession.CaptureCallback() {
      override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
        results.offer(res)
        while (results.size > 64) results.poll()
      }
    }
    val opened = CountDownLatch(1)
    val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
    try {
      var openErr: String? = null
      cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
        override fun onOpened(d: CameraDevice) {
          if (abandoned.get()) d.close() else device = d
          opened.countDown()
        }
        override fun onDisconnected(d: CameraDevice) { d.close(); openErr = "disconnected"; opened.countDown() }
        override fun onError(d: CameraDevice, error: Int) { d.close(); openErr = "error $error"; opened.countDown() }
      }, handler)
      if (!opened.await(5, TimeUnit.SECONDS) || device == null) throw IllegalStateException("open failed: ${openErr ?: "timeout"}")
      val dev = device!!

      val configured = CountDownLatch(1)
      @Suppress("DEPRECATION")
      dev.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) { session = s; configured.countDown() }
        override fun onConfigureFailed(s: CameraCaptureSession) { configured.countDown() }
      }, handler)
      if (!configured.await(5, TimeUnit.SECONDS) || session == null) throw IllegalStateException("session configure failed")
      val sess = session!!

      val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
      builder.addTarget(reader.surface)
      builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
      builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
      if (dark) {
        // Starve PDAF with the shortest exposure and lowest gain; only an active emitter can focus.
        val exp = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp?.lower ?: 100_000L)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso?.lower ?: 50)
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L)
      } else {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
      }
      sess.setRepeatingRequest(builder.build(), cb, handler)
      Thread.sleep(600) // let AE / exposure settle

      val cells = ArrayList<Map<String, Any?>>()
      val cw = active.width() / n
      val chh = active.height() / n
      for (row in 0 until n) for (col in 0 until n) {
        // CONTROL_AF_REGIONS use active-array coordinates, origin at the array's own (0,0).
        val x0 = col * cw
        val y0 = row * chh
        val region = arrayOf(MeteringRectangle(x0, y0, cw, chh, MeteringRectangle.METERING_WEIGHT_MAX - 1))
        // Results are tagged per cell so an in-flight lock from the previous cell is never read.
        val tag = "cell-$row-$col"
        if (canResetLens) {
          // Park the lens at infinity first so each cell is measured fresh.
          builder.setTag("$tag-park")
          builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
          builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
          sess.setRepeatingRequest(builder.build(), cb, handler)
          Thread.sleep(180)
          builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
        }
        builder.setTag(tag)
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, region)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        sess.capture(builder.build(), cb, handler)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        sess.setRepeatingRequest(builder.build(), cb, handler)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        results.clear()
        builder.setTag("$tag-start")
        sess.capture(builder.build(), cb, handler)
        builder.setTag(tag)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)

        var started = false
        val deadline = System.currentTimeMillis() + 2500
        var locked: TotalCaptureResult? = null
        var lockState = -1
        while (System.currentTimeMillis() < deadline) {
          val r = results.poll(200, TimeUnit.MILLISECONDS) ?: continue
          val t = r.request.tag as? String ?: continue
          if (t == "$tag-start") started = true
          if (!started || (t != tag && t != "$tag-start")) continue
          val af = r.get(CaptureResult.CONTROL_AF_STATE) ?: continue
          if (af == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
            locked = r
            lockState = af
            break
          }
        }
        // Collect settled readings for settleMs and take the median of stationary ones.
        val readings = ArrayList<Float>()
        locked?.let { r -> stationaryFocus(r)?.let { readings.add(it) } }
        val settleEnd = System.currentTimeMillis() + settleMs
        var lastResult: TotalCaptureResult? = locked
        while (System.currentTimeMillis() < settleEnd) {
          val r = results.poll(100, TimeUnit.MILLISECONDS) ?: continue
          val rt = r.request.tag as? String
          if (rt != tag && rt != "$tag-start") continue
          lastResult = r
          stationaryFocus(r)?.let { readings.add(it) }
        }
        readings.sort()
        val diop = if (readings.isEmpty()) -1f else readings[readings.size / 2]
        val vendor = LinkedHashMap<String, String>()
        lastResult?.let { r ->
          for (k in r.keys) {
            if (k.name.startsWith("android.") || !flagRe.containsMatchIn(k.name)) continue
            vendor[k.name] = try { stringify(r.get(k)) } catch (e: Exception) { "<$e>" }
          }
        }
        cells.add(
          mapOf(
            "row" to row, "col" to col,
            "u" to (x0 + cw / 2.0), "v" to (y0 + chh / 2.0),
            "diopters" to diop.toDouble(),
            "meters" to if (diop > 0f) 1.0 / diop else -1.0,
            "focused" to (lockState == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED),
            "samples" to readings.size,
            "vendor" to vendor
          )
        )
        sendEvent("onSweepProgress", mapOf("done" to cells.size, "total" to n * n))
      }
      builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
      sess.capture(builder.build(), cb, handler)

      val intr = ch.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
      val fx: Double
      val fy: Double
      val cx: Double
      val cy: Double
      if (intr != null && intr.size >= 4 && intr[0] > 0f) {
        // Calibration is in pre-correction array pixels; shift into active-array pixels.
        val pre = ch.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        val ox = if (pre != null) (active.left - pre.left).toDouble() else 0.0
        val oy = if (pre != null) (active.top - pre.top).toDouble() else 0.0
        fx = intr[0].toDouble(); fy = intr[1].toDouble(); cx = intr[2] - ox; cy = intr[3] - oy
      } else {
        val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 4.5f
        val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pix = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val pxPerMm = if (phys != null && pix != null && phys.width > 0) pix.width / phys.width.toDouble() else active.width() / 6.4
        fx = f * pxPerMm; fy = fx
        cx = active.width() / 2.0; cy = active.height() / 2.0
      }
      val verdict = if (dark) darkVerdict(cells, hyper, meanLuma) else null
      return mapOf(
        "cameraId" to cameraId, "grid" to n, "dark" to dark, "meanLuma" to meanLuma,
        "lensReset" to canResetLens, "verdict" to verdict,
        "width" to active.width(), "height" to active.height(),
        "intrinsics" to mapOf("fx" to fx, "fy" to fy, "cx" to cx, "cy" to cy, "fromCalibration" to (intr != null && intr.isNotEmpty() && intr[0] > 0f)),
        "cells" to cells
      )
    } finally {
      abandoned.set(true)
      // A late onOpened must still run on this handler so it can close the device.
      if (device == null) opened.await(5, TimeUnit.SECONDS)
      try { session?.close() } catch (_: Exception) {}
      try { device?.close() } catch (_: Exception) {}
      reader.close()
      thread.quitSafely()
    }
  }

  // Dark sweep: no lock = no laser, one distance = single zone. Needs near and far parts in view.
  private fun darkVerdict(cells: List<Map<String, Any?>>, hyperDiop: Float, luma: Double): Map<String, Any?> {
    val valid = cells.filter {
      val d = (it["diopters"] as Double).toFloat()
      (it["focused"] as Boolean) && d > 0f && (hyperDiop <= 0f || d > hyperDiop * 1.05f)
    }
    val zs = valid.map { it["meters"] as Double }
    val distinct = zs.map { Math.round(it * 100) }.toSet().size
    val ratio = if (zs.isEmpty()) 1.0 else zs.maxOrNull()!! / zs.minOrNull()!!
    val frac = valid.size.toDouble() / cells.size.coerceAtLeast(1)
    val kind = when {
      frac < 0.3 -> LaserKind.NONE
      distinct <= 2 || ratio < 1.08 -> LaserKind.SINGLE
      else -> LaserKind.MULTI
    }
    val darkEnough = luma in 0.0..20.0
    val confident = darkEnough && cells.size >= 9 && (kind != LaserKind.MULTI || valid.size >= 4)
    val reason = when (kind) {
      LaserKind.NONE -> "Focus couldn't lock in ${cells.size - valid.size} of ${cells.size} cells without light: no active laser."
      LaserKind.SINGLE -> "Focus locked without light, but every region read about the same distance: one laser zone."
      else -> "Regions read $distinct different distances without light: the laser has multiple zones."
    } + if (!darkEnough) " The frame wasn't dark (luma ${"%.0f".format(luma)}), so PDAF may have helped; retry with the lens covered by your hand at ~5 cm, or in a dim room." else ""
    return mapOf(
      "kind" to kind.id, "confident" to confident, "validCells" to valid.size,
      "distinct" to distinct, "ratio" to ratio, "reason" to reason
    )
  }

  private fun stationaryFocus(r: TotalCaptureResult): Float? {
    val lens = r.get(CaptureResult.LENS_STATE) ?: return null
    if (lens != CameraMetadata.LENS_STATE_STATIONARY) return null
    return r.get(CaptureResult.LENS_FOCUS_DISTANCE)
  }

  @Suppress("UNCHECKED_CAST")
  private fun saveSweep(s: Map<String, Any?>): Map<String, Any?> {
    val cells = s["cells"] as List<Map<String, Any?>>
    val intr = s["intrinsics"] as Map<String, Any?>
    val fx = intr["fx"] as Double
    val fy = intr["fy"] as Double
    val cx = intr["cx"] as Double
    val cy = intr["cy"] as Double
    val pts = cells.filter { (it["meters"] as Double) > 0 }
    if (pts.isEmpty()) throw IllegalStateException("sweep has no valid cells")
    val zs = pts.map { it["meters"] as Double }
    val zMin = zs.minOrNull()!!
    val zMax = zs.maxOrNull()!!
    val id = "probe-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val dir = File(File(ctx.filesDir, "scans"), id).apply { mkdirs() }
    val sb = StringBuilder()
    sb.append("ply\nformat ascii 1.0\ncomment LaserScan AF-region sweep\n")
    sb.append("element vertex ${pts.size}\nproperty float x\nproperty float y\nproperty float z\n")
    sb.append("property uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n")
    for (p in pts) {
      val z = p["meters"] as Double
      val u = p["u"] as Double
      val v = p["v"] as Double
      // GL camera convention: +Y up, -Z forward
      val x = (u - cx) / fx * z
      val y = -(v - cy) / fy * z
      val t = if (zMax > zMin) (z - zMin) / (zMax - zMin) else 0.5
      val r = (255 * t).toInt()
      val b = (255 * (1 - t)).toInt()
      sb.append(String.format(Locale.US, "%.4f %.4f %.4f %d %d %d\n", x, y, -z, r, 80, b))
    }
    File(dir, "cloud.ply").writeText(sb.toString())
    val meta = JSONObject()
      .put("id", id).put("name", id).put("kind", "probe")
      .put("createdAt", System.currentTimeMillis())
      .put("grid", s["grid"]).put("cameraId", s["cameraId"])
      .put("validCells", pts.size).put("cloudPoints", pts.size)
      .put("device", Build.MANUFACTURER + " " + Build.MODEL)
    File(dir, "meta.json").writeText(meta.toString(2))
    return mapOf("id" to id, "path" to dir.absolutePath, "cloudPoints" to pts.size)
  }

  private fun stringify(v: Any?): String = when (v) {
    null -> "null"
    is FloatArray -> v.joinToString(prefix = "[", postfix = "]")
    is IntArray -> v.joinToString(prefix = "[", postfix = "]")
    is LongArray -> v.joinToString(prefix = "[", postfix = "]")
    is ByteArray -> v.joinToString(prefix = "[", postfix = "]")
    is DoubleArray -> v.joinToString(prefix = "[", postfix = "]")
    is BooleanArray -> v.joinToString(prefix = "[", postfix = "]")
    is Array<*> -> v.joinToString(prefix = "[", postfix = "]") { stringify(it) }
    else -> v.toString()
  }.let { if (it.length > 400) it.substring(0, 400) + "..." else it }
}

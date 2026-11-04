package dev.vakharwalad23.lasercore

import com.google.ar.core.ArCoreApk
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class ScanModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("LaserScan")

    OnActivityEntersForeground { ScanView.active?.get()?.hostResume() }
    OnActivityEntersBackground { ScanView.active?.get()?.hostPause() }

    AsyncFunction("arcoreAvailability") {
      val ctx = appContext.reactContext ?: return@AsyncFunction "UNKNOWN_ERROR"
      ArCoreApk.getInstance().checkAvailability(ctx).name
    }

    View(ScanView::class) {
      Events("onStatus", "onScanError")

      Prop("scanning") { view: ScanView, v: Boolean -> view.scanning = v }
      Prop("mode") { view: ScanView, v: String -> view.setMode(v) }
      Prop("voxelSize") { view: ScanView, v: Double -> view.setVoxelSize(v.toFloat()) }
      Prop("maxDepth") { view: ScanView, v: Double -> view.setMaxDepth(v.toFloat()) }
      Prop("maxLaserRange") { view: ScanView, v: Double -> view.maxLaserRange = v.toFloat() }
      Prop("applyLaserScale") { view: ScanView, v: Boolean -> view.setApplyLaserScale(v) }
      Prop("pointSize") { view: ScanView, v: Double -> view.pointSize = v.toFloat() }
      Prop("laserProfile") { view: ScanView, v: String -> view.setLaserProfile(v) }

      AsyncFunction("reset") { view: ScanView -> view.resetScan() }
      AsyncFunction("save") { view: ScanView, name: String?, promise: Promise -> view.save(name, promise) }

      OnViewDestroys { view: ScanView -> view.release() }
    }
  }
}

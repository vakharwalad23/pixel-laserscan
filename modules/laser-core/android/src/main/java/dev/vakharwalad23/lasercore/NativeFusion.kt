package dev.vakharwalad23.lasercore

import java.nio.ByteBuffer

// JNI bindings for liblaserfusion.so; all calls are thread-safe.
object NativeFusion {
  init {
    System.loadLibrary("laserfusion")
  }

  const val PREVIEW_POINT_BYTES = 16

  @JvmStatic external fun nativeCreate(voxel: Float, trunc: Float, maxDepth: Float, minConf: Int, stride: Int): Long
  @JvmStatic external fun nativeDestroy(handle: Long)
  @JvmStatic external fun nativeReset(handle: Long)
  @JvmStatic external fun nativeSetModes(handle: Long, depth: Boolean, laser: Boolean, scale: Float)

  @JvmStatic external fun nativeIntegrateDepth(
    handle: Long,
    depth: ByteBuffer, depthRowStride: Int,
    conf: ByteBuffer?, confRowStride: Int, confPixelStride: Int,
    width: Int, height: Int,
    intrinsics: FloatArray, pose: FloatArray,
    y: ByteBuffer?, u: ByteBuffer?, v: ByteBuffer?,
    yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
    colorWidth: Int, colorHeight: Int, colorIntrinsics: FloatArray?,
    colorPose: FloatArray?
  ): Int

  @JvmStatic external fun nativeAddLaser(handle: Long, pose: FloatArray, intrinsics: FloatArray, u: Float, v: Float, z: Float, weight: Float, spotHalfAngle: Float)
  // ARCore depth in the laser cone: [center, p10, p50, p90, samples], meters, -1 = n/a
  @JvmStatic external fun nativeConeStats(
    depth: ByteBuffer, depthRowStride: Int,
    conf: ByteBuffer?, confRowStride: Int, confPixelStride: Int,
    width: Int, height: Int, intrinsics: FloatArray,
    rx: Float, ry: Float, halfAngle: Float, minConf: Int
  ): FloatArray

  @JvmStatic external fun nativeAddScaleSample(handle: Long, laserZ: Float, depthZ: Float)

  // [count, median, mad]
  @JvmStatic external fun nativeScaleStats(handle: Long): FloatArray

  // [blocks, previewPoints, laserPoints, depthFrames, laserAccepted, lastIntegrateMs, blockLimitHit]
  @JvmStatic external fun nativeStats(handle: Long): FloatArray

  @JvmStatic external fun nativePreviewSince(handle: Long, from: Int, out: ByteBuffer, maxPoints: Int): Int

  // Files scaled by scale: [vertices, triangles, cloudPoints, laserPoints] or null
  @JvmStatic external fun nativeExport(handle: Long, dir: String, minWeight: Float, scale: Float): IntArray?

  // [FloatArray positions, FloatArray normals, ByteArray colors, IntArray indices] or null
  @JvmStatic external fun nativeLoadPly(path: String): Array<Any>?
}

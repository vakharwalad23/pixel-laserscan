#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include "fusion.h"

using laser::Fusion;

namespace {

inline Fusion* F(jlong h) { return reinterpret_cast<Fusion*>(h); }

template <typename T>
T* addr(JNIEnv* env, jobject buf) {
  return buf ? static_cast<T*>(env->GetDirectBufferAddress(buf)) : nullptr;
}

std::string str(JNIEnv* env, jstring s) {
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string out(c ? c : "");
  if (c) env->ReleaseStringUTFChars(s, c);
  return out;
}

bool readFloats(JNIEnv* env, jfloatArray a, float* out, int n) {
  if (!a || env->GetArrayLength(a) < n) return false;
  env->GetFloatArrayRegion(a, 0, n, out);
  return true;
}

}  // namespace

#define JNI_FN(name) Java_dev_vakharwalad23_lasercore_NativeFusion_##name

extern "C" {

JNIEXPORT jlong JNICALL JNI_FN(nativeCreate)(JNIEnv*, jclass, jfloat voxel,
                                            jfloat trunc, jfloat maxDepth,
                                            jint minConf, jint stride) {
  laser::Config c;
  c.voxelSize = voxel;
  c.truncation = trunc;
  c.maxDepth = maxDepth;
  c.minConfidence = minConf;
  c.depthStride = stride;
  return reinterpret_cast<jlong>(new Fusion(c));
}

JNIEXPORT void JNICALL JNI_FN(nativeDestroy)(JNIEnv*, jclass, jlong h) {
  delete F(h);
}

JNIEXPORT void JNICALL JNI_FN(nativeReset)(JNIEnv*, jclass, jlong h) {
  F(h)->reset();
}

JNIEXPORT void JNICALL JNI_FN(nativeSetModes)(JNIEnv*, jclass, jlong h,
                                             jboolean depth, jboolean laser,
                                             jfloat scale) {
  F(h)->setModes(depth, laser, scale);
}

// intr/cIntr: fx, fy, cx, cy of the depth/colour image (cIntr may be null); pose: cam-to-world.
JNIEXPORT jint JNICALL JNI_FN(nativeIntegrateDepth)(
    JNIEnv* env, jclass, jlong h, jobject depth, jint depthRowStride,
    jobject conf, jint confRowStride, jint confPixelStride, jint w, jint hgt,
    jfloatArray intr, jfloatArray pose, jobject y, jobject u, jobject v,
    jint yRow, jint uvRow, jint uvPix, jint cw, jint ch, jfloatArray cIntr,
    jfloatArray cPose) {
  float K4[4], M[16], C4[4], CM[16];
  if (!readFloats(env, intr, K4, 4) || !readFloats(env, pose, M, 16)) return -1;
  laser::Intrinsics K{K4[0], K4[1], K4[2], K4[3], w, hgt};
  laser::YuvImage img;
  laser::YuvImage* imgPtr = nullptr;
  if (y && readFloats(env, cIntr, C4, 4)) {
    img.y = addr<const uint8_t>(env, y);
    img.u = addr<const uint8_t>(env, u);
    img.v = addr<const uint8_t>(env, v);
    img.width = cw;
    img.height = ch;
    img.yRowStride = yRow;
    img.uvRowStride = uvRow;
    img.uvPixelStride = uvPix;
    img.K = {C4[0], C4[1], C4[2], C4[3], cw, ch};
    if (readFloats(env, cPose, CM, 16)) {
      laser::rigidInverse(CM, img.worldToCam);
      img.hasPose = true;
    }
    if (img.y) imgPtr = &img;
  }
  return F(h)->integrateDepth(addr<const uint16_t>(env, depth), depthRowStride,
                              addr<const uint8_t>(env, conf), confRowStride,
                              confPixelStride, K, M, imgPtr);
}

JNIEXPORT void JNICALL JNI_FN(nativeAddLaser)(JNIEnv* env, jclass, jlong h,
                                             jfloatArray pose, jfloatArray intr,
                                             jfloat u, jfloat v, jfloat z,
                                             jfloat weight, jfloat spot) {
  float K4[4], M[16];
  if (!readFloats(env, intr, K4, 4) || !readFloats(env, pose, M, 16)) return;
  laser::Intrinsics K{K4[0], K4[1], K4[2], K4[3], 0, 0};
  F(h)->addLaserSample(M, K, u, v, z, nullptr, weight, spot);
}

// Returns [center, p10, p50, p90, samples] in meters (-1 = n/a).
JNIEXPORT jfloatArray JNICALL JNI_FN(nativeConeStats)(
    JNIEnv* env, jclass, jobject depth, jint depthRowStride, jobject conf,
    jint confRowStride, jint confPixelStride, jint w, jint hgt,
    jfloatArray intr, jfloat rx, jfloat ry, jfloat halfAngle, jint minConf) {
  float K4[4] = {0, 0, 0, 0};
  readFloats(env, intr, K4, 4);
  laser::Intrinsics K{K4[0], K4[1], K4[2], K4[3], w, hgt};
  auto s = laser::coneDepthStats(addr<const uint16_t>(env, depth), depthRowStride,
                                 addr<const uint8_t>(env, conf), confRowStride,
                                 confPixelStride, K, rx, ry, halfAngle, minConf);
  float out[5] = {s.center, s.p10, s.p50, s.p90, (float)s.samples};
  jfloatArray a = env->NewFloatArray(5);
  env->SetFloatArrayRegion(a, 0, 5, out);
  return a;
}

JNIEXPORT void JNICALL JNI_FN(nativeAddScaleSample)(JNIEnv*, jclass, jlong h,
                                                   jfloat laserZ, jfloat depthZ) {
  F(h)->addScaleSample(laserZ, depthZ);
}

JNIEXPORT jfloatArray JNICALL JNI_FN(nativeScaleStats)(JNIEnv* env, jclass,
                                                      jlong h) {
  auto s = F(h)->scaleStats();
  float out[3] = {static_cast<float>(s.count), s.median, s.mad};
  jfloatArray a = env->NewFloatArray(3);
  env->SetFloatArrayRegion(a, 0, 3, out);
  return a;
}

// [blocks, previewPoints, laserPoints, depthFrames, laserAccepted, lastMs, limitHit]
JNIEXPORT jfloatArray JNICALL JNI_FN(nativeStats)(JNIEnv* env, jclass, jlong h) {
  auto s = F(h)->stats();
  float out[7] = {(float)s.blocks, (float)s.previewPoints, (float)s.laserPoints,
                  (float)s.depthFrames, (float)s.laserAccepted,
                  s.lastIntegrateMs, s.blockLimitHit ? 1.f : 0.f};
  jfloatArray a = env->NewFloatArray(7);
  env->SetFloatArrayRegion(a, 0, 7, out);
  return a;
}

// Copies up to maxPoints points from `from` into a direct buffer (16 B each); returns count.
JNIEXPORT jint JNICALL JNI_FN(nativePreviewSince)(JNIEnv* env, jclass, jlong h,
                                                 jint from, jobject out,
                                                 jint maxPoints) {
  auto* dst = addr<uint8_t>(env, out);
  if (!dst || maxPoints <= 0) return 0;
  std::vector<laser::PreviewPoint> pts;
  F(h)->previewSince(static_cast<size_t>(from), pts, static_cast<size_t>(maxPoints));
  const size_t n = std::min(pts.size(), static_cast<size_t>(maxPoints));
  std::memcpy(dst, pts.data(), n * sizeof(laser::PreviewPoint));
  return static_cast<jint>(n);
}

// Snapshots are taken under the volume lock; files are written with no lock held. Null on failure.
JNIEXPORT jintArray JNICALL JNI_FN(nativeExport)(JNIEnv* env, jclass, jlong h,
                                                jstring jdir, jfloat minWeight,
                                                jfloat scale) {
  const std::string dir = str(env, jdir);
  Fusion* f = F(h);
  laser::MeshData mesh = f->extractMesh(minWeight);
  auto cloud = f->surfacePoints(minWeight);
  auto hits = f->laserPoints();
  const float s = scale > 0.2f && scale < 5.0f ? scale : 1.0f;
  laser::scalePositions(mesh, s);
  laser::scalePositions(cloud, s);
  laser::scalePositions(hits, s);
  bool ok = true;
  if (mesh.vertexCount() > 0) {
    ok &= laser::writePlyMesh(dir + "/mesh.ply", mesh);
    ok &= laser::writeObjMesh(dir + "/mesh.obj", mesh);
  }
  if (!cloud.empty()) ok &= laser::writePlyPoints(dir + "/cloud.ply", cloud);
  if (!hits.empty()) ok &= laser::writePlyPoints(dir + "/laser.ply", hits);
  if (!ok) return nullptr;
  jint out[4] = {(jint)mesh.vertexCount(), (jint)(mesh.indices.size() / 3),
                 (jint)cloud.size(), (jint)hits.size()};
  jintArray a = env->NewIntArray(4);
  env->SetIntArrayRegion(a, 0, 4, out);
  return a;
}

// Returns Object[]{float[] positions, float[] normals, byte[] colors, int[] indices}
JNIEXPORT jobjectArray JNICALL JNI_FN(nativeLoadPly)(JNIEnv* env, jclass,
                                                    jstring jpath) {
  laser::MeshData m;
  std::string err;
  if (!laser::readPly(str(env, jpath), m, &err)) return nullptr;
  jclass objCls = env->FindClass("java/lang/Object");
  jobjectArray out = env->NewObjectArray(4, objCls, nullptr);
  jfloatArray p = env->NewFloatArray((jsize)m.positions.size());
  env->SetFloatArrayRegion(p, 0, (jsize)m.positions.size(), m.positions.data());
  jfloatArray n = env->NewFloatArray((jsize)m.normals.size());
  env->SetFloatArrayRegion(n, 0, (jsize)m.normals.size(), m.normals.data());
  jbyteArray c = env->NewByteArray((jsize)m.colors.size());
  env->SetByteArrayRegion(c, 0, (jsize)m.colors.size(),
                          reinterpret_cast<const jbyte*>(m.colors.data()));
  jintArray i = env->NewIntArray((jsize)m.indices.size());
  env->SetIntArrayRegion(i, 0, (jsize)m.indices.size(),
                         reinterpret_cast<const jint*>(m.indices.data()));
  env->SetObjectArrayElement(out, 0, p);
  env->SetObjectArrayElement(out, 1, n);
  env->SetObjectArrayElement(out, 2, c);
  env->SetObjectArrayElement(out, 3, i);
  return out;
}

}  // extern "C"

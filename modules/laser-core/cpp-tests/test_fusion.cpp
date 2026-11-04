// Host-side tests for the fusion core; run with npm run test:core.
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "fusion.h"

using namespace laser;

static int g_fail = 0;
#define CHECK(cond, ...)                                  \
  do {                                                    \
    if (!(cond)) {                                        \
      std::printf("FAIL %s:%d: ", __FILE__, __LINE__);    \
      std::printf(__VA_ARGS__);                           \
      std::printf("\n");                                  \
      ++g_fail;                                           \
    }                                                     \
  } while (0)

struct V { float x, y, z; };
static V sub(V a, V b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
static V cross(V a, V b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
static float dot(V a, V b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
static V norm(V a) { float l = std::sqrt(dot(a, a)); return {a.x / l, a.y / l, a.z / l}; }

// Camera-to-world, GL convention (camera looks down -Z), column-major.
static void lookAt(V eye, V target, float* M) {
  V z = norm(sub(eye, target));
  V x = norm(cross({0, 1, 0}, z));
  V y = cross(z, x);
  float m[16] = {x.x, x.y, x.z, 0, y.x, y.y, y.z, 0, z.x, z.y, z.z, 0, eye.x, eye.y, eye.z, 1};
  for (int i = 0; i < 16; ++i) M[i] = m[i];
}

// Ray/sphere: returns z-depth (camera units) or -1.
static float renderSphere(const float* M, const Intrinsics& K, int u, int v, V c, float r) {
  float rx = (u + 0.5f - K.cx) / K.fx, ry = (v + 0.5f - K.cy) / K.fy;
  V dcam = {rx, -ry, -1};
  V d = {M[0] * dcam.x + M[4] * dcam.y + M[8] * dcam.z,
         M[1] * dcam.x + M[5] * dcam.y + M[9] * dcam.z,
         M[2] * dcam.x + M[6] * dcam.y + M[10] * dcam.z};
  V o = {M[12], M[13], M[14]};
  V oc = sub(o, c);
  float a = dot(d, d), b = 2 * dot(oc, d), cc = dot(oc, oc) - r * r;
  float disc = b * b - 4 * a * cc;
  if (disc < 0) return -1;
  float t = (-b - std::sqrt(disc)) / (2 * a);
  return t > 0 ? t : -1;  // dcam has z=-1, so t is z-depth
}

static void testSphereReconstruction() {
  Config cfg;
  cfg.voxelSize = 0.01f;
  cfg.truncation = 0.04f;
  cfg.minConfidence = 0;
  Fusion f(cfg);
  Intrinsics K{120, 120, 80, 60, 160, 120};
  const V center{0, 0, -1};
  const float R = 0.2f;
  std::vector<uint16_t> depth(K.width * K.height);
  int frames = 0;
  for (int ring = 0; ring < 3; ++ring) {
    float elev = -0.4f + 0.4f * ring;
    for (int k = 0; k < 24; ++k) {
      float az = k * 2 * 3.14159265f / 24;
      V eye{center.x + 0.7f * std::cos(elev) * std::sin(az), center.y + 0.7f * std::sin(elev),
            center.z + 0.7f * std::cos(elev) * std::cos(az)};
      float M[16];
      lookAt(eye, center, M);
      for (int v = 0; v < K.height; ++v)
        for (int u = 0; u < K.width; ++u) {
          float z = renderSphere(M, K, u, v, center, R);
          depth[v * K.width + u] = z > 0 ? (uint16_t)std::lround(z * 1000) : 0;
        }
      f.integrateDepth(depth.data(), K.width * 2, nullptr, 0, 1, K, M, nullptr);
      ++frames;
    }
  }
  Stats s = f.stats();
  CHECK(s.depthFrames == frames, "frames %d", s.depthFrames);
  CHECK(s.blocks > 50, "blocks %d", s.blocks);
  CHECK(s.previewPoints > 1000, "preview %d", s.previewPoints);

  MeshData m = f.extractMesh(2.0f);
  const size_t nv = m.vertexCount();
  CHECK(nv > 2000, "verts %zu", nv);
  CHECK(m.indices.size() > nv * 3, "tris %zu", m.indices.size() / 3);
  double errSum = 0, errMax = 0;
  int outward = 0;
  for (size_t i = 0; i < nv; ++i) {
    V p{m.positions[3 * i], m.positions[3 * i + 1], m.positions[3 * i + 2]};
    V d = sub(p, center);
    double e = std::fabs(std::sqrt(dot(d, d)) - R);
    errSum += e;
    errMax = std::max(errMax, e);
    V n{m.normals[3 * i], m.normals[3 * i + 1], m.normals[3 * i + 2]};
    if (dot(n, d) > 0) ++outward;
  }
  double meanErr = errSum / nv;
  double outFrac = (double)outward / nv;
  std::printf("sphere: frames=%d blocks=%d verts=%zu tris=%zu meanErr=%.4fm maxErr=%.4fm outward=%.3f integrate=%.2fms\n",
              frames, s.blocks, nv, m.indices.size() / 3, meanErr, errMax, outFrac, s.lastIntegrateMs);
  CHECK(meanErr < 0.004, "mean error %.4f", meanErr);
  CHECK(errMax < 0.02, "max error %.4f", errMax);
  CHECK(outFrac > 0.97, "outward normals %.3f", outFrac);

  // Edge manifoldness: every interior edge should be shared by <= 2 triangles.
  std::unordered_map<uint64_t, int> edges;
  for (size_t i = 0; i < m.indices.size(); i += 3)
    for (int e = 0; e < 3; ++e) {
      uint64_t a = m.indices[i + e], b = m.indices[i + (e + 1) % 3];
      if (a > b) std::swap(a, b);
      edges[(a << 32) | b]++;
    }
  int over = 0, boundary = 0;
  for (auto& kv : edges) { if (kv.second > 2) ++over; if (kv.second == 1) ++boundary; }
  std::printf("sphere: edges=%zu nonManifold=%d boundary=%d\n", edges.size(), over, boundary);
  CHECK(over == 0, "non-manifold edges %d", over);
  CHECK(boundary < (int)edges.size() / 50, "too many boundary edges %d", boundary);

  const std::string ply = "sphere.ply";
  CHECK(writePlyMesh(ply, m), "write ply");
  MeshData r;
  std::string err;
  CHECK(readPly(ply, r, &err), "read ply: %s", err.c_str());
  CHECK(r.vertexCount() == nv, "rt verts %zu vs %zu", r.vertexCount(), nv);
  CHECK(r.indices == m.indices, "rt indices differ");
  CHECK(r.colors.size() == m.colors.size(), "rt colors");
  bool same = r.positions.size() == m.positions.size();
  for (size_t i = 0; same && i < r.positions.size(); ++i) same = r.positions[i] == m.positions[i];
  CHECK(same, "rt positions differ");
  CHECK(writeObjMesh("sphere.obj", m), "write obj");

  auto sp = f.surfacePoints(2.0f);
  CHECK(sp.size() > 1000, "surface points %zu", sp.size());
  CHECK(writePlyPoints("sphere_points.ply", sp), "write points");
  MeshData rp;
  CHECK(readPly("sphere_points.ply", rp, &err) && rp.vertexCount() == sp.size() && rp.indices.empty(),
        "points roundtrip");
}

static void testLaserOnly() {
  // Laser-only: a camera sweeping a wall at z=-1 lands every hit on it and fuses a patch.
  Config cfg;
  cfg.integrateDepth = false;
  cfg.voxelSize = 0.01f;
  Fusion f(cfg);
  Intrinsics K{500, 500, 320, 240, 640, 480};
  for (int i = 0; i < 60; ++i) {
    float M[16];
    V eye{-0.3f + i * 0.01f, 0.0f, 0.0f};
    lookAt(eye, {eye.x, 0, -1}, M);
    f.addLaserSample(M, K, K.cx, K.cy, 1.0f, nullptr);
  }
  auto pts = f.laserPoints();
  CHECK(pts.size() == 60, "laser points %zu", pts.size());
  double maxErr = 0;
  for (size_t i = 0; i < pts.size(); ++i) {
    maxErr = std::max(maxErr, (double)std::fabs(pts[i].z + 1.0f));
    maxErr = std::max(maxErr, (double)std::fabs(pts[i].x - (-0.3f + i * 0.01f)));
    maxErr = std::max(maxErr, (double)std::fabs(pts[i].y));
  }
  CHECK(maxErr < 1e-4, "laser point error %.6f", maxErr);
  CHECK(pts[0].kind == 1, "kind");
  Stats s = f.stats();
  CHECK(s.laserAccepted == 60 && s.depthFrames == 0, "laser stats");
  auto surf = f.surfacePoints(6.0f);
  CHECK(!surf.empty(), "laser fused surface empty");
  double zErr = 0;
  for (auto& p : surf) zErr = std::max(zErr, (double)std::fabs(p.z + 1.0f));
  std::printf("laser: hits=%zu fusedSurfaceVoxels=%zu maxZErr=%.4f\n", pts.size(), surf.size(), zErr);
  CHECK(zErr < 0.015, "laser surface z error %.4f", zErr);

  // Off-centre AF region: pixel (u,v) maps along its ray at the given z-depth.
  Fusion g(cfg);
  float M[16];
  lookAt({0, 0, 0}, {0, 0, -1}, M);
  g.addLaserSample(M, K, K.cx + 100, K.cy - 50, 2.0f, nullptr);
  auto q = g.laserPoints();
  // x = (u-cx)/fx*z = 0.4 ; y_gl = -(v-cy)/fy*z = +0.2 ; z_gl = -2
  CHECK(std::fabs(q[0].x - 0.4f) < 1e-4 && std::fabs(q[0].y - 0.2f) < 1e-4 && std::fabs(q[0].z + 2.0f) < 1e-4,
        "off-centre laser %f %f %f", q[0].x, q[0].y, q[0].z);
}

static void testScaleAndModes() {
  Fusion f(Config{});
  for (int i = 0; i < 100; ++i) f.addScaleSample(1.05f * (1 + 0.001f * (i % 7)), 1.0f);
  f.addScaleSample(3.0f, 1.0f);  // outlier
  ScaleStats s = f.scaleStats();
  CHECK(s.count == 101, "count %d", s.count);
  CHECK(std::fabs(s.median - 1.053f) < 0.005f, "median %.4f", s.median);
  CHECK(s.mad < 0.01f, "mad %.4f", s.mad);

  // Depth scale changes the fused geometry.
  Config cfg;
  cfg.minConfidence = 0;
  Fusion g(cfg);
  g.setModes(true, true, 1.1f);
  Intrinsics K{100, 100, 50, 50, 100, 100};
  std::vector<uint16_t> d(100 * 100, 1000);
  float M[16];
  lookAt({0, 0, 0}, {0, 0, -1}, M);
  for (int i = 0; i < 5; ++i) g.integrateDepth(d.data(), 200, nullptr, 0, 1, K, M, nullptr);
  auto pts = g.surfacePoints(3.0f);
  double meanZ = 0;
  for (auto& p : pts) meanZ += p.z;
  meanZ /= pts.size();
  CHECK(std::fabs(meanZ + 1.1) < 0.01, "scaled wall at %.4f", meanZ);

  Config c2;
  c2.minConfidence = 200;
  Fusion h(c2);
  std::vector<uint8_t> conf(100 * 100, 100);
  int rays = h.integrateDepth(d.data(), 200, conf.data(), 100, 1, K, M, nullptr);
  CHECK(rays == 0, "low confidence should be rejected, got %d", rays);
  std::fill(conf.begin(), conf.end(), 255);
  rays = h.integrateDepth(d.data(), 200, conf.data(), 100, 1, K, M, nullptr);
  CHECK(rays == 10000, "high confidence rays %d", rays);
}

static void testColorAndLimits() {
  // Solid-color YUV image: Y=81,U=90,V=240 is ~pure red in BT.601 full range.
  std::vector<uint8_t> Y(64 * 48, 81), U(32 * 24, 90), Vp(32 * 24, 240);
  YuvImage img{Y.data(), U.data(), Vp.data(), 64, 48, 64, 32, 1, {50, 50, 32, 24, 64, 48}};
  uint8_t r, g, b;
  CHECK(sampleYuv(img, 0, 0, 1, r, g, b), "sample");
  CHECK(r > 230 && g < 40 && b < 40, "red got %d %d %d", r, g, b);
  CHECK(!sampleYuv(img, 10, 0, 1, r, g, b), "out of bounds should fail");

  Config cfg;
  cfg.minConfidence = 0;
  cfg.maxBlocks = 10;
  Fusion f(cfg);
  Intrinsics K{100, 100, 50, 50, 100, 100};
  std::vector<uint16_t> d(100 * 100, 1000);
  float M[16];
  lookAt({0, 0, 0}, {0, 0, -1}, M);
  f.integrateDepth(d.data(), 200, nullptr, 0, 1, K, M, &img);
  Stats s = f.stats();
  CHECK(s.blocks == 10 && s.blockLimitHit, "block cap blocks=%d hit=%d", s.blocks, s.blockLimitHit);
  f.reset();
  s = f.stats();
  CHECK(s.blocks == 0 && !s.blockLimitHit && s.previewPoints == 0, "reset");
}

static void testLaserWeightOverride() {
  // Same hits, different per-profile weight: only the heavy ones pass a weight-6 surface filter.
  Config cfg;
  cfg.integrateDepth = false;
  Intrinsics K{500, 500, 320, 240, 640, 480};
  float M[16];
  lookAt({0, 0, 0}, {0, 0, -1}, M);
  Fusion light(cfg), heavy(cfg);
  light.addLaserSample(M, K, K.cx, K.cy, 0.8f, nullptr, 1.0f, 0.012f);
  heavy.addLaserSample(M, K, K.cx, K.cy, 0.8f, nullptr, 10.0f, 0.012f);
  CHECK(light.surfacePoints(6.0f).empty(), "weight-1 hit should not pass weight 6");
  CHECK(!heavy.surfacePoints(6.0f).empty(), "weight-10 hit should pass weight 6");
  // Wider spot covers more voxels.
  Fusion narrow(cfg), wide(cfg);
  narrow.addLaserSample(M, K, K.cx, K.cy, 1.0f, nullptr, 6.0f, 0.004f);
  wide.addLaserSample(M, K, K.cx, K.cy, 1.0f, nullptr, 6.0f, 0.04f);
  CHECK(wide.surfacePoints(1.0f).size() > narrow.surfacePoints(1.0f).size() * 2, "spot width %zu vs %zu",
        wide.surfacePoints(1.0f).size(), narrow.surfacePoints(1.0f).size());
}

static void testConeStats() {
  // 160x120 depth, fx=120: a 12.5 deg cone is ~27 px in radius.
  Intrinsics K{120, 120, 80, 60, 160, 120};
  std::vector<uint16_t> flat(160 * 120, 800);
  ConeStats a = coneDepthStats(flat.data(), 320, nullptr, 0, 1, K, 0, 0, 12.5f * 3.14159f / 180, 0);
  CHECK(std::fabs(a.center - 0.8f) < 1e-4 && std::fabs(a.p50 - 0.8f) < 1e-4 && a.spread() < 1e-4,
        "flat cone c=%.3f p50=%.3f spread=%.3f", a.center, a.p50, a.spread());
  CHECK(a.samples > 100, "flat samples %d", a.samples);

  // Object edge 10 px right of centre: near box (0.5 m) on the left, wall (1.2 m) on the right.
  std::vector<uint16_t> edge(160 * 120);
  for (int v = 0; v < 120; ++v)
    for (int u = 0; u < 160; ++u) edge[v * 160 + u] = u < 90 ? 500 : 1200;
  ConeStats single = coneDepthStats(edge.data(), 320, nullptr, 0, 1, K, 0, 0, 12.5f * 3.14159f / 180, 0);
  ConeStats multi = coneDepthStats(edge.data(), 320, nullptr, 0, 1, K, 0, 0, 3.0f * 3.14159f / 180, 0);
  std::printf("cone: single spread=%.2f (c=%.2f p10=%.2f p90=%.2f) multi spread=%.2f\n",
              single.spread(), single.center, single.p10, single.p90, multi.spread());
  CHECK(std::fabs(single.center - 0.5f) < 1e-3, "edge centre %.3f", single.center);
  CHECK(single.spread() > 0.5f, "single-zone cone should see the edge, spread %.3f", single.spread());
  CHECK(multi.spread() < 0.01f, "narrow multi-zone cone should not, spread %.3f", multi.spread());

  std::vector<uint8_t> conf(160 * 120, 50);
  ConeStats none = coneDepthStats(flat.data(), 320, conf.data(), 160, 1, K, 0, 0, 0.2f, 100);
  CHECK(none.center < 0 && none.samples == 0, "low confidence must yield no samples");
  ConeStats off = coneDepthStats(edge.data(), 320, nullptr, 0, 1, K, 30.0f / 120, 0, 0.02f, 0);
  CHECK(std::fabs(off.center - 1.2f) < 1e-3, "off-centre ray centre %.3f", off.center);
}

static void testPreviewColorPoseAndCap() {
  Config cfg;
  cfg.minConfidence = 0;
  Intrinsics K{100, 100, 50, 50, 100, 100};
  std::vector<uint16_t> d(100 * 100, 1000);
  float M[16];
  lookAt({0, 0, 0}, {0, 0, -1}, M);

  // Colour frame taken 0.2 m to the right: its left half is red, right half blue.
  std::vector<uint8_t> Y(64 * 48), U(32 * 24), Vp(32 * 24);
  for (int v = 0; v < 48; ++v)
    for (int u = 0; u < 64; ++u) Y[v * 64 + u] = u < 32 ? 81 : 41;
  for (int v = 0; v < 24; ++v)
    for (int u = 0; u < 32; ++u) { U[v * 32 + u] = u < 16 ? 90 : 240; Vp[v * 32 + u] = u < 16 ? 240 : 110; }
  YuvImage img{Y.data(), U.data(), Vp.data(), 64, 48, 64, 32, 1, {50, 50, 32, 24, 64, 48}};
  float C[16];
  lookAt({0.2f, 0, 0}, {0.2f, 0, -1}, C);
  rigidInverse(C, img.worldToCam);
  img.hasPose = true;

  Fusion f(cfg);
  for (int i = 0; i < 4; ++i) f.integrateDepth(d.data(), 200, nullptr, 0, 1, K, M, &img);
  int red = 0, blue = 0;
  for (auto& p : f.surfacePoints(3.0f)) {
    if (std::fabs(p.x) > 0.03f) continue;  // centre of the depth camera's view
    if (p.r > 200 && p.b < 60) ++red;
    if (p.b > 200 && p.r < 60) ++blue;
  }
  std::printf("colour pose: centre voxels red=%d blue=%d\n", red, blue);
  CHECK(red > 20 && blue == 0, "colour must come from the colour frame's pose (red=%d blue=%d)", red, blue);

  std::vector<PreviewPoint> out;
  const size_t total = f.previewSince(0, out, 10);
  CHECK(out.size() == 10 && total > 10, "previewSince max %zu of %zu", out.size(), total);
  out.clear();
  f.previewSince(total - 3, out, 10);
  CHECK(out.size() == 3, "previewSince tail %zu", out.size());

  // At the block cap, existing blocks still refine (rays continue past unallocated space).
  Config c2 = cfg;
  c2.maxBlocks = 40;
  Fusion g(c2);
  g.integrateDepth(d.data(), 200, nullptr, 0, 1, K, M, nullptr);
  const size_t before = g.surfacePoints(3.0f).size();
  for (int i = 0; i < 4; ++i) g.integrateDepth(d.data(), 200, nullptr, 0, 1, K, M, nullptr);
  const size_t after = g.surfacePoints(3.0f).size();
  CHECK(g.stats().blockLimitHit && after > before, "capped volume should keep refining (%zu -> %zu)", before, after);

  // Lock-free mode switch takes effect immediately.
  Fusion h(cfg);
  h.setModes(false, true, 1.0f);
  CHECK(h.integrateDepth(d.data(), 200, nullptr, 0, 1, K, M, nullptr) == 0, "depth off");
  h.setModes(true, true, 1.0f);
  CHECK(h.integrateDepth(d.data(), 200, nullptr, 0, 1, K, M, nullptr) > 0, "depth on");
}

static void testAsciiPly() {
  const std::string p = "quad.ply";
  FILE* f = std::fopen(p.c_str(), "w");
  if (!f) { CHECK(false, "cannot write %s", p.c_str()); return; }
  std::fprintf(f, "ply\nformat ascii 1.0\nelement vertex 4\nproperty float x\nproperty float y\nproperty float z\n"
                  "element face 1\nproperty list uchar int vertex_indices\nend_header\n"
                  "0 0 0\n1 0 0\n1 1 0\n0 1 0\n4 0 1 2 3\n");
  std::fclose(f);
  MeshData m;
  std::string err;
  CHECK(readPly(p, m, &err), "ascii read %s", err.c_str());
  CHECK(m.vertexCount() == 4 && m.indices.size() == 6, "ascii quad %zu %zu", m.vertexCount(), m.indices.size());
  CHECK(m.normals.size() == 12 && std::fabs(m.normals[2] - 1) < 1e-5, "ascii normals");
}

int main() {
  testSphereReconstruction();
  testLaserOnly();
  testScaleAndModes();
  testColorAndLimits();
  testLaserWeightOverride();
  testConeStats();
  testPreviewColorPoseAndCap();
  testAsciiPly();
  if (g_fail) {
    std::printf("%d check(s) failed\n", g_fail);
    return 1;
  }
  std::printf("all tests passed\n");
  return 0;
}

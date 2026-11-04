#include "fusion.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>

namespace laser {
namespace {

constexpr int kOff = 1 << 20;
constexpr uint64_t kMask21 = (1ull << 21) - 1;

inline uint64_t pack3(int x, int y, int z) {
  return (static_cast<uint64_t>(x + kOff) & kMask21) << 42 |
         (static_cast<uint64_t>(y + kOff) & kMask21) << 21 |
         (static_cast<uint64_t>(z + kOff) & kMask21);
}

inline void unpack3(uint64_t k, int& x, int& y, int& z) {
  x = static_cast<int>((k >> 42) & kMask21) - kOff;
  y = static_cast<int>((k >> 21) & kMask21) - kOff;
  z = static_cast<int>(k & kMask21) - kOff;
}

inline int floorDiv8(int a) { return a >> 3; }  // arithmetic shift
inline int mod8(int a) { return a & 7; }
inline int localIndex(int lx, int ly, int lz) {
  return (lz * kBlockDim + ly) * kBlockDim + lx;
}

inline void xform(const float* M, float x, float y, float z, float& ox,
                  float& oy, float& oz) {
  ox = M[0] * x + M[4] * y + M[8] * z + M[12];
  oy = M[1] * x + M[5] * y + M[9] * z + M[13];
  oz = M[2] * x + M[6] * y + M[10] * z + M[14];
}

}  // namespace

void rigidInverse(const float* M, float* Mi) {
  Mi[0] = M[0]; Mi[1] = M[4]; Mi[2] = M[8];  Mi[3] = 0;
  Mi[4] = M[1]; Mi[5] = M[5]; Mi[6] = M[9];  Mi[7] = 0;
  Mi[8] = M[2]; Mi[9] = M[6]; Mi[10] = M[10]; Mi[11] = 0;
  Mi[12] = -(M[0] * M[12] + M[1] * M[13] + M[2] * M[14]);
  Mi[13] = -(M[4] * M[12] + M[5] * M[13] + M[6] * M[14]);
  Mi[14] = -(M[8] * M[12] + M[9] * M[13] + M[10] * M[14]);
  Mi[15] = 1;
}

namespace {

inline uint8_t clamp8(float v) {
  return static_cast<uint8_t>(v < 0 ? 0 : (v > 255 ? 255 : v + 0.5f));
}

constexpr uint8_t kFlagEmitted = 1;
constexpr uint8_t kFlagColor = 2;

}  // namespace

bool sampleYuv(const YuvImage& img, float x, float y, float z, uint8_t& r,
               uint8_t& g, uint8_t& b) {
  if (!img.y || z <= 1e-4f) return false;
  const float u = img.K.fx * x / z + img.K.cx;
  const float v = img.K.fy * y / z + img.K.cy;
  const int iu = static_cast<int>(u), iv = static_cast<int>(v);
  if (iu < 0 || iv < 0 || iu >= img.width || iv >= img.height) return false;
  const float Y = img.y[iv * img.yRowStride + iu];
  float U = 128, V = 128;
  if (img.u && img.v) {
    const int ci = (iv >> 1) * img.uvRowStride + (iu >> 1) * img.uvPixelStride;
    U = img.u[ci];
    V = img.v[ci];
  }
  r = clamp8(Y + 1.402f * (V - 128));
  g = clamp8(Y - 0.344136f * (U - 128) - 0.714136f * (V - 128));
  b = clamp8(Y + 1.772f * (U - 128));
  return true;
}

ConeStats coneDepthStats(const uint16_t* depth, int rowStride,
                         const uint8_t* conf, int confRow, int confPix,
                         const Intrinsics& K, float rx, float ry,
                         float halfAngle, int minConf) {
  ConeStats s;
  if (!depth || K.fx <= 0 || K.width <= 0 || K.height <= 0) return s;
  auto at = [&](int u, int v) -> float {
    if (u < 0 || v < 0 || u >= K.width || v >= K.height) return -1.0f;
    if (conf && conf[v * confRow + u * confPix] < minConf) return -1.0f;
    const auto* row = reinterpret_cast<const uint16_t*>(
        reinterpret_cast<const uint8_t*>(depth) + v * rowStride);
    return row[u] ? row[u] * 0.001f : -1.0f;
  };
  const int cu = static_cast<int>(std::floor(K.cx + rx * K.fx));
  const int cv = static_cast<int>(std::floor(K.cy + ry * K.fy));
  std::vector<float> c;
  for (int dv = -2; dv <= 2; ++dv)
    for (int du = -2; du <= 2; ++du) {
      const float d = at(cu + du, cv + dv);
      if (d > 0) c.push_back(d);
    }
  if (c.size() >= 5) {
    std::nth_element(c.begin(), c.begin() + c.size() / 2, c.end());
    s.center = c[c.size() / 2];
  }
  const float rad = std::tan(halfAngle) * K.fx;
  const int step = std::max(1, static_cast<int>(rad / 8.0f));
  const int r = static_cast<int>(rad);
  std::vector<float> v;
  for (int dv = -r; dv <= r; dv += step)
    for (int du = -r; du <= r; du += step) {
      if (du * du + dv * dv > r * r) continue;
      const float d = at(cu + du, cv + dv);
      if (d > 0) v.push_back(d);
    }
  s.samples = static_cast<int>(v.size());
  if (v.size() < 8) return s;
  std::sort(v.begin(), v.end());
  s.p10 = v[v.size() / 10];
  s.p50 = v[v.size() / 2];
  s.p90 = v[(v.size() * 9) / 10];
  return s;
}

Fusion::Fusion(const Config& cfg)
    : cfg_(cfg),
      modeDepth_(cfg.integrateDepth),
      modeLaser_(cfg.integrateLaser),
      depthScale_(cfg.depthScale) {}

void scalePositions(MeshData& m, float s) {
  if (s == 1.0f) return;
  for (auto& p : m.positions) p *= s;
}

void scalePositions(std::vector<PreviewPoint>& pts, float s) {
  if (s == 1.0f) return;
  for (auto& p : pts) { p.x *= s; p.y *= s; p.z *= s; }
}

void Fusion::flushPreview() {
  if (pending_.empty()) return;
  std::lock_guard<std::mutex> l(previewMu_);
  preview_.insert(preview_.end(), pending_.begin(), pending_.end());
  pending_.clear();
  aPreview_ = static_cast<int>(preview_.size());
}

void Fusion::reset() {
  std::lock_guard<std::mutex> l(mu_);
  blocks_.clear();
  blockKeys_.clear();
  pending_.clear();
  laser_.clear();
  cacheKey_ = ~0ull;
  cacheBlock_ = nullptr;
  {
    std::lock_guard<std::mutex> p(previewMu_);
    preview_.clear();
  }
  {
    std::lock_guard<std::mutex> s(scaleMu_);
    scaleRatios_.clear();
    scaleHead_ = 0;
  }
  aBlocks_ = 0; aPreview_ = 0; aLaser_ = 0; aDepthFrames_ = 0; aLaserAccepted_ = 0;
  aLastMs_ = 0.0f;
  aLimit_ = false;
}

Config Fusion::config() const {
  Config c = cfg_;  // immutable after construction except the atomics below
  c.integrateDepth = modeDepth_;
  c.integrateLaser = modeLaser_;
  c.depthScale = depthScale_;
  return c;
}

void Fusion::setModes(bool integrateDepth, bool integrateLaser,
                      float depthScale) {
  modeDepth_ = integrateDepth;
  modeLaser_ = integrateLaser;
  depthScale_ = depthScale > 0.2f && depthScale < 5.0f ? depthScale : 1.0f;
}

Voxel* Fusion::voxelAt(int ix, int iy, int iz, bool create) {
  const int bx = floorDiv8(ix), by = floorDiv8(iy), bz = floorDiv8(iz);
  const uint64_t key = pack3(bx, by, bz);
  Block* blk = nullptr;
  if (key == cacheKey_) {
    blk = cacheBlock_;
  } else {
    auto it = blocks_.find(key);
    if (it != blocks_.end()) {
      blk = it->second.get();
    } else {
      if (!create) return nullptr;
      if (static_cast<int>(blocks_.size()) >= cfg_.maxBlocks) {
        aLimit_ = true;
        return nullptr;
      }
      auto nb = std::make_unique<Block>();
      blk = nb.get();
      blocks_.emplace(key, std::move(nb));
      blockKeys_.push_back(key);
      aBlocks_ = static_cast<int>(blocks_.size());
    }
    cacheKey_ = key;
    cacheBlock_ = blk;
  }
  return &blk->v[localIndex(mod8(ix), mod8(iy), mod8(iz))];
}

const Voxel* Fusion::voxelAtConst(int ix, int iy, int iz) const {
  auto it = blocks_.find(pack3(floorDiv8(ix), floorDiv8(iy), floorDiv8(iz)));
  if (it == blocks_.end()) return nullptr;
  return &it->second->v[localIndex(mod8(ix), mod8(iy), mod8(iz))];
}

void Fusion::maybeEmitPreview(Voxel& vox, int ix, int iy, int iz) {
  if ((vox.flags & kFlagEmitted) || vox.weight < cfg_.previewMinWeight ||
      std::fabs(vox.tsdf) > 0.3f)
    return;
  vox.flags |= kFlagEmitted;
  const float vs = cfg_.voxelSize;
  PreviewPoint p{(ix + 0.5f) * vs, (iy + 0.5f) * vs, (iz + 0.5f) * vs,
                 160, 170, 180, 0};
  if (vox.flags & kFlagColor) { p.r = vox.r; p.g = vox.g; p.b = vox.b; }
  pending_.push_back(p);
}

void Fusion::integrateRay(const float* M, const float* Minv, float rx,
                          float ry, float d, float w, const YuvImage* color,
                          std::unordered_set<uint64_t>* visited) {
  const float vs = cfg_.voxelSize;
  const float tr = cfg_.truncation;
  const float norm = std::sqrt(rx * rx + ry * ry + 1.0f);
  const float stepZ = vs * 0.7f / norm;
  const float z0 = std::max(d - tr, cfg_.minDepth * 0.5f);
  const float z1 = d + tr;
  uint64_t lastKey = ~0ull;
  for (float z = z0; z <= z1; z += stepZ) {
    float wx, wy, wz;
    xform(M, rx * z, -ry * z, -z, wx, wy, wz);
    const int ix = static_cast<int>(std::floor(wx / vs));
    const int iy = static_cast<int>(std::floor(wy / vs));
    const int iz = static_cast<int>(std::floor(wz / vs));
    const uint64_t key = pack3(ix, iy, iz);
    if (key == lastKey) continue;
    lastKey = key;
    if (visited && !visited->insert(key).second) continue;

    // Projective SDF evaluated at the voxel centre.
    float cx, cy, cz;
    xform(Minv, (ix + 0.5f) * vs, (iy + 0.5f) * vs, (iz + 0.5f) * vs, cx, cy,
          cz);
    const float zc = -cz;
    const float sdf = d - zc;
    if (sdf < -tr) continue;

    Voxel* vox = voxelAt(ix, iy, iz, true);
    if (!vox) continue;  // block budget exhausted; keep refining existing blocks
    const float t = std::min(1.0f, sdf / tr);
    const float wOld = vox->weight;
    const float wNew = wOld + w;
    vox->tsdf = wOld <= 0 ? t : (vox->tsdf * wOld + t * w) / wNew;
    vox->weight = std::min(wNew, cfg_.maxWeight);

    if (color && std::fabs(sdf) < 1.5f * vs) {
      uint8_t r, g, b;
      float px = cx, py = cy, pz = cz;
      if (color->hasPose)
        xform(color->worldToCam, (ix + 0.5f) * vs, (iy + 0.5f) * vs, (iz + 0.5f) * vs, px, py, pz);
      if (sampleYuv(*color, px, -py, -pz, r, g, b)) {
        if (!(vox->flags & kFlagColor)) {
          vox->r = r; vox->g = g; vox->b = b;
          vox->flags |= kFlagColor;
        } else {
          const float a = w / wNew;
          vox->r = clamp8(vox->r + a * (r - vox->r));
          vox->g = clamp8(vox->g + a * (g - vox->g));
          vox->b = clamp8(vox->b + a * (b - vox->b));
        }
      }
    }
    maybeEmitPreview(*vox, ix, iy, iz);
  }
}

int Fusion::integrateDepth(const uint16_t* depth, int depthRowStrideBytes,
                           const uint8_t* conf, int confRowStrideBytes,
                           int confPixelStride, const Intrinsics& K,
                           const float* camToWorld, const YuvImage* color) {
  std::lock_guard<std::mutex> l(mu_);
  if (!modeDepth_ || !depth || K.fx <= 0 || K.fy <= 0) return 0;
  const float depthScale = depthScale_;
  const auto t0 = std::chrono::steady_clock::now();
  float Minv[16];
  rigidInverse(camToWorld, Minv);
  const int s = std::max(1, cfg_.depthStride);
  int rays = 0;
  for (int v = 0; v < K.height; v += s) {
    const auto* row = reinterpret_cast<const uint16_t*>(
        reinterpret_cast<const uint8_t*>(depth) + v * depthRowStrideBytes);
    for (int u = 0; u < K.width; u += s) {
      const uint16_t raw = row[u];
      if (raw == 0) continue;
      if (conf &&
          conf[v * confRowStrideBytes + u * confPixelStride] < cfg_.minConfidence)
        continue;
      const float d = raw * 0.001f * depthScale;
      if (d < cfg_.minDepth || d > cfg_.maxDepth) continue;
      const float rx = (u + 0.5f - K.cx) / K.fx;
      const float ry = (v + 0.5f - K.cy) / K.fy;
      integrateRay(camToWorld, Minv, rx, ry, d, cfg_.depthWeight, color);
      ++rays;
    }
  }
  flushPreview();
  aDepthFrames_++;
  aLastMs_ = std::chrono::duration<float, std::milli>(std::chrono::steady_clock::now() - t0).count();
  return rays;
}

void Fusion::addLaserSample(const float* M, const Intrinsics& K, float u,
                            float v, float z, const YuvImage* color,
                            float weight, float spotHalfAngle) {
  std::lock_guard<std::mutex> l(mu_);
  if (K.fx <= 0 || z <= 0) return;
  const float rx = (u - K.cx) / K.fx;
  const float ry = (v - K.cy) / K.fy;
  float wx, wy, wz;
  xform(M, rx * z, -ry * z, -z, wx, wy, wz);
  PreviewPoint p{wx, wy, wz, 255, 40, 40, 1};
  if (color) {
    uint8_t r, g, b;
    if (sampleYuv(*color, rx * z, ry * z, z, r, g, b)) { p.r = r; p.g = g; p.b = b; }
  }
  laser_.push_back(p);
  pending_.push_back(p);
  aLaser_ = static_cast<int>(laser_.size());
  aLaserAccepted_++;

  if (modeLaser_) {
    float Minv[16];
    rigidInverse(M, Minv);
    const float a = spotHalfAngle > 0 ? spotHalfAngle : cfg_.laserSpotHalfAngle;
    const float w = weight > 0 ? weight : cfg_.laserWeight;
    std::unordered_set<uint64_t> visited;
    for (int j = -2; j <= 2; ++j)
      for (int i = -2; i <= 2; ++i)
        integrateRay(M, Minv, rx + i * a * 0.5f, ry + j * a * 0.5f, z, w, color, &visited);
  }
  flushPreview();
}

void Fusion::addScaleSample(float laserZ, float depthZ) {
  if (laserZ <= 0 || depthZ <= 0) return;
  std::lock_guard<std::mutex> l(scaleMu_);
  constexpr size_t kCap = 512;
  const float r = laserZ / depthZ;
  if (scaleRatios_.size() < kCap) {
    scaleRatios_.push_back(r);
  } else {
    scaleRatios_[scaleHead_] = r;
    scaleHead_ = (scaleHead_ + 1) % kCap;
  }
}

ScaleStats Fusion::scaleStats() const {
  std::vector<float> v;
  {
    std::lock_guard<std::mutex> l(scaleMu_);
    v = scaleRatios_;
  }
  ScaleStats s;
  s.count = static_cast<int>(v.size());
  if (v.empty()) return s;
  auto mid = v.begin() + v.size() / 2;
  std::nth_element(v.begin(), mid, v.end());
  s.median = *mid;
  for (auto& x : v) x = std::fabs(x - s.median);
  std::nth_element(v.begin(), mid, v.end());
  s.mad = *mid;
  return s;
}

size_t Fusion::previewSince(size_t from, std::vector<PreviewPoint>& out,
                            size_t maxCount) const {
  std::lock_guard<std::mutex> l(previewMu_);
  if (from < preview_.size()) {
    const size_t n = std::min(maxCount, preview_.size() - from);
    out.insert(out.end(), preview_.begin() + from, preview_.begin() + from + n);
  }
  return preview_.size();
}

Stats Fusion::stats() const {
  Stats s;
  s.blocks = aBlocks_;
  s.previewPoints = aPreview_;
  s.laserPoints = aLaser_;
  s.depthFrames = aDepthFrames_;
  s.laserAccepted = aLaserAccepted_;
  s.lastIntegrateMs = aLastMs_;
  s.blockLimitHit = aLimit_;
  return s;
}

std::vector<PreviewPoint> Fusion::laserPoints() const {
  std::lock_guard<std::mutex> l(mu_);
  return laser_;
}

std::vector<PreviewPoint> Fusion::surfacePoints(float minWeight) const {
  std::lock_guard<std::mutex> l(mu_);
  std::vector<PreviewPoint> out;
  const float vs = cfg_.voxelSize;
  for (uint64_t key : blockKeys_) {
    int bx, by, bz;
    unpack3(key, bx, by, bz);
    const Block& blk = *blocks_.at(key);
    for (int lz = 0; lz < kBlockDim; ++lz)
      for (int ly = 0; ly < kBlockDim; ++ly)
        for (int lx = 0; lx < kBlockDim; ++lx) {
          const Voxel& vx = blk.v[localIndex(lx, ly, lz)];
          if (vx.weight < minWeight || std::fabs(vx.tsdf) > 0.25f) continue;
          PreviewPoint p{(bx * 8 + lx + 0.5f) * vs, (by * 8 + ly + 0.5f) * vs,
                         (bz * 8 + lz + 0.5f) * vs, 160, 170, 180, 0};
          if (vx.flags & kFlagColor) { p.r = vx.r; p.g = vx.g; p.b = vx.b; }
          out.push_back(p);
        }
  }
  return out;
}

// Naive surface nets; winding is fixed so normals point from inside (tsdf < 0) to free space.
MeshData Fusion::extractMesh(float minWeight) const {
  std::lock_guard<std::mutex> l(mu_);
  MeshData mesh;
  const float vs = cfg_.voxelSize;

  // Lookup with a one-block cache (extraction walks blocks spatially).
  uint64_t ck = ~0ull;
  const Block* cb = nullptr;
  auto get = [&](int ix, int iy, int iz) -> const Voxel* {
    const uint64_t k = pack3(floorDiv8(ix), floorDiv8(iy), floorDiv8(iz));
    if (k != ck) {
      auto it = blocks_.find(k);
      ck = k;
      cb = it == blocks_.end() ? nullptr : it->second.get();
    }
    if (!cb) return nullptr;
    const Voxel* v = &cb->v[localIndex(mod8(ix), mod8(iy), mod8(iz))];
    return v->weight >= minWeight ? v : nullptr;
  };

  static const int kEdges[12][2] = {{0, 1}, {2, 3}, {4, 5}, {6, 7},
                                    {0, 2}, {1, 3}, {4, 6}, {5, 7},
                                    {0, 4}, {1, 5}, {2, 6}, {3, 7}};
  std::unordered_map<uint64_t, uint32_t> vertIndex;
  vertIndex.reserve(blockKeys_.size() * 64);

  for (uint64_t key : blockKeys_) {
    int bx, by, bz;
    unpack3(key, bx, by, bz);
    for (int lz = 0; lz < kBlockDim; ++lz)
      for (int ly = 0; ly < kBlockDim; ++ly)
        for (int lx = 0; lx < kBlockDim; ++lx) {
          const int ix = bx * 8 + lx, iy = by * 8 + ly, iz = bz * 8 + lz;
          const Voxel* c[8];
          bool ok = true;
          int mask = 0;
          for (int i = 0; i < 8 && ok; ++i) {
            c[i] = get(ix + (i & 1), iy + ((i >> 1) & 1), iz + ((i >> 2) & 1));
            if (!c[i]) ok = false;
            else if (c[i]->tsdf < 0) mask |= 1 << i;
          }
          if (!ok || mask == 0 || mask == 255) continue;
          float px = 0, py = 0, pz = 0;
          int n = 0;
          for (const auto& e : kEdges) {
            const Voxel* a = c[e[0]];
            const Voxel* b = c[e[1]];
            if ((a->tsdf < 0) == (b->tsdf < 0)) continue;
            const float t = a->tsdf / (a->tsdf - b->tsdf);
            const float ax = (e[0] & 1), ay = ((e[0] >> 1) & 1), az = ((e[0] >> 2) & 1);
            const float bxf = (e[1] & 1), byf = ((e[1] >> 1) & 1), bzf = ((e[1] >> 2) & 1);
            px += ax + t * (bxf - ax);
            py += ay + t * (byf - ay);
            pz += az + t * (bzf - az);
            ++n;
          }
          px /= n; py /= n; pz /= n;
          int cr = 0, cg = 0, cbl = 0, cn = 0;
          for (int i = 0; i < 8; ++i)
            if (c[i]->flags & kFlagColor) { cr += c[i]->r; cg += c[i]->g; cbl += c[i]->b; ++cn; }
          vertIndex.emplace(pack3(ix, iy, iz),
                            static_cast<uint32_t>(mesh.vertexCount()));
          mesh.positions.push_back((ix + 0.5f + px) * vs);
          mesh.positions.push_back((iy + 0.5f + py) * vs);
          mesh.positions.push_back((iz + 0.5f + pz) * vs);
          if (cn) {
            mesh.colors.push_back(static_cast<uint8_t>(cr / cn));
            mesh.colors.push_back(static_cast<uint8_t>(cg / cn));
            mesh.colors.push_back(static_cast<uint8_t>(cbl / cn));
          } else {
            mesh.colors.push_back(170); mesh.colors.push_back(175); mesh.colors.push_back(185);
          }
        }
  }

  auto vid = [&](int x, int y, int z, uint32_t& out) {
    auto it = vertIndex.find(pack3(x, y, z));
    if (it == vertIndex.end()) return false;
    out = it->second;
    return true;
  };
  const float* P = nullptr;
  for (uint64_t key : blockKeys_) {
    int bx, by, bz;
    unpack3(key, bx, by, bz);
    for (int lz = 0; lz < kBlockDim; ++lz)
      for (int ly = 0; ly < kBlockDim; ++ly)
        for (int lx = 0; lx < kBlockDim; ++lx) {
          const int p[3] = {bx * 8 + lx, by * 8 + ly, bz * 8 + lz};
          const Voxel* vp = get(p[0], p[1], p[2]);
          if (!vp) continue;
          for (int a = 0; a < 3; ++a) {
            int q[3] = {p[0], p[1], p[2]};
            q[a] += 1;
            const Voxel* vq = get(q[0], q[1], q[2]);
            if (!vq) continue;
            const bool sp = vp->tsdf < 0, sq = vq->tsdf < 0;
            if (sp == sq) continue;
            const int b = (a + 1) % 3, c = (a + 2) % 3;
            static const int kOffs[4][2] = {{0, 0}, {-1, 0}, {-1, -1}, {0, -1}};
            uint32_t v[4];
            bool ok = true;
            for (int k = 0; k < 4 && ok; ++k) {
              int cc[3] = {p[0], p[1], p[2]};
              cc[b] += kOffs[k][0];
              cc[c] += kOffs[k][1];
              ok = vid(cc[0], cc[1], cc[2], v[k]);
            }
            if (!ok) continue;
            P = mesh.positions.data();
            const float* p0 = P + 3 * v[0];
            const float* p1 = P + 3 * v[1];
            const float* p2 = P + 3 * v[2];
            const float e1[3] = {p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]};
            const float e2[3] = {p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2]};
            const float nrm[3] = {e1[1] * e2[2] - e1[2] * e2[1],
                                  e1[2] * e2[0] - e1[0] * e2[2],
                                  e1[0] * e2[1] - e1[1] * e2[0]};
            // Outward = towards positive tsdf.
            const float dir = sp ? 1.0f : -1.0f;
            const bool flip = nrm[a] * dir < 0;
            if (!flip) {
              mesh.indices.insert(mesh.indices.end(), {v[0], v[1], v[2], v[0], v[2], v[3]});
            } else {
              mesh.indices.insert(mesh.indices.end(), {v[0], v[2], v[1], v[0], v[3], v[2]});
            }
          }
        }
  }
  computeNormals(mesh);
  return mesh;
}

void computeNormals(MeshData& m) {
  const size_t n = m.vertexCount();
  m.normals.assign(n * 3, 0.0f);
  const float* P = m.positions.data();
  for (size_t i = 0; i + 2 < m.indices.size(); i += 3) {
    const uint32_t a = m.indices[i], b = m.indices[i + 1], c = m.indices[i + 2];
    if (a >= n || b >= n || c >= n) continue;
    const float e1[3] = {P[3 * b] - P[3 * a], P[3 * b + 1] - P[3 * a + 1], P[3 * b + 2] - P[3 * a + 2]};
    const float e2[3] = {P[3 * c] - P[3 * a], P[3 * c + 1] - P[3 * a + 1], P[3 * c + 2] - P[3 * a + 2]};
    const float nx = e1[1] * e2[2] - e1[2] * e2[1];
    const float ny = e1[2] * e2[0] - e1[0] * e2[2];
    const float nz = e1[0] * e2[1] - e1[1] * e2[0];
    for (uint32_t idx : {a, b, c}) {
      m.normals[3 * idx] += nx;
      m.normals[3 * idx + 1] += ny;
      m.normals[3 * idx + 2] += nz;
    }
  }
  for (size_t i = 0; i < n; ++i) {
    float* v = &m.normals[3 * i];
    const float len = std::sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    if (len > 1e-12f) { v[0] /= len; v[1] /= len; v[2] /= len; }
    else { v[0] = 0; v[1] = 1; v[2] = 0; }
  }
}

bool writePlyMesh(const std::string& path, const MeshData& m) {
  FILE* f = std::fopen(path.c_str(), "wb");
  if (!f) return false;
  const size_t nv = m.vertexCount(), nf = m.indices.size() / 3;
  const bool hasN = m.normals.size() == nv * 3;
  const bool hasC = m.colors.size() == nv * 3;
  std::fprintf(f,
               "ply\nformat binary_little_endian 1.0\ncomment LaserScan\n"
               "element vertex %zu\nproperty float x\nproperty float y\n"
               "property float z\n",
               nv);
  if (hasN) std::fprintf(f, "property float nx\nproperty float ny\nproperty float nz\n");
  if (hasC) std::fprintf(f, "property uchar red\nproperty uchar green\nproperty uchar blue\n");
  if (nf) std::fprintf(f, "element face %zu\nproperty list uchar int vertex_indices\n", nf);
  std::fprintf(f, "end_header\n");
  std::vector<uint8_t> buf;
  buf.reserve(nv * 27);
  for (size_t i = 0; i < nv; ++i) {
    const auto put = [&](const void* p, size_t s) {
      const auto* b = static_cast<const uint8_t*>(p);
      buf.insert(buf.end(), b, b + s);
    };
    put(&m.positions[3 * i], 12);
    if (hasN) put(&m.normals[3 * i], 12);
    if (hasC) put(&m.colors[3 * i], 3);
  }
  std::fwrite(buf.data(), 1, buf.size(), f);
  buf.clear();
  for (size_t i = 0; i < nf; ++i) {
    buf.push_back(3);
    const auto* b = reinterpret_cast<const uint8_t*>(&m.indices[3 * i]);
    buf.insert(buf.end(), b, b + 12);  // uint32 written as int
  }
  std::fwrite(buf.data(), 1, buf.size(), f);
  return std::fclose(f) == 0;
}

bool writeObjMesh(const std::string& path, const MeshData& m) {
  FILE* f = std::fopen(path.c_str(), "w");
  if (!f) return false;
  const size_t nv = m.vertexCount();
  const bool hasN = m.normals.size() == nv * 3;
  const bool hasC = m.colors.size() == nv * 3;
  std::fprintf(f, "# LaserScan mesh (vertex colors as v x y z r g b)\n");
  for (size_t i = 0; i < nv; ++i) {
    if (hasC)
      std::fprintf(f, "v %.5f %.5f %.5f %.4f %.4f %.4f\n", m.positions[3 * i],
                   m.positions[3 * i + 1], m.positions[3 * i + 2],
                   m.colors[3 * i] / 255.0f, m.colors[3 * i + 1] / 255.0f,
                   m.colors[3 * i + 2] / 255.0f);
    else
      std::fprintf(f, "v %.5f %.5f %.5f\n", m.positions[3 * i],
                   m.positions[3 * i + 1], m.positions[3 * i + 2]);
  }
  if (hasN)
    for (size_t i = 0; i < nv; ++i)
      std::fprintf(f, "vn %.4f %.4f %.4f\n", m.normals[3 * i],
                   m.normals[3 * i + 1], m.normals[3 * i + 2]);
  for (size_t i = 0; i + 2 < m.indices.size(); i += 3) {
    const unsigned a = m.indices[i] + 1, b = m.indices[i + 1] + 1,
                   c = m.indices[i + 2] + 1;
    if (hasN) std::fprintf(f, "f %u//%u %u//%u %u//%u\n", a, a, b, b, c, c);
    else std::fprintf(f, "f %u %u %u\n", a, b, c);
  }
  return std::fclose(f) == 0;
}

bool writePlyPoints(const std::string& path, const std::vector<PreviewPoint>& pts) {
  MeshData m;
  m.positions.reserve(pts.size() * 3);
  m.colors.reserve(pts.size() * 3);
  for (const auto& p : pts) {
    m.positions.insert(m.positions.end(), {p.x, p.y, p.z});
    m.colors.insert(m.colors.end(), {p.r, p.g, p.b});
  }
  return writePlyMesh(path, m);
}

namespace {

int plyTypeSize(const std::string& t) {
  if (t == "char" || t == "uchar" || t == "int8" || t == "uint8") return 1;
  if (t == "short" || t == "ushort" || t == "int16" || t == "uint16") return 2;
  if (t == "int" || t == "uint" || t == "int32" || t == "uint32" ||
      t == "float" || t == "float32")
    return 4;
  if (t == "double" || t == "float64") return 8;
  return 0;
}

double plyRead(const uint8_t* p, const std::string& t) {
  if (t == "char" || t == "int8") return *reinterpret_cast<const int8_t*>(p);
  if (t == "uchar" || t == "uint8") return *p;
  int16_t s; uint16_t us; int32_t i; uint32_t ui; float f; double d;
  if (t == "short" || t == "int16") { std::memcpy(&s, p, 2); return s; }
  if (t == "ushort" || t == "uint16") { std::memcpy(&us, p, 2); return us; }
  if (t == "int" || t == "int32") { std::memcpy(&i, p, 4); return i; }
  if (t == "uint" || t == "uint32") { std::memcpy(&ui, p, 4); return ui; }
  if (t == "float" || t == "float32") { std::memcpy(&f, p, 4); return f; }
  std::memcpy(&d, p, 8);
  return d;
}

struct PlyProp {
  std::string name, type, countType;  // countType set => list
};
struct PlyElement {
  std::string name;
  size_t count = 0;
  std::vector<PlyProp> props;
};

}  // namespace

bool readPly(const std::string& path, MeshData& out, std::string* err) {
  auto fail = [&](const char* msg) {
    if (err) *err = msg;
    return false;
  };
  std::ifstream in(path, std::ios::binary);
  if (!in) return fail("cannot open file");
  std::string line;
  std::getline(in, line);
  if (line.rfind("ply", 0) != 0) return fail("not a PLY file");
  bool binary = false, ascii = false;
  std::vector<PlyElement> elems;
  while (std::getline(in, line)) {
    if (!line.empty() && line.back() == '\r') line.pop_back();
    std::istringstream ss(line);
    std::string tok;
    ss >> tok;
    if (tok == "format") {
      std::string fmt;
      ss >> fmt;
      binary = fmt == "binary_little_endian";
      ascii = fmt == "ascii";
      if (!binary && !ascii) return fail("unsupported PLY format");
    } else if (tok == "element") {
      PlyElement e;
      ss >> e.name >> e.count;
      elems.push_back(e);
    } else if (tok == "property") {
      if (elems.empty()) return fail("property before element");
      PlyProp p;
      std::string t;
      ss >> t;
      if (t == "list") {
        ss >> p.countType >> p.type >> p.name;
      } else {
        p.type = t;
        ss >> p.name;
      }
      elems.back().props.push_back(p);
    } else if (tok == "end_header") {
      break;
    }
  }
  out = MeshData{};
  for (const auto& e : elems) {
    const bool isVertex = e.name == "vertex";
    const bool isFace = e.name == "face";
    int ix = -1, iy = -1, iz = -1, inx = -1, iny = -1, inz = -1, ir = -1, ig = -1, ib = -1;
    for (size_t i = 0; i < e.props.size(); ++i) {
      const auto& n = e.props[i].name;
      if (n == "x") ix = (int)i; else if (n == "y") iy = (int)i; else if (n == "z") iz = (int)i;
      else if (n == "nx") inx = (int)i; else if (n == "ny") iny = (int)i; else if (n == "nz") inz = (int)i;
      else if (n == "red" || n == "r") ir = (int)i;
      else if (n == "green" || n == "g") ig = (int)i;
      else if (n == "blue" || n == "b") ib = (int)i;
    }
    if (isVertex && (ix < 0 || iy < 0 || iz < 0)) return fail("vertex lacks xyz");
    const bool hasN = inx >= 0 && iny >= 0 && inz >= 0;
    const bool hasC = ir >= 0 && ig >= 0 && ib >= 0;
    std::vector<double> vals(e.props.size());
    for (size_t r = 0; r < e.count; ++r) {
      std::vector<uint32_t> faceIdx;
      for (size_t i = 0; i < e.props.size(); ++i) {
        const auto& p = e.props[i];
        if (!p.countType.empty()) {
          size_t cnt = 0;
          if (binary) {
            uint8_t b[8];
            const int cs = plyTypeSize(p.countType), vsz = plyTypeSize(p.type);
            if (!cs || !vsz || !in.read(reinterpret_cast<char*>(b), cs)) return fail("truncated list");
            cnt = static_cast<size_t>(plyRead(b, p.countType));
            for (size_t k = 0; k < cnt; ++k) {
              if (!in.read(reinterpret_cast<char*>(b), vsz)) return fail("truncated list");
              faceIdx.push_back(static_cast<uint32_t>(plyRead(b, p.type)));
            }
          } else {
            double c;
            in >> c;
            cnt = static_cast<size_t>(c);
            for (size_t k = 0; k < cnt; ++k) {
              double x;
              in >> x;
              faceIdx.push_back(static_cast<uint32_t>(x));
            }
          }
          vals[i] = 0;
        } else if (binary) {
          uint8_t b[8];
          const int sz = plyTypeSize(p.type);
          if (!sz || !in.read(reinterpret_cast<char*>(b), sz)) return fail("truncated data");
          vals[i] = plyRead(b, p.type);
        } else {
          in >> vals[i];
        }
      }
      if (!in) return fail("truncated data");
      if (isVertex) {
        out.positions.insert(out.positions.end(),
                             {(float)vals[ix], (float)vals[iy], (float)vals[iz]});
        if (hasN)
          out.normals.insert(out.normals.end(),
                             {(float)vals[inx], (float)vals[iny], (float)vals[inz]});
        if (hasC) {
          const bool isFloat = e.props[ir].type.find("float") == 0 || e.props[ir].type == "double";
          const double k = isFloat ? 255.0 : 1.0;
          out.colors.insert(out.colors.end(), {clamp8((float)(vals[ir] * k)),
                                               clamp8((float)(vals[ig] * k)),
                                               clamp8((float)(vals[ib] * k))});
        }
      } else if (isFace) {
        for (size_t k = 1; k + 1 < faceIdx.size(); ++k)  // fan triangulation
          out.indices.insert(out.indices.end(), {faceIdx[0], faceIdx[k], faceIdx[k + 1]});
      }
    }
  }
  const size_t nv = out.vertexCount();
  for (uint32_t i : out.indices)
    if (i >= nv) return fail("face index out of range");
  if (!out.indices.empty() && out.normals.size() != nv * 3) computeNormals(out);
  return true;
}

}  // namespace laser

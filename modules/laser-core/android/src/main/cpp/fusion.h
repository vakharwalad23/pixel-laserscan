// ARCore world (m, +Y up); poses column-major cam-to-world looking down -Z; pixels v down.

#pragma once

#include <atomic>
#include <cstddef>
#include <limits>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace laser {

struct Vec3 {
  float x = 0, y = 0, z = 0;
};

struct Intrinsics {
  float fx = 0, fy = 0, cx = 0, cy = 0;
  int width = 0, height = 0;
};

// View over a YUV_420_888 image (planes may be interleaved, pixelStride 2).
struct YuvImage {
  const uint8_t* y = nullptr;
  const uint8_t* u = nullptr;
  const uint8_t* v = nullptr;
  int width = 0, height = 0;
  int yRowStride = 0, uvRowStride = 0, uvPixelStride = 1;
  Intrinsics K;
  // Raw depth lags the camera image, so colour is projected with the image's own pose.
  bool hasPose = false;
  float worldToCam[16] = {};
};

struct Config {
  float voxelSize = 0.01f;    // meters
  float truncation = 0.04f;   // meters (TSDF band half-width)
  float minDepth = 0.12f;     // meters
  float maxDepth = 2.5f;      // meters
  int minConfidence = 160;    // ARCore raw depth confidence, 0..255
  int depthStride = 1;        // integrate every Nth depth pixel
  float depthScale = 1.0f;    // multiplier on ARCore depth (laser-calibrated)
  float maxWeight = 64.0f;
  float depthWeight = 1.0f;
  float laserWeight = 6.0f;   // laser rays count this much per sample
  float laserSpotHalfAngle = 0.012f;  // radians; splat cone for one laser hit
  float previewMinWeight = 3.0f;
  int maxBlocks = 24000;      // ~150 MB cap
  bool integrateDepth = true; // false = laser-only mode
  bool integrateLaser = true; // false = depth-only mode
};

struct Voxel {
  float tsdf = 1.0f;
  float weight = 0.0f;
  uint8_t r = 0, g = 0, b = 0;
  uint8_t flags = 0;  // bit0: emitted to preview
};

constexpr int kBlockDim = 8;
constexpr int kBlockVoxels = kBlockDim * kBlockDim * kBlockDim;

struct Block {
  Voxel v[kBlockVoxels];
};

// 16 bytes, uploaded straight into a GL vertex buffer.
struct PreviewPoint {
  float x, y, z;
  uint8_t r, g, b, kind;  // kind: 0 = fused surface, 1 = laser hit
};

struct Stats {
  int blocks = 0;
  int previewPoints = 0;
  int laserPoints = 0;
  int depthFrames = 0;
  int laserAccepted = 0;
  float lastIntegrateMs = 0;
  bool blockLimitHit = false;
};

struct ScaleStats {
  int count = 0;
  float median = 1.0f;  // laser_z / arcore_z
  float mad = 0.0f;     // median absolute deviation of the ratio
};

struct MeshData {
  std::vector<float> positions;  // xyz
  std::vector<float> normals;    // xyz (may be empty)
  std::vector<uint8_t> colors;   // rgb
  std::vector<uint32_t> indices; // triangles (empty = point cloud)
  size_t vertexCount() const { return positions.size() / 3; }
};

class Fusion {
 public:
  explicit Fusion(const Config& cfg);

  void reset();
  Config config() const;
  // Lock-free; voxelSize/truncation changes require a new Fusion.
  void setModes(bool integrateDepth, bool integrateLaser, float depthScale);

  // depth: uint16 mm, strides in bytes, conf may be null. Returns depth rays integrated.
  int integrateDepth(const uint16_t* depth, int depthRowStrideBytes,
                     const uint8_t* conf, int confRowStrideBytes,
                     int confPixelStride, const Intrinsics& K,
                     const float* camToWorld, const YuvImage* color);

  // Metric z at pixel (u,v): always recorded, fused only in laser mode; <=0 args use Config.
  void addLaserSample(const float* camToWorld, const Intrinsics& K, float u,
                      float v, float zMeters, const YuvImage* color,
                      float weight = 0.0f, float spotHalfAngle = 0.0f);

  void addScaleSample(float laserZ, float depthZ);
  ScaleStats scaleStats() const;

  // Takes only the preview lock, so it never waits on integration or export.
  size_t previewSince(size_t from, std::vector<PreviewPoint>& out,
                      size_t maxCount = std::numeric_limits<size_t>::max()) const;
  Stats stats() const;  // lock-free

  MeshData extractMesh(float minWeight) const;            // surface nets
  std::vector<PreviewPoint> surfacePoints(float minWeight) const;
  std::vector<PreviewPoint> laserPoints() const;

 private:
  Voxel* voxelAt(int ix, int iy, int iz, bool create);
  const Voxel* voxelAtConst(int ix, int iy, int iz) const;
  // visited: voxels already hit by this sample are skipped, so a laser splat counts once.
  void integrateRay(const float* M, const float* Minv, float rx, float ry,
                    float d, float w, const YuvImage* color,
                    std::unordered_set<uint64_t>* visited = nullptr);
  void maybeEmitPreview(Voxel& vox, int ix, int iy, int iz);
  void flushPreview();  // moves pending_ into preview_ (caller holds mu_)

  // Lock order: mu_ -> previewMu_ / scaleMu_.
  mutable std::mutex mu_;        // volume, laser hits, pending preview
  mutable std::mutex previewMu_;
  mutable std::mutex scaleMu_;
  Config cfg_;
  std::atomic<bool> modeDepth_{true};
  std::atomic<bool> modeLaser_{true};
  std::atomic<float> depthScale_{1.0f};
  std::unordered_map<uint64_t, std::unique_ptr<Block>> blocks_;
  std::vector<uint64_t> blockKeys_;
  std::vector<PreviewPoint> pending_;
  std::vector<PreviewPoint> preview_;
  std::vector<PreviewPoint> laser_;
  std::vector<float> scaleRatios_;
  size_t scaleHead_ = 0;
  std::atomic<int> aBlocks_{0}, aPreview_{0}, aLaser_{0}, aDepthFrames_{0}, aLaserAccepted_{0};
  std::atomic<float> aLastMs_{0.0f};
  std::atomic<bool> aLimit_{false};
  uint64_t cacheKey_ = ~0ull;
  Block* cacheBlock_ = nullptr;
};

// Depth stats (m) in the laser cone around the AF ray; flags readings straddling an edge.
struct ConeStats {
  float center = -1.0f;  // median of the 5x5 patch on the ray
  float p10 = -1.0f, p50 = -1.0f, p90 = -1.0f;
  int samples = 0;
  float spread() const { return p50 > 0 ? (p90 - p10) / p50 : 0.0f; }
};
ConeStats coneDepthStats(const uint16_t* depth, int depthRowStrideBytes,
                         const uint8_t* conf, int confRowStrideBytes,
                         int confPixelStride, const Intrinsics& K, float rx,
                         float ry, float halfAngleRad, int minConfidence);

// Rigid (rotation + translation) inverse of a column-major 4x4.
void rigidInverse(const float* M, float* Minv);

// Uniformly scales positions about the world origin (laser-derived scale).
void scalePositions(MeshData& mesh, float s);
void scalePositions(std::vector<PreviewPoint>& pts, float s);

// Color sampling helper (exposed for tests).
bool sampleYuv(const YuvImage& img, float xCam, float yCam, float zCam,
               uint8_t& r, uint8_t& g, uint8_t& b);

void computeNormals(MeshData& mesh);

bool writePlyMesh(const std::string& path, const MeshData& mesh);
bool writeObjMesh(const std::string& path, const MeshData& mesh);
bool writePlyPoints(const std::string& path,
                    const std::vector<PreviewPoint>& pts);
// Reads binary little-endian or ASCII PLY (vertices + optional faces).
bool readPly(const std::string& path, MeshData& out, std::string* err);

}  // namespace laser

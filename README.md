# LaserScan

A small Android scanner that treats the phone's laser autofocus (LDAF) module as a hand-swept,
single-beam LiDAR, fuses it with ARCore raw depth, and exports 3D meshes and point clouds.

React Native (Expo SDK 58, new architecture) + Kotlin + a C++17 fusion core over JNI.

## The hardware

- Pixel Pro models: ST VL53L8, 8x8 multi-zone direct ToF.
- Base Pixels: ST VL53L1, single zone.

No public Android API exposes the raw zone grid. The camera HAL consumes it to drive focus, so
apps never see it directly. Without root, three things are reachable:

1. `LENS_FOCUS_DISTANCE` in every frame's metadata. On LDAF phones the laser drives this value. It
   is a metric z-distance (1 / diopters) at the AF point.
2. Vendor camera keys. Some makers publish extra metadata. The Probe tab lists anything that looks
   range-related.
3. ARCore raw depth. This is dense depth-from-motion (ML), not laser.

No open-source project was found that does this. Prior art is either real ToF cameras
(`DEPTH16` on old Huawei/Samsung phones) or ARCore depth on its own.

## Laser hardware detection

Every phone gets a laser profile at startup:

| Kind | Pixels (Google's published specs) | How readings are treated |
|---|---|---|
| Multi-zone | 8 Pro, 9 Pro / Pro XL, 10, 10 Pro / Pro XL, 11, 11 Pro / Pro XL | 3 deg cone, 0.7-1.4x agreement with ARCore, weight 6, range 3.5 m |
| Single-zone | Pixel, 2, 6 / 6 Pro, 7 / 7 Pro, 8, 9 | 12.5 deg cone with edge check, 0.8-1.25x, weight 4, range 2.5 m, strict without depth |
| Laser, zones unknown | Fold models | Single-zone rules until tested |
| None | 3-5 series, 6a, 7a, 8a, Tablet | Focus-distance hint only: weight 1.5, range 1.2 m |
| Unknown | 9a, 10a, future models, non-Pixels | Cautious single-zone rules, range 2 m |

The table is keyed on `Build.MODEL`, with `Build.DEVICE` codenames as a fallback.

**Laser test (Probe tab).** Spec tables can be wrong, so any phone can test itself:

1. Exposure drops to the sensor minimum, so phase-detect AF goes blind and only an active emitter can
   still focus.
2. Before each of 25 AF regions, the lens is parked at infinity, so every reading is fresh.
3. The result is classified:
   - **no laser:** focus can't lock in the dark;
   - **single-zone:** every region locks to the same distance;
   - **multi-zone:** near and far regions read different distances.
4. Frame brightness is reported, so you can tell whether the scene was really dark.

Saving the result overrides the table for that model. The scanner applies it the next time Scan
opens.

**Single-zone edge check.** A single-zone emitter sees a ~25 degree cone. Near a depth edge it
blends surfaces or locks onto the nearest one. For every reading, the app measures ARCore depth
spread inside that cone (p90-p10 over the median). When the cone straddles an edge (spread above
0.12), the reading is kept only if it matches ARCore's depth on the AF ray within 6%, and its
weight is reduced. With no ARCore depth to compare against, single-zone readings are accepted only
right after the lens settles.

## How it works

```
ARCore frame ──┬─ pose (VIO) ────────────────────────────┐
               ├─ image metadata: focus distance, AF/lens ─┤
               │    state ──> gate ──> laser hit (u,v,z) ──┼──> C++ TSDF (sparse voxel hash)
               └─ raw depth 16-bit + confidence + YUV ─────┘          │
                       (worker thread)                                ├─ live preview points (GL)
                                                                      └─ export: surface nets mesh
```

- **Laser hits.** A frame's focus distance is used only when:
  - the lens is stationary and AF reports focused;
  - the distance lies between the closest focus and hyperfocal (outside that window the lens
    position says nothing about range);
  - the phone hasn't moved more than 8 cm or 6 degrees since the lens last settled. Continuous AF
    holds the lens while the target stays in focus, so a reading stays valid as you sweep at a
    constant distance. Large motion with no refocus means it has probably gone stale.
  - where ARCore depth exists on the same ray, the laser agrees with it within the profile's ratio
    band, and passes the single-zone edge check above.

  Each accepted hit is the AF ray (image centre) at that z, placed in world space with the ARCore
  pose. It is splatted into the TSDF with the profile's weight (6x one depth ray on multi-zone,
  counted once per voxel), and stored separately as `laser.ply`.
- **Depth.** Raw depth frames are matched to the camera pose with the same timestamp (within one
  frame). Pixels below 160/255 confidence are dropped. Color comes from the current CPU image,
  projected with that image's own pose, because raw depth usually lags the camera.
- **Scale.** Laser hits taken right after the lens settles (moved under 2 cm or 2 degrees, no edge
  in the cone) are compared with ARCore's depth on the same ray, and the running median is shown
  live.
  - With **Scale the model to the laser** on, that ratio locks once it has 20 steady samples.
  - Fusion stays in ARCore units: laser hits are converted with the locked ratio, and the saved
    model is scaled by it. ARCore's depth and its tracking share one scale, so scaling the whole
    model is consistent, where rescaling depth alone wouldn't be.
- **Mesh.** Naive surface nets over the sparse TSDF. Winding is fixed geometrically, so normals
  point out. Host tests reconstruct a sphere at 1 cm voxels with 0.7 mm mean error, 0 non-manifold
  edges and 0 boundary edges.

## Screens

- **Scan.** Live camera with fused points (laser hits in red). A rangefinder readout shows laser mm
  next to ARCore mm and the difference between them.
  - Modes: Laser + depth, Laser only, Depth only.
  - Resolutions: 6 mm, 1 cm, 2 cm.
  - Saving writes `mesh.ply`, `mesh.obj`, `cloud.ply`, `laser.ply` and `meta.json`.
- **Probe.** Runs without ARCore. It shows the laser profile and runs the laser test. It also reports:
  - focus-distance calibration (`calibrated`, `approximate` or `uncalibrated`);
  - closest focus and hyperfocal distances;
  - AF region support and `DEPTH16` streams;
  - range-related vendor keys and sensors.

  It also runs a **zone sweep**: it steers the AF region over a 3x3, 5x5 or 8x8 grid and records the
  settled focus distance per cell. On a Pixel Pro this tests whether the HAL picks different laser
  zones per region, which would give a coarse metric depth image without root. The result can be
  saved as a point cloud.
- **Library.** Orbit viewer (drag, pinch, double-tap) for meshes, clouds and laser hits, with share
  and delete.

Output coordinates are ARCore world space: meters, +Y up. Blender: import PLY with Y up.
MeshLab: opens as-is with vertex colors.

## Build

Requirements:
- Node 20+
- JDK 17 or 21
- Android SDK: platform 37, build-tools 37.0.0, NDK 27.1.12297006, CMake 3.22.1

```bash
npm install
npx expo prebuild -p android          # regenerates android/ (don't edit it by hand)
cd android
./gradlew assembleRelease -PreactNativeArchitectures=arm64-v8a
adb install app/build/outputs/apk/release/app-release.apk
```

Or `npx expo run:android --variant release` with a phone attached. Release builds are signed
with the debug key; change that before publishing anywhere.

C++ core tests (host, with sanitizers):

```bash
cd modules/laser-core/cpp-tests
g++ -std=c++17 -O2 -fsanitize=address,undefined -I../android/src/main/cpp \
  ../android/src/main/cpp/fusion.cpp test_fusion.cpp -o t && ./t
```

## Scanning tips

- Objects at 30-120 cm, good light, textured surfaces. ARCore needs features to track, and the
  laser AF needs contrast to confirm focus.
- Move slowly. Pause briefly on areas you want laser hits on: each pause gives the lens time to
  settle.
- Laser-only mode shows exactly what the laser contributes: a sparse trail of red hits. Expect a
  few per second, not a dense cloud.

## Limitations

- The app can't read raw laser zones. One focus distance per frame is the ceiling without root, and
  the HAL blends laser with PDAF to produce it.
- Diopter resolution makes the focus distance coarse past about 1.5-2 m. Hits beyond 2.5 m are
  rejected.
- Phones without LDAF still report a focus distance from PDAF or contrast AF. It is often
  `uncalibrated`; Probe shows which.
- Fusion has a ~150 MB voxel budget (24k blocks). That covers roughly 75 m² of surface at 1 cm voxels.
  Past the cap, existing surfaces keep refining but new space isn't added.
- Saving exports while the preview keeps running; fusion pauses until the files are written.

## Code map

```
App.tsx                         tab shell (one screen mounted at a time; camera is exclusive)
src/ScanScreen.tsx              rangefinder HUD + scan controls
src/ProbeScreen.tsx             capability report + zone sweep heat grid
src/LibraryScreen.tsx           list, viewer, share
modules/laser-core/
  src/index.ts                  typed JS bindings (ScanView, ModelView, LaserProbe, LaserLibrary)
  android/src/main/cpp/
    fusion.{h,cpp}              TSDF, laser splats, cone depth stats, scale estimator, surface nets, PLY/OBJ I/O
    jni_bridge.cpp              JNI surface
  android/src/main/java/dev/vakharwalad23/lasercore/
    ScanView.kt                 ARCore session, per-profile laser gating, depth worker, GL preview
    LaserProfile.kt             model table, laser kinds, per-kind tuning, saved test results
    ProbeModule.kt              Camera2 capability dump + AF-region sweep
    LibraryModule.kt, ModelView.kt   files, sharing, GL orbit viewer
    Renderers.kt, GlUtil.kt, NativeFusion.kt
  cpp-tests/test_fusion.cpp     host tests
```

## License

MIT, see LICENSE.

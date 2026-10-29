# Neuz 3D

**Neuz 3D** is a native-first Android 3D editor and the foundation for a mobile modeling, animation, and 3D movie-making studio inspired by Blender and mobile 3D creation tools like Prisma3D.

- Android UI: Kotlin / native Android views (no HTML or WebView editor)
- Native core: C++20, JNI, CMake
- Current visible renderer: Android OpenGL ES 3.x context with PBR multi-light shading
- Build: Android Gradle Plugin + CMake, GitHub Actions debug APK artifact workflow
- Theme: Blender-like dark theme with custom color/dimension/style resources

## Implemented in the current editor foundation

- Native Android app shell and C++ JNI library with Vulkan 1.1/1.2/1.3 capability detection via dlsym (no direct Vulkan 1.1+ linkage).
- GPU/GLSL vendor/version status and OpenGL ES 3.2 runtime capability check (string-based, no GLES32 class dependency).
- Perspective 3D viewport with ground grid, red/blue/green axes, and PBR-shaded scene objects with per-vertex normals.
- Orbit camera using one-finger drag; pinch-to-zoom; reset-camera; adjustable FOV.
- 3D primitives: Cube, Sphere, Cylinder, Cone, Torus, Plane, Pyramid, Capsule, and IcoSphere. Each supports solid and wireframe rendering. All have computed per-face normals.
- Add Light and Camera scene entries (displayed as icon markers).
- **Lighting system:** up to 8 dynamic lights with type (Directional/Point/Spot), per-light color, intensity, position, enable toggle, and shadow cast flag. Lighting editor dialog with live sliders. Lights contribute to the PBR shader with distance attenuation.
- **Camera system:** scene cameras with FOV, near/far, position, look-at target, dolly/truck/orbit controls, and active camera selection. Camera editor dialog with live sliders.
- **Vertex paint system:** brush-based per-vertex color painting with adjustable radius, strength, and brush color. Proximity-based falloff. Vertex paint mode with touch painting on the viewport.
- **Skeleton and skinning system:** bone hierarchy with parent-child relationships, inverse bind matrices, rest pose, per-bone transform (position/rotation/scale), and bone matrix computation for skinning. Skeleton editor with bone selection and pose controls.
- **Modeling tools:** subdivide, extrude, bevel, loop cut, flip normals, merge operations implemented as geometry functions. Modeling tools toolbar.
- **Editor mode system:** Object, Edit, Sculpt, Vertex Paint, Weight Paint, Rig, and Pose modes (Blender-like mode switching).
- Per-object material editor: metallic, roughness, RGB base color, emissive, and wireframe toggle with live sliders.
- Full 3-axis transform controls: translate X/Y/Z, rotate Y/X/Z, scale, and reset.
- Object duplication, visibility toggle, wireframe toggle, and grid snap.
- Scene selection controls for cycling through hierarchy entries.
- In-memory undo/redo history for scene edits, bounded to 80 snapshots.
- Save/load scene objects, lights, cameras, and skeletons to local app preferences.
- Keyframe timeline with transform snapshots, ease-in-out cubic interpolation, adjustable playback speed (5-60 fps), 240-frame range, and local keyframe persistence.
- Wavefront OBJ geometry importer with normals: triangulates polygon faces, resolves negative indices, computes per-face normals.
- glTF 2.0/GLB 2.0 importer: indexed/non-indexed triangle primitives, embedded base64 buffers, normals, joints/weights for skinning, and inverse bind matrices. External .bin files, textures, and morph targets are not yet imported.
- Three-step quality selector (Low/Balanced/High).
- PBR Cook-Torrance shading with GGX distribution, Smith geometry, Fresnel Schlick, multi-light accumulation, environment ambient, emissive, and ACES tonemapping.
- Selection highlight rim lighting on the selected object.
- Blender-like dark theme with custom Android resources (colors, dimensions, styles) in `res/values/`.
- Native Vulkan loader/physical-device capability probe for versions 1.0 through 1.3 using dlsym for all Vulkan 1.1+ functions.

## Renderer selection and truthfulness

The renderer menu offers Automatic, OpenGL ES, and Vulkan 1.1/1.2/1.3. The visible viewport uses OpenGL ES. When a Vulkan version is selected, the app probes the device's Vulkan capability and displays the result, then returns to the GLES viewport. The Vulkan rendering backend is not implemented.

`GLSurfaceView.setEGLContextClientVersion(3)` requests an OpenGL ES 3.x context. The app checks the GL_VERSION string at runtime to detect ES 3.2. It does not use the GLES32 class (which can cause compilation issues on some NDK setups) — all version detection is string-based.

## CI build fix

The previous CI failure was caused by:
1. `vkGetPhysicalDeviceFeatures2` being called directly instead of via `dlsym` — this function requires Vulkan 1.1+ linkage that may not be available in all NDK toolchain configurations. All Vulkan 1.1+ functions are now loaded via `dlsym`.
2. `GLES32` class import in Kotlin — replaced with string-based version detection using `GLES20.glGetString()`.
3. GitHub Actions using deprecated Node.js 20 actions — updated to v5.

## Source status

A true Vulkan drawing backend, PBR material pipeline with image-based lighting/shadows/reflections, FBX import, glTF texture support, production-grade gizmos, full camera/shot editor, and rendered movie export are not implemented. Skeletal animation playback in the shader, weight painting, and texture painting are scaffolded but not fully wired. These must not be treated as available in the APK.

## Build locally in Android Studio

1. Open the repository root in a recent Android Studio.
2. Install Android SDK Platform 35, build tools 35.0.0, NDK 27.2.12479018, and CMake 3.22.1.
3. Sync Gradle and run the `app` configuration on an Android device or emulator.
4. Or use the GitHub Actions workflow `.github/workflows/android.yml`.

## Verification status

The source and CI workflow have been committed. A successful GitHub Actions run, APK artifact, and physical-device smoke test have not been verified yet.

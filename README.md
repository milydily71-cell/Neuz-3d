# Neuz 3D

**Neuz 3D** is a native-first Android 3D editor and the foundation for a mobile modeling, animation, and 3D movie-making studio inspired by mobile 3D creation tools like Prisma3D.

- Android UI: Kotlin / native Android views (no HTML or WebView editor)
- Native core: C++20, JNI, CMake
- Current visible renderer: Android OpenGL ES 3.x context using GLES shader/draw APIs
- Build: Android Gradle Plugin + CMake, GitHub Actions debug APK artifact workflow

## Implemented in the current editor foundation

- Native Android app shell and C++ JNI library.
- Vulkan 1.1, 1.2, and 1.3 instance-level capability detection with physical-device enumeration, queue family, and memory heap reporting.
- GPU/GLSL vendor/version status and OpenGL ES 3.2 capability check in the editor.
- Perspective 3D viewport with a ground grid, red/blue axes, and PBR-shaded scene objects.
- Orbit camera using one-finger drag; pinch-to-zoom; reset-camera control.
- 3D primitives: Cube, Sphere, Cylinder, Cone, Torus, Plane, Pyramid, Capsule, and IcoSphere (subdivided icosahedron). Each supports solid and wireframe rendering.
- Add Light and Camera scene entries (displayed as icon markers).
- Per-object material editor: metallic, roughness, and RGB base color with live sliders.
- Full 3-axis transform controls: translate X/Y/Z, rotate Y/X/Z, scale, and reset.
- Object duplication, visibility toggle, wireframe toggle, and grid snap.
- Scene selection controls for cycling through hierarchy entries.
- In-memory undo/redo history for scene edits, bounded to the most recent 80 snapshots.
- Save/load scene objects (with full transform, material, color, and visibility state) to local app preferences.
- Basic keyframe timeline with transform snapshots, ease-in-out cubic interpolation, adjustable playback speed (5–60 fps), 240-frame range, playback, and local keyframe persistence.
- Wavefront OBJ geometry importer: triangulates polygon faces and resolves negative vertex indices.
- Geometry-focused glTF 2.0/GLB 2.0 importer: indexed/non-indexed triangle primitives and embedded base64 buffers; external `.bin` files, materials/textures, skinning, morph targets and animation channels are not yet imported.
- Three-step quality selector (Low/Balanced/High).
- PBR Cook-Torrance shading with GGX distribution, Smith geometry, Fresnel Schlick, environment ambient, and tonemapping.
- Selection highlight rim lighting on the selected object.
- Native Vulkan loader/physical-device capability probe for versions 1.0 through 1.3. This **does not** constitute a Vulkan renderer.

## Renderer selection and truthfulness

The renderer menu offers Automatic, OpenGL ES, and Vulkan 1.1/1.2/1.3. The visible viewport currently uses OpenGL ES. When a Vulkan version is selected, the app probes the device's Vulkan capability for that version and displays the result (instance API, device API, driver version, graphics queue support, memory heaps), then explains that the Vulkan rendering backend is not implemented yet and returns to the GLES viewport. The native core creates a Vulkan instance and enumerates a physical device so capability reporting can be developed, but it does not yet create an Android surface/swapchain or submit Vulkan draw calls.

`GLSurfaceView.setEGLContextClientVersion(3)` requests an OpenGL ES 3.x context, **not a guaranteed ES 3.2 context**. The app now checks `GLES32.GL_VERSION` at runtime to confirm whether the driver actually reports ES 3.2. If it does not, the status bar honestly reports the detected version instead of claiming 3.2. Exact version/extension validation and a dedicated GLES 3.2 capability gate for advanced features (tessellation, compute, ASTC) remain required.

## Source status

The features listed above are the features currently implemented in source. This repository does not label a UI placeholder or capability probe as a finished subsystem. A true Vulkan drawing backend, PBR material pipeline with image-based lighting/shadows/reflections, FBX import, glTF material/texture support, production-grade selection/gizmos, full camera/shot editor, and rendered movie export are not implemented in this source version; they must not be treated as available in the APK.

## Build locally in Android Studio

1. Open the repository root in a recent Android Studio.
2. Install Android SDK Platform 35, Android build tools 35.0.0, NDK 27.2.12479018, and CMake 3.22.1 if Android Studio does not install them automatically. CI is pinned to Ubuntu 24.04 to avoid runner-image changes.
3. Sync Gradle and run the `app` configuration on an Android device or emulator.
4. Or use the GitHub Actions workflow `.github/workflows/android.yml`; a successful run uploads `neuz3d-debug-apk`.

## Verification status

The source and CI workflow have been committed. A successful GitHub Actions run, APK artifact, physical-device smoke test, and Vulkan rendering have **not** been verified yet. Do not treat planned items as implemented features.

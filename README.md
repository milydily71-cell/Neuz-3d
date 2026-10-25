# Neuz 3D

**Neuz 3D** is a native-first Android 3D editor and the foundation for a mobile modeling, animation, and 3D movie-making studio inspired by mobile 3D creation tools.

- Android UI: Kotlin / native Android views (no HTML or WebView editor)
- Native core: C++20, JNI, CMake
- Current visible renderer: Android OpenGL ES 3.x context using GLES shader/draw APIs
- Build: Android Gradle Plugin + CMake, GitHub Actions debug APK artifact workflow

## Implemented in the current editor foundation

- Native Android app shell and C++ JNI library.
- GPU/GLSL vendor/version status in the editor.
- Perspective 3D viewport with a ground grid, red/blue axes, and a colored cube mesh.
- Orbit camera using one-finger drag; pinch-to-zoom; reset-camera control.
- Add Cube, Sphere, Light, and Camera scene entries; sphere primitives use generated triangle geometry.
- Basic transform controls for the selected scene object: translate X/Y, rotate, scale, and delete.
- Scene selection controls for cycling through hierarchy entries.
- In-memory undo/redo history for scene edits, bounded to the most recent 80 snapshots.
- Save/load scene object transforms and types to local app preferences.
- Undo/redo history (up to 80 scene snapshots).
- Basic keyframe timeline with transform snapshots, interpolation, playback, and local keyframe persistence.
- Wavefront OBJ geometry importer: triangulates polygon faces and resolves negative vertex indices.
- Geometry-focused glTF 2.0/GLB 2.0 importer: indexed/non-indexed triangle primitives and embedded base64 buffers; external `.bin` files, materials/textures, skinning, morph targets and animation channels are not yet imported.
- Three-step quality selector (advanced GPU quality scaling is not fully wired).
- Native Vulkan loader/physical-device capability probe. This **does not** constitute a Vulkan renderer.

## Renderer selection and truthfulness

The renderer menu offers Automatic, OpenGL ES, and Vulkan. The visible viewport currently uses OpenGL ES. Vulkan selection explains that the Vulkan rendering backend is not implemented yet and remains on the working GLES viewport. The native core attempts to create a Vulkan instance and enumerate a physical device so capability reporting can be developed, but it does not yet create an Android surface/swapchain or submit Vulkan draw calls.

`GLSurfaceView.setEGLContextClientVersion(3)` requests an OpenGL ES 3.x context, **not a guaranteed ES 3.2 context**. The app currently does not claim exact ES 3.2 support. Exact version/extension validation and a dedicated GLES 3.2 capability gate remain required.

## Source status

The features listed above are the features currently implemented in source. This repository does not label a UI placeholder or capability probe as a finished subsystem. A true Vulkan drawing backend, PBR material pipeline with image-based lighting/shadows/reflections, FBX import, glTF material/texture support, production-grade selection/gizmos, full camera/shot editor, and rendered movie export are not implemented in this source version; they must not be treated as available in the APK.

## Build locally in Android Studio

1. Open the repository root in a recent Android Studio.
2. Install Android SDK Platform 35, Android build tools 35.0.0, NDK 27.2.12479018, and CMake 3.22.1 if Android Studio does not install them automatically. CI is pinned to Ubuntu 24.04 to avoid runner-image changes.
3. Sync Gradle and run the `app` configuration on an Android device or emulator.
4. Or use the GitHub Actions workflow `.github/workflows/android.yml`; a successful run uploads `neuz3d-debug-apk`.

## Verification status

The source and CI workflow have been committed. A successful GitHub Actions run, APK artifact, physical-device smoke test, and Vulkan rendering have **not** been verified yet. Do not treat planned items as implemented features.

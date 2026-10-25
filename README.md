# Neuz 3D

**Neuz 3D** is a native-first Android 3D modeling, animation, scene-building, and 3D movie/game-maker project inspired by mobile tools such as Prisma3D.

The project target is Kotlin for the Android editor and lifecycle, with a C++ native rendering core. No HTML/WebView editor is planned.

## Rendering architecture

- **Vulkan**: a real Vulkan renderer selected only after runtime capability and surface/device initialization checks succeed.
- **OpenGL ES 3.2**: a native GLES 3.2 renderer with shader compilation and capability checks.
- **Automatic**: prefer Vulkan when supported and initialization succeeds; otherwise fall back to GLES 3.2, then show a clear unsupported-device message if neither path is usable.
- A graphics dropdown allows users to choose **Automatic**, **Vulkan**, or **OpenGL ES 3.2**. Unsupported/manual selections must be disabled or explain why they cannot run.
- Render quality tiers should scale resolution, shadows, reflections, anti-aliasing, texture quality, and post-processing to device capabilities and thermal limits.

## Planned editor features

- Touch-first 3D viewport, orbit/pan/zoom, grid, axes, selection, transform gizmos, object hierarchy, and inspector.
- Mesh primitives and modeling operations, materials, texture assignment, PBR metallic/roughness workflow.
- Image-based lighting (environment maps), directional/point/spot lights, shadow maps, and screen-space or probe-based reflections where supported.
- Import/export pipeline for GLB, glTF, OBJ, and FBX. Format support must be implemented and tested per format; FBX may require a native conversion/import library and licensing review.
- Timeline, keyframes, animation tracks, camera animation, scene shots, and movie rendering/export.
- Scene/project save/load, undo/redo, asset browser, and diagnostics.
- Android Studio Gradle + CMake build workflow and GitHub Actions APK artifact workflow.

## Build requirements

- Android Studio with a recent Android SDK/NDK and CMake.
- Kotlin/Android host plus C++ native renderer through JNI.
- Native shader sources for Vulkan and GLES; do not label a renderer "supported" until device detection, initialization, shader compilation, and a visible render pass succeed.
- CI should compile the Android app and upload a debug APK artifact. Device-only GPU behavior still needs on-device smoke testing.

## Development status

This repository is being bootstrapped. The feature list above is the target roadmap, **not a claim that every feature is already implemented**. Vulkan, GLES, importers, PBR, animation, and APK generation each need implementation and verification milestones.

## Suggested milestones

1. Android app skeleton, native CMake target, capability report, and CI APK.
2. Working GLES 3.2 viewport with grid, axes, and a lit test mesh.
3. Real Vulkan instance/device/swapchain/render pass and shader pipeline, tested on a Vulkan-capable device.
4. Shared scene graph, camera controls, hierarchy, transforms, and project serialization.
5. PBR materials, environment lighting, shadows, and reflections.
6. glTF/GLB and OBJ import/export; FBX support after library selection.
7. Timeline, keyframe animation, camera tracks, and movie export.
8. Modeling tools, undo/redo, performance profiling, and device-specific quality presets.

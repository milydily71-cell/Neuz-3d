# Neuz 3D — Product Roadmap

**Vision:** a mobile-first Android 3D studio for modeling, animation, rendering, and 3D movie-making, inspired by Blender and Prisma3D.

Status legend: implemented in source · in progress · planned

## 1. 3D Tools & Viewport

| Item | Status | Notes |
|---|---|---|
| Orbit / pinch camera and tap selection | implemented | One-finger orbit, pinch zoom, screen-space picking |
| Transform controls: Move/Rotate/Scale on all 3 axes | implemented | X/Y/Z translate, Y/X/Z rotate, scale, reset |
| 9 primitives: Cube, Sphere, Cylinder, Cone, Torus, Plane, Pyramid, Capsule, IcoSphere | implemented | Wireframe + solid, per-face normals |
| Duplicate, visibility toggle, wireframe toggle, grid snap | implemented | Per-object controls |
| Per-object PBR material editor (metallic, roughness, base color, emissive) | implemented | Live slider editor |
| Selection highlight with rim lighting | implemented | Selected object gets visual highlight |
| Editor mode system (Object/Edit/Sculpt/Vertex Paint/Weight Paint/Rig/Pose) | implemented | Blender-like mode switching |
| Modeling operations (subdivide, extrude, bevel, loop cut, flip normals, merge) | implemented | Geometry functions available |
| Extrude/subdivide/bevel interactive editing | in progress | Functions exist; interactive viewport editing pending |
| Snapping and multi-selection | in progress | Grid snap done; multi-select planned |

## 2. Import & Materials

| Item | Status | Notes |
|---|---|---|
| OBJ geometry import with normals | implemented | Polygon triangulation, negative indices, computed normals |
| glTF 2.0 / GLB 2.0 geometry import | implemented | Indexed/non-indexed primitives, normals, joints/weights |
| glTF skinning data (joints, weights, inverse bind matrices) | implemented | Parsed from glTF; shader skinning pending full wiring |
| glTF metallic/roughness and base-color factors | implemented | Factor import |
| External glTF buffers and texture maps | planned | Not yet complete |
| FBX import | planned | |

## 3. Lighting & Cameras

| Item | Status | Notes |
|---|---|---|
| Multi-light PBR shading (up to 8 lights) | implemented | Directional/Point/Spot, distance attenuation |
| Lighting editor with type, color, intensity, position | implemented | Live sliders |
| Per-light enable/shadow-cast flags | implemented | Shadow rendering not yet implemented |
| Scene cameras with FOV, near/far, position, look-at | implemented | Camera editor dialog |
| Camera controls: dolly, truck, orbit | implemented | Programmatic controls |
| Active camera selection | implemented | |
| Real-time shadows | planned | Needs shadow map rendering pass |

## 4. Animation

| Item | Status | Notes |
|---|---|---|
| Transform keyframes and timeline playback | implemented | Local persistence, 240-frame range |
| Ease-in-out cubic interpolation | implemented | Smoothed playback |
| Adjustable playback speed (5-60 fps) | implemented | |
| 3-axis rotation keyframe interpolation | implemented | RotX, RotY, RotZ |
| Skeletal animation (bone keyframes, skinning) | in progress | Bone system + matrix computation done; shader skinning pending |
| Named animation clips and property tracks | in progress | Core data model in place |
| Graph editor | planned | |

## 5. Skeleton & Skinning

| Item | Status | Notes |
|---|---|---|
| Bone hierarchy with parent-child | implemented | |
| Inverse bind matrix computation | implemented | |
| Rest pose and current pose | implemented | |
| Bone matrix computation for skinning | implemented | |
| Skeleton editor with bone selection | implemented | |
| Pose mode with per-bone transform | implemented | |
| Shader skinning (GPU bone deformation) | in progress | Bone matrices sent to renderer; vertex shader skinning pending |
| Weight painting | in progress | Brush system exists; weight paint mode scaffolded |

## 6. Vertex Painting

| Item | Status | Notes |
|---|---|---|
| Brush-based vertex color painting | implemented | Proximity falloff |
| Adjustable brush radius, strength, color | implemented | |
| Vertex paint mode with touch painting | implemented | |
| Vertex colors in PBR shader | in progress | Attribute wired; full blending pending |
| Texture painting | planned | |

## 7. Rendering & Export

| Item | Status | Notes |
|---|---|---|
| OpenGL ES viewport with PBR Cook-Torrance shader | implemented | GGX + Smith + Fresnel + multi-light + tonemapping |
| GLES 3.2 runtime capability detection | implemented | String-based, no GLES32 dependency |
| Low / Balanced / High quality profiles | in progress | Not all advanced GPU settings wired |
| Image-based lighting, real shadows, reflections | planned | |
| Frame-sequence export | in progress | |
| MP4 movie export | planned | Via Android MediaCodec |
| Vulkan 1.1/1.2/1.3 capability detection | implemented | dlsym-based, no direct 1.1+ linkage |
| Vulkan rendering backend | planned | Device probing only |

## 8. Design System

| Item | Status | Notes |
|---|---|---|
| Blender-like dark theme (colors, dimensions, styles) | implemented | res/values/ resources |
| Editor HUD, toolbar, properties, timeline layouts | implemented | UI layout foundation |
| Material/lighting/camera/vertex paint/skeleton editor dialogs | implemented | |
| Mode switcher bar (Object/Edit/Sculpt/etc) | implemented | |
| Render/export HUD and shot-list widgets | in progress | |

## Milestones

- M1 — Editor core: scene objects, camera, selection, undo, save/load, 9 primitives, materials.
- M2 — Content import: OBJ and glTF/GLB geometry with normals and skin data.
- M3 — Animation: clips, easing, timeline, keyframing, adjustable speed, 3-axis rotation.
- M4 — Lighting & cameras: multi-light PBR, camera system, lighting/camera editors.
- M5 — Skeleton & skinning: bones, inverse bind, pose mode, skeleton editor.
- M6 — Vertex paint: brush system, proximity painting, vertex paint mode.
- M7 — Modeling: subdivide, extrude, bevel, loop cut, flip normals, merge.
- M8 — Render/export: IBL, shadows, frame export, MP4.
- M9 — Movie maker: shots, storyboard, camera cuts, audio.
- M10 — Game export: GLB clips and asset bundles.

## Implementation honesty

Vulkan rendering, real-time shadows, IBL, complete glTF textures, FBX, texture painting, shader skinning, and MP4 export must not be advertised as complete until implemented and verified.

# Neuz 3D — Product Roadmap

**Vision:** a mobile-first Android 3D studio for modeling, animation, rendering, and 3D movie-making.

Status legend: ✅ implemented in source · 🚧 in progress or scaffolded · ⬜ planned

## 1. 3D Tools & Viewport

| Item | Status | Notes |
|---|---|---|
| Orbit / pinch camera and tap selection | ✅ | One-finger orbit, pinch zoom, screen-space picking |
| Transform gizmos and Select/Move/Rotate/Scale tools | ✅ | Axis gizmo and tool state model |
| Cube, sphere, light, and camera primitives | ✅ | Sphere generated from triangle geometry |
| Extrude, subdivide, loop cut, bevel, paint | 🚧 | Modeling operations still in progress |
| Snapping and multi-selection | 🚧 / ⬜ | Increment snapping scaffolded; multi-select planned |

## 2. Import & Materials

| Item | Status | Notes |
|---|---|---|
| OBJ geometry import | ✅ | Polygon triangulation and negative vertex indices |
| glTF 2.0 / GLB 2.0 geometry import | ✅ | Indexed and non-indexed triangle primitives |
| glTF metallic/roughness and base-color factors | ✅ | Factor import; complete texture/material pipeline remains future work |
| External glTF buffers and texture maps | ⬜ | Not yet complete |
| Skeletal meshes, skinning, and animation import | ⬜ | Requires a skinned-mesh pipeline |
| FBX import and sculpting | ⬜ | Planned |

## 3. Animation

| Item | Status | Notes |
|---|---|---|
| Transform keyframes and timeline playback | ✅ | Local persistence |
| Interpolation and easing model | 🚧 | Curve editor and advanced controls planned |
| Named animation clips and property tracks | 🚧 | Core data model in place |
| Graph editor, skeletal animation, ragdoll | ⬜ | Future milestones |

## 4. Rendering & Export

| Item | Status | Notes |
|---|---|---|
| Android OpenGL ES viewport and PBR shader | ✅ | Current renderer is GLES |
| Low / Balanced / High quality profiles | 🚧 | Not all advanced GPU settings are wired |
| Image-based lighting, real shadows, reflections | ⬜ | Needs environment/offscreen rendering passes |
| Frame-sequence export | 🚧 | Export path requires completion and verification |
| MP4 movie export | ⬜ | Planned via Android MediaCodec |
| Vulkan rendering backend | ⬜ | Device probing only; not a Vulkan renderer |

## 5. Movie Studio

| Item | Status | Notes |
|---|---|---|
| Shot and sequence data model | 🚧 | Foundation in place |
| Storyboard / shot list UI | 🚧 | Layouts designed; full workflow pending |
| Camera framing per shot, takes, audio sync | ⬜ | Planned |
| Game-ready export and GLB animation clips | ⬜ | Planned |

## 6. Design System

| Item | Status | Notes |
|---|---|---|
| Theme colors, dimensions, styles | ✅ | Android resources |
| Editor HUD, toolbar, properties, timeline layouts | ✅ | UI layout foundation |
| Render/export HUD and shot-list widgets | 🚧 | Requires end-to-end wiring and device verification |

## Milestones

- **M1 — Editor core:** scene objects, camera, selection, undo, and save/load.
- **M2 — Content import:** OBJ and basic glTF/GLB geometry with material factors.
- **M3 — Animation:** clips, easing, timeline, and keyframing.
- **M4 — Modeling:** mesh buffers, editing operations, and modifiers.
- **M5 — Render/export:** IBL, shadows, frame export, and MP4.
- **M6 — Movie maker:** shots, storyboard, camera cuts, and audio.
- **M7 — Game export:** GLB clips and asset bundles.

## Implementation honesty

Vulkan rendering, complete glTF textures/materials, skeletal animation, physics, IBL, real-time shadows/reflections, FBX, sculpting, and MP4 export must not be advertised as complete until implemented and verified. A roadmap entry or UI layout is not proof that a feature is operational in the APK.

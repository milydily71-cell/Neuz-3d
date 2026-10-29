package com.neuz3d.editor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Locale

class MainActivity : Activity() {
    private external fun nativeEngineInfo(): String
    private external fun nativeVulkanInfo(): String

    private lateinit var viewport: GLSurfaceView
    private lateinit var renderer: SceneRenderer
    private lateinit var status: TextView
    private lateinit var objectLabel: TextView
    private lateinit var rendererPicker: Spinner
    private lateinit var modeLabel: TextView

    private val scene = mutableListOf<SceneObject>()
    private val lights = mutableListOf<SceneLight>()
    private val cameras = mutableListOf<SceneCamera>()
    private val skeletons = mutableListOf<Skeleton>()
    private val editorState = EditorState()

    private val undoStack = java.util.ArrayDeque<String>()
    private val redoStack = java.util.ArrayDeque<String>()
    private val keyframes = java.util.TreeMap<Int, KeyframeData>()
    private val timelineHandler = Handler(Looper.getMainLooper())
    private var currentFrame = 0
    private var isPlaying = false
    private var playbackFps = 20
    private var nextId = 1
    private var nextLightId = 1
    private var nextCamId = 1
    private var nextSkelId = 1
    private var selectedId = 1
    private var selectedLightId = -1
    private var selectedCamId = -1
    private var quality = 1
    private var rendererMode = 0

    private val materialPresets = listOf(
        MaterialPreset("Plastic", 0.05f, 0.65f, floatArrayOf(0.5f, 0.5f, 0.55f)),
        MaterialPreset("Metal", 0.95f, 0.2f, floatArrayOf(0.75f, 0.75f, 0.78f)),
        MaterialPreset("Gold", 0.95f, 0.25f, floatArrayOf(1f, 0.78f, 0.35f)),
        MaterialPreset("Copper", 0.9f, 0.35f, floatArrayOf(0.95f, 0.6f, 0.45f)),
        MaterialPreset("Glass", 0.0f, 0.05f, floatArrayOf(0.8f, 0.9f, 1f)),
        MaterialPreset("Wood", 0.0f, 0.85f, floatArrayOf(0.55f, 0.35f, 0.18f)),
        MaterialPreset("Stone", 0.02f, 0.9f, floatArrayOf(0.6f, 0.58f, 0.55f)),
        MaterialPreset("Rubber", 0.0f, 0.98f, floatArrayOf(0.15f, 0.15f, 0.15f)),
        MaterialPreset("Skin", 0.02f, 0.55f, floatArrayOf(0.9f, 0.7f, 0.6f)),
        MaterialPreset("Emissive", 0.0f, 0.4f, floatArrayOf(0.2f, 0.8f, 0.4f), 1.5f)
    )

    private val texturePresets = listOf(
        TexturePreset("Solid", 0),
        TexturePreset("Checker", 1),
        TexturePreset("Grid", 2),
        TexturePreset("Brick", 3),
        TexturePreset("Noise", 4)
    )

    private lateinit var timelineSeekBar: SeekBar
    private lateinit var timelineLabel: TextView
    private lateinit var outlinerText: TextView

    private val prefs by lazy { getSharedPreferences("neuz3d_scene", MODE_PRIVATE) }

    data class KeyframeData(
        val objects: List<SceneObject>,
        val lights: List<SceneLight>,
        val bones: List<Bone>
    )

    data class MaterialPreset(
        val name: String, val metallic: Float, val roughness: Float,
        val color: FloatArray, val emissive: Float = 0f
    )

    data class TexturePreset(val name: String, val pattern: Int)

    private val primitiveLabels = mapOf(
        "+ Cube" to "Cube", "+ Sphere" to "Sphere", "+ Cylinder" to "Cylinder",
        "+ Cone" to "Cone", "+ Torus" to "Torus", "+ Plane" to "Plane",
        "+ Pyramid" to "Pyramid", "+ Capsule" to "Capsule", "+ IcoSphere" to "IcoSphere"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { System.loadLibrary("neuz3d") } catch (e: UnsatisfiedLinkError) {
            Toast.makeText(this, "Native core failed to load: ${e.message}", Toast.LENGTH_LONG).show()
        }
        if (scene.isEmpty()) scene.add(SceneObject(nextId++, "Cube"))
        if (lights.isEmpty()) lights.add(SceneLight(nextLightId++, "Sun", "Directional",
            floatArrayOf(1f, 0.95f, 0.85f), 1.2f, 15f, 4f, 6f, 3f, false, true))
        buildEditorUi()
    }

    private fun buildEditorUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 19, 25))
        }

        // Header bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(25, 28, 37))
        }
        header.addView(TextView(this).apply {
            text = "NEUZ 3D"
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        modeLabel = TextView(this).apply {
            text = "Object Mode"
            textSize = 11f
            setTextColor(Color.rgb(200, 160, 50))
            setPadding(dp(8), 0, dp(8), 0)
        }
        header.addView(modeLabel)

        rendererPicker = Spinner(this)
        rendererPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Automatic", "OpenGL ES", "Vulkan 1.1", "Vulkan 1.2", "Vulkan 1.3"))
        rendererPicker.setSelection(0)
        rendererPicker.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                rendererMode = position
                when (position) {
                    0 -> status.text = "Renderer: Automatic • GLES viewport active"
                    1 -> {
                        val det = renderer.version
                        if (renderer.isGles32) {
                            status.text = "OpenGL ES 3.2 confirmed • $det"
                        } else {
                            status.text = "GLES ${renderer.glesMajorVersion / 10}.${renderer.glesMajorVersion % 10} context • ES 3.2 not confirmed by driver"
                            Toast.makeText(this@MainActivity,
                                "Requests an OpenGL ES 3.x context. Will not claim ES 3.2 unless the driver reports it.",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                    2 -> showVulkanInfo(1)
                    3 -> showVulkanInfo(2)
                    4 -> showVulkanInfo(3)
                }
            }
        })
        header.addView(rendererPicker, LinearLayout.LayoutParams(dp(140), dp(44)))
        root.addView(header)

        // Editor mode switcher
        val modeRow = horizontalStrip()
        modeRow.addView(modeButton("Object") { setEditorMode(EditorMode.OBJECT_MODE) })
        modeRow.addView(modeButton("Edit") { setEditorMode(EditorMode.EDIT_MODE) })
        modeRow.addView(modeButton("Sculpt") { setEditorMode(EditorMode.SCULPT_MODE) })
        modeRow.addView(modeButton("Vertex Paint") { setEditorMode(EditorMode.VERTEX_PAINT_MODE) })
        modeRow.addView(modeButton("Weight Paint") { setEditorMode(EditorMode.WEIGHT_PAINT_MODE) })
        modeRow.addView(modeButton("Rig") { setEditorMode(EditorMode.RIG_MODE) })
        modeRow.addView(modeButton("Pose") { setEditorMode(EditorMode.POSE_MODE) })
        root.addView(modeRow)

        // Primitives toolbar
        val tools = horizontalStrip()
        primitiveLabels.forEach { (label, type) ->
            tools.addView(toolButton(label) { addObject(type) })
        }
        tools.addView(toolButton("Light") { addLight() })
        tools.addView(toolButton("Camera") { addCamera() })
        root.addView(tools)

        // Transform toolbar
        val transformRow = horizontalStrip()
        transformRow.addView(toolButton("◀ Prev") { selectAdjacent(-1) })
        transformRow.addView(toolButton("Next ▶") { selectAdjacent(1) })
        transformRow.addView(toolButton("Delete") { deleteSelected() })
        transformRow.addView(toolButton("Duplicate") { duplicateSelected() })
        transformRow.addView(toolButton("Move X+") { transformSelected(dx = 0.25f) })
        transformRow.addView(toolButton("Move X-") { transformSelected(dx = -0.25f) })
        transformRow.addView(toolButton("Move Y+") { transformSelected(dy = 0.25f) })
        transformRow.addView(toolButton("Move Y-") { transformSelected(dy = -0.25f) })
        transformRow.addView(toolButton("Move Z+") { transformSelected(dz = 0.25f) })
        transformRow.addView(toolButton("Move Z-") { transformSelected(dz = -0.25f) })
        root.addView(transformRow)

        // Rotation/scale/misc toolbar
        val rotRow = horizontalStrip()
        rotRow.addView(toolButton("Rot Y+") { transformSelected(rotation = 15f) })
        rotRow.addView(toolButton("Rot Y-") { transformSelected(rotation = -15f) })
        rotRow.addView(toolButton("Rot X+") { transformSelected(rotX = 15f) })
        rotRow.addView(toolButton("Rot X-") { transformSelected(rotX = -15f) })
        rotRow.addView(toolButton("Scale +") { transformSelected(scale = 0.1f) })
        rotRow.addView(toolButton("Scale -") { transformSelected(scale = -0.1f) })
        rotRow.addView(toolButton("Reset Xfm") { resetTransform() })
        rotRow.addView(toolButton("Wireframe") { toggleWireframe() })
        rotRow.addView(toolButton("Hide") { toggleVisibility() })
        root.addView(rotRow)

        // Modeling tools row
        val modelRow = horizontalStrip()
        modelRow.addView(toolButton("Subdivide") { applyModelingOp("subdivide") })
        modelRow.addView(toolButton("Extrude") { applyModelingOp("extrude") })
        modelRow.addView(toolButton("Bevel") { applyModelingOp("bevel") })
        modelRow.addView(toolButton("Loop Cut") { applyModelingOp("loopcut") })
        modelRow.addView(toolButton("Flip Normals") { applyModelingOp("flip") })
        modelRow.addView(toolButton("Add Bone") { addBone() })
        modelRow.addView(toolButton("Add Skeleton") { addSkeleton() })
        root.addView(modelRow)

        // Viewport
        viewport = GLSurfaceView(this)
        viewport.setEGLContextClientVersion(3)
        renderer = SceneRenderer(
            onGpuInfo = { v, rendererName, is32, hasFloat ->
                runOnUiThread {
                    val floatStr = if (hasFloat) "float-buffer" else "no float-buffer"
                    status.text = "GPU: $rendererName • ${if (is32) "ES 3.2" else "ES 3.x"} • $floatStr"
                }
            },
            onPick = { pickedId ->
                runOnUiThread {
                    selectedId = pickedId
                    syncSceneToRenderer()
                }
            }
        )
        viewport.setRenderer(renderer)
        viewport.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        viewport.setOnTouchListener { _, event -> handleViewportTouch(event) }
        root.addView(viewport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Properties panel
        val objectPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setBackgroundColor(Color.rgb(25, 28, 37))
        }
        objectLabel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; text = "Hierarchy • Cube"
        }
        objectPanel.addView(objectLabel)

        // Action row
        val actionRow = horizontalStrip()
        actionRow.addView(toolButton("Undo") { undo() })
        actionRow.addView(toolButton("Redo") { redo() })
        actionRow.addView(toolButton("Material") { showMaterialEditor() })
        actionRow.addView(toolButton("Presets") { showMaterialPresetPicker() })
        actionRow.addView(toolButton("Texture") { showTexturePicker() })
        actionRow.addView(toolButton("Lighting") { showLightingEditor() })
        actionRow.addView(toolButton("Camera") { showCameraEditor() })
        actionRow.addView(toolButton("Vertex Paint") { showVertexPaintEditor() })
        actionRow.addView(toolButton("Skel/Rig") { showSkeletonEditor() })
        actionRow.addView(toolButton("Reset View") { renderer.resetCamera() })
        actionRow.addView(toolButton("Snap") { toggleSnap() })
        objectPanel.addView(actionRow)

        // Timeline row
        val timelineRow = horizontalStrip()
        timelineRow.addView(toolButton("Keyframe") { captureKeyframe() })
        timelineRow.addView(toolButton("Frame -") { stepTimeline(-1) })
        timelineRow.addView(toolButton("Frame +") { stepTimeline(1) })
        timelineRow.addView(toolButton("Play/Stop") { toggleTimeline() })
        timelineRow.addView(toolButton("Clear Keys") { clearKeyframes() })
        timelineRow.addView(toolButton("Speed-") { adjustSpeed(-5) })
        timelineRow.addView(toolButton("Speed+") { adjustSpeed(5) })
        objectPanel.addView(timelineRow)

        // Visual timeline strip
        timelineLabel = TextView(this).apply {
            text = "Frame 0 / 240"; setTextColor(Color.LTGRAY); textSize = 11f
            setPadding(0, dp(4), 0, dp(2))
        }
        objectPanel.addView(timelineLabel)
        timelineSeekBar = SeekBar(this).apply {
            setMax(240); setProgress(0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) { currentFrame = p; applyTimelineFrame(p); updateTimelineLabel() }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        objectPanel.addView(timelineSeekBar)

        // Scene outliner
        outlinerText = TextView(this).apply {
            setTextColor(Color.rgb(200, 200, 210)); textSize = 11f
            setPadding(0, dp(4), 0, dp(2))
        }
        objectPanel.addView(outlinerText)
        root.addView(objectPanel)

        // Bottom bar
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(8))
            setBackgroundColor(Color.rgb(19, 21, 28))
        }
        val bottomTools = horizontalStrip()
        bottomTools.addView(toolButton("Save Scene") { saveScene() })
        bottomTools.addView(toolButton("Load Scene") { loadScene() })
        bottomTools.addView(toolButton("Projects") { showProjectManager() })
        bottomTools.addView(toolButton("Export JSON") { exportScene() })
        bottomTools.addView(toolButton("Import OBJ") { pickModel("obj") })
        bottomTools.addView(toolButton("Import glTF") { pickModel("gltf") })
        bottomTools.addView(toolButton("Quality") { cycleQuality() })
        bottomTools.addView(toolButton("GPU Info") { showGpuInfo() })
        bottom.addView(bottomTools)

        status = TextView(this).apply {
            text = "Starting renderer..."
            textSize = 11f; setTextColor(Color.LTGRAY); setPadding(0, dp(5), 0, 0)
        }
        bottom.addView(status)
        root.addView(bottom)
        setContentView(root)
        syncSceneToRenderer()
    }

    private fun setEditorMode(mode: EditorMode) {
        editorState.mode = mode
        val name = when (mode) {
            EditorMode.OBJECT_MODE -> "Object Mode"
            EditorMode.EDIT_MODE -> "Edit Mode"
            EditorMode.SCULPT_MODE -> "Sculpt Mode"
            EditorMode.VERTEX_PAINT_MODE -> "Vertex Paint"
            EditorMode.WEIGHT_PAINT_MODE -> "Weight Paint"
            EditorMode.RIG_MODE -> "Rig Mode"
            EditorMode.POSE_MODE -> "Pose Mode"
        }
        modeLabel.text = name
        Toast.makeText(this, "Mode: $name", Toast.LENGTH_SHORT).show()
        syncSceneToRenderer()
    }

    // --- Lighting ---
    private fun addLight() {
        val light = SceneLight(nextLightId++, "Light ${nextLightId - 1}", "Point",
            floatArrayOf(1f, 0.95f, 0.85f), 1.0f, 15f,
            ((lights.size % 3) - 1) * 2f, 4f, ((lights.size / 3) % 3) * 2f)
        lights.add(light)
        selectedLightId = light.id
        syncSceneToRenderer()
        Toast.makeText(this, "Added ${light.name}", Toast.LENGTH_SHORT).show()
    }

    private fun showLightingEditor() {
        if (lights.isEmpty()) { Toast.makeText(this, "No lights. Add a Light first.", Toast.LENGTH_SHORT).show(); return }
        val light = lights.firstOrNull { it.id == selectedLightId } ?: lights.first()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Directional", "Point", "Spot"))
            setSelection(listOf("Directional", "Point", "Spot").indexOf(light.type))
        }
        dialogView.addView(TextView(this).apply { text = "Light Type"; setTextColor(Color.WHITE) })
        dialogView.addView(typeSpinner)

        val intLabel = TextView(this).apply { text = "Intensity: ${"%.2f".format(Locale.US, light.intensity)}"; setTextColor(Color.WHITE) }
        val intBar = SeekBar(this).apply {
            max = 300; progress = (light.intensity * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    light.intensity = p / 100f; intLabel.text = "Intensity: ${"%.2f".format(Locale.US, light.intensity)}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { syncSceneToRenderer() }
            })
        }
        dialogView.addView(intLabel); dialogView.addView(intBar)

        for ((label, getter, setter) in listOf<Triple<String, () -> Float, (Float) -> Unit>>(
            Triple("Red", { light.color[0] }, { light.color[0] = it }),
            Triple("Green", { light.color[1] }, { light.color[1] = it }),
            Triple("Blue", { light.color[2] }, { light.color[2] = it })
        )) {
            val l = TextView(this).apply { text = "$label: ${"%.2f".format(Locale.US, getter())}"; setTextColor(Color.WHITE) }
            val b = SeekBar(this).apply {
                max = 100; progress = (getter() * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        setter(p / 100f); l.text = "$label: ${"%.2f".format(Locale.US, getter())}"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) { syncSceneToRenderer() }
                })
            }
            dialogView.addView(l); dialogView.addView(b)
        }

        for ((label, getter, setter) in listOf<Triple<String, () -> Float, (Float) -> Unit>>(
            Triple("Position X", { light.x }, { light.x = it }),
            Triple("Position Y", { light.y }, { light.y = it }),
            Triple("Position Z", { light.z }, { light.z = it })
        )) {
            val l = TextView(this).apply { text = "$label: ${"%.2f".format(Locale.US, getter())}"; setTextColor(Color.WHITE) }
            val b = SeekBar(this).apply {
                max = 200; progress = ((getter() + 10f) * 10).toInt().coerceIn(0, 200)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        setter(p / 10f - 10f); l.text = "$label: ${"%.2f".format(Locale.US, getter())}"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) { syncSceneToRenderer() }
                })
            }
            dialogView.addView(l); dialogView.addView(b)
        }

        val enCheck = CheckBox(this).apply {
            text = "Enabled"; isChecked = light.enabled
            setOnCheckedChangeListener { _, c -> light.enabled = c; syncSceneToRenderer() }
        }
        dialogView.addView(enCheck)
        val shadCheck = CheckBox(this).apply {
            text = "Cast Shadows (planned)"; isChecked = light.castShadows
            setOnCheckedChangeListener { _, c -> light.castShadows = c }
        }
        dialogView.addView(shadCheck)

        typeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                light.type = listOf("Directional", "Point", "Spot")[pos]; syncSceneToRenderer()
            }
        }

        val scroll = ScrollView(this).apply { addView(dialogView) }
        AlertDialog.Builder(this).setTitle("Lighting Editor — ${light.name}")
            .setView(scroll)
            .setPositiveButton("Done") { _, _ -> syncSceneToRenderer() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Camera ---
    private fun addCamera() {
        val cam = SceneCamera(nextCamId++, "Camera ${nextCamId - 1}")
        cameras.add(cam)
        selectedCamId = cam.id
        syncSceneToRenderer()
        Toast.makeText(this, "Added ${cam.name}", Toast.LENGTH_SHORT).show()
    }

    private fun showCameraEditor() {
        if (cameras.isEmpty()) { Toast.makeText(this, "No cameras. Add a Camera first.", Toast.LENGTH_SHORT).show(); return }
        val cam = cameras.firstOrNull { it.id == selectedCamId } ?: cameras.first()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        addSliderRow(dialogView, "FOV", cam.fov, 20f, 100f) { cam.fov = it; renderer.setFov(it); syncSceneToRenderer() }
        addSliderRow(dialogView, "Near", cam.near, 0.1f, 5f) { cam.near = it; syncSceneToRenderer() }
        addSliderRow(dialogView, "Far", cam.far, 50f, 300f) { cam.far = it; syncSceneToRenderer() }
        addSliderRow(dialogView, "Pos X", cam.x, -20f, 20f) { cam.x = it; syncSceneToRenderer() }
        addSliderRow(dialogView, "Pos Y", cam.y, -20f, 20f) { cam.y = it; syncSceneToRenderer() }
        addSliderRow(dialogView, "Pos Z", cam.z, -20f, 20f) { cam.z = it; syncSceneToRenderer() }
        addSliderRow(dialogView, "Look X", cam.lookAtX, -20f, 20f) { cam.lookAtX = it; syncSceneToRenderer() }
        addSliderRow(dialogView, "Look Y", cam.lookAtY, -20f, 20f) { cam.lookAtY = it; syncSceneToRenderer() }
        addSliderRow(dialogView, "Look Z", cam.lookAtZ, -20f, 20f) { cam.lookAtZ = it; syncSceneToRenderer() }

        val camButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        camButtons.addView(toolButton("Dolly In") { CameraSystem.dolly(cam, -0.5f); syncSceneToRenderer() })
        camButtons.addView(toolButton("Dolly Out") { CameraSystem.dolly(cam, 0.5f); syncSceneToRenderer() })
        camButtons.addView(toolButton("Truck L") { CameraSystem.truck(cam, -0.5f); syncSceneToRenderer() })
        camButtons.addView(toolButton("Truck R") { CameraSystem.truck(cam, 0.5f); syncSceneToRenderer() })
        dialogView.addView(camButtons)

        val activeCheck = CheckBox(this).apply {
            text = "Set as active camera"; isChecked = cam.active
            setOnCheckedChangeListener { _, c -> cameras.forEach { it.active = (it.id == cam.id && c) }; syncSceneToRenderer() }
        }
        dialogView.addView(activeCheck)

        val scroll = ScrollView(this).apply { addView(dialogView) }
        AlertDialog.Builder(this).setTitle("Camera Editor — ${cam.name}")
            .setView(scroll)
            .setPositiveButton("Done") { _, _ -> syncSceneToRenderer() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Vertex Paint ---
    private fun showVertexPaintEditor() {
        val obj = scene.firstOrNull { it.id == selectedId } ?: run {
            Toast.makeText(this, "Select an object first.", Toast.LENGTH_SHORT).show(); return
        }
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        dialogView.addView(TextView(this).apply {
            text = "Vertex Paint Mode\nBrush color and controls:"; setTextColor(Color.WHITE); textSize = 14f
        })

        for ((label, getter, setter) in listOf<Triple<String, () -> Float, (Float) -> Unit>>(
            Triple("Brush R", { editorState.brushColor[0] }, { editorState.brushColor[0] = it }),
            Triple("Brush G", { editorState.brushColor[1] }, { editorState.brushColor[1] = it }),
            Triple("Brush B", { editorState.brushColor[2] }, { editorState.brushColor[2] = it })
        )) {
            addSliderRow(dialogView, label, getter(), 0f, 1f) { setter(it) }
        }
        addSliderRow(dialogView, "Brush Radius", editorState.brushRadius, 0.1f, 3f) { editorState.brushRadius = it }
        addSliderRow(dialogView, "Brush Strength", editorState.brushStrength, 0f, 1f) { editorState.brushStrength = it }

        dialogView.addView(TextView(this).apply {
            text = "Tap on the viewport in Vertex Paint mode to paint the selected object.\n" +
                   "Vertex colors blend with the base material color in the shader."
            setTextColor(Color.LTGRAY); textSize = 12f; setPadding(0, dp(8), 0, 0)
        })

        val paintBtn = toolButton("Enter Vertex Paint Mode") {
            setEditorMode(EditorMode.VERTEX_PAINT_MODE)
        }
        dialogView.addView(paintBtn)

        val scroll = ScrollView(this).apply { addView(dialogView) }
        AlertDialog.Builder(this).setTitle("Vertex Paint — ${obj.name}")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Skeleton / Rigging ---
    private fun addSkeleton() {
        val skel = SkeletonSystem.createSimpleSkeleton("Skeleton ${nextSkelId}", 3)
        skel.id = nextSkelId++
        skeletons.add(skel)
        Toast.makeText(this, "Added ${skel.name} with ${skel.bones.size} bones", Toast.LENGTH_SHORT).show()
        syncSceneToRenderer()
    }

    private fun addBone() {
        if (skeletons.isEmpty()) { Toast.makeText(this, "Add a Skeleton first.", Toast.LENGTH_SHORT).show(); return }
        val skel = skeletons.first()
        val parentIdx = skel.bones.lastIndex
        val bone = Bone(
            id = skel.bones.size, name = "${skel.name}_Bone_${skel.bones.size}",
            parentIndex = parentIdx, y = (skel.bones.size) * 0.5f,
            restY = (skel.bones.size) * 0.5f,
            inverseBind = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
        )
        skel.bones.add(bone)
        SkeletonSystem.computeInverseBind(skel)
        Toast.makeText(this, "Added bone ${bone.name}", Toast.LENGTH_SHORT).show()
        syncSceneToRenderer()
    }

    private fun showSkeletonEditor() {
        if (skeletons.isEmpty()) { Toast.makeText(this, "No skeletons. Add one first.", Toast.LENGTH_SHORT).show(); return }
        val skel = skeletons.first()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        dialogView.addView(TextView(this).apply {
            text = "${skel.name} — ${skel.bones.size} bones"; setTextColor(Color.WHITE); textSize = 14f
        })

        skel.bones.forEachIndexed { idx, bone ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3)) }
            row.addView(TextView(this).apply {
                text = if (bone.parentIndex >= 0) "  └ ${bone.name}" else "■ ${bone.name}"
                setTextColor(if (idx == editorState.selectedBoneIndex) Color.rgb(200, 160, 50) else Color.WHITE)
                textSize = 12f
            })
            row.addView(toolButton("Select") {
                editorState.selectedBoneIndex = idx
                setEditorMode(EditorMode.POSE_MODE)
            })
            row.addView(toolButton("Move Y+") {
                bone.y += 0.2f; SkeletonSystem.computeInverseBind(skel); syncSceneToRenderer()
            })
            row.addView(toolButton("Rot Y+") {
                bone.rotY += 15f; syncSceneToRenderer()
            })
            dialogView.addView(row)
        }

        val scroll = ScrollView(this).apply { addView(dialogView) }
        AlertDialog.Builder(this).setTitle("Skeleton & Rigging")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Modeling operations ---
    private fun applyModelingOp(op: String) {
        val obj = scene.firstOrNull { it.id == selectedId } ?: run {
            Toast.makeText(this, "Select an object first.", Toast.LENGTH_SHORT).show(); return
        }
        Toast.makeText(this, "$op applied to ${obj.name} (demo)", Toast.LENGTH_SHORT).show()
    }

    // --- Vulkan info ---
    private fun showVulkanInfo(targetMinor: Int) {
        try {
            val info = nativeVulkanInfo()
            val supports = when (targetMinor) {
                1 -> info.contains("v1.1 yes")
                2 -> info.contains("v1.2 yes")
                3 -> info.contains("v1.3 yes")
                else -> false
            }
            val msg = if (supports) {
                "Vulkan 1.$targetMinor is supported by this device.\n\n$info\n\n" +
                "However, the Vulkan rendering backend is not implemented yet. " +
                "The viewport will remain on the working OpenGL ES renderer."
            } else {
                "Vulkan 1.$targetMinor is NOT supported by this device.\n\n$info\n\n" +
                "The viewport remains on the OpenGL ES renderer."
            }
            AlertDialog.Builder(this).setTitle("Vulkan 1.$targetMinor Probe").setMessage(msg)
                .setPositiveButton("OK") { _, _ -> rendererPicker.setSelection(0) }.show()
            status.text = "Vulkan 1.$targetMinor ${if (supports) "supported" else "not supported"} • GLES viewport active"
        } catch (e: Exception) {
            status.text = "Vulkan probe failed: ${e.message}"
            rendererPicker.setSelection(0)
        }
    }

    private fun showGpuInfo() {
        val nativeInfo = try { nativeEngineInfo() } catch (e: Exception) { "Native info unavailable" }
        val glesInfo = renderer.version
        val is32 = renderer.isGles32
        val msg = StringBuilder()
        msg.append("OpenGL ES Context:\n$glesInfo\n\n")
        msg.append("GLES 3.2: ${if (is32) "confirmed" else "not confirmed"}\n")
        msg.append("GLES major: ${renderer.glesMajorVersion / 10}.${renderer.glesMajorVersion % 10}\n\n")
        msg.append("Native Engine:\n$nativeInfo")
        AlertDialog.Builder(this).setTitle("GPU & Renderer Info").setMessage(msg.toString())
            .setPositiveButton("OK", null).show()
    }

    private fun showMaterialEditor() {
        val obj = scene.firstOrNull { it.id == selectedId } ?: return
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        addSliderRow(dialogView, "Metallic", obj.metallic, 0f, 1f) { obj.metallic = it; syncSceneToRenderer() }
        addSliderRow(dialogView, "Roughness", obj.roughness, 0.045f, 1f) { obj.roughness = it; syncSceneToRenderer() }
        addSliderRow(dialogView, "Red", obj.color[0], 0f, 1f) { obj.color[0] = it; syncSceneToRenderer() }
        addSliderRow(dialogView, "Green", obj.color[1], 0f, 1f) { obj.color[1] = it; syncSceneToRenderer() }
        addSliderRow(dialogView, "Blue", obj.color[2], 0f, 1f) { obj.color[2] = it; syncSceneToRenderer() }
        addSliderRow(dialogView, "Emissive", obj.emissive, 0f, 2f) { obj.emissive = it; syncSceneToRenderer() }

        val wireCheck = CheckBox(this).apply {
            text = "Wireframe mode"; isChecked = obj.wireframe
            setOnCheckedChangeListener { _, c -> obj.wireframe = c; syncSceneToRenderer() }
        }
        dialogView.addView(wireCheck)

        val scroll = ScrollView(this).apply { addView(dialogView) }
        AlertDialog.Builder(this).setTitle("Material Editor — ${obj.name}")
            .setView(scroll)
            .setPositiveButton("Done") { _, _ -> checkpoint(); syncSceneToRenderer() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Material preset picker ---
    private fun showMaterialPresetPicker() {
        val obj = scene.firstOrNull { it.id == selectedId } ?: run {
            Toast.makeText(this, "Select an object first.", Toast.LENGTH_SHORT).show(); return
        }
        val labels = materialPresets.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Material Presets — ${obj.name}")
            .setItems(labels) { _, which ->
                val preset = materialPresets[which]
                checkpoint()
                obj.metallic = preset.metallic
                obj.roughness = preset.roughness
                obj.color = preset.color.copyOf()
                obj.emissive = preset.emissive
                syncSceneToRenderer()
                Toast.makeText(this, "Applied ${preset.name} to ${obj.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Texture picker ---
    private var selectedTexturePattern = 0
    private fun showTexturePicker() {
        val obj = scene.firstOrNull { it.id == selectedId } ?: run {
            Toast.makeText(this, "Select an object first.", Toast.LENGTH_SHORT).show(); return
        }
        val labels = texturePresets.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Texture Pattern — ${obj.name}")
            .setItems(labels) { _, which ->
                selectedTexturePattern = texturePresets[which].pattern
                applyTexturePattern(obj, texturePresets[which])
                Toast.makeText(this, "Applied ${texturePresets[which].name} texture", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyTexturePattern(obj: SceneObject, tex: TexturePreset) {
        val meshData = MeshBuilder.build(obj.type)
        val vertCount = meshData.vertices.size / 3
        val colors = FloatArray(vertCount * 3)
        val baseR = obj.color[0]; val baseG = obj.color[1]; val baseB = obj.color[2]
        when (tex.pattern) {
            0 -> { for (i in 0 until vertCount) { colors[i*3] = baseR; colors[i*3+1] = baseG; colors[i*3+2] = baseB } }
            1 -> { for (i in 0 until vertCount) {
                val x = meshData.vertices[i*3]; val y = meshData.vertices[i*3+1]
                val checker = ((Math.floor(x.toDouble()*4) + Math.floor(y.toDouble()*4)).toInt() % 2 == 0)
                if (checker) { colors[i*3] = baseR; colors[i*3+1] = baseG; colors[i*3+2] = baseB }
                else { colors[i*3] = baseR*0.4f; colors[i*3+1] = baseG*0.4f; colors[i*3+2] = baseB*0.4f }
            } }
            2 -> { for (i in 0 until vertCount) {
                val x = meshData.vertices[i*3]; val z = meshData.vertices[i*3+2]
                val onLine = Math.abs(x*5 - Math.round(x*5).toFloat()) < 0.15f || Math.abs(z*5 - Math.round(z*5).toFloat()) < 0.15f
                if (onLine) { colors[i*3] = 0.8f; colors[i*3+1] = 0.8f; colors[i*3+2] = 0.8f }
                else { colors[i*3] = baseR*0.5f; colors[i*3+1] = baseG*0.5f; colors[i*3+2] = baseB*0.5f }
            } }
            3 -> { for (i in 0 until vertCount) {
                val x = meshData.vertices[i*3]; val y = meshData.vertices[i*3+1]
                val bx = (x*3).toInt(); val by = (y*3).toInt()
                val offset = if (by % 2 == 0) 0 else 1
                val inBrick = Math.abs(x*3 - bx.toFloat() - offset*0.5f) < 0.85f && Math.abs(y*3 - by.toFloat()) < 0.85f
                if (inBrick) { colors[i*3] = 0.65f; colors[i*3+1] = 0.35f; colors[i*3+2] = 0.2f }
                else { colors[i*3] = 0.3f; colors[i*3+1] = 0.3f; colors[i*3+2] = 0.3f }
            } }
            4 -> { val rnd = java.util.Random(obj.id.toLong())
                for (i in 0 until vertCount) {
                    val n = rnd.nextFloat()
                    colors[i*3] = baseR * (0.5f + n * 0.5f)
                    colors[i*3+1] = baseG * (0.5f + n * 0.5f)
                    colors[i*3+2] = baseB * (0.5f + n * 0.5f)
                }
            }
        }
        obj.vertexColors = colors
        syncSceneToRenderer()
    }

    // --- Project manager (named save slots) ---
    private fun showProjectManager() {
        val projectList = getProjectList()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        dialogView.addView(TextView(this).apply {
            text = "Saved Projects:"; setTextColor(Color.WHITE); textSize = 14f; setPadding(0, 0, 0, dp(8))
        })
        if (projectList.isEmpty()) {
            dialogView.addView(TextView(this).apply {
                text = "No saved projects yet.\nSave the current scene with a name below."
                setTextColor(Color.LTGRAY); textSize = 12f
            })
        } else {
            for (proj in projectList) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3)) }
                row.addView(TextView(this).apply {
                    text = "  ${proj.name}  (${proj.objectCount} objects, ${proj.keyframeCount} keys)"
                    setTextColor(Color.rgb(200, 200, 210)); textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(toolButton("Load") { loadProject(proj.name) })
                row.addView(toolButton("Delete") { deleteProject(proj.name) })
                dialogView.addView(row)
            }
        }
        val nameInput = EditText(this).apply {
            hint = "Project name..."; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            setSingleLine(true)
        }
        dialogView.addView(nameInput)
        val saveBtn = Button(this).apply {
            text = "Save Current Scene as Project"; textSize = 11f; isAllCaps = false
            setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Enter a project name.", Toast.LENGTH_SHORT).show()
                } else {
                    saveProject(name)
                }
            }
        }
        dialogView.addView(saveBtn)
        val scroll = ScrollView(this).apply { addView(dialogView) }
        AlertDialog.Builder(this).setTitle("Project Manager")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private data class ProjectInfo(val name: String, val objectCount: Int, val keyframeCount: Int)

    private fun getProjectList(): List<ProjectInfo> {
        val raw = prefs.getString("project_index", "[]") ?: "[]"
        val names = JSONArray(raw)
        val list = ArrayList<ProjectInfo>()
        for (i in 0 until names.length()) {
            val name = names.optString(i)
            val data = prefs.getString("project:$name", null) ?: continue
            try {
                val json = JSONObject(data)
                val objCount = json.optJSONArray("objects")?.length() ?: 0
                val kfRaw = json.optString("keyframes", "{}")
                val kfCount = JSONObject(kfRaw).length()
                list.add(ProjectInfo(name, objCount, kfCount))
            } catch (e: Exception) { list.add(ProjectInfo(name, 0, 0)) }
        }
        return list
    }

    private fun saveProject(name: String) {
        val data = JSONObject()
            .put("scene", JSONObject(sceneJson()))
            .put("keyframes", keyframesJson())
            .put("savedAt", System.currentTimeMillis())
            .toString()
        prefs.edit().putString("project:$name", data).apply()
        val raw = prefs.getString("project_index", "[]") ?: "[]"
        val names = JSONArray(raw)
        val nameList = ArrayList<String>()
        for (i in 0 until names.length()) {
            val n = names.optString(i)
            if (n != name) nameList.add(n)
        }
        nameList.add(name)
        prefs.edit().putString("project_index", JSONArray(nameList).toString()).apply()
        Toast.makeText(this, "Project '$name' saved.", Toast.LENGTH_SHORT).show()
        showProjectManager()
    }

    private fun loadProject(name: String) {
        val data = prefs.getString("project:$name", null) ?: run {
            Toast.makeText(this, "Project not found.", Toast.LENGTH_SHORT).show(); return
        }
        try {
            val json = JSONObject(data)
            restoreSceneJson(json.getString("scene"))
            restoreKeyframes(json.optString("keyframes", null))
            currentFrame = 0; updateTimelineLabel()
            Toast.makeText(this, "Project '$name' loaded.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Load failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun deleteProject(name: String) {
        prefs.edit().remove("project:$name").apply()
        val raw = prefs.getString("project_index", "[]") ?: "[]"
        val names = JSONArray(raw)
        val nameList = ArrayList<String>()
        for (i in 0 until names.length()) {
            val n = names.optString(i)
            if (n != name) nameList.add(n)
        }
        prefs.edit().putString("project_index", JSONArray(nameList).toString()).apply()
        Toast.makeText(this, "Project '$name' deleted.", Toast.LENGTH_SHORT).show()
        showProjectManager()
    }

    // --- Timeline label & outliner update ---
    private fun updateTimelineLabel() {
        val keyFrames = if (keyframes.isEmpty()) "" else
            keyframes.keys.joinToString(",") { it.toString() }
        timelineLabel.text = "Frame $currentFrame / 240  •  ${keyframes.size} keyframes  •  keys: $keyFrames"
        if (::timelineSeekBar.isInitialized) timelineSeekBar.setProgress(currentFrame)
    }

    private fun updateOutliner() {
        if (!::outlinerText.isInitialized) return
        val sb = StringBuilder()
        scene.forEachIndexed { idx, obj ->
            val marker = if (obj.id == selectedId) "▶" else " "
            val vis = if (obj.visible) "" else " [hidden]"
            sb.append("$marker ${idx + 1}. ${obj.name} ($obj.type)$vis\n")
        }
        if (lights.isNotEmpty()) {
            sb.append("\nLights: ${lights.size}\n")
            lights.forEach { l -> sb.append("  • ${l.name} ($l.type) ${if (l.enabled) "on" else "off"}\n") }
        }
        if (cameras.isNotEmpty()) {
            sb.append("\nCameras: ${cameras.size}\n")
            cameras.forEach { c -> sb.append("  • ${c.name}${if (c.active) " (active)" else ""}\n") }
        }
        if (skeletons.isNotEmpty()) {
            sb.append("\nSkeletons: ${skeletons.size}\n")
            skeletons.forEach { s -> sb.append("  • ${s.name} (${s.bones.size} bones)\n") }
        }
        outlinerText.text = sb.toString().trim()
    }

    // --- Slider helper ---
    private fun addSliderRow(
        parent: LinearLayout, label: String, current: Float, min: Float, max: Float, onChange: (Float) -> Unit
    ) {
        val labelView = TextView(this).apply {
            text = "$label: ${"%.2f".format(Locale.US, current)}"; setTextColor(Color.WHITE)
        }
        val range = ((max - min) * 100).toInt().coerceAtLeast(1)
        val progress = ((current - min) * 100).toInt().coerceIn(0, range)
        val bar = SeekBar(this).apply {
            setMax(range); setProgress(progress)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val val_ = min + p / 100f
                    labelView.text = "$label: ${"%.2f".format(Locale.US, val_)}"
                    onChange(val_)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        parent.addView(labelView); parent.addView(bar)
    }

    // --- Snap, speed, keyframes ---
    private var snapEnabled = false
    private fun toggleSnap() {
        snapEnabled = !snapEnabled
        Toast.makeText(this, "Grid snap: ${if (snapEnabled) "on" else "off"}", Toast.LENGTH_SHORT).show()
    }

    private fun adjustSpeed(delta: Int) {
        playbackFps = (playbackFps + delta).coerceIn(5, 60)
        status.text = "Timeline playback speed: $playbackFps fps"
    }

    private fun clearKeyframes() {
        if (keyframes.isEmpty()) { Toast.makeText(this, "No keyframes to clear.", Toast.LENGTH_SHORT).show(); return }
        keyframes.clear(); prefs.edit().remove("timeline_keyframes").apply()
        status.text = "Timeline cleared"; updateTimelineLabel()
        Toast.makeText(this, "All keyframes cleared.", Toast.LENGTH_SHORT).show()
    }

    // --- UI builders ---
    private fun horizontalStrip() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(5), dp(3), dp(5), dp(3)); setBackgroundColor(Color.rgb(29, 32, 40))
    }

    private fun toolButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label; textSize = 10f; isAllCaps = false
            setPadding(dp(5), 0, dp(5), 0); minWidth = 0; minimumWidth = 0
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginStart = dp(2); marginEnd = dp(2)
            }
        }

    private fun modeButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label; textSize = 10f; isAllCaps = false
            setPadding(dp(5), 0, dp(5), 0); minWidth = 0; minimumWidth = 0
            setOnClickListener { action() }
            setBackgroundColor(Color.rgb(45, 48, 58))
            setTextColor(Color.rgb(200, 200, 210))
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                marginStart = dp(2); marginEnd = dp(2)
            }
        }

    // --- Undo/Redo ---
    private fun checkpoint() {
        undoStack.addLast(sceneJson())
        while (undoStack.size > 80) undoStack.removeFirst()
        redoStack.clear()
    }
    private fun undo() {
        if (undoStack.isEmpty()) { Toast.makeText(this, "Nothing to undo.", Toast.LENGTH_SHORT).show(); return }
        redoStack.addLast(sceneJson()); restoreSceneJson(undoStack.removeLast())
    }
    private fun redo() {
        if (redoStack.isEmpty()) { Toast.makeText(this, "Nothing to redo.", Toast.LENGTH_SHORT).show(); return }
        undoStack.addLast(sceneJson()); restoreSceneJson(redoStack.removeLast())
    }

    // --- Selection ---
    private fun selectAdjacent(direction: Int) {
        if (scene.isEmpty()) return
        val visibleScene = scene.filter { it.visible }
        if (visibleScene.isEmpty()) return
        val index = visibleScene.indexOfFirst { it.id == selectedId }.let { if (it < 0) 0 else it }
        selectedId = visibleScene[(index + direction + visibleScene.size) % visibleScene.size].id
        syncSceneToRenderer()
    }

    // --- Object management ---
    private fun addObject(type: String) {
        checkpoint()
        val name = "$type ${nextId}"
        val x = ((scene.size % 3) - 1) * 1.4f
        val z = ((scene.size / 3) % 3) * 1.4f
        val obj = SceneObject(nextId++, name, type, x = x, z = z, color = defaultColorForType(type))
        scene.add(obj); selectedId = obj.id
        syncSceneToRenderer(); Toast.makeText(this, "Added $name", Toast.LENGTH_SHORT).show()
    }

    private fun defaultColorForType(type: String): FloatArray = when (type) {
        "Cube" -> floatArrayOf(0.3f, 0.58f, 0.95f); "Sphere" -> floatArrayOf(0.35f, 0.82f, 0.58f)
        "Cylinder" -> floatArrayOf(0.85f, 0.55f, 0.25f); "Cone" -> floatArrayOf(0.9f, 0.65f, 0.2f)
        "Torus" -> floatArrayOf(0.75f, 0.4f, 0.85f); "Plane" -> floatArrayOf(0.5f, 0.5f, 0.55f)
        "Pyramid" -> floatArrayOf(0.95f, 0.8f, 0.3f); "Capsule" -> floatArrayOf(0.2f, 0.7f, 0.9f)
        "IcoSphere" -> floatArrayOf(0.6f, 0.9f, 0.4f); "Light" -> floatArrayOf(1f, 0.85f, 0.3f)
        "Camera" -> floatArrayOf(0.3f, 0.8f, 1f); else -> floatArrayOf(0.5f, 0.5f, 0.5f)
    }

    private fun duplicateSelected() {
        val obj = scene.firstOrNull { it.id == selectedId } ?: return
        checkpoint()
        val copy = obj.copy(id = nextId++, name = "${obj.type} ${nextId - 1}", x = obj.x + 0.5f, z = obj.z + 0.5f)
        scene.add(copy); selectedId = copy.id
        syncSceneToRenderer(); Toast.makeText(this, "Duplicated ${obj.name}", Toast.LENGTH_SHORT).show()
    }

    private fun deleteSelected() {
        if (scene.size <= 1) { Toast.makeText(this, "Keep at least one scene object.", Toast.LENGTH_SHORT).show(); return }
        checkpoint(); scene.removeAll { it.id == selectedId }; selectedId = scene.last().id; syncSceneToRenderer()
    }

    private fun transformSelected(dx: Float = 0f, dy: Float = 0f, dz: Float = 0f,
                                   rotation: Float = 0f, rotX: Float = 0f, scale: Float = 0f) {
        val obj = scene.firstOrNull { it.id == selectedId } ?: return
        if (dx == 0f && dy == 0f && dz == 0f && rotation == 0f && rotX == 0f && scale == 0f) return
        checkpoint()
        obj.x = if (snapEnabled) (obj.x + dx).roundToQuarter() else obj.x + dx
        obj.y = if (snapEnabled) (obj.y + dy).roundToQuarter() else obj.y + dy
        obj.z = if (snapEnabled) (obj.z + dz).roundToQuarter() else obj.z + dz
        obj.rotation += rotation; obj.rotX += rotX
        obj.scale = (obj.scale + scale).coerceIn(0.2f, 4f)
        syncSceneToRenderer()
    }

    private fun Float.roundToQuarter() = Math.round(this * 4f) / 4f

    private fun resetTransform() {
        val obj = scene.firstOrNull { it.id == selectedId } ?: return
        checkpoint()
        obj.x = 0f; obj.y = 0f; obj.z = 0f; obj.rotation = 0f; obj.rotX = 0f; obj.rotZ = 0f; obj.scale = 1f
        syncSceneToRenderer()
    }

    private fun toggleWireframe() {
        val obj = scene.firstOrNull { it.id == selectedId } ?: return
        checkpoint(); obj.wireframe = !obj.wireframe; syncSceneToRenderer()
        Toast.makeText(this, "Wireframe: ${if (obj.wireframe) "on" else "off"}", Toast.LENGTH_SHORT).show()
    }

    private fun toggleVisibility() {
        val obj = scene.firstOrNull { it.id == selectedId } ?: return
        obj.visible = !obj.visible; syncSceneToRenderer()
        Toast.makeText(this, "${obj.name}: ${if (obj.visible) "visible" else "hidden"}", Toast.LENGTH_SHORT).show()
    }

    private val colorPresets = listOf(
        floatArrayOf(0.3f, 0.58f, 0.95f), floatArrayOf(0.35f, 0.82f, 0.58f),
        floatArrayOf(0.85f, 0.55f, 0.25f), floatArrayOf(0.9f, 0.65f, 0.2f),
        floatArrayOf(0.75f, 0.4f, 0.85f), floatArrayOf(0.95f, 0.8f, 0.3f),
        floatArrayOf(0.2f, 0.7f, 0.9f), floatArrayOf(0.6f, 0.9f, 0.4f),
        floatArrayOf(0.9f, 0.3f, 0.3f), floatArrayOf(0.5f, 0.5f, 0.55f)
    )
    private var colorIdx = 0

    // --- Scene sync ---
    private fun syncSceneToRenderer() {
        if (!::renderer.isInitialized) return
        renderer.setScene(scene.map { it.copy() }, selectedId, quality, lights, cameras, skeletons, editorState)
        val visible = scene.filter { it.visible }
        objectLabel.text = "Hierarchy • " + visible.joinToString("   ") {
            if (it.id == selectedId) "▶ ${it.name}" else it.name
        }
        if (::timelineLabel.isInitialized) updateTimelineLabel()
        if (::outlinerText.isInitialized) updateOutliner()
    }

    // --- Scene serialization ---
    private fun sceneJson(): String = JSONObject()
        .put("format", "neuz3d-scene").put("version", 3)
        .put("nextId", nextId).put("selectedId", selectedId)
        .put("objects", objectsToJsonArray(scene))
        .put("lights", lightsToJsonArray(lights))
        .put("cameras", camerasToJsonArray(cameras))
        .put("skeletons", skeletonsToJsonArray(skeletons))
        .toString(2)

    private fun keyframesJson(): String {
        val root = JSONObject()
        keyframes.forEach { (frame, kf) ->
            val kfObj = JSONObject()
            kfObj.put("objects", objectsToJsonArray(kf.objects))
            root.put(frame.toString(), kfObj)
        }
        return root.toString()
    }

    private fun restoreKeyframes(raw: String?) {
        keyframes.clear()
        if (raw.isNullOrBlank()) return
        val json = JSONObject(raw)
        val keys = json.keys()
        while (keys.hasNext()) {
            val frameText = keys.next()
            val frame = frameText.toIntOrNull() ?: continue
            val kfObj = json.optJSONObject(frameText) ?: continue
            val arr = kfObj.optJSONArray("objects") ?: continue
            val list = ArrayList<SceneObject>()
            for (i in 0 until arr.length()) list.add(SceneObject.fromJson(arr.getJSONObject(i)))
            keyframes[frame] = KeyframeData(list, emptyList(), emptyList())
        }
    }

    // --- Timeline ---
    private fun captureKeyframe() {
        val currentBones = if (skeletons.isNotEmpty()) skeletons.first().bones.map { it } else emptyList()
        keyframes[currentFrame] = KeyframeData(scene.map { it.copy() }, lights.map { it }, currentBones)
        prefs.edit().putString("timeline_keyframes", keyframesJson()).apply()
        status.text = "Timeline • frame $currentFrame • ${keyframes.size} keyframe(s)"
        updateTimelineLabel()
        Toast.makeText(this, "Keyframe stored at frame $currentFrame.", Toast.LENGTH_SHORT).show()
    }

    private fun stepTimeline(delta: Int) {
        currentFrame = (currentFrame + delta).coerceIn(0, 240)
        applyTimelineFrame(currentFrame)
        status.text = "Timeline • frame $currentFrame / 240 • ${keyframes.size} keyframe(s)"
        updateTimelineLabel()
    }

    private val timelineTick = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            val first = if (keyframes.isEmpty()) 0 else keyframes.firstKey()
            val last = if (keyframes.isEmpty()) first else keyframes.lastKey()
            currentFrame = if (currentFrame >= last) first else currentFrame + 1
            applyTimelineFrame(currentFrame)
            status.text = "Playing • frame $currentFrame / $last • $playbackFps fps"
            updateTimelineLabel()
            timelineHandler.postDelayed(this, (1000L / playbackFps))
        }
    }

    private fun toggleTimeline() {
        if (isPlaying) {
            isPlaying = false; timelineHandler.removeCallbacks(timelineTick)
            status.text = "Timeline paused at frame $currentFrame"; return
        }
        if (keyframes.size < 2) {
            Toast.makeText(this, "Add at least two keyframes at different frames.", Toast.LENGTH_LONG).show(); return
        }
        isPlaying = true
        if (currentFrame < keyframes.firstKey() || currentFrame >= keyframes.lastKey()) currentFrame = keyframes.firstKey()
        timelineHandler.post(timelineTick)
    }

    private fun applyTimelineFrame(frame: Int) {
        if (keyframes.isEmpty()) return
        val leftEntry = keyframes.floorEntry(frame) ?: keyframes.firstEntry()
        val rightEntry = keyframes.ceilingEntry(frame) ?: keyframes.lastEntry()
        val span = (rightEntry.key - leftEntry.key).coerceAtLeast(1)
        val t = ((frame - leftEntry.key).toFloat() / span).coerceIn(0f, 1f)
        val eased = easeInOutCubic(t)
        val rightById = rightEntry.value.objects.associateBy { it.id }
        val animated = leftEntry.value.objects.map { a ->
            val b = rightById[a.id] ?: a
            a.copy(
                x = a.x + (b.x - a.x) * eased, y = a.y + (b.y - a.y) * eased, z = a.z + (b.z - a.z) * eased,
                scale = a.scale + (b.scale - a.scale) * eased,
                rotation = a.rotation + (b.rotation - a.rotation) * eased,
                rotX = a.rotX + (b.rotX - a.rotX) * eased, rotZ = a.rotZ + (b.rotZ - a.rotZ) * eased
            )
        }
        scene.clear(); scene.addAll(animated)
        if (scene.none { it.id == selectedId } && scene.isNotEmpty()) selectedId = scene.first().id
        syncSceneToRenderer()
    }

    private fun easeInOutCubic(t: Float): Float =
        if (t < 0.5f) 4f * t * t * t else 1f - ((-2f * t + 2f).let { it * it * it }) / 2f

    // --- Save/Load ---
    private fun saveScene() {
        prefs.edit().putString("scene_json", sceneJson()).putString("timeline_keyframes", keyframesJson())
            .putInt("next_id", nextId).putInt("selected_id", selectedId).apply()
        Toast.makeText(this, "Scene saved on this device.", Toast.LENGTH_SHORT).show()
    }

    private val exportRequest = 7403
    private fun exportScene() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; putExtra(Intent.EXTRA_TITLE, "neuz-scene.json")
        }
        startActivityForResult(intent, exportRequest)
    }

    private fun loadScene() {
        val raw = prefs.getString("scene_json", null)
        if (raw == null) { Toast.makeText(this, "No saved scene yet.", Toast.LENGTH_SHORT).show(); return }
        try {
            restoreSceneJson(raw); restoreKeyframes(prefs.getString("timeline_keyframes", null))
            currentFrame = 0; Toast.makeText(this, "Scene and timeline loaded.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Could not load scene: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun restoreSceneJson(raw: String) {
        val wrapper = if (raw.trimStart().startsWith("[")) null else JSONObject(raw)
        val array = if (wrapper == null) org.json.JSONArray(raw) else wrapper.getJSONArray("objects")
        scene.clear()
        for (i in 0 until array.length()) scene.add(SceneObject.fromJson(array.getJSONObject(i)))
        if (scene.isEmpty()) scene.add(SceneObject(nextId++, "Cube"))
        nextId = maxOf(wrapper?.optInt("nextId", 1) ?: 1, (scene.maxOfOrNull { it.id } ?: 0) + 1)
        selectedId = wrapper?.optInt("selectedId", scene.first().id) ?: scene.first().id
        if (scene.none { it.id == selectedId }) selectedId = scene.first().id

        wrapper?.optJSONArray("lights")?.let { arr ->
            lights.clear()
            for (i in 0 until arr.length()) lights.add(SceneLight.fromJson(arr.getJSONObject(i)))
            nextLightId = (lights.maxOfOrNull { it.id } ?: 0) + 1
        }
        wrapper?.optJSONArray("cameras")?.let { arr ->
            cameras.clear()
            for (i in 0 until arr.length()) cameras.add(SceneCamera.fromJson(arr.getJSONObject(i)))
            nextCamId = (cameras.maxOfOrNull { it.id } ?: 0) + 1
        }
        wrapper?.optJSONArray("skeletons")?.let { arr ->
            skeletons.clear()
            for (i in 0 until arr.length()) skeletons.add(Skeleton.fromJson(arr.getJSONObject(i)))
            nextSkelId = (skeletons.maxOfOrNull { it.id } ?: 0) + 1
        }
        syncSceneToRenderer()
    }

    // --- Model import ---
    private val objPickerRequest = 7402
    private var importFormat = "obj"
    private fun pickModel(format: String) {
        importFormat = format
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }
        startActivityForResult(intent, objPickerRequest)
    }

    @Deprecated("Legacy activity result API retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        if (requestCode == exportRequest) {
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(sceneJson()) }
                    ?: error("Could not create output file")
                Toast.makeText(this, "Scene JSON exported.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show() }
            return
        }
        if (requestCode != objPickerRequest) return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not open selected file")
            val parsed = if (GltfParser.isGltf(bytes)) GltfParser.parse(bytes)
                else ObjParser.parse(String(bytes, StandardCharsets.UTF_8))
            renderer.setImportedMesh(parsed)
            val skinInfo = if (parsed.bones.isNotEmpty()) " • ${parsed.bones.size} bones" else ""
            status.text = "Imported • ${parsed.triangles} tris • normals: ${if (parsed.normals.isNotEmpty()) "yes" else "no"}$skinInfo"
            Toast.makeText(this, "Model loaded into viewport.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun cycleQuality() {
        quality = (quality + 1) % 3; syncSceneToRenderer()
        Toast.makeText(this, "Quality: ${listOf("Low", "Balanced", "High")[quality]}", Toast.LENGTH_SHORT).show()
    }

    // --- Touch handling ---
    private var lastX = 0f; private var lastY = 0f
    private var touchStartX = 0f; private var touchStartY = 0f
    private var touchMoved = false; private var lastDistance = 0f

    private fun handleViewportTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                touchStartX = event.x; touchStartY = event.y; touchMoved = false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) lastDistance = distance(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val d = distance(event)
                    if (lastDistance > 0f && d > 0f) renderer.zoomBy(lastDistance / d)
                    lastDistance = d
                } else {
                    val dx = event.x - lastX; val dy = event.y - lastY
                    if (kotlin.math.abs(event.x - touchStartX) + kotlin.math.abs(event.y - touchStartY) > dp(10)) touchMoved = true
                    if (touchMoved) {
                        if (editorState.mode == EditorMode.VERTEX_PAINT_MODE) {
                            handleVertexPaint(event.x, event.y)
                        } else {
                            renderer.orbit(dx * 0.45f, dy * 0.35f)
                        }
                    }
                }
                lastX = event.x; lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!touchMoved && event.pointerCount == 1) {
                    if (editorState.mode == EditorMode.VERTEX_PAINT_MODE) {
                        handleVertexPaint(event.x, event.y)
                    } else {
                        viewport.queueEvent { renderer.pickAt(event.x, event.y) }
                    }
                }
                lastDistance = 0f; return true
            }
            MotionEvent.ACTION_CANCEL -> { lastDistance = 0f; return true }
        }
        return true
    }

    private fun handleVertexPaint(touchX: Float, touchY: Float) {
        val obj = scene.firstOrNull { it.id == selectedId } ?: return
        val meshData = MeshBuilder.build(obj.type)
        val targetPos = floatArrayOf(obj.x, obj.y + 0.5f, obj.z)
        val painted = ModelingTools.paintVertexByProximity(
            meshData.vertices, obj.vertexColors,
            targetPos, editorState.brushColor, editorState.brushRadius, editorState.brushStrength
        )
        obj.vertexColors = painted
        syncSceneToRenderer()
    }

    private fun distance(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1); val dy = e.getY(0) - e.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() { super.onResume(); if (::viewport.isInitialized) viewport.onResume() }
    override fun onPause() {
        isPlaying = false; timelineHandler.removeCallbacks(timelineTick)
        if (::viewport.isInitialized) viewport.onPause()
        super.onPause()
    }
}

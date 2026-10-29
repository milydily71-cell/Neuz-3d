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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
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

    private val scene = mutableListOf<SceneObject>()
    private val undoStack = java.util.ArrayDeque<String>()
    private val redoStack = java.util.ArrayDeque<String>()
    private val keyframes = java.util.TreeMap<Int, List<SceneObject>>()
    private val timelineHandler = Handler(Looper.getMainLooper())
    private var currentFrame = 0
    private var isPlaying = false
    private var playbackFps = 20
    private var nextId = 1
    private var selectedId = 1
    private var quality = 1
    private var rendererMode = 0

    private val prefs by lazy { getSharedPreferences("neuz3d_scene", MODE_PRIVATE) }

    private val primitiveTypes = listOf(
        "Cube", "Sphere", "Cylinder", "Cone", "Torus", "Plane", "Pyramid", "Capsule", "IcoSphere"
    )
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
        buildEditorUi()
    }

    private fun buildEditorUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 19, 25))
        }

        // Header
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
                                "This build requests an OpenGL ES 3.x context. It will not claim ES 3.2 unless the driver reports it.",
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

        // Primitive toolbar
        val tools = horizontalStrip()
        primitiveLabels.forEach { (label, type) ->
            tools.addView(toolButton(label) { addObject(type) })
        }
        tools.addView(toolButton("Light") { addObject("Light") })
        tools.addView(toolButton("Camera") { addObject("Camera") })
        root.addView(tools)

        // Selection and transform toolbar
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

        // Rotation and scale toolbar
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

        // Viewport
        viewport = GLSurfaceView(this)
        viewport.setEGLContextClientVersion(3)
        renderer = SceneRenderer(
            onGpuInfo = { v, rendererName, is32, hasFloat ->
                runOnUiThread {
                    val floatStr = if (hasFloat) "float-buffer" else "no float-buffer"
                    status.text = "GPU: $rendererName • ${if (is32) "ES 3.2" else "ES 3.x"} • $floatStr"
                }
            }
        )
        viewport.setRenderer(renderer)
        viewport.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        viewport.setOnTouchListener { _, event -> handleViewportTouch(event) }
        root.addView(viewport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Object panel with properties
        val objectPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setBackgroundColor(Color.rgb(25, 28, 37))
        }
        objectLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            text = "Hierarchy • Cube"
        }
        objectPanel.addView(objectLabel)

        // Properties buttons
        val actionRow = horizontalStrip()
        actionRow.addView(toolButton("Undo") { undo() })
        actionRow.addView(toolButton("Redo") { redo() })
        actionRow.addView(toolButton("Material") { showMaterialEditor() })
        actionRow.addView(toolButton("Color") { cycleColor() })
        actionRow.addView(toolButton("Reset View") { renderer.resetCamera() })
        actionRow.addView(toolButton("Snap") { toggleSnap() })
        objectPanel.addView(actionRow)

        // Timeline
        val timelineRow = horizontalStrip()
        timelineRow.addView(toolButton("Keyframe") { captureKeyframe() })
        timelineRow.addView(toolButton("Frame −") { stepTimeline(-1) })
        timelineRow.addView(toolButton("Frame +") { stepTimeline(1) })
        timelineRow.addView(toolButton("Play/Stop") { toggleTimeline() })
        timelineRow.addView(toolButton("Clear Keys") { clearKeyframes() })
        timelineRow.addView(toolButton("Speed-") { adjustSpeed(-5) })
        timelineRow.addView(toolButton("Speed+") { adjustSpeed(5) })
        objectPanel.addView(timelineRow)
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
        bottomTools.addView(toolButton("Export JSON") { exportScene() })
        bottomTools.addView(toolButton("Import OBJ") { pickModel("obj") })
        bottomTools.addView(toolButton("Import glTF") { pickModel("gltf") })
        bottomTools.addView(toolButton("Quality") { cycleQuality() })
        bottomTools.addView(toolButton("GPU Info") { showGpuInfo() })
        bottom.addView(bottomTools)

        status = TextView(this).apply {
            text = "Starting renderer…"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(5), 0, 0)
        }
        bottom.addView(status)
        root.addView(bottom)
        setContentView(root)
        syncSceneToRenderer()
    }

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
                .setPositiveButton("OK") { _, _ -> rendererPicker.setSelection(0) }
                .show()
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
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        val metallicLabel = TextView(this).apply { text = "Metallic: ${"%.2f".format(Locale.US, obj.metallic)}"; setTextColor(Color.WHITE) }
        val metallicBar = SeekBar(this).apply {
            max = 100; progress = (obj.metallic * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    obj.metallic = p / 100f
                    metallicLabel.text = "Metallic: ${"%.2f".format(Locale.US, obj.metallic)}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { syncSceneToRenderer() }
            })
        }

        val roughnessLabel = TextView(this).apply { text = "Roughness: ${"%.2f".format(Locale.US, obj.roughness)}"; setTextColor(Color.WHITE) }
        val roughnessBar = SeekBar(this).apply {
            max = 100; progress = (obj.roughness * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    obj.roughness = (p / 100f).coerceIn(0.045f, 1f)
                    roughnessLabel.text = "Roughness: ${"%.2f".format(Locale.US, obj.roughness)}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { syncSceneToRenderer() }
            })
        }

        val redLabel = TextView(this).apply { text = "Red: ${"%.2f".format(Locale.US, obj.color[0])}"; setTextColor(Color.WHITE) }
        val redBar = SeekBar(this).apply {
            max = 100; progress = (obj.color[0] * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    obj.color[0] = p / 100f
                    redLabel.text = "Red: ${"%.2f".format(Locale.US, obj.color[0])}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { syncSceneToRenderer() }
            })
        }

        val greenLabel = TextView(this).apply { text = "Green: ${"%.2f".format(Locale.US, obj.color[1])}"; setTextColor(Color.WHITE) }
        val greenBar = SeekBar(this).apply {
            max = 100; progress = (obj.color[1] * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    obj.color[1] = p / 100f
                    greenLabel.text = "Green: ${"%.2f".format(Locale.US, obj.color[1])}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { syncSceneToRenderer() }
            })
        }

        val blueLabel = TextView(this).apply { text = "Blue: ${"%.2f".format(Locale.US, obj.color[2])}"; setTextColor(Color.WHITE) }
        val blueBar = SeekBar(this).apply {
            max = 100; progress = (obj.color[2] * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    obj.color[2] = p / 100f
                    blueLabel.text = "Blue: ${"%.2f".format(Locale.US, obj.color[2])}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { syncSceneToRenderer() }
            })
        }

        val wireCheck = CheckBox(this).apply {
            text = "Wireframe mode"
            isChecked = obj.wireframe
            setOnCheckedChangeListener { _, checked -> obj.wireframe = checked; syncSceneToRenderer() }
        }

        dialogView.addView(metallicLabel); dialogView.addView(metallicBar)
        dialogView.addView(roughnessLabel); dialogView.addView(roughnessBar)
        dialogView.addView(redLabel); dialogView.addView(redBar)
        dialogView.addView(greenLabel); dialogView.addView(greenBar)
        dialogView.addView(blueLabel); dialogView.addView(blueBar)
        dialogView.addView(wireCheck)

        val scroll = ScrollView(this).apply { addView(dialogView) }

        AlertDialog.Builder(this).setTitle("Material Editor — ${obj.name}")
            .setView(scroll)
            .setPositiveButton("Done") { _, _ -> checkpoint(); syncSceneToRenderer() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private var snapEnabled = false
    private fun toggleSnap() {
        snapEnabled = !snapEnabled
        Toast.makeText(this, "Grid snap: ${if (snapEnabled) "on" else "off"}", Toast.LENGTH_SHORT).show()
    }

    private fun adjustSpeed(delta: Int) {
        playbackFps = (playbackFps + delta).coerceIn(5, 60)
        status.text = "Timeline playback speed: $playbackFps fps"
        Toast.makeText(this, "Playback: $playbackFps fps", Toast.LENGTH_SHORT).show()
    }

    private fun clearKeyframes() {
        if (keyframes.isEmpty()) { Toast.makeText(this, "No keyframes to clear.", Toast.LENGTH_SHORT).show(); return }
        keyframes.clear()
        prefs.edit().remove("timeline_keyframes").apply()
        status.text = "Timeline cleared"
        Toast.makeText(this, "All keyframes cleared.", Toast.LENGTH_SHORT).show()
    }

    private fun horizontalStrip() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(5), dp(3), dp(5), dp(3))
    }

    private fun toolButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 10f
            isAllCaps = false
            setPadding(dp(5), 0, dp(5), 0)
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
        }

    private fun checkpoint() {
        undoStack.addLast(sceneJson())
        while (undoStack.size > 80) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun undo() {
        if (undoStack.isEmpty()) { Toast.makeText(this, "Nothing to undo.", Toast.LENGTH_SHORT).show(); return }
        redoStack.addLast(sceneJson())
        restoreSceneJson(undoStack.removeLast())
    }

    private fun redo() {
        if (redoStack.isEmpty()) { Toast.makeText(this, "Nothing to redo.", Toast.LENGTH_SHORT).show(); return }
        undoStack.addLast(sceneJson())
        restoreSceneJson(redoStack.removeLast())
    }

    private fun selectAdjacent(direction: Int) {
        if (scene.isEmpty()) return
        val visibleScene = scene.filter { it.visible }
        if (visibleScene.isEmpty()) return
        val index = visibleScene.indexOfFirst { it.id == selectedId }.let { if (it < 0) 0 else it }
        selectedId = visibleScene[(index + direction + visibleScene.size) % visibleScene.size].id
        syncSceneToRenderer()
    }

    private fun addObject(type: String) {
        checkpoint()
        val name = "$type ${nextId}"
        val x = ((scene.size % 3) - 1) * 1.4f
        val z = ((scene.size / 3) % 3) * 1.4f
        val obj = SceneObject(nextId++, name, type, x = x, z = z,
            color = defaultColorForType(type))
        scene.add(obj)
        selectedId = obj.id
        syncSceneToRenderer()
        Toast.makeText(this, "Added $name", Toast.LENGTH_SHORT).show()
    }

    private fun defaultColorForType(type: String): FloatArray = when (type) {
        "Cube" -> floatArrayOf(0.3f, 0.58f, 0.95f)
        "Sphere" -> floatArrayOf(0.35f, 0.82f, 0.58f)
        "Cylinder" -> floatArrayOf(0.85f, 0.55f, 0.25f)
        "Cone" -> floatArrayOf(0.9f, 0.65f, 0.2f)
        "Torus" -> floatArrayOf(0.75f, 0.4f, 0.85f)
        "Plane" -> floatArrayOf(0.5f, 0.5f, 0.55f)
        "Pyramid" -> floatArrayOf(0.95f, 0.8f, 0.3f)
        "Capsule" -> floatArrayOf(0.2f, 0.7f, 0.9f)
        "IcoSphere" -> floatArrayOf(0.6f, 0.9f, 0.4f)
        "Light" -> floatArrayOf(1f, 0.85f, 0.3f)
        "Camera" -> floatArrayOf(0.3f, 0.8f, 1f)
        else -> floatArrayOf(0.5f, 0.5f, 0.5f)
    }

    private fun duplicateSelected() {
        val obj = scene.firstOrNull { it.id == selectedId } ?: return
        checkpoint()
        val copy = obj.copy(id = nextId++, name = "${obj.type} ${nextId - 1}", x = obj.x + 0.5f, z = obj.z + 0.5f)
        scene.add(copy)
        selectedId = copy.id
        syncSceneToRenderer()
        Toast.makeText(this, "Duplicated ${obj.name}", Toast.LENGTH_SHORT).show()
    }

    private fun deleteSelected() {
        if (scene.size <= 1) {
            Toast.makeText(this, "Keep at least one scene object.", Toast.LENGTH_SHORT).show()
            return
        }
        checkpoint()
        scene.removeAll { it.id == selectedId }
        selectedId = scene.last().id
        syncSceneToRenderer()
    }

    private fun transformSelected(dx: Float = 0f, dy: Float = 0f, dz: Float = 0f,
                                   rotation: Float = 0f, rotX: Float = 0f, scale: Float = 0f) {
        val obj = scene.firstOrNull { it.id == selectedId } ?: return
        if (dx == 0f && dy == 0f && dz == 0f && rotation == 0f && rotX == 0f && scale == 0f) return
        checkpoint()
        obj.x = if (snapEnabled) (obj.x + dx).roundToQuarter() else obj.x + dx
        obj.y = if (snapEnabled) (obj.y + dy).roundToQuarter() else obj.y + dy
        obj.z = if (snapEnabled) (obj.z + dz).roundToQuarter() else obj.z + dz
        obj.rotation += rotation
        obj.rotX += rotX
        obj.scale = (obj.scale + scale).coerceIn(0.2f, 4f)
        syncSceneToRenderer()
    }

    private fun Float.roundToQuarter() = Math.round(this * 4f) / 4f

    private fun resetTransform() {
        val obj = scene.firstOrNull { it.id == selectedId } ?: return
        checkpoint()
        obj.x = 0f; obj.y = 0f; obj.z = 0f
        obj.rotation = 0f; obj.rotX = 0f; obj.rotZ = 0f
        obj.scale = 1f
        syncSceneToRenderer()
    }

    private fun toggleWireframe() {
        val obj = scene.firstOrNull { it.id == selectedId } ?: return
        checkpoint()
        obj.wireframe = !obj.wireframe
        syncSceneToRenderer()
        Toast.makeText(this, "Wireframe: ${if (obj.wireframe) "on" else "off"}", Toast.LENGTH_SHORT).show()
    }

    private fun toggleVisibility() {
        val obj = scene.firstOrNull { it.id == selectedId } ?: return
        obj.visible = !obj.visible
        syncSceneToRenderer()
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
    private fun cycleColor() {
        val obj = scene.firstOrNull { it.id == selectedId } ?: return
        checkpoint()
        colorIdx = (colorIdx + 1) % colorPresets.size
        obj.color = colorPresets[colorIdx].copyOf()
        syncSceneToRenderer()
    }

    private fun syncSceneToRenderer() {
        if (!::renderer.isInitialized) return
        renderer.setScene(scene.map { it.copy() }, selectedId, quality)
        val visible = scene.filter { it.visible }
        objectLabel.text = "Hierarchy • " + visible.joinToString("   ") {
            if (it.id == selectedId) "▶ ${it.name}" else it.name
        }
    }

    private fun sceneJson(): String = JSONObject()
        .put("format", "neuz3d-scene").put("version", 2)
        .put("nextId", nextId).put("selectedId", selectedId)
        .put("objects", objectsToJsonArray(scene)).toString(2)

    private fun keyframesJson(): String {
        val root = JSONObject()
        keyframes.forEach { (frame, objects) -> root.put(frame.toString(), objectsToJsonArray(objects)) }
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
            val array = json.optJSONArray(frameText) ?: continue
            val list = ArrayList<SceneObject>()
            for (i in 0 until array.length()) {
                list.add(SceneObject.fromJson(array.getJSONObject(i)))
            }
            keyframes[frame] = list
        }
    }

    private fun captureKeyframe() {
        keyframes[currentFrame] = scene.map { it.copy() }
        prefs.edit().putString("timeline_keyframes", keyframesJson()).apply()
        status.text = "Timeline • frame $currentFrame • ${keyframes.size} keyframe(s)"
        Toast.makeText(this, "Keyframe stored at frame $currentFrame.", Toast.LENGTH_SHORT).show()
    }

    private fun stepTimeline(delta: Int) {
        currentFrame = (currentFrame + delta).coerceIn(0, 240)
        applyTimelineFrame(currentFrame)
        status.text = "Timeline • frame $currentFrame / 240 • ${keyframes.size} keyframe(s)"
    }

    private val timelineTick = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            val first = if (keyframes.isEmpty()) 0 else keyframes.firstKey()
            val last = if (keyframes.isEmpty()) first else keyframes.lastKey()
            currentFrame = if (currentFrame >= last) first else currentFrame + 1
            applyTimelineFrame(currentFrame)
            status.text = "Playing • frame $currentFrame / $last • $playbackFps fps"
            timelineHandler.postDelayed(this, (1000L / playbackFps))
        }
    }

    private fun toggleTimeline() {
        if (isPlaying) {
            isPlaying = false
            timelineHandler.removeCallbacks(timelineTick)
            status.text = "Timeline paused at frame $currentFrame"
            return
        }
        if (keyframes.size < 2) {
            Toast.makeText(this, "Add at least two keyframes at different frames to play animation.", Toast.LENGTH_LONG).show()
            return
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
        val rightById = rightEntry.value.associateBy { it.id }
        val animated = leftEntry.value.map { a ->
            val b = rightById[a.id] ?: a
            a.copy(
                x = a.x + (b.x - a.x) * eased,
                y = a.y + (b.y - a.y) * eased,
                z = a.z + (b.z - a.z) * eased,
                scale = a.scale + (b.scale - a.scale) * eased,
                rotation = a.rotation + (b.rotation - a.rotation) * eased,
                rotX = a.rotX + (b.rotX - a.rotX) * eased,
                rotZ = a.rotZ + (b.rotZ - a.rotZ) * eased
            )
        }
        scene.clear()
        scene.addAll(animated)
        if (scene.none { it.id == selectedId } && scene.isNotEmpty()) selectedId = scene.first().id
        syncSceneToRenderer()
    }

    private fun easeInOutCubic(t: Float): Float =
        if (t < 0.5f) 4f * t * t * t else 1f - ((-2f * t + 2f).let { it * it * it }) / 2f

    private fun saveScene() {
        prefs.edit()
            .putString("scene_json", sceneJson())
            .putString("timeline_keyframes", keyframesJson())
            .putInt("next_id", nextId)
            .putInt("selected_id", selectedId)
            .apply()
        Toast.makeText(this, "Scene saved on this device.", Toast.LENGTH_SHORT).show()
    }

    private val exportRequest = 7403
    private fun exportScene() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "neuz-scene.json")
        }
        startActivityForResult(intent, exportRequest)
    }

    private fun loadScene() {
        val raw = prefs.getString("scene_json", null)
        if (raw == null) {
            Toast.makeText(this, "No saved scene yet. Use Save Scene first.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            restoreSceneJson(raw)
            restoreKeyframes(prefs.getString("timeline_keyframes", null))
            currentFrame = 0
            Toast.makeText(this, "Scene and timeline loaded.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not load scene: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreSceneJson(raw: String) {
        val wrapper = if (raw.trimStart().startsWith("[")) null else JSONObject(raw)
        val array = if (wrapper == null) org.json.JSONArray(raw) else wrapper.getJSONArray("objects")
        scene.clear()
        for (i in 0 until array.length()) {
            scene.add(SceneObject.fromJson(array.getJSONObject(i)))
        }
        if (scene.isEmpty()) scene.add(SceneObject(nextId++, "Cube"))
        nextId = maxOf(wrapper?.optInt("nextId", 1) ?: 1, (scene.maxOfOrNull { it.id } ?: 0) + 1)
        selectedId = wrapper?.optInt("selectedId", scene.first().id) ?: scene.first().id
        if (scene.none { it.id == selectedId }) selectedId = scene.first().id
        syncSceneToRenderer()
    }

    private val objPickerRequest = 7402
    private var importFormat = "obj"
    private fun pickModel(format: String) {
        importFormat = format
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
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
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode != objPickerRequest) return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Could not open selected file")
            val parsed = if (GltfParser.isGltf(bytes)) GltfParser.parse(bytes)
                else ObjParser.parse(String(bytes, StandardCharsets.UTF_8))
            renderer.setImportedMesh(parsed)
            status.text = "Imported model • ${parsed.triangles} triangles • PBR metallic ${"%.2f".format(Locale.US, parsed.metallic)} • roughness ${"%.2f".format(Locale.US, parsed.roughness)}"
            Toast.makeText(this, "Model geometry loaded into viewport.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Model import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun cycleQuality() {
        quality = (quality + 1) % 3
        syncSceneToRenderer()
        Toast.makeText(this, "Viewport quality: ${listOf("Low", "Balanced", "High")[quality]}", Toast.LENGTH_SHORT).show()
    }

    private var lastX = 0f
    private var lastY = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchMoved = false
    private var lastDistance = 0f
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
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (kotlin.math.abs(event.x - touchStartX) + kotlin.math.abs(event.y - touchStartY) > dp(10)) touchMoved = true
                    if (touchMoved) renderer.orbit(dx * 0.45f, dy * 0.35f)
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!touchMoved && event.pointerCount == 1) {
                    val pickX = event.x
                    val pickY = event.y
                    viewport.queueEvent { renderer.pickAt(pickX, pickY) }
                }
                lastDistance = 0f
                return true
            }
            MotionEvent.ACTION_CANCEL -> { lastDistance = 0f; return true }
        }
        return true
    }

    private fun distance(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() { super.onResume(); if (::viewport.isInitialized) viewport.onResume() }
    override fun onPause() {
        isPlaying = false
        timelineHandler.removeCallbacks(timelineTick)
        if (::viewport.isInitialized) viewport.onPause()
        super.onPause()
    }
}

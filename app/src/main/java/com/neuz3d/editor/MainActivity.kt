package com.neuz3d.editor

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.FloatBuffer
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

/**
 * Native-first mobile 3D editor bootstrap.
 * The viewport is a real GLES 3.x context with a perspective camera, ground grid,
 * colored axes, a lit cube, touch orbit/pinch controls, scene objects and local save/load.
 */
class MainActivity : Activity() {
    private external fun nativeEngineInfo(): String
    private lateinit var viewport: GLSurfaceView
    private lateinit var renderer: SceneRenderer
    private lateinit var status: TextView
    private lateinit var objectLabel: TextView
    private lateinit var qualityPicker: Spinner
    private val scene = mutableListOf<SceneObject>()
    private val undoStack = java.util.ArrayDeque<String>()
    private val redoStack = java.util.ArrayDeque<String>()
    private val keyframes = java.util.TreeMap<Int, List<SceneObject>>()
    private val timelineHandler = Handler(Looper.getMainLooper())
    private var currentFrame = 0
    private var isPlaying = false
    private var nextId = 1
    private var selectedId = 1
    private var quality = 1
    private val prefs by lazy { getSharedPreferences("neuz3d_scene", MODE_PRIVATE) }

    data class ImportedMesh(
        val positions: FloatArray,
        val triangles: Int,
        val baseColor: FloatArray = floatArrayOf(1f, 1f, 1f),
        val metallic: Float = 0.05f,
        val roughness: Float = 0.75f
    )

    data class SceneObject(
        val id: Int,
        var name: String,
        var type: String = "Cube",
        var x: Float = 0f,
        var y: Float = 0f,
        var z: Float = 0f,
        var scale: Float = 1f,
        var rotation: Float = 0f
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            System.loadLibrary("neuz3d")
        } catch (e: UnsatisfiedLinkError) {
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

        val rendererPicker = Spinner(this)
        rendererPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Automatic", "OpenGL ES", "Vulkan"))
        rendererPicker.setSelection(0)
        rendererPicker.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    0 -> status.text = "Renderer: Automatic • GLES viewport active"
                    1 -> {
                        val detected = renderer.version
                        if (detected.contains("OpenGL ES 3.2", ignoreCase = true)) {
                            status.text = "OpenGL ES 3.2 detected • $detected"
                        } else {
                            status.text = "GLES 3.2 not confirmed; current context: " + detected.ifBlank { "checking device…" }
                            Toast.makeText(this@MainActivity,
                                "This build requests an OpenGL ES 3.x context. It will not claim ES 3.2 unless the driver reports it.", Toast.LENGTH_LONG).show()
                        }
                    }
                    2 -> {
                        Toast.makeText(this@MainActivity,
                            "Vulkan is not implemented in this build yet. Staying on the working GLES renderer.", Toast.LENGTH_LONG).show()
                        rendererPicker.setSelection(0)
                    }
                }
            }
        })
        header.addView(rendererPicker, LinearLayout.LayoutParams(dp(138), dp(44)))
        root.addView(header)

        val tools = horizontalStrip()
        tools.addView(toolButton("+ Cube") { addObject("Cube") })
        tools.addView(toolButton("+ Sphere") { addObject("Sphere") })
        tools.addView(toolButton("+ Light") { addObject("Light") })
        tools.addView(toolButton("+ Camera") { addObject("Camera") })
        tools.addView(toolButton("Delete") { deleteSelected() })
        tools.addView(toolButton("Select ◀") { selectAdjacent(-1) })
        tools.addView(toolButton("Select ▶") { selectAdjacent(1) })
        root.addView(tools)

        viewport = GLSurfaceView(this)
        viewport.setEGLContextClientVersion(3)
        renderer = SceneRenderer(
            onGpuInfo = { v, rendererName ->
                runOnUiThread {
                    status.text = "GPU: $rendererName • $v"
                }
            },
            onFrame = {},
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
        val actionRow = horizontalStrip()
        actionRow.addView(toolButton("Undo") { undo() })
        actionRow.addView(toolButton("Redo") { redo() })
        actionRow.addView(toolButton("Move X+") { transformSelected(dx = 0.25f) })
        actionRow.addView(toolButton("Move Y+") { transformSelected(dy = 0.25f) })
        actionRow.addView(toolButton("Rotate") { transformSelected(rotation = 15f) })
        actionRow.addView(toolButton("Scale +") { transformSelected(scale = 0.1f) })
        actionRow.addView(toolButton("Reset View") { renderer.resetCamera() })
        objectPanel.addView(actionRow)
        val timelineRow = horizontalStrip()
        timelineRow.addView(toolButton("Keyframe") { captureKeyframe() })
        timelineRow.addView(toolButton("Frame −") { stepTimeline(-1) })
        timelineRow.addView(toolButton("Frame +") { stepTimeline(1) })
        timelineRow.addView(toolButton("Play / Stop") { toggleTimeline() })
        objectPanel.addView(timelineRow)
        root.addView(objectPanel)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(8))
            setBackgroundColor(Color.rgb(19, 21, 28))
        }
        val bottomTools = horizontalStrip()
        bottomTools.addView(toolButton("Save Scene") { saveScene() })
        bottomTools.addView(toolButton("Load Scene") { loadScene() })
        bottomTools.addView(toolButton("Export JSON") { exportScene() })
        bottomTools.addView(toolButton("Import OBJ") { pickModel() })
        bottomTools.addView(toolButton("Import glTF/GLB") { pickModel() })
        bottomTools.addView(toolButton("Quality") { cycleQuality() })
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
        val index = scene.indexOfFirst { it.id == selectedId }.let { if (it < 0) 0 else it }
        selectedId = scene[(index + direction + scene.size) % scene.size].id
        syncSceneToRenderer()
    }

    private fun addObject(type: String) {
        checkpoint()
        val name = when (type) {
            "Cube" -> "Cube"
            else -> type
        } + " ${nextId}"
        val obj = SceneObject(nextId++, name, type, x = ((scene.size % 3) - 1) * 1.4f)
        scene.add(obj)
        selectedId = obj.id
        syncSceneToRenderer()
        Toast.makeText(this, "Added $name", Toast.LENGTH_SHORT).show()
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

    private fun transformSelected(dx: Float = 0f, dy: Float = 0f, rotation: Float = 0f, scale: Float = 0f) {
        val obj = scene.firstOrNull { it.id == selectedId } ?: return
        if (dx == 0f && dy == 0f && rotation == 0f && scale == 0f) return
        checkpoint()
        obj.x += dx
        obj.y += dy
        obj.rotation += rotation
        obj.scale = (obj.scale + scale).coerceIn(0.2f, 4f)
        syncSceneToRenderer()
    }

    private fun syncSceneToRenderer() {
        if (!::renderer.isInitialized) return
        renderer.setScene(scene.map { it.copy() }, selectedId, quality)
        objectLabel.text = "Hierarchy • " + scene.joinToString("   ") {
            if (it.id == selectedId) "▶ ${it.name}" else it.name
        }
    }

    private fun objectsJson(objects: List<SceneObject>): JSONArray {
        val array = JSONArray()
        objects.forEach { o ->
            array.put(JSONObject().put("id", o.id).put("name", o.name).put("type", o.type)
                .put("x", o.x).put("y", o.y).put("z", o.z).put("scale", o.scale).put("rotation", o.rotation))
        }
        return array
    }

    private fun sceneJson(): String = JSONObject().put("format", "neuz3d-scene").put("version", 1)
        .put("nextId", nextId).put("selectedId", selectedId).put("objects", objectsJson(scene)).toString(2)

    private fun keyframesJson(): String {
        val root = JSONObject()
        keyframes.forEach { (frame, objects) -> root.put(frame.toString(), objectsJson(objects)) }
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
                val o = array.getJSONObject(i)
                list.add(SceneObject(o.getInt("id"), o.getString("name"), o.optString("type", "Cube"),
                    o.optDouble("x", 0.0).toFloat(), o.optDouble("y", 0.0).toFloat(),
                    o.optDouble("z", 0.0).toFloat(), o.optDouble("scale", 1.0).toFloat(),
                    o.optDouble("rotation", 0.0).toFloat()))
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
        currentFrame = (currentFrame + delta).coerceIn(0, 120)
        applyTimelineFrame(currentFrame)
        status.text = "Timeline • frame $currentFrame / 120 • ${keyframes.size} keyframe(s)"
    }

    private val timelineTick = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            val first = if (keyframes.isEmpty()) 0 else keyframes.firstKey()
            val last = if (keyframes.isEmpty()) first else keyframes.lastKey()
            currentFrame = if (currentFrame >= last) first else currentFrame + 1
            applyTimelineFrame(currentFrame)
            status.text = "Playing • frame $currentFrame / $last"
            timelineHandler.postDelayed(this, 50L)
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
        val rightById = rightEntry.value.associateBy { it.id }
        val animated = leftEntry.value.map { a ->
            val b = rightById[a.id] ?: a
            a.copy(
                x = a.x + (b.x - a.x) * t,
                y = a.y + (b.y - a.y) * t,
                z = a.z + (b.z - a.z) * t,
                scale = a.scale + (b.scale - a.scale) * t,
                rotation = a.rotation + (b.rotation - a.rotation) * t
            )
        }
        scene.clear()
        scene.addAll(animated)
        if (scene.none { it.id == selectedId } && scene.isNotEmpty()) selectedId = scene.first().id
        syncSceneToRenderer()
    }

    private fun saveScene() {
        prefs.edit().putString("scene_json", sceneJson()).putString("timeline_keyframes", keyframesJson())
            .putInt("next_id", nextId).putInt("selected_id", selectedId).apply()
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
        val array = if (wrapper == null) JSONArray(raw) else wrapper.getJSONArray("objects")
        scene.clear()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            scene.add(SceneObject(o.getInt("id"), o.getString("name"), o.optString("type", "Cube"),
                o.optDouble("x", 0.0).toFloat(), o.optDouble("y", 0.0).toFloat(),
                o.optDouble("z", 0.0).toFloat(), o.optDouble("scale", 1.0).toFloat(),
                o.optDouble("rotation", 0.0).toFloat()))
        }
        if (scene.isEmpty()) scene.add(SceneObject(nextId++, "Cube"))
        nextId = max(wrapper?.optInt("nextId", 1) ?: 1, (scene.maxOfOrNull { it.id } ?: 0) + 1)
        selectedId = wrapper?.optInt("selectedId", scene.first().id) ?: scene.first().id
        if (scene.none { it.id == selectedId }) selectedId = scene.first().id
        syncSceneToRenderer()
    }

    private val objPickerRequest = 7402
    private fun pickModel() {
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

    private class SceneRenderer(
        private val onGpuInfo: (String, String) -> Unit,
        private val onFrame: () -> Unit,
        private val onPick: (Int) -> Unit
    ) : GLSurfaceView.Renderer {
        @Volatile private var objects: List<SceneObject> = emptyList()
        @Volatile private var selectedId: Int = 1
        @Volatile private var quality: Int = 1
        @Volatile private var importedPositions: FloatArray? = null
        @Volatile private var importedTriangles: Int = 0
        @Volatile private var importedBaseColor = floatArrayOf(1f, 1f, 1f)
        @Volatile private var importedMetallic = 0.05f
        @Volatile private var importedRoughness = 0.75f
        @Volatile var version: String = ""
            private set
        private var program = 0
        private var aPosition = 0
        private var aColor = 0
        private var uMvp = 0
        private var uLight = 0
        private var uMetallic = -1
        private var uRoughness = -1
        private var uBaseColor = -1
        private var width = 1
        private var height = 1
        private var yaw = 35f
        private var pitch = 24f
        private var distance = 9f
        private val projection = FloatArray(16)
        private val view = FloatArray(16)
        private val model = FloatArray(16)
        private val mvp = FloatArray(16)
        private val temp = FloatArray(16)
        private var gridBuffer: FloatBuffer? = null
        private var cubeBuffer: FloatBuffer? = null
        private var sphereBuffer: FloatBuffer? = null
        private var sphereCount = 0
        private var axisBuffer: FloatBuffer? = null
        private var markerBuffer: FloatBuffer? = null
        private var gizmoBuffer: FloatBuffer? = null
        private var importedBuffer: FloatBuffer? = null
        private val vboCache = java.util.IdentityHashMap<FloatBuffer, Int>()
        private var gridCount = 0

        fun setScene(newObjects: List<SceneObject>, selected: Int, newQuality: Int) {
            objects = newObjects
            selectedId = selected
            quality = newQuality
        }
        fun orbit(dx: Float, dy: Float) {
            yaw = (yaw + dx) % 360f
            pitch = (pitch + dy).coerceIn(-10f, 78f)
        }
        fun zoomBy(factor: Float) { distance = (distance * factor).coerceIn(2.5f, 35f) }
        fun resetCamera() { yaw = 35f; pitch = 24f; distance = 9f }
        fun pickAt(screenX: Float, screenY: Float) {
            val viewModelPick = FloatArray(16)
            val mvpPick = FloatArray(16)
            val modelPick = FloatArray(16)
            val eye = FloatArray(4)
            val clipPosition = floatArrayOf(0f, 0f, 0f, 1f)
            var bestId = -1
            var bestDistance = 56f * 56f
            for (obj in objects) {
                if (obj.type == "Light" || obj.type == "Camera") continue
                Matrix.setIdentityM(modelPick, 0)
                Matrix.translateM(modelPick, 0, obj.x, obj.y + 0.5f, obj.z)
                Matrix.multiplyMM(viewModelPick, 0, view, 0, modelPick, 0)
                Matrix.multiplyMM(mvpPick, 0, projection, 0, viewModelPick, 0)
                Matrix.multiplyMV(eye, 0, mvpPick, 0, clipPosition, 0)
                if (eye[3] <= 0f) continue
                val sx = (eye[0] / eye[3] * 0.5f + 0.5f) * width
                val sy = (1f - (eye[1] / eye[3] * 0.5f + 0.5f)) * height
                val dx = sx - screenX; val dy = sy - screenY
                val d2 = dx * dx + dy * dy
                if (d2 < bestDistance) { bestDistance = d2; bestId = obj.id }
            }
            if (bestId >= 0) onPick(bestId)
        }
        fun setImportedMesh(mesh: ImportedMesh) {
            importedPositions = mesh.positions
            importedTriangles = mesh.triangles
            importedBaseColor = mesh.baseColor.copyOf()
            importedMetallic = mesh.metallic
            importedRoughness = mesh.roughness
            importedBuffer = floatBuffer(mesh.positions)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            vboCache.clear()
            GLES20.glClearColor(0.055f, 0.065f, 0.085f, 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            val glVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: "unknown"
            val shading = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION) ?: "unknown"
            version = "$glVersion • GLSL $shading"
            val vendor = GLES20.glGetString(GLES20.GL_RENDERER) ?: "GPU"
            onGpuInfo(version, vendor)
            val vs = compile(GLES20.GL_VERTEX_SHADER,
                "attribute vec3 aPosition; attribute vec3 aColor; uniform mat4 uMvp; varying vec3 vColor; varying vec3 vNormal; void main(){ gl_Position=uMvp*vec4(aPosition,1.0); vColor=aColor; vNormal=normalize(aPosition); }")
            val fs = compile(GLES20.GL_FRAGMENT_SHADER,
                "precision highp float; varying vec3 vColor; varying vec3 vNormal; uniform float uMetallic; uniform float uRoughness; uniform vec3 uBaseColor; const float PI=3.14159265359; vec3 fresnelSchlick(float c,vec3 F0){return F0+(1.0-F0)*pow(clamp(1.0-c,0.0,1.0),5.0);} float D_GGX(vec3 N,vec3 H,float r){float a=r*r;float a2=a*a;float nh=max(dot(N,H),0.0);float d=nh*nh*(a2-1.0)+1.0;return a2/max(PI*d*d,0.0001);} float G1(float nv,float r){float k=(r+1.0);k=k*k/8.0;return nv/max(nv*(1.0-k)+k,0.0001);} void main(){vec3 N=normalize(vNormal);vec3 V=vec3(0.0,0.0,1.0);vec3 L=normalize(vec3(-0.45,0.85,0.55));vec3 H=normalize(V+L);float m=clamp(uMetallic,0.0,1.0);float r=clamp(uRoughness,0.045,1.0);float nv=max(dot(N,V),0.0);float nl=max(dot(N,L),0.0);vec3 albedo=max(vColor*uBaseColor,vec3(0.0));vec3 F0=mix(vec3(0.04),albedo,m);vec3 F=fresnelSchlick(max(dot(H,V),0.0),F0);float D=D_GGX(N,H,r);float G=G1(nv,r)*G1(nl,r);vec3 spec=(D*G*F)/max(4.0*nv*nl,0.001);vec3 kd=(vec3(1.0)-F)*(1.0-m);float sky=clamp(N.y*0.5+0.5,0.0,1.0);vec3 env=mix(vec3(0.055,0.065,0.08),vec3(0.28,0.38,0.55),sky);vec3 color=kd*albedo*env+(kd*albedo/PI+spec)*vec3(3.0,2.85,2.65)*nl+F*env*(1.0-r*0.7);color=color/(color+vec3(1.0));color=pow(max(color,vec3(0.0)),vec3(1.0/2.2));gl_FragColor=vec4(color,1.0);}")
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glBindAttribLocation(program, 0, "aPosition")
            GLES20.glBindAttribLocation(program, 1, "aColor")
            GLES20.glLinkProgram(program)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] == GLES20.GL_TRUE) { "Program link failed: " + GLES20.glGetProgramInfoLog(program) }
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aColor = GLES20.glGetAttribLocation(program, "aColor")
            uMvp = GLES20.glGetUniformLocation(program, "uMvp")
            uMetallic = GLES20.glGetUniformLocation(program, "uMetallic")
            uRoughness = GLES20.glGetUniformLocation(program, "uRoughness")
            uBaseColor = GLES20.glGetUniformLocation(program, "uBaseColor")
            createGeometry()
        }

        private fun createGeometry() {
            val grid = ArrayList<Float>()
            for (i in -20..20) {
                val c = if (i == 0) 0.28f else if (i % 5 == 0) 0.16f else 0.09f
                addLine(grid, i.toFloat(), 0f, -20f, i.toFloat(), 0f, 20f, c, c, c)
                addLine(grid, -20f, 0f, i.toFloat(), 20f, 0f, i.toFloat(), c, c, c)
            }
            gridBuffer = floatBuffer(grid.toFloatArray())
            gridCount = grid.size / 6
            val axes = ArrayList<Float>()
            addLine(axes, -20f, 0.006f, 0f, 20f, 0.006f, 0f, 0.95f, 0.20f, 0.20f)
            addLine(axes, 0f, -0.006f, -20f, 0f, -0.006f, 20f, 0.20f, 0.45f, 1f)
            addLine(axes, 0f, 0f, 0f, 0f, 6f, 0f, 0.25f, 0.95f, 0.40f)
            axisBuffer = floatBuffer(axes.toFloatArray())
            val marker = ArrayList<Float>()
            addLine(marker, -0.45f, 0f, 0f, 0.45f, 0f, 0f, 1f, 0.72f, 0.20f)
            addLine(marker, 0f, -0.45f, 0f, 0f, 0.45f, 0f, 1f, 0.72f, 0.20f)
            addLine(marker, 0f, 0f, -0.45f, 0f, 0f, 0.45f, 1f, 0.72f, 0.20f)
            markerBuffer = floatBuffer(marker.toFloatArray())
            val gizmo = ArrayList<Float>()
            addLine(gizmo, 0f, 0f, 0f, 1.35f, 0f, 0f, 1f, 0.2f, 0.2f)
            addLine(gizmo, 0f, 0f, 0f, 0f, 1.35f, 0f, 0.25f, 1f, 0.35f)
            addLine(gizmo, 0f, 0f, 0f, 0f, 0f, 1.35f, 0.2f, 0.5f, 1f)
            gizmoBuffer = floatBuffer(gizmo.toFloatArray())
            val cube = ArrayList<Float>()
            val p = arrayOf(
                floatArrayOf(-0.5f,-0.5f, 0.5f), floatArrayOf(0.5f,-0.5f, 0.5f),
                floatArrayOf(0.5f,0.5f,0.5f), floatArrayOf(-0.5f,0.5f,0.5f),
                floatArrayOf(-0.5f,-0.5f,-0.5f), floatArrayOf(0.5f,-0.5f,-0.5f),
                floatArrayOf(0.5f,0.5f,-0.5f), floatArrayOf(-0.5f,0.5f,-0.5f)
            )
            val faces = arrayOf(
                intArrayOf(0,1,2,0,2,3), intArrayOf(5,4,7,5,7,6),
                intArrayOf(4,0,3,4,3,7), intArrayOf(1,5,6,1,6,2),
                intArrayOf(3,2,6,3,6,7), intArrayOf(4,5,1,4,1,0)
            )
            val faceColors = arrayOf(
                floatArrayOf(0.18f,0.58f,0.98f), floatArrayOf(0.12f,0.38f,0.72f),
                floatArrayOf(0.16f,0.45f,0.82f), floatArrayOf(0.25f,0.65f,1f),
                floatArrayOf(0.38f,0.72f,1f), floatArrayOf(0.10f,0.30f,0.58f)
            )
            for (f in faces.indices) for (idx in faces[f]) {
                val v = p[idx]; val c = faceColors[f]
                cube.add(v[0]); cube.add(v[1]); cube.add(v[2]); cube.add(c[0]); cube.add(c[1]); cube.add(c[2])
            }
            cubeBuffer = floatBuffer(cube.toFloatArray())
            val sphere = ArrayList<Float>()
            val rings = 12
            val segments = 18
            for (ring in 0 until rings) {
                val lat0 = (-Math.PI / 2.0 + Math.PI * ring / rings)
                val lat1 = (-Math.PI / 2.0 + Math.PI * (ring + 1) / rings)
                for (seg in 0 until segments) {
                    val lon0 = 2.0 * Math.PI * seg / segments
                    val lon1 = 2.0 * Math.PI * (seg + 1) / segments
                    val v00 = spherePoint(lat0, lon0); val v01 = spherePoint(lat0, lon1)
                    val v10 = spherePoint(lat1, lon0); val v11 = spherePoint(lat1, lon1)
                    addSphereVertex(sphere, v00); addSphereVertex(sphere, v10); addSphereVertex(sphere, v11)
                    addSphereVertex(sphere, v00); addSphereVertex(sphere, v11); addSphereVertex(sphere, v01)
                }
            }
            sphereBuffer = floatBuffer(sphere.toFloatArray())
            sphereCount = sphere.size / 6
        }

        private fun spherePoint(lat: Double, lon: Double): FloatArray =
            floatArrayOf((kotlin.math.cos(lat) * kotlin.math.cos(lon)).toFloat(),
                kotlin.math.sin(lat).toFloat(), (kotlin.math.cos(lat) * kotlin.math.sin(lon)).toFloat())

        private fun addSphereVertex(out: MutableList<Float>, p: FloatArray) {
            out.add(p[0] * 0.5f); out.add(p[1] * 0.5f); out.add(p[2] * 0.5f)
            out.add(0.35f); out.add(0.82f); out.add(0.58f)
        }

        private fun addLine(out: MutableList<Float>, x1: Float, y1: Float, z1: Float,
                            x2: Float, y2: Float, z2: Float, r: Float, g: Float, b: Float) {
            out.addAll(listOf(x1,y1,z1,r,g,b,x2,y2,z2,r,g,b))
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            width = max(1, w); height = max(1, h)
            GLES20.glViewport(0, 0, width, height)
            Matrix.perspectiveM(projection, 0, 48f, width.toFloat() / height.toFloat(), 0.1f, 120f)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (program == 0) return
            val yr = Math.toRadians(yaw.toDouble()).toFloat()
            val pr = Math.toRadians(pitch.toDouble()).toFloat()
            val eyeX = (distance * kotlin.math.cos(pr) * kotlin.math.sin(yr))
            val eyeY = distance * kotlin.math.sin(pr)
            val eyeZ = (distance * kotlin.math.cos(pr) * kotlin.math.cos(yr))
            Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, 0f, 0.8f, 0f, 0f, 1f, 0f)
            GLES20.glUseProgram(program)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            Matrix.setIdentityM(model, 0)
            drawBuffer(gridBuffer, gridCount, GLES20.GL_LINES, model)
            drawBuffer(axisBuffer, 6, GLES20.GL_LINES, model)
            val current = objects
            current.forEach { obj ->
                if (obj.type == "Light" || obj.type == "Camera") {
                    drawMarker(obj)
                } else {
                    Matrix.setIdentityM(model, 0)
                    Matrix.translateM(model, 0, obj.x, obj.y + if (obj.id == selectedId) 0.52f else 0.5f, obj.z)
                    Matrix.rotateM(model, 0, obj.rotation, 0f, 1f, 0f)
                    Matrix.scaleM(model, 0, obj.scale, obj.scale, obj.scale)
                    if (obj.id == selectedId) GLES20.glLineWidth(2f) else GLES20.glLineWidth(1f)
                    if (obj.type == "Sphere") drawBuffer(sphereBuffer, sphereCount, GLES20.GL_TRIANGLES, model)
                    else drawBuffer(cubeBuffer, 36, GLES20.GL_TRIANGLES, model)
                    if (obj.id == selectedId) {
                        Matrix.setIdentityM(model, 0)
                        Matrix.translateM(model, 0, obj.x, obj.y + 0.5f, obj.z)
                        drawBuffer(gizmoBuffer, 6, GLES20.GL_LINES, model)
                    }
                }
            }
            importedBuffer?.let { buf ->
                Matrix.setIdentityM(model, 0)
                Matrix.translateM(model, 0, 0f, 1.5f, 0f)
                Matrix.scaleM(model, 0, 1f, 1f, 1f)
                drawRawPositionBuffer(buf, importedTriangles * 3)
            }
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            onFrame()
        }

        private fun drawMarker(obj: SceneObject) {
            // Shared immutable geometry avoids a fresh Java list, FloatBuffer and GPU upload each frame.
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, obj.x, obj.y + 0.7f, obj.z)
            drawBuffer(markerBuffer, 6, GLES20.GL_LINES, model)
        }

        private fun getVbo(buffer: FloatBuffer): Int {
            val cached = vboCache[buffer]
            if (cached != null) return cached
            val ids = IntArray(1)
            GLES20.glGenBuffers(1, ids, 0)
            val id = ids[0]
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id)
            val upload = buffer.duplicate()
            upload.position(0)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, upload.capacity() * 4, upload, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            vboCache[buffer] = id
            return id
        }

        private fun drawRawPositionBuffer(buf: FloatBuffer, count: Int) {
            if (count <= 0) return
            Matrix.multiplyMM(temp, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, temp, 0)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            if (uMetallic >= 0) GLES20.glUniform1f(uMetallic, importedMetallic)
            if (uRoughness >= 0) GLES20.glUniform1f(uRoughness, importedRoughness)
            if (uBaseColor >= 0) GLES20.glUniform3f(uBaseColor, importedBaseColor[0], importedBaseColor[1], importedBaseColor[2])
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, getVbo(buf))
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glDisableVertexAttribArray(aColor)
            GLES20.glVertexAttrib3f(aColor, 0.85f, 0.48f, 0.25f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, min(count, buf.capacity() / 3))
            GLES20.glEnableVertexAttribArray(aColor)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }

        private fun drawBuffer(buffer: FloatBuffer?, count: Int, mode: Int, modelMatrix: FloatArray) {
            if (buffer == null || count <= 0) return
            Matrix.multiplyMM(temp, 0, view, 0, modelMatrix, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, temp, 0)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            if (uMetallic >= 0) GLES20.glUniform1f(uMetallic, if (quality >= 2) 0.38f else 0.12f)
            if (uRoughness >= 0) GLES20.glUniform1f(uRoughness, when (quality) { 0 -> 0.82f; 1 -> 0.48f; else -> 0.24f })
            if (uBaseColor >= 0) GLES20.glUniform3f(uBaseColor, 1f, 1f, 1f)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, getVbo(buffer))
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glEnableVertexAttribArray(aColor)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 24, 0)
            GLES20.glVertexAttribPointer(aColor, 3, GLES20.GL_FLOAT, false, 24, 12)
            GLES20.glDrawArrays(mode, 0, count)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] == GLES20.GL_TRUE) { "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader) }
            return shader
        }

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    }

 
    /**
     * Geometry-focused glTF 2.0 importer. Supports GLB 2.0 and glTF JSON with embedded
     * data-URI buffers; external .bin files, skins, morph targets and material textures
     * are intentionally rejected instead of silently importing broken geometry.
     */
    private object GltfParser {
        private const val GLB_MAGIC = 0x46546C67
        private const val JSON_CHUNK = 0x4E4F534A
        private const val BIN_CHUNK = 0x004E4942

        fun isGltf(bytes: ByteArray): Boolean {
            if (bytes.size >= 4 && ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int == GLB_MAGIC) return true
            return String(bytes, 0, min(bytes.size, 32), StandardCharsets.UTF_8).trimStart().startsWith("{")
        }

        fun parse(bytes: ByteArray): ImportedMesh {
            var jsonText: String
            var binaryChunk: ByteArray? = null
            if (bytes.size >= 12 && ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int == GLB_MAGIC) {
                val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                require(input.int == GLB_MAGIC) { "Invalid GLB magic" }
                require(input.int == 2) { "Only GLB version 2 is supported" }
                val declaredLength = input.int
                require(declaredLength <= bytes.size && declaredLength >= 12) { "Invalid GLB length" }
                var json: ByteArray? = null
                while (input.position() + 8 <= declaredLength) {
                    val length = input.int
                    val type = input.int
                    require(length >= 0 && input.position().toLong() + length <= declaredLength.toLong()) { "Invalid GLB chunk" }
                    val chunk = ByteArray(length)
                    input.get(chunk)
                    if (type == JSON_CHUNK) json = chunk
                    if (type == BIN_CHUNK) binaryChunk = chunk
                }
                jsonText = String(json ?: error("GLB has no JSON chunk"), StandardCharsets.UTF_8).trimEnd('\u0000', ' ', '\n', '\r', '\t')
            } else {
                jsonText = String(bytes, StandardCharsets.UTF_8)
            }
            val root = JSONObject(jsonText)
            var baseColor = floatArrayOf(1f, 1f, 1f)
            var metallic = 1f
            var roughness = 1f
            val materials = root.optJSONArray("materials")
            if (materials != null && materials.length() > 0) {
                val pbr = materials.getJSONObject(0).optJSONObject("pbrMetallicRoughness")
                if (pbr != null) {
                    val factor = pbr.optJSONArray("baseColorFactor")
                    if (factor != null && factor.length() >= 3) {
                        baseColor = floatArrayOf(factor.optDouble(0, 1.0).toFloat().coerceIn(0f, 1f),
                            factor.optDouble(1, 1.0).toFloat().coerceIn(0f, 1f),
                            factor.optDouble(2, 1.0).toFloat().coerceIn(0f, 1f))
                    }
                    metallic = pbr.optDouble("metallicFactor", 1.0).toFloat().coerceIn(0f, 1f)
                    roughness = pbr.optDouble("roughnessFactor", 1.0).toFloat().coerceIn(0.045f, 1f)
                }
            }
            val buffersJson = root.optJSONArray("buffers") ?: error("glTF has no buffers")
            val buffers = ArrayList<ByteArray>()
            for (i in 0 until buffersJson.length()) {
                val item = buffersJson.getJSONObject(i)
                val uri = item.optString("uri", "")
                val data = when {
                    uri.startsWith("data:") -> {
                        val comma = uri.indexOf(',')
                        require(comma >= 0 && uri.substring(0, comma).contains(";base64")) { "Only base64 embedded buffers are supported" }
                        android.util.Base64.decode(uri.substring(comma + 1), android.util.Base64.DEFAULT)
                    }
                    uri.isEmpty() && i == 0 && binaryChunk != null -> binaryChunk!!
                    else -> error("External glTF buffers are not supported yet; use GLB or embed the buffer as base64")
                }
                require(data.size >= item.optInt("byteLength", 0)) { "glTF buffer is shorter than declared byteLength" }
                buffers.add(data)
            }
            val views = root.getJSONArray("bufferViews")
            val accessors = root.getJSONArray("accessors")
            val meshes = root.getJSONArray("meshes")
            val output = ArrayList<Float>()
            for (mi in 0 until meshes.length()) {
                val primitives = meshes.getJSONObject(mi).getJSONArray("primitives")
                for (pi in 0 until primitives.length()) {
                    val primitive = primitives.getJSONObject(pi)
                    require(primitive.optInt("mode", 4) == 4) { "Only TRIANGLES glTF primitives are supported" }
                    val positionIndex = primitive.getJSONObject("attributes").optInt("POSITION", -1)
                    require(positionIndex >= 0) { "Primitive has no POSITION attribute" }
                    val positions = readFloatVec3(accessors, views, buffers, positionIndex)
                    val indicesIndex = primitive.optInt("indices", -1)
                    if (indicesIndex < 0) {
                        require(positions.size % 9 == 0) { "Non-indexed primitive vertex count must form triangles" }
                        output.addAll(positions.toList())
                    } else {
                        val indices = readIndices(accessors, views, buffers, indicesIndex)
                        require(indices.size % 3 == 0) { "Index count must form triangles" }
                        for (index in indices) {
                            require(index >= 0 && index * 3 + 2 < positions.size) { "Index is outside the position accessor" }
                            output.add(positions[index * 3]); output.add(positions[index * 3 + 1]); output.add(positions[index * 3 + 2])
                        }
                    }
                }
            }
            require(output.isNotEmpty()) { "No triangle mesh geometry found" }
            require(output.size % 9 == 0) { "Imported geometry is not a triangle list" }
            val maxAbs = output.maxOf { kotlin.math.abs(it) }.coerceAtLeast(0.001f)
            val normalized = output.map { it / maxAbs * 1.5f }.toFloatArray()
            return ImportedMesh(normalized, normalized.size / 9, baseColor, metallic, roughness)
        }

        private fun readFloatVec3(accessors: JSONArray, views: JSONArray, buffers: List<ByteArray>, index: Int): FloatArray {
            val accessor = accessors.getJSONObject(index)
            require(accessor.optInt("componentType") == 5126 && accessor.optString("type") == "VEC3") {
                "POSITION accessor must use FLOAT VEC3"
            }
            val count = accessor.getInt("count")
            val viewIndex = accessor.getInt("bufferView")
            val view = views.getJSONObject(viewIndex)
            val buffer = buffers[view.getInt("buffer")]
            val stride = view.optInt("byteStride", 12)
            require(stride >= 12) { "Invalid POSITION byteStride" }
            val start = view.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
            val out = FloatArray(count * 3)
            val data = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                val offset = start + i * stride
                require(offset >= 0 && offset.toLong() + 12 <= buffer.size.toLong()) { "POSITION accessor exceeds buffer bounds" }
                out[i * 3] = data.getFloat(offset)
                out[i * 3 + 1] = data.getFloat(offset + 4)
                out[i * 3 + 2] = data.getFloat(offset + 8)
            }
            return out
        }

        private fun readIndices(accessors: JSONArray, views: JSONArray, buffers: List<ByteArray>, index: Int): IntArray {
            val accessor = accessors.getJSONObject(index)
            require(accessor.optString("type") == "SCALAR") { "Index accessor must be SCALAR" }
            val componentType = accessor.getInt("componentType")
            val bytesPerIndex = when (componentType) { 5121 -> 1; 5123 -> 2; 5125 -> 4; else -> error("Unsupported index component type") }
            val view = views.getJSONObject(accessor.getInt("bufferView"))
            val buffer = buffers[view.getInt("buffer")]
            val stride = view.optInt("byteStride", bytesPerIndex)
            require(stride >= bytesPerIndex) { "Invalid index byteStride" }
            val start = view.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
            val data = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            val out = IntArray(accessor.getInt("count"))
            for (i in out.indices) {
                val offset = start + i * stride
                require(offset >= 0 && offset.toLong() + bytesPerIndex <= buffer.size.toLong()) { "Index accessor exceeds buffer bounds" }
                out[i] = when (componentType) {
                    5121 -> data.get(offset).toInt() and 0xff
                    5123 -> data.getShort(offset).toInt() and 0xffff
                    else -> data.getInt(offset)
                }
                require(out[i] >= 0) { "Unsigned index exceeds supported integer range" }
            }
            return out
        }
    }

    private object ObjParser {
        /** Minimal Wavefront OBJ geometry importer: triangulates polygon faces and resolves negative indices. */
        fun parse(source: String): ImportedMesh {
            val vertices = mutableListOf<FloatArray>()
            val output = ArrayList<Float>()
            source.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.startsWith("v ")) {
                    val p = line.split(Regex("\\s+"))
                    if (p.size >= 4) vertices.add(floatArrayOf(p[1].toFloat(), p[2].toFloat(), p[3].toFloat()))
                } else if (line.startsWith("f ")) {
                    val refs = line.substring(2).trim().split(Regex("\\s+"))
                        .map { it.substringBefore('/').toInt() }
                    if (refs.size >= 3) {
                        val resolved = refs.map { idx -> if (idx < 0) vertices.size + idx else idx - 1 }
                        for (i in 1 until resolved.size - 1) {
                            listOf(resolved[0], resolved[i], resolved[i + 1]).forEach { index ->
                                require(index in vertices.indices) { "Face references a missing vertex" }
                                val v = vertices[index]
                                output.add(v[0]); output.add(v[1]); output.add(v[2])
                            }
                        }
                    }
                }
            }
            require(output.isNotEmpty()) { "No valid triangular face geometry found" }
            val maxAbs = output.maxOf { kotlin.math.abs(it) }.coerceAtLeast(0.001f)
            val normalized = output.map { it / maxAbs * 1.5f }.toFloatArray()
            return ImportedMesh(normalized, normalized.size / 9)
        }
    }
}

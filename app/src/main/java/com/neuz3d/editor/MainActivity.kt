package com.neuz3d.editor

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
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
    private var nextId = 1
    private var selectedId = 1
    private var quality = 1
    private val prefs by lazy { getSharedPreferences("neuz3d_scene", MODE_PRIVATE) }

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
        root.addView(tools)

        viewport = GLSurfaceView(this)
        viewport.setEGLContextClientVersion(3)
        renderer = SceneRenderer(
            onGpuInfo = { v, rendererName ->
                runOnUiThread {
                    status.text = "GPU: $rendererName • $v"
                }
            },
            onFrame = {}
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
        actionRow.addView(toolButton("Move X+") { transformSelected(dx = 0.25f) })
        actionRow.addView(toolButton("Move Y+") { transformSelected(dy = 0.25f) })
        actionRow.addView(toolButton("Rotate") { transformSelected(rotation = 15f) })
        actionRow.addView(toolButton("Scale +") { transformSelected(scale = 0.1f) })
        actionRow.addView(toolButton("Reset View") { renderer.resetCamera() })
        objectPanel.addView(actionRow)
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
        bottomTools.addView(toolButton("Import OBJ") { pickObj() })
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

    private fun addObject(type: String) {
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
        scene.removeAll { it.id == selectedId }
        selectedId = scene.last().id
        syncSceneToRenderer()
    }

    private fun transformSelected(dx: Float = 0f, dy: Float = 0f, rotation: Float = 0f, scale: Float = 0f) {
        val obj = scene.firstOrNull { it.id == selectedId } ?: return
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

    private fun sceneJson(): String {
        val array = JSONArray()
        scene.forEach { o ->
            array.put(JSONObject().put("id", o.id).put("name", o.name).put("type", o.type)
                .put("x", o.x).put("y", o.y).put("z", o.z).put("scale", o.scale).put("rotation", o.rotation))
        }
        return JSONObject().put("format", "neuz3d-scene").put("version", 1).put("objects", array).toString(2)
    }

    private fun saveScene() {
        prefs.edit().putString("scene_json", sceneJson()).putInt("next_id", nextId).putInt("selected_id", selectedId).apply()
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
            val array = if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw).getJSONArray("objects")
            scene.clear()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                scene.add(SceneObject(o.getInt("id"), o.getString("name"), o.optString("type", "Cube"),
                    o.optDouble("x", 0.0).toFloat(), o.optDouble("y", 0.0).toFloat(),
                    o.optDouble("z", 0.0).toFloat(), o.optDouble("scale", 1.0).toFloat(),
                    o.optDouble("rotation", 0.0).toFloat()))
            }
            if (scene.isEmpty()) scene.add(SceneObject(nextId++, "Cube"))
            nextId = max(prefs.getInt("next_id", 1), (scene.maxOfOrNull { it.id } ?: 0) + 1)
            selectedId = prefs.getInt("selected_id", scene.first().id)
            if (scene.none { it.id == selectedId }) selectedId = scene.first().id
            syncSceneToRenderer()
            Toast.makeText(this, "Scene loaded.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not load scene: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val objPickerRequest = 7402
    private fun pickObj() {
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
            val text = contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            } ?: error("Could not open file")
            val parsed = ObjParser.parse(text)
            renderer.setImportedMesh(parsed.first, parsed.second)
            status.text = "Imported OBJ • ${parsed.second} triangles • mesh preview"
            Toast.makeText(this, "OBJ geometry loaded into viewport.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "OBJ import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun cycleQuality() {
        quality = (quality + 1) % 3
        syncSceneToRenderer()
        Toast.makeText(this, "Viewport quality: ${listOf("Low", "Balanced", "High")[quality]}", Toast.LENGTH_SHORT).show()
    }

    private var lastX = 0f
    private var lastY = 0f
    private var lastDistance = 0f
    private fun handleViewportTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; return true }
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
                    renderer.orbit(dx * 0.45f, dy * 0.35f)
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { lastDistance = 0f; return true }
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
    override fun onPause() { if (::viewport.isInitialized) viewport.onPause(); super.onPause() }

    private class SceneRenderer(
        private val onGpuInfo: (String, String) -> Unit,
        private val onFrame: () -> Unit
    ) : GLSurfaceView.Renderer {
        @Volatile private var objects: List<SceneObject> = emptyList()
        @Volatile private var selectedId: Int = 1
        @Volatile private var quality: Int = 1
        @Volatile private var importedPositions: FloatArray? = null
        @Volatile private var importedTriangles: Int = 0
        @Volatile var version: String = ""
            private set
        private var program = 0
        private var aPosition = 0
        private var aColor = 0
        private var uMvp = 0
        private var uLight = 0
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
        fun setImportedMesh(positions: FloatArray, triangles: Int) {
            importedPositions = positions
            importedTriangles = triangles
            importedBuffer = floatBuffer(positions)
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
                "attribute vec3 aPosition; attribute vec3 aColor; uniform mat4 uMvp; varying vec3 vColor; varying float vLight; void main(){ gl_Position=uMvp*vec4(aPosition,1.0); vColor=aColor; vLight=0.55+0.45*max(dot(normalize(vec3(0.35,0.8,0.5)),vec3(0.0,0.0,1.0)),0.0); }")
            val fs = compile(GLES20.GL_FRAGMENT_SHADER,
                "precision mediump float; varying vec3 vColor; varying float vLight; void main(){ gl_FragColor=vec4(vColor*vLight,1.0); }")
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
            uLight = GLES20.glGetUniformLocation(program, "uLight")
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
            val lines = ArrayList<Float>()
            val s = if (obj.type == "Light") 0.45f else 0.35f
            addLine(lines, -s, 0f, 0f, s, 0f, 0f, if (obj.type == "Light") 1f else 0.95f, 0.75f, 0.2f)
            addLine(lines, 0f, -s, 0f, 0f, s, 0f, if (obj.type == "Light") 1f else 0.95f, 0.75f, 0.2f)
            addLine(lines, 0f, 0f, -s, 0f, 0f, s, if (obj.type == "Light") 1f else 0.95f, 0.75f, 0.2f)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, obj.x, obj.y + 0.7f, obj.z)
            drawBuffer(floatBuffer(lines.toFloatArray()), lines.size / 6, GLES20.GL_LINES, model)
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

    private object ObjParser {
        /** Minimal Wavefront OBJ geometry importer: triangulates polygon faces and resolves negative indices. */
        fun parse(source: String): Pair<FloatArray, Int> {
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
            return Pair(normalized, normalized.size / 9)
        }
    }
}

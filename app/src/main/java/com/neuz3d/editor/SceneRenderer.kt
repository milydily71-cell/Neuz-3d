package com.neuz3d.editor

import android.opengl.GLES20
import android.opengl.GLES32
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class SceneRenderer(
    private val onGpuInfo: (String, String, Boolean, Boolean) -> Unit,
    private val onFrame: () -> Unit = {},
    private val onPick: (Int) -> Unit = {}
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
    @Volatile var isGles32: Boolean = false
        private set
    @Volatile var glesMajorVersion: Int = 2
        private set

    private var program = 0
    private var lineProgram = 0
    private var aPosition = 0
    private var aColor = 0
    private var uMvp = 0
    private var uLight = 0
    private var uMetallic = -1
    private var uRoughness = -1
    private var uBaseColor = -1
    private var uSelected = -1
    private var line_aPosition = 0
    private var line_uMvp = 0
    private var line_uColor = 0
    private var width = 1
    private var height = 1
    private var yaw = 35f
    private var pitch = 24f
    private var camDistance = 9f
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val temp = FloatArray(16)

    private var gridBuffer: FloatBuffer? = null
    private var axisBuffer: FloatBuffer? = null
    private var markerBuffer: FloatBuffer? = null
    private var gizmoBuffer: FloatBuffer? = null
    private var importedBuffer: FloatBuffer? = null
    private val meshCache = HashMap<String, Pair<FloatBuffer, FloatBuffer>>()
    private val meshDataCache = HashMap<String, MeshData>()
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

    fun zoomBy(factor: Float) { camDistance = (camDistance * factor).coerceIn(2.5f, 35f) }
    fun resetCamera() { yaw = 35f; pitch = 24f; camDistance = 9f }

    fun pickAt(screenX: Float, screenY: Float) {
        val viewModelPick = FloatArray(16)
        val mvpPick = FloatArray(16)
        val modelPick = FloatArray(16)
        val eye = FloatArray(4)
        val clipPosition = floatArrayOf(0f, 0f, 0f, 1f)
        var bestId = -1
        var bestDistance = 56f * 56f
        for (obj in objects) {
            if (obj.type == "Light" || obj.type == "Camera" || !obj.visible) continue
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
        importedBuffer = MeshBuilder.floatBuffer(mesh.positions)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        vboCache.clear()
        GLES20.glClearColor(0.055f, 0.065f, 0.085f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glEnable(GLES20.GL_CULL_FACE)

        val glVersionStr = GLES20.glGetString(GLES20.GL_VERSION) ?: "unknown"
        val shading = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION) ?: "unknown"
        version = "$glVersionStr • GLSL $shading"
        val vendor = GLES20.glGetString(GLES20.GL_RENDERER) ?: "GPU"

        val is32 = try { GLES32.glGetString(GLES32.GL_VERSION)?.contains("3.2") == true } catch (e: Exception) { false }
        isGles32 = is32
        glesMajorVersion = if (glVersionStr.contains("3.2")) 32
            else if (glVersionStr.contains("3.1")) 31
            else if (glVersionStr.contains("3.0")) 30
            else 20

        val extString = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        val hasAstc = extString.contains("ASTC_texture", ignoreCase = true)
        val hasFloat = extString.contains("color_buffer_float", ignoreCase = true)

        onGpuInfo(version, vendor, is32, hasFloat)

        val vs = compile(GLES20.GL_VERTEX_SHADER, """
            attribute vec3 aPosition;
            uniform mat4 uMvp;
            varying vec3 vNormal;
            varying vec3 vWorldPos;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vNormal = normalize(aPosition);
                vWorldPos = aPosition;
            }
        """.trimIndent())

        val fs = compile(GLES20.GL_FRAGMENT_SHADER, """
            precision highp float;
            varying vec3 vNormal;
            varying vec3 vWorldPos;
            uniform float uMetallic;
            uniform float uRoughness;
            uniform vec3 uBaseColor;
            uniform float uSelected;
            const float PI = 3.14159265359;

            vec3 fresnelSchlick(float c, vec3 F0) { return F0 + (1.0 - F0) * pow(clamp(1.0 - c, 0.0, 1.0), 5.0); }
            float D_GGX(vec3 N, vec3 H, float r) {
                float a = r * r; float a2 = a * a;
                float nh = max(dot(N, H), 0.0);
                float d = nh * nh * (a2 - 1.0) + 1.0;
                return a2 / max(PI * d * d, 0.0001);
            }
            float G1(float nv, float r) { float k = (r + 1.0); k = k * k / 8.0; return nv / max(nv * (1.0 - k) + k, 0.0001); }

            void main() {
                vec3 N = normalize(vNormal);
                vec3 V = vec3(0.0, 0.0, 1.0);
                vec3 L = normalize(vec3(-0.45, 0.85, 0.55));
                vec3 H = normalize(V + L);
                float m = clamp(uMetallic, 0.0, 1.0);
                float r = clamp(uRoughness, 0.045, 1.0);
                float nv = max(dot(N, V), 0.0);
                float nl = max(dot(N, L), 0.0);
                vec3 albedo = max(uBaseColor, vec3(0.0));
                vec3 F0 = mix(vec3(0.04), albedo, m);
                vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);
                float D = D_GGX(N, H, r);
                float G = G1(nv, r) * G1(nl, r);
                vec3 spec = (D * G * F) / max(4.0 * nv * nl, 0.001);
                vec3 kd = (vec3(1.0) - F) * (1.0 - m);
                float sky = clamp(N.y * 0.5 + 0.5, 0.0, 1.0);
                vec3 env = mix(vec3(0.055, 0.065, 0.08), vec3(0.28, 0.38, 0.55), sky);
                vec3 color = kd * albedo * env + (kd * albedo / PI + spec) * vec3(3.0, 2.85, 2.65) * nl + F * env * (1.0 - r * 0.7);
                if (uSelected > 0.5) {
                    float rim = 1.0 - clamp(dot(N, V), 0.0, 1.0);
                    color += vec3(0.15, 0.35, 0.6) * pow(rim, 2.0) * 0.5;
                }
                color = color / (color + vec3(1.0));
                color = pow(max(color, vec3(0.0)), vec3(1.0 / 2.2));
                gl_FragColor = vec4(color, 1.0);
            }
        """.trimIndent())

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glBindAttribLocation(program, 0, "aPosition")
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "Program link failed: " + GLES20.glGetProgramInfoLog(program) }
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uMetallic = GLES20.glGetUniformLocation(program, "uMetallic")
        uRoughness = GLES20.glGetUniformLocation(program, "uRoughness")
        uBaseColor = GLES20.glGetUniformLocation(program, "uBaseColor")
        uSelected = GLES20.glGetUniformLocation(program, "uSelected")

        val lineVs = compile(GLES20.GL_VERTEX_SHADER, """
            attribute vec3 aPosition;
            uniform mat4 uMvp;
            void main() { gl_Position = uMvp * vec4(aPosition, 1.0); }
        """.trimIndent())
        val lineFs = compile(GLES20.GL_FRAGMENT_SHADER, """
            precision mediump float;
            uniform vec3 uColor;
            void main() { gl_FragColor = vec4(uColor, 1.0); }
        """.trimIndent())
        lineProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(lineProgram, lineVs)
        GLES20.glAttachShader(lineProgram, lineFs)
        GLES20.glLinkProgram(lineProgram)
        GLES20.glGetProgramiv(lineProgram, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "Line program link failed: " + GLES20.glGetProgramInfoLog(lineProgram) }
        line_aPosition = GLES20.glGetAttribLocation(lineProgram, "aPosition")
        line_uMvp = GLES20.glGetUniformLocation(lineProgram, "uMvp")
        line_uColor = GLES20.glGetUniformLocation(lineProgram, "uColor")

        createEnvironment()
        preloadMeshes()
    }

    private fun preloadMeshes() {
        val types = listOf("Cube", "Sphere", "Cylinder", "Cone", "Torus", "Plane", "Pyramid", "Capsule", "IcoSphere")
        for (type in types) {
            val mesh = MeshBuilder.build(type)
            meshDataCache[type] = mesh
            meshCache[type] = Pair(MeshBuilder.floatBuffer(mesh.vertices), MeshBuilder.floatBuffer(mesh.wireVertices))
        }
    }

    private fun createEnvironment() {
        val grid = ArrayList<Float>()
        for (i in -20..20) {
            val c = if (i == 0) 0.28f else if (i % 5 == 0) 0.16f else 0.09f
            addLine(grid, i.toFloat(), 0f, -20f, i.toFloat(), 0f, 20f, c, c, c)
            addLine(grid, -20f, 0f, i.toFloat(), 20f, 0f, i.toFloat(), c, c, c)
        }
        gridBuffer = MeshBuilder.floatBuffer(grid.toFloatArray())
        gridCount = grid.size / 6

        val axes = ArrayList<Float>()
        addLine(axes, -20f, 0.006f, 0f, 20f, 0.006f, 0f, 0.95f, 0.20f, 0.20f)
        addLine(axes, 0f, -0.006f, -20f, 0f, -0.006f, 20f, 0.20f, 0.45f, 1f)
        addLine(axes, 0f, 0f, 0f, 0f, 6f, 0f, 0.25f, 0.95f, 0.40f)
        axisBuffer = MeshBuilder.floatBuffer(axes.toFloatArray())

        val marker = ArrayList<Float>()
        addLine(marker, -0.45f, 0f, 0f, 0.45f, 0f, 0f, 1f, 0.72f, 0.20f)
        addLine(marker, 0f, -0.45f, 0f, 0f, 0.45f, 0f, 1f, 0.72f, 0.20f)
        addLine(marker, 0f, 0f, -0.45f, 0f, 0f, 0.45f, 1f, 0.72f, 0.20f)
        markerBuffer = MeshBuilder.floatBuffer(marker.toFloatArray())

        val gizmo = ArrayList<Float>()
        addLine(gizmo, 0f, 0f, 0f, 1.35f, 0f, 0f, 1f, 0.2f, 0.2f)
        addLine(gizmo, 0f, 0f, 0f, 0f, 1.35f, 0f, 0.25f, 1f, 0.35f)
        addLine(gizmo, 0f, 0f, 0f, 0f, 0f, 1.35f, 0.2f, 0.5f, 1f)
        gizmoBuffer = MeshBuilder.floatBuffer(gizmo.toFloatArray())
    }

    private fun addLine(out: MutableList<Float>, x1: Float, y1: Float, z1: Float,
                         x2: Float, y2: Float, z2: Float, r: Float, g: Float, b: Float) {
        out.addAll(listOf(x1, y1, z1, r, g, b, x2, y2, z2, r, g, b))
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
        val eyeX = (camDistance * kotlin.math.cos(pr) * kotlin.math.sin(yr))
        val eyeY = camDistance * kotlin.math.sin(pr)
        val eyeZ = (camDistance * kotlin.math.cos(pr) * kotlin.math.cos(yr))
        Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, 0f, 0.8f, 0f, 0f, 1f, 0f)

        GLES20.glUseProgram(lineProgram)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        Matrix.setIdentityM(model, 0)
        drawLines(gridBuffer, gridCount, model, 0.12f, 0.14f, 0.18f)
        drawLines(axisBuffer, 6, model, 0.3f, 0.3f, 0.35f)

        val current = objects
        current.forEach { obj ->
            if (!obj.visible) return@forEach
            if (obj.type == "Light" || obj.type == "Camera") {
                drawMarker(obj)
            } else {
                drawSceneObject(obj)
            }
        }

        importedBuffer?.let { buf ->
            GLES20.glUseProgram(program)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, 0f, 1.5f, 0f)
            Matrix.multiplyMM(temp, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, temp, 0)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniform1f(uMetallic, importedMetallic)
            GLES20.glUniform1f(uRoughness, importedRoughness)
            GLES20.glUniform3f(uBaseColor, importedBaseColor[0], importedBaseColor[1], importedBaseColor[2])
            GLES20.glUniform1f(uSelected, 0f)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, getVbo(buf))
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, min(importedTriangles * 3, buf.capacity() / 3))
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }

        GLES20.glEnable(GLES20.GL_CULL_FACE)
        onFrame()
    }

    private fun drawSceneObject(obj: SceneObject) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, obj.x, obj.y + 0.5f, obj.z)
        Matrix.rotateM(model, 0, obj.rotation, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, obj.rotX, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, obj.rotZ, 0f, 0f, 1f)
        Matrix.scaleM(model, 0, obj.scale, obj.scale, obj.scale)

        val isSelected = obj.id == selectedId

        if (obj.wireframe) {
            val wireBuf = meshCache[obj.type]?.second
            val wireCount = meshDataCache[obj.type]?.wireCount ?: 0
            if (wireBuf != null && wireCount > 0) {
                drawLines(wireBuf, wireCount, model, obj.color[0], obj.color[1], obj.color[2])
            }
        } else {
            GLES20.glUseProgram(program)
            val triBuf = meshCache[obj.type]?.first
            val triCount = meshDataCache[obj.type]?.triangleCount ?: 0
            if (triBuf != null && triCount > 0) {
                Matrix.multiplyMM(temp, 0, view, 0, model, 0)
                Matrix.multiplyMM(mvp, 0, projection, 0, temp, 0)
                GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
                GLES20.glUniform1f(uMetallic, obj.metallic)
                GLES20.glUniform1f(uRoughness, obj.roughness)
                GLES20.glUniform3f(uBaseColor, obj.color[0], obj.color[1], obj.color[2])
                GLES20.glUniform1f(uSelected, if (isSelected) 1f else 0f)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, getVbo(triBuf))
                GLES20.glEnableVertexAttribArray(aPosition)
                GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, triCount * 3)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            }
        }

        if (isSelected) {
            GLES20.glUseProgram(lineProgram)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, obj.x, obj.y + 0.5f, obj.z)
            drawLines(gizmoBuffer, 6, model, 0.8f, 0.8f, 0.3f)
        }
    }

    private fun drawMarker(obj: SceneObject) {
        GLES20.glUseProgram(lineProgram)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, obj.x, obj.y + 0.7f, obj.z)
        val c = if (obj.type == "Light") floatArrayOf(1f, 0.85f, 0.3f) else floatArrayOf(0.3f, 0.8f, 1f)
        drawLines(markerBuffer, 6, model, c[0], c[1], c[2])
    }

    private fun drawLines(buffer: FloatBuffer?, count: Int, modelMatrix: FloatArray, r: Float, g: Float, b: Float) {
        if (buffer == null || count <= 0) return
        Matrix.multiplyMM(temp, 0, view, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, temp, 0)
        GLES20.glUniformMatrix4fv(line_uMvp, 1, false, mvp, 0)
        GLES20.glUniform3f(line_uColor, r, g, b)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, getVbo(buffer))
        GLES20.glEnableVertexAttribArray(line_aPosition)
        GLES20.glVertexAttribPointer(line_aPosition, 3, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, count)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
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

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader) }
        return shader
    }
}

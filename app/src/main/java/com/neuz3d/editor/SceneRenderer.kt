package com.neuz3d.editor

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

class SceneRenderer(
    private val onGpuInfo: (String, String, Boolean, Boolean) -> Unit,
    private val onFrame: () -> Unit = {},
    private val onPick: (Int) -> Unit = {}
) : GLSurfaceView.Renderer {

    @Volatile private var objects: List<SceneObject> = emptyList()
    @Volatile private var lights: List<SceneLight> = emptyList()
    @Volatile private var cameras: List<SceneCamera> = emptyList()
    @Volatile private var skeletons: List<Skeleton> = emptyList()
    @Volatile private var editorState: EditorState = EditorState()
    @Volatile private var selectedId: Int = 1
    @Volatile private var quality: Int = 1
    @Volatile private var lightArray: FloatArray = FloatArray(LightingSystem.maxLightCount() * 8)
    @Volatile private var lightCount: Int = 0
    @Volatile private var boneMatrices: FloatArray = FloatArray(0)
    @Volatile private var boneCount: Int = 0

    @Volatile private var importedPositions: FloatArray? = null
    @Volatile private var importedNormals: FloatArray? = null
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
    private var aNormal = 0
    private var aVertexColor = 0
    private var uMvp = 0
    private var uModel = 0
    private var uNormalMat = 0
    private var uMetallic = -1
    private var uRoughness = -1
    private var uBaseColor = -1
    private var uSelected = -1
    private var uEmissive = -1
    private var uLightArray = -1
    private var uLightCount = -1
    private var uHasVertexColors = -1
    private var uBoneMatrices = -1
    private var uBoneCount = -1
    private var line_aPosition = 0
    private var line_uMvp = 0
    private var line_uColor = 0
    private var width = 1
    private var height = 1
    private var yaw = 35f
    private var pitch = 24f
    private var camDistance = 9f
    private var camFov = 48f
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val temp = FloatArray(16)
    private val normalMat = FloatArray(16)

    private var gridBuffer: FloatBuffer? = null
    private var axisBuffer: FloatBuffer? = null
    private var markerBuffer: FloatBuffer? = null
    private var gizmoBuffer: FloatBuffer? = null
    private var boneBuffer: FloatBuffer? = null
    private var importedBuffer: FloatBuffer? = null
    private var importedNormBuffer: FloatBuffer? = null
    private val meshCache = HashMap<String, Pair<FloatBuffer, FloatBuffer>>()
    private val meshDataCache = HashMap<String, MeshData>()
    private val vboCache = java.util.IdentityHashMap<FloatBuffer, Int>()
    private var gridCount = 0

    fun setScene(
        newObjects: List<SceneObject>, selected: Int, newQuality: Int,
        newLights: List<SceneLight> = emptyList(),
        newCameras: List<SceneCamera> = emptyList(),
        newSkeletons: List<Skeleton> = emptyList(),
        newEditorState: EditorState = EditorState()
    ) {
        objects = newObjects
        selectedId = selected
        quality = newQuality
        lights = newLights
        cameras = newCameras
        skeletons = newSkeletons
        editorState = newEditorState
        if (newLights.isNotEmpty()) {
            lightArray = LightingSystem.lightsToArray(newLights)
            lightCount = min(newLights.size, LightingSystem.maxLightCount())
        } else {
            lightArray = LightingSystem.lightsToArray(listOf(
                SceneLight(0, "Sun", "Directional", floatArrayOf(1f, 0.95f, 0.85f), 1.2f, 15f, 4f, 6f, 3f, false, true)
            ))
            lightCount = 1
        }
        if (newSkeletons.isNotEmpty()) {
            val skel = newSkeletons.first()
            val matrices = SkeletonSystem.computeBoneMatrices(skel)
            boneMatrices = FloatArray(matrices.size * 16)
            for (i in matrices.indices) {
                System.arraycopy(matrices[i], 0, boneMatrices, i * 16, 16)
            }
            boneCount = matrices.size
        } else {
            boneCount = 0
        }
    }

    fun orbit(dx: Float, dy: Float) {
        yaw = (yaw + dx) % 360f
        pitch = (pitch + dy).coerceIn(-10f, 78f)
    }

    fun zoomBy(factor: Float) { camDistance = (camDistance * factor).coerceIn(2.5f, 35f) }
    fun resetCamera() { yaw = 35f; pitch = 24f; camDistance = 9f; camFov = 48f }
    fun setFov(fov: Float) { camFov = fov.coerceIn(20f, 100f) }

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
        importedNormals = mesh.normals
        importedTriangles = mesh.triangles
        importedBaseColor = mesh.baseColor.copyOf()
        importedMetallic = mesh.metallic
        importedRoughness = mesh.roughness
        importedBuffer = MeshBuilder.floatBuffer(mesh.positions)
        importedNormBuffer = if (mesh.normals.isNotEmpty()) MeshBuilder.floatBuffer(mesh.normals) else null
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

        isGles32 = glVersionStr.contains("3.2", ignoreCase = true)
        glesMajorVersion = when {
            glVersionStr.contains("3.2") -> 32
            glVersionStr.contains("3.1") -> 31
            glVersionStr.contains("3.0") -> 30
            else -> 20
        }

        val extString = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        val hasFloat = extString.contains("color_buffer_float", ignoreCase = true)

        onGpuInfo(version, vendor, isGles32, hasFloat)

        val vs = compile(GLES20.GL_VERTEX_SHADER, """
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec3 aVertexColor;
            uniform mat4 uMvp;
            uniform mat4 uModel;
            uniform mat4 uNormalMat;
            uniform vec3 uBaseColor;
            uniform int uHasVertexColors;
            varying vec3 vNormal;
            varying vec3 vWorldPos;
            varying vec3 vColor;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vNormal = normalize((uNormalMat * vec4(aNormal, 0.0)).xyz);
                vWorldPos = (uModel * vec4(aPosition, 1.0)).xyz;
                vColor = uHasVertexColors == 1 ? aVertexColor : uBaseColor;
            }
        """.trimIndent())

        val fs = compile(GLES20.GL_FRAGMENT_SHADER, """
            precision highp float;
            varying vec3 vNormal;
            varying vec3 vWorldPos;
            varying vec3 vColor;
            uniform float uMetallic;
            uniform float uRoughness;
            uniform float uSelected;
            uniform float uEmissive;
            uniform int uLightCount;
            uniform int uHasVertexColors;
            #define MAX_LIGHTS 8
            uniform vec4 uLights[MAX_LIGHTS]; // xyz=position, w=intensity
            uniform vec3 uLightColors[MAX_LIGHTS];
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
                vec3 V = normalize(-vWorldPos);
                float m = clamp(uMetallic, 0.0, 1.0);
                float r = clamp(uRoughness, 0.045, 1.0);
                float nv = max(dot(N, V), 0.0);
                vec3 albedo = max(vColor, vec3(0.0));
                vec3 F0 = mix(vec3(0.04), albedo, m);

                vec3 totalDiffuse = vec3(0.0);
                vec3 totalSpecular = vec3(0.0);

                for (int i = 0; i < MAX_LIGHTS; i++) {
                    if (i >= uLightCount) break;
                    vec3 lightPos = uLights[i].xyz;
                    float intensity = uLights[i].w;
                    vec3 toLight = lightPos - vWorldPos;
                    float dist = length(toLight);
                    vec3 L = toLight / max(dist, 0.001);
                    vec3 lightCol = uLightColors[i] * intensity;
                    float atten = 1.0 / (1.0 + dist * dist * 0.02);
                    float nl = max(dot(N, L), 0.0);
                    vec3 H = normalize(V + L);
                    vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);
                    float D = D_GGX(N, H, r);
                    float G = G1(nv, r) * G1(nl, r);
                    vec3 spec = (D * G * F) / max(4.0 * nv * nl, 0.001);
                    totalDiffuse += lightCol * nl * atten;
                    totalSpecular += spec * lightCol * nl * atten;
                }

                vec3 kd = (vec3(1.0) - F0) * (1.0 - m);
                float sky = clamp(N.y * 0.5 + 0.5, 0.0, 1.0);
                vec3 env = mix(vec3(0.055, 0.065, 0.08), vec3(0.28, 0.38, 0.55), sky);
                vec3 ambient = kd * albedo * env;
                vec3 color = ambient + kd * albedo / PI * totalDiffuse + totalSpecular;
                color += uEmissive * albedo;
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
        GLES20.glBindAttribLocation(program, 1, "aNormal")
        GLES20.glBindAttribLocation(program, 2, "aVertexColor")
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "Program link failed: " + GLES20.glGetProgramInfoLog(program) }
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aVertexColor = GLES20.glGetAttribLocation(program, "aVertexColor")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uNormalMat = GLES20.glGetUniformLocation(program, "uNormalMat")
        uMetallic = GLES20.glGetUniformLocation(program, "uMetallic")
        uRoughness = GLES20.glGetUniformLocation(program, "uRoughness")
        uBaseColor = GLES20.glGetUniformLocation(program, "uBaseColor")
        uSelected = GLES20.glGetUniformLocation(program, "uSelected")
        uEmissive = GLES20.glGetUniformLocation(program, "uEmissive")
        uLightArray = GLES20.glGetUniformLocation(program, "uLights")
        uLightCount = GLES20.glGetUniformLocation(program, "uLightCount")
        uHasVertexColors = GLES20.glGetUniformLocation(program, "uHasVertexColors")

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
            val interleaved = mesh.interleavedPosNorm()
            meshCache[type] = Pair(MeshBuilder.floatBuffer(interleaved), MeshBuilder.floatBuffer(mesh.wireVertices))
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

        val bones = ArrayList<Float>()
        addLine(bones, 0f, 0f, 0f, 0f, 0.5f, 0f, 0.8f, 0.6f, 0.2f)
        addLine(bones, 0f, 0.5f, 0f, 0f, 1f, 0f, 0.8f, 0.6f, 0.2f)
        boneBuffer = MeshBuilder.floatBuffer(bones.toFloatArray())
    }

    private fun addLine(out: MutableList<Float>, x1: Float, y1: Float, z1: Float,
                         x2: Float, y2: Float, z2: Float, r: Float, g: Float, b: Float) {
        out.addAll(listOf(x1, y1, z1, r, g, b, x2, y2, z2, r, g, b))
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = max(1, w); height = max(1, h)
        GLES20.glViewport(0, 0, width, height)
        Matrix.perspectiveM(projection, 0, camFov, width.toFloat() / height.toFloat(), 0.1f, 120f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (program == 0) return

        Matrix.perspectiveM(projection, 0, camFov, width.toFloat() / height.toFloat(), 0.1f, 120f)

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

        if (skeletons.isNotEmpty() && editorState.mode == EditorMode.RIG_MODE) {
            drawSkeleton(skeletons.first())
        }

        importedBuffer?.let { buf ->
            GLES20.glUseProgram(program)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, 0f, 1.5f, 0f)
            drawImportedMesh(buf)
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
            val interleavedBuf = meshCache[obj.type]?.first
            val triCount = meshDataCache[obj.type]?.triangleCount ?: 0
            if (interleavedBuf != null && triCount > 0) {
                setupUniforms(obj, isSelected)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, getVbo(interleavedBuf))
                GLES20.glEnableVertexAttribArray(aPosition)
                GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 24, 0)
                GLES20.glEnableVertexAttribArray(aNormal)
                GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 24, 12)
                GLES20.glDisableVertexAttribArray(aVertexColor)
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

    private fun setupUniforms(obj: SceneObject, isSelected: Boolean) {
        Matrix.multiplyMM(temp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, temp, 0)
        Matrix.invertM(normalMat, 0, model, 0)
        Matrix.transposeM(normalMat, 0, normalMat, 0)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniformMatrix4fv(uNormalMat, 1, false, normalMat, 0)
        GLES20.glUniform1f(uMetallic, obj.metallic)
        GLES20.glUniform1f(uRoughness, obj.roughness)
        GLES20.glUniform3f(uBaseColor, obj.color[0], obj.color[1], obj.color[2])
        GLES20.glUniform1f(uSelected, if (isSelected) 1f else 0f)
        GLES20.glUniform1f(uEmissive, obj.emissive)
        GLES20.glUniform1i(uHasVertexColors, 0)
        GLES20.glUniform1i(uLightCount, lightCount)

        if (uLightArray >= 0 && lightCount > 0) {
            val lightPos = FloatArray(lightCount * 4)
            val lightCols = FloatArray(lightCount * 3)
            for (i in 0 until lightCount) {
                lightPos[i * 4] = lightArray[i * 8]
                lightPos[i * 4 + 1] = lightArray[i * 8 + 1]
                lightPos[i * 4 + 2] = lightArray[i * 8 + 2]
                lightPos[i * 4 + 3] = lightArray[i * 8 + 3]
                lightCols[i * 3] = lightArray[i * 8 + 4]
                lightCols[i * 3 + 1] = lightArray[i * 8 + 5]
                lightCols[i * 3 + 2] = lightArray[i * 8 + 6]
            }
            GLES20.glUniform4fv(uLightArray, lightCount, lightPos, 0)
            val lightColorLoc = GLES20.glGetUniformLocation(program, "uLightColors")
            if (lightColorLoc >= 0) GLES20.glUniform3fv(lightColorLoc, lightCount, lightCols, 0)
        }
    }

    private fun drawImportedMesh(buf: FloatBuffer) {
        Matrix.multiplyMM(temp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, temp, 0)
        Matrix.invertM(normalMat, 0, model, 0)
        Matrix.transposeM(normalMat, 0, normalMat, 0)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniformMatrix4fv(uNormalMat, 1, false, normalMat, 0)
        GLES20.glUniform1f(uMetallic, importedMetallic)
        GLES20.glUniform1f(uRoughness, importedRoughness)
        GLES20.glUniform3f(uBaseColor, importedBaseColor[0], importedBaseColor[1], importedBaseColor[2])
        GLES20.glUniform1f(uSelected, 0f)
        GLES20.glUniform1f(uEmissive, 0f)
        GLES20.glUniform1i(uHasVertexColors, 0)
        GLES20.glUniform1i(uLightCount, lightCount)

        if (uLightArray >= 0 && lightCount > 0) {
            val lightPos = FloatArray(lightCount * 4)
            val lightCols = FloatArray(lightCount * 3)
            for (i in 0 until lightCount) {
                lightPos[i * 4] = lightArray[i * 8]
                lightPos[i * 4 + 1] = lightArray[i * 8 + 1]
                lightPos[i * 4 + 2] = lightArray[i * 8 + 2]
                lightPos[i * 4 + 3] = lightArray[i * 8 + 3]
                lightCols[i * 3] = lightArray[i * 8 + 4]
                lightCols[i * 3 + 1] = lightArray[i * 8 + 5]
                lightCols[i * 3 + 2] = lightArray[i * 8 + 6]
            }
            GLES20.glUniform4fv(uLightArray, lightCount, lightPos, 0)
            val lightColorLoc = GLES20.glGetUniformLocation(program, "uLightColors")
            if (lightColorLoc >= 0) GLES20.glUniform3fv(lightColorLoc, lightCount, lightCols, 0)
        }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, getVbo(buf))
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDisableVertexAttribArray(aNormal)
        GLES20.glVertexAttrib3f(aNormal, 0f, 1f, 0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, min(importedTriangles * 3, buf.capacity() / 3))
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawMarker(obj: SceneObject) {
        GLES20.glUseProgram(lineProgram)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, obj.x, obj.y + 0.7f, obj.z)
        val c = if (obj.type == "Light") floatArrayOf(1f, 0.85f, 0.3f) else floatArrayOf(0.3f, 0.8f, 1f)
        drawLines(markerBuffer, 6, model, c[0], c[1], c[2])
    }

    private fun drawSkeleton(skel: Skeleton) {
        GLES20.glUseProgram(lineProgram)
        for (bone in skel.bones) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, bone.x, bone.y, bone.z)
            val color = if (bone.id == editorState.selectedBoneIndex)
                floatArrayOf(0.9f, 0.8f, 0.2f) else floatArrayOf(0.8f, 0.6f, 0.2f)
            drawLines(boneBuffer, 4, model, color[0], color[1], color[2])
        }
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

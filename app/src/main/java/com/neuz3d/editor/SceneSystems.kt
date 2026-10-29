package com.neuz3d.editor

import android.opengl.Matrix
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object ModelMath {
    fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    fun sub(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    fun add(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    fun scale(a: FloatArray, s: Float): FloatArray = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)
    fun norm(a: FloatArray): FloatArray { val l = len(a); return if (l > 0.0001f) scale(a, 1f / l) else floatArrayOf(0f, 1f, 0f) }
    fun len(a: FloatArray): Float = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
    fun dot(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}

object LightingSystem {
    fun computeLightContribution(lights: List<SceneLight>, surfacePos: FloatArray, normal: FloatArray, viewDir: FloatArray): FloatArray {
        var r = 0.05f; var g = 0.05f; var b = 0.06f
        for (light in lights) {
            if (!light.enabled) continue
            val lightPos = floatArrayOf(light.x, light.y, light.z)
            val lightDir = ModelMath.norm(ModelMath.sub(lightPos, surfacePos))
            val dist = ModelMath.len(ModelMath.sub(lightPos, surfacePos))
            val attenuation = when (light.type) {
                "Directional" -> 1f
                "Spot" -> max(0f, ModelMath.dot(lightDir, normal)) * light.intensity
                else -> max(0f, ModelMath.dot(lightDir, normal)) * light.intensity / (1f + dist * dist * 0.02f)
            }
            r += light.color[0] * attenuation
            g += light.color[1] * attenuation
            b += light.color[2] * attenuation
        }
        return floatArrayOf(min(r, 2f), min(g, 2f), min(b, 2f))
    }

    fun maxLightCount(): Int = 8

    fun lightsToArray(lights: List<SceneLight>): FloatArray {
        val max = maxLightCount()
        val out = FloatArray(max * 8)
        for (i in 0 until min(lights.size, max)) {
            val l = lights[i]
            out[i * 8] = l.x; out[i * 8 + 1] = l.y; out[i * 8 + 2] = l.z
            out[i * 8 + 3] = l.intensity
            out[i * 8 + 4] = l.color[0]; out[i * 8 + 5] = l.color[1]; out[i * 8 + 6] = l.color[2]
            out[i * 8 + 7] = if (l.enabled) 1f else 0f
        }
        return out
    }
}

object CameraSystem {
    fun buildViewMatrix(cam: SceneCamera): FloatArray {
        val view = FloatArray(16)
        Matrix.setLookAtM(view, 0, cam.x, cam.y, cam.z, cam.lookAtX, cam.lookAtY, cam.lookAtZ, 0f, 1f, 0f)
        return view
    }

    fun buildProjectionMatrix(cam: SceneCamera, aspect: Float): FloatArray {
        val proj = FloatArray(16)
        Matrix.perspectiveM(proj, 0, cam.fov, aspect, cam.near, cam.far)
        return proj
    }

    fun dolly(cam: SceneCamera, amount: Float) {
        val dx = cam.lookAtX - cam.x
        val dy = cam.lookAtY - cam.y
        val dz = cam.lookAtZ - cam.z
        val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.001f)
        cam.x += dx / len * amount
        cam.y += dy / len * amount
        cam.z += dz / len * amount
    }

    fun truck(cam: SceneCamera, amount: Float) {
        val dx = cam.lookAtX - cam.x
        val dz = cam.lookAtZ - cam.z
        val len = sqrt(dx * dx + dz * dz).coerceAtLeast(0.001f)
        cam.x += -dz / len * amount
        cam.z += dx / len * amount
        cam.lookAtX += -dz / len * amount
        cam.lookAtZ += dx / len * amount
    }

    fun orbit(cam: SceneCamera, yawDelta: Float, pitchDelta: Float) {
        val dx = cam.x - cam.lookAtX
        val dy = cam.y - cam.lookAtY
        val dz = cam.z - cam.lookAtZ
        val radius = sqrt(dx * dx + dz * dz)
        val currentYaw = Math.toDegrees(atan2Safe(dz, dx).toDouble()).toFloat()
        val currentPitch = Math.toDegrees(atan2Safe(dy, radius).toDouble()).toFloat()
        val newYaw = Math.toRadians((currentYaw + yawDelta).toDouble()).toFloat()
        val newPitch = Math.toRadians((currentPitch + pitchDelta).coerceIn(-80f, 80f).toDouble()).toFloat()
        val totalRadius = sqrt(dx * dx + dy * dy + dz * dz)
        cam.x = cam.lookAtX + totalRadius * cos(newPitch) * cos(newYaw)
        cam.y = cam.lookAtY + totalRadius * sin(newPitch)
        cam.z = cam.lookAtZ + totalRadius * cos(newPitch) * sin(newYaw)
    }

    private fun atan2Safe(y: Float, x: Float): Float =
        if (x == 0f && y == 0f) 0f else kotlin.math.atan2(y, x)
}

object SkeletonSystem {
    fun computeBoneMatrices(skeleton: Skeleton): Array<FloatArray> {
        val matrices = Array(skeleton.bones.size) { FloatArray(16) }
        for (i in skeleton.bones.indices) {
            val bone = skeleton.bones[i]
            val local = FloatArray(16)
            Matrix.setIdentityM(local, 0)
            Matrix.translateM(local, 0, bone.x, bone.y, bone.z)
            Matrix.rotateM(local, 0, bone.rotY, 0f, 1f, 0f)
            Matrix.rotateM(local, 0, bone.rotX, 1f, 0f, 0f)
            Matrix.rotateM(local, 0, bone.rotZ, 0f, 0f, 1f)
            Matrix.scaleM(local, 0, bone.scale, bone.scale, bone.scale)

            if (bone.parentIndex >= 0 && bone.parentIndex < i) {
                Matrix.multiplyMM(matrices[i], 0, matrices[bone.parentIndex], 0, local, 0)
            } else {
                System.arraycopy(local, 0, matrices[i], 0, 16)
            }
            val skinMatrix = FloatArray(16)
            Matrix.multiplyMM(skinMatrix, 0, matrices[i], 0, bone.inverseBind, 0)
            System.arraycopy(skinMatrix, 0, matrices[i], 0, 16)
        }
        return matrices
    }

    fun computeRestMatrices(skeleton: Skeleton): Array<FloatArray> {
        val matrices = Array(skeleton.bones.size) { FloatArray(16) }
        for (i in skeleton.bones.indices) {
            val bone = skeleton.bones[i]
            val local = FloatArray(16)
            Matrix.setIdentityM(local, 0)
            Matrix.translateM(local, 0, bone.restX, bone.restY, bone.restZ)
            Matrix.rotateM(local, 0, bone.restRotY, 0f, 1f, 0f)
            Matrix.rotateM(local, 0, bone.restRotX, 1f, 0f, 0f)
            Matrix.rotateM(local, 0, bone.restRotZ, 0f, 0f, 1f)
            if (bone.parentIndex >= 0 && bone.parentIndex < i) {
                Matrix.multiplyMM(matrices[i], 0, matrices[bone.parentIndex], 0, local, 0)
            } else {
                System.arraycopy(local, 0, matrices[i], 0, 16)
            }
        }
        return matrices
    }

    fun computeInverseBind(skeleton: Skeleton): Array<FloatArray> {
        val rest = computeRestMatrices(skeleton)
        val inverse = Array(skeleton.bones.size) { FloatArray(16) }
        for (i in rest.indices) {
            Matrix.invertM(inverse[i], 0, rest[i], 0)
            skeleton.bones[i].inverseBind = inverse[i].copyOf()
        }
        return inverse
    }

    fun createSimpleSkeleton(baseName: String, boneCount: Int): Skeleton {
        val skel = Skeleton(1, baseName)
        for (i in 0 until boneCount) {
            skel.bones.add(Bone(
                id = i, name = "${baseName}_Bone_$i", parentIndex = if (i > 0) i - 1 else -1,
                x = 0f, y = i * 0.5f, z = 0f,
                restX = 0f, restY = i * 0.5f, restZ = 0f,
                inverseBind = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
            ))
        }
        computeInverseBind(skel)
        return skel
    }
}

object ModelingTools {
    fun subdivide(positions: FloatArray, triangleCount: Int): FloatArray {
        if (positions.size < 9 || triangleCount == 0) return positions
        val out = ArrayList<Float>(positions.size * 4)
        for (t in 0 until triangleCount) {
            val base = t * 9
            val a = floatArrayOf(positions[base], positions[base + 1], positions[base + 2])
            val b = floatArrayOf(positions[base + 3], positions[base + 4], positions[base + 5])
            val c = floatArrayOf(positions[base + 6], positions[base + 7], positions[base + 8])
            val ab = midpoint(a, b)
            val bc = midpoint(b, c)
            val ca = midpoint(c, a)
            emitTri(out, a, ab, ca)
            emitTri(out, ab, b, bc)
            emitTri(out, ca, bc, c)
            emitTri(out, ab, bc, ca)
        }
        return out.toFloatArray()
    }

    fun extrude(positions: FloatArray, triangleCount: Int, offset: Float): FloatArray {
        if (positions.size < 9) return positions
        val out = ArrayList<Float>(positions.size * 3)
        for (t in 0 until triangleCount) {
            val base = t * 9
            for (i in 0 until 3) {
                out.add(positions[base + i * 3])
                out.add(positions[base + i * 3 + 1] + offset)
                out.add(positions[base + i * 3 + 2])
            }
        }
        return out.toFloatArray()
    }

    fun bevel(positions: FloatArray, triangleCount: Int, amount: Float): FloatArray {
        if (positions.size < 9) return positions
        val out = ArrayList<Float>(positions.size)
        for (t in 0 until triangleCount) {
            val base = t * 9
            val a = floatArrayOf(positions[base], positions[base + 1], positions[base + 2])
            val b = floatArrayOf(positions[base + 3], positions[base + 4], positions[base + 5])
            val c = floatArrayOf(positions[base + 6], positions[base + 7], positions[base + 8])
            val centroid = floatArrayOf((a[0] + b[0] + c[0]) / 3f, (a[1] + b[1] + c[1]) / 3f, (a[2] + b[2] + c[2]) / 3f)
            val aN = lerp(a, centroid, amount)
            val bN = lerp(b, centroid, amount)
            val cN = lerp(c, centroid, amount)
            emitTri(out, aN, bN, cN)
        }
        return out.toFloatArray()
    }

    fun merge(positionsA: FloatArray, positionsB: FloatArray): FloatArray {
        val out = FloatArray(positionsA.size + positionsB.size)
        System.arraycopy(positionsA, 0, out, 0, positionsA.size)
        System.arraycopy(positionsB, 0, out, positionsA.size, positionsB.size)
        return out
    }

    fun loopCut(positions: FloatArray, triangleCount: Int): FloatArray {
        return subdivide(positions, triangleCount)
    }

    fun flipNormals(positions: FloatArray, triangleCount: Int): FloatArray {
        if (positions.size < 9) return positions
        val out = positions.copyOf()
        for (t in 0 until triangleCount) {
            val base = t * 9
            val a = floatArrayOf(out[base], out[base + 1], out[base + 2])
            val b = floatArrayOf(out[base + 3], out[base + 4], out[base + 5])
            val c = floatArrayOf(out[base + 6], out[base + 7], out[base + 8])
            System.arraycopy(b, 0, out, base, 3)
            System.arraycopy(a, 0, out, base + 3, 3)
        }
        return out
    }

    fun computeNormals(positions: FloatArray, triangleCount: Int): FloatArray {
        if (positions.size < 9) return FloatArray(0)
        val normals = FloatArray(positions.size)
        for (t in 0 until triangleCount) {
            val base = t * 9
            val a = floatArrayOf(positions[base], positions[base + 1], positions[base + 2])
            val b = floatArrayOf(positions[base + 3], positions[base + 4], positions[base + 5])
            val c = floatArrayOf(positions[base + 6], positions[base + 7], positions[base + 8])
            val edge1 = ModelMath.sub(b, a)
            val edge2 = ModelMath.sub(c, a)
            val n = ModelMath.norm(ModelMath.cross(edge1, edge2))
            for (i in 0 until 3) {
                normals[base + i * 3] = n[0]
                normals[base + i * 3 + 1] = n[1]
                normals[base + i * 3 + 2] = n[2]
            }
        }
        return normals
    }

    fun paintVertex(vertexColors: FloatArray, vertexIndex: Int, color: FloatArray, radius: Float, strength: Float): FloatArray {
        val out = vertexColors.copyOf()
        val count = out.size / 3
        if (vertexIndex < 0 || vertexIndex >= count) return out
        out[vertexIndex * 3] = color[0] * strength + out[vertexIndex * 3] * (1f - strength)
        out[vertexIndex * 3 + 1] = color[1] * strength + out[vertexIndex * 3 + 1] * (1f - strength)
        out[vertexIndex * 3 + 2] = color[2] * strength + out[vertexIndex * 3 + 2] * (1f - strength)
        return out
    }

    fun paintVertexByProximity(
        positions: FloatArray, vertexColors: FloatArray,
        targetPos: FloatArray, paintColor: FloatArray, radius: Float, strength: Float
    ): FloatArray {
        val count = positions.size / 3
        val out = if (vertexColors.size == count * 3) vertexColors.copyOf() else FloatArray(count * 3)
        val r2 = radius * radius
        for (i in 0 until count) {
            val dx = positions[i * 3] - targetPos[0]
            val dy = positions[i * 3 + 1] - targetPos[1]
            val dz = positions[i * 3 + 2] - targetPos[2]
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 < r2) {
                val falloff = 1f - (sqrt(d2) / radius)
                val s = strength * falloff
                out[i * 3] = paintColor[0] * s + out[i * 3] * (1f - s)
                out[i * 3 + 1] = paintColor[1] * s + out[i * 3 + 1] * (1f - s)
                out[i * 3 + 2] = paintColor[2] * s + out[i * 3 + 2] * (1f - s)
            }
        }
        return out
    }

    private fun midpoint(a: FloatArray, b: FloatArray) = floatArrayOf((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, (a[2] + b[2]) / 2f)
    private fun lerp(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)
    private fun emitTri(out: MutableList<Float>, a: FloatArray, b: FloatArray, c: FloatArray) {
        out.add(a[0]); out.add(a[1]); out.add(a[2])
        out.add(b[0]); out.add(b[1]); out.add(b[2])
        out.add(c[0]); out.add(c[1]); out.add(c[2])
    }
}

enum class PaintMode {
    OBJECT, EDIT, SCULPT, VERTEX_PAINT, WEIGHT_PAINT, TEXTURE_PAINT, RIG
}

enum class EditorMode {
    OBJECT_MODE, EDIT_MODE, SCULPT_MODE, VERTEX_PAINT_MODE, WEIGHT_PAINT_MODE, RIG_MODE, POSE_MODE
}

enum class TransformTool {
    MOVE, ROTATE, SCALE, COMBINED
}

data class EditorState(
    var mode: EditorMode = EditorMode.OBJECT_MODE,
    var paintMode: PaintMode = PaintMode.OBJECT,
    var transformTool: TransformTool = TransformTool.MOVE,
    var selectedBoneIndex: Int = -1,
    var brushRadius: Float = 0.5f,
    var brushStrength: Float = 0.5f,
    var brushColor: FloatArray = floatArrayOf(0.8f, 0.3f, 0.2f)
)

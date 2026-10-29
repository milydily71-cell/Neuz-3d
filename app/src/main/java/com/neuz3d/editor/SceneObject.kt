package com.neuz3d.editor

import org.json.JSONArray
import org.json.JSONObject

data class ImportedMesh(
    val positions: FloatArray,
    val normals: FloatArray = FloatArray(0),
    val triangles: Int,
    val baseColor: FloatArray = floatArrayOf(1f, 1f, 1f),
    val metallic: Float = 0.05f,
    val roughness: Float = 0.75f,
    val vertexColors: FloatArray = FloatArray(0),
    val bones: List<BoneData> = emptyList(),
    val skinWeights: FloatArray = FloatArray(0),
    val skinJoints: IntArray = IntArray(0)
)

data class BoneData(
    val name: String,
    val parentIndex: Int,
    val inverseBind: FloatArray,
    val restPosition: FloatArray = floatArrayOf(0f, 0f, 0f),
    val restRotation: FloatArray = floatArrayOf(0f, 0f, 0f)
)

data class SceneLight(
    var id: Int,
    var name: String,
    var type: String = "Point",
    var color: FloatArray = floatArrayOf(1f, 0.95f, 0.85f),
    var intensity: Float = 1.0f,
    var range: Float = 15f,
    var x: Float = 3f,
    var y: Float = 5f,
    var z: Float = 3f,
    var castShadows: Boolean = false,
    var enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type)
        .put("colorR", color[0]).put("colorG", color[1]).put("colorB", color[2])
        .put("intensity", intensity).put("range", range)
        .put("x", x).put("y", y).put("z", z)
        .put("castShadows", castShadows).put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject): SceneLight = SceneLight(
            o.getInt("id"), o.getString("name"), o.optString("type", "Point"),
            floatArrayOf(o.optDouble("colorR", 1.0).toFloat(), o.optDouble("colorG", 0.95).toFloat(), o.optDouble("colorB", 0.85).toFloat()),
            o.optDouble("intensity", 1.0).toFloat(), o.optDouble("range", 15.0).toFloat(),
            o.optDouble("x", 3.0).toFloat(), o.optDouble("y", 5.0).toFloat(), o.optDouble("z", 3.0).toFloat(),
            o.optBoolean("castShadows", false), o.optBoolean("enabled", true)
        )
    }
}

data class SceneCamera(
    var id: Int,
    var name: String,
    var fov: Float = 48f,
    var near: Float = 0.1f,
    var far: Float = 120f,
    var x: Float = 6f,
    var y: Float = 5f,
    var z: Float = 8f,
    var lookAtX: Float = 0f,
    var lookAtY: Float = 0.8f,
    var lookAtZ: Float = 0f,
    var active: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("fov", fov).put("near", near).put("far", far)
        .put("x", x).put("y", y).put("z", z)
        .put("lookAtX", lookAtX).put("lookAtY", lookAtY).put("lookAtZ", lookAtZ)
        .put("active", active)

    companion object {
        fun fromJson(o: JSONObject): SceneCamera = SceneCamera(
            o.getInt("id"), o.getString("name"),
            o.optDouble("fov", 48.0).toFloat(), o.optDouble("near", 0.1).toFloat(), o.optDouble("far", 120.0).toFloat(),
            o.optDouble("x", 6.0).toFloat(), o.optDouble("y", 5.0).toFloat(), o.optDouble("z", 8.0).toFloat(),
            o.optDouble("lookAtX", 0.0).toFloat(), o.optDouble("lookAtY", 0.8).toFloat(), o.optDouble("lookAtZ", 0.0).toFloat(),
            o.optBoolean("active", false)
        )
    }
}

data class Bone(
    var id: Int,
    var name: String,
    var parentIndex: Int,
    var x: Float = 0f, var y: Float = 0f, var z: Float = 0f,
    var rotX: Float = 0f, var rotY: Float = 0f, var rotZ: Float = 0f,
    var scale: Float = 1f,
    var inverseBind: FloatArray = FloatArray(16),
    var restX: Float = 0f, var restY: Float = 0f, var restZ: Float = 0f,
    var restRotX: Float = 0f, var restRotY: Float = 0f, var restRotZ: Float = 0f
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("parentIndex", parentIndex)
        .put("x", x).put("y", y).put("z", z)
        .put("rotX", rotX).put("rotY", rotY).put("rotZ", rotZ).put("scale", scale)
        .put("restX", restX).put("restY", restY).put("restZ", restZ)
        .put("restRotX", restRotX).put("restRotY", restRotY).put("restRotZ", restRotZ)
        .put("inverseBind", floatArrayToJson(inverseBind))

    companion object {
        fun fromJson(o: JSONObject): Bone = Bone(
            o.getInt("id"), o.getString("name"), o.optInt("parentIndex", -1),
            o.optDouble("x", 0.0).toFloat(), o.optDouble("y", 0.0).toFloat(), o.optDouble("z", 0.0).toFloat(),
            o.optDouble("rotX", 0.0).toFloat(), o.optDouble("rotY", 0.0).toFloat(), o.optDouble("rotZ", 0.0).toFloat(),
            o.optDouble("scale", 1.0).toFloat(),
            jsonToFloatArray(o.optJSONArray("inverseBind"), 16),
            o.optDouble("restX", 0.0).toFloat(), o.optDouble("restY", 0.0).toFloat(), o.optDouble("restZ", 0.0).toFloat(),
            o.optDouble("restRotX", 0.0).toFloat(), o.optDouble("restRotY", 0.0).toFloat(), o.optDouble("restRotZ", 0.0).toFloat()
        )
    }
}

data class Skeleton(
    var id: Int,
    var name: String,
    var bones: MutableList<Bone> = mutableListOf(),
    var skinWeights: FloatArray = FloatArray(0),
    var skinJoints: IntArray = IntArray(0)
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("bones", JSONArray().also { arr -> bones.forEach { arr.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Skeleton {
            val skel = Skeleton(o.getInt("id"), o.getString("name"))
            val arr = o.optJSONArray("bones")
            if (arr != null) {
                for (i in 0 until arr.length()) skel.bones.add(Bone.fromJson(arr.getJSONObject(i)))
            }
            return skel
        }
    }
}

data class SceneObject(
    val id: Int,
    var name: String,
    var type: String = "Cube",
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
    var scale: Float = 1f,
    var rotation: Float = 0f,
    var rotX: Float = 0f,
    var rotZ: Float = 0f,
    var color: FloatArray = floatArrayOf(0.3f, 0.58f, 0.95f),
    var metallic: Float = 0.12f,
    var roughness: Float = 0.48f,
    var visible: Boolean = true,
    var wireframe: Boolean = false,
    var vertexColors: FloatArray = FloatArray(0),
    var skeletonId: Int = -1,
    var castShadows: Boolean = true,
    var receiveShadows: Boolean = true,
    var emissive: Float = 0f
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type)
        .put("x", x).put("y", y).put("z", z)
        .put("scale", scale).put("rotation", rotation)
        .put("rotX", rotX).put("rotZ", rotZ)
        .put("metallic", metallic).put("roughness", roughness)
        .put("visible", visible).put("wireframe", wireframe)
        .put("colorR", color[0]).put("colorG", color[1]).put("colorB", color[2])
        .put("skeletonId", skeletonId)
        .put("castShadows", castShadows).put("receiveShadows", receiveShadows)
        .put("emissive", emissive)

    companion object {
        fun fromJson(o: JSONObject): SceneObject {
            return SceneObject(
                o.getInt("id"), o.getString("name"), o.optString("type", "Cube"),
                o.optDouble("x", 0.0).toFloat(), o.optDouble("y", 0.0).toFloat(),
                o.optDouble("z", 0.0).toFloat(), o.optDouble("scale", 1.0).toFloat(),
                o.optDouble("rotation", 0.0).toFloat(),
                o.optDouble("rotX", 0.0).toFloat(), o.optDouble("rotZ", 0.0).toFloat(),
                floatArrayOf(o.optDouble("colorR", 0.3).toFloat(), o.optDouble("colorG", 0.58).toFloat(), o.optDouble("colorB", 0.95).toFloat()),
                o.optDouble("metallic", 0.12).toFloat(),
                o.optDouble("roughness", 0.48).toFloat(),
                o.optBoolean("visible", true),
                o.optBoolean("wireframe", false),
                FloatArray(0),
                o.optInt("skeletonId", -1),
                o.optBoolean("castShadows", true),
                o.optBoolean("receiveShadows", true),
                o.optDouble("emissive", 0.0).toFloat()
            )
        }
    }
}

private fun floatArrayToJson(arr: FloatArray): JSONArray {
    val out = JSONArray()
    arr.forEach { out.put(it.toDouble()) }
    return out
}

private fun jsonToFloatArray(arr: JSONArray?, defaultSize: Int): FloatArray {
    if (arr == null) return FloatArray(defaultSize)
    val out = FloatArray(arr.length())
    for (i in out.indices) out[i] = arr.optDouble(i, 0.0).toFloat()
    return out
}

fun objectsToJsonArray(objects: List<SceneObject>): JSONArray {
    val array = JSONArray()
    objects.forEach { array.put(it.toJson()) }
    return array
}

fun lightsToJsonArray(lights: List<SceneLight>): JSONArray {
    val array = JSONArray()
    lights.forEach { array.put(it.toJson()) }
    return array
}

fun camerasToJsonArray(cameras: List<SceneCamera>): JSONArray {
    val array = JSONArray()
    cameras.forEach { array.put(it.toJson()) }
    return array
}

fun skeletonsToJsonArray(skeletons: List<Skeleton>): JSONArray {
    val array = JSONArray()
    skeletons.forEach { array.put(it.toJson()) }
    return array
}

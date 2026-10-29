package com.neuz3d.editor

import org.json.JSONArray
import org.json.JSONObject

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
    var rotation: Float = 0f,
    var rotX: Float = 0f,
    var rotZ: Float = 0f,
    var color: FloatArray = floatArrayOf(0.3f, 0.58f, 0.95f),
    var metallic: Float = 0.12f,
    var roughness: Float = 0.48f,
    var visible: Boolean = true,
    var wireframe: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type)
        .put("x", x).put("y", y).put("z", z)
        .put("scale", scale).put("rotation", rotation)
        .put("rotX", rotX).put("rotZ", rotZ)
        .put("metallic", metallic).put("roughness", roughness)
        .put("visible", visible).put("wireframe", wireframe)
        .put("colorR", color[0]).put("colorG", color[1]).put("colorB", color[2])

    companion object {
        fun fromJson(o: JSONObject): SceneObject {
            val color = floatArrayOf(
                o.optDouble("colorR", 0.3).toFloat(),
                o.optDouble("colorG", 0.58).toFloat(),
                o.optDouble("colorB", 0.95).toFloat()
            )
            return SceneObject(
                o.getInt("id"), o.getString("name"), o.optString("type", "Cube"),
                o.optDouble("x", 0.0).toFloat(), o.optDouble("y", 0.0).toFloat(),
                o.optDouble("z", 0.0).toFloat(), o.optDouble("scale", 1.0).toFloat(),
                o.optDouble("rotation", 0.0).toFloat(),
                o.optDouble("rotX", 0.0).toFloat(), o.optDouble("rotZ", 0.0).toFloat(),
                color,
                o.optDouble("metallic", 0.12).toFloat(),
                o.optDouble("roughness", 0.48).toFloat(),
                o.optBoolean("visible", true),
                o.optBoolean("wireframe", false)
            )
        }
    }
}

fun objectsToJsonArray(objects: List<SceneObject>): JSONArray {
    val array = JSONArray()
    objects.forEach { array.put(it.toJson()) }
    return array
}

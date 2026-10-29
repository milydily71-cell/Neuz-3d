package com.neuz3d.editor

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Geometry-focused glTF 2.0 importer. Supports GLB 2.0 and glTF JSON with embedded
 * data-URI buffers; external .bin files, skins, morph targets and material textures
 * are intentionally rejected instead of silently importing broken geometry.
 */
object GltfParser {
    private const val GLB_MAGIC = 0x46546C67
    private const val JSON_CHUNK = 0x4E4F534A
    private const val BIN_CHUNK = 0x004E4942

    fun isGltf(bytes: ByteArray): Boolean {
        if (bytes.size >= 4 && ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int == GLB_MAGIC) return true
        return String(bytes, 0, minOf(bytes.size, 32), StandardCharsets.UTF_8).trimStart().startsWith("{")
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
                    baseColor = floatArrayOf(
                        factor.optDouble(0, 1.0).toFloat().coerceIn(0f, 1f),
                        factor.optDouble(1, 1.0).toFloat().coerceIn(0f, 1f),
                        factor.optDouble(2, 1.0).toFloat().coerceIn(0f, 1f)
                    )
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

/**
 * Minimal Wavefront OBJ geometry importer: triangulates polygon faces and resolves negative indices.
 */
object ObjParser {
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

package com.neuz3d.editor

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class MeshData(
    val vertices: FloatArray,
    val normals: FloatArray,
    val wireVertices: FloatArray,
    val triangleCount: Int,
    val wireCount: Int
) {
    fun vertexCount(): Int = vertices.size / 3
    fun hasNormals(): Boolean = normals.size == vertices.size
    fun interleavedPosNorm(): FloatArray {
        if (normals.isEmpty()) return vertices
        val out = FloatArray(vertices.size + normals.size)
        for (i in 0 until vertexCount()) {
            out[i * 6] = vertices[i * 3]
            out[i * 6 + 1] = vertices[i * 3 + 1]
            out[i * 6 + 2] = vertices[i * 3 + 2]
            out[i * 6 + 3] = normals[i * 3]
            out[i * 6 + 4] = normals[i * 3 + 1]
            out[i * 6 + 5] = normals[i * 3 + 2]
        }
        return out
    }
}

object MeshBuilder {

    private const val STRIDE = 6

    fun build(type: String): MeshData {
        return when (type) {
            "Cube" -> buildCube()
            "Sphere" -> buildSphere(16, 24)
            "Cylinder" -> buildCylinder(16)
            "Cone" -> buildCone(16)
            "Torus" -> buildTorus(20, 12)
            "Plane" -> buildPlane()
            "Pyramid" -> buildPyramid()
            "Capsule" -> buildCapsule(16, 24)
            "IcoSphere" -> buildIcoSphere(1)
            else -> buildCube()
        }
    }

    private fun computeNormals(positions: FloatArray, triCount: Int): FloatArray {
        val normals = FloatArray(positions.size)
        for (t in 0 until triCount) {
            val b = t * 9
            val ax = positions[b]; val ay = positions[b+1]; val az = positions[b+2]
            val bx = positions[b+3]; val by = positions[b+4]; val bz = positions[b+5]
            val cx = positions[b+6]; val cy = positions[b+7]; val cz = positions[b+8]
            val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
            val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
            var nx = e1y * e2z - e1z * e2y
            var ny = e1z * e2x - e1x * e2z
            var nz = e1x * e2y - e1y * e2x
            val len = sqrt(nx*nx + ny*ny + nz*nz).coerceAtLeast(0.0001f)
            nx /= len; ny /= len; nz /= len
            for (i in 0 until 3) {
                normals[b + i*3] = nx; normals[b + i*3 + 1] = ny; normals[b + i*3 + 2] = nz
            }
        }
        return normals
    }

    fun buildCube(): MeshData {
        val p = arrayOf(
            floatArrayOf(-0.5f, -0.5f, 0.5f), floatArrayOf(0.5f, -0.5f, 0.5f),
            floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(-0.5f, 0.5f, 0.5f),
            floatArrayOf(-0.5f, -0.5f, -0.5f), floatArrayOf(0.5f, -0.5f, -0.5f),
            floatArrayOf(0.5f, 0.5f, -0.5f), floatArrayOf(-0.5f, 0.5f, -0.5f)
        )
        val faces = arrayOf(
            intArrayOf(0, 1, 2, 0, 2, 3), intArrayOf(5, 4, 7, 5, 7, 6),
            intArrayOf(4, 0, 3, 4, 3, 7), intArrayOf(1, 5, 6, 1, 6, 2),
            intArrayOf(3, 2, 6, 3, 6, 7), intArrayOf(4, 5, 1, 4, 1, 0)
        )
        val out = ArrayList<Float>(108)
        for (f in faces.indices) for (idx in faces[f]) {
            val v = p[idx]
            out.add(v[0]); out.add(v[1]); out.add(v[2])
        }
        val triCount = 12
        val normals = computeNormals(out.toFloatArray(), triCount)
        val wire = ArrayList<Float>(72)
        val edges = arrayOf(
            intArrayOf(0,1), intArrayOf(1,2), intArrayOf(2,3), intArrayOf(3,0),
            intArrayOf(4,5), intArrayOf(5,6), intArrayOf(6,7), intArrayOf(7,4),
            intArrayOf(0,4), intArrayOf(1,5), intArrayOf(2,6), intArrayOf(3,7)
        )
        for (e in edges) {
            val a = p[e[0]]; val b = p[e[1]]
            wire.add(a[0]); wire.add(a[1]); wire.add(a[2])
            wire.add(b[0]); wire.add(b[1]); wire.add(b[2])
        }
        return MeshData(out.toFloatArray(), normals, wire.toFloatArray(), triCount, 24)
    }

    fun buildSphere(rings: Int = 16, segments: Int = 24): MeshData {
        val tri = ArrayList<Float>()
        val wire = ArrayList<Float>()
        for (ring in 0 until rings) {
            val lat0 = -PI / 2 + PI * ring / rings
            val lat1 = -PI / 2 + PI * (ring + 1) / rings
            for (seg in 0 until segments) {
                val lon0 = 2 * PI * seg / segments
                val lon1 = 2 * PI * (seg + 1) / segments
                val v00 = sph(lat0, lon0); val v01 = sph(lat0, lon1)
                val v10 = sph(lat1, lon0); val v11 = sph(lat1, lon1)
                addTri(tri, v00, v10, v11)
                addTri(tri, v00, v11, v01)
                if (ring % 4 == 0) addLine(wire, v00, v01)
            }
        }
        val positions = tri.toFloatArray()
        val triCount = positions.size / 9
        val normals = computeNormals(positions, triCount)
        return MeshData(positions, normals, wire.toFloatArray(), triCount, wire.size / 3)
    }

    private fun sph(lat: Double, lon: Double): FloatArray = floatArrayOf(
        (cos(lat) * cos(lon)).toFloat() * 0.5f,
        sin(lat).toFloat() * 0.5f,
        (cos(lat) * sin(lon)).toFloat() * 0.5f
    )

    fun buildCylinder(segments: Int = 16): MeshData {
        val tri = ArrayList<Float>()
        val wire = ArrayList<Float>()
        val h = 0.5f
        for (i in 0 until segments) {
            val a0 = (2 * PI * i / segments).toFloat()
            val a1 = (2 * PI * (i + 1) / segments).toFloat()
            val c0 = floatArrayOf(cos(a0) * 0.5f, -h, sin(a0) * 0.5f)
            val c1 = floatArrayOf(cos(a1) * 0.5f, -h, sin(a1) * 0.5f)
            val t0 = floatArrayOf(cos(a0) * 0.5f, h, sin(a0) * 0.5f)
            val t1 = floatArrayOf(cos(a1) * 0.5f, h, sin(a1) * 0.5f)
            addTri(tri, c0, c1, t1)
            addTri(tri, c0, t1, t0)
            addTri(tri, floatArrayOf(0f, -h, 0f), c1, c0)
            addTri(tri, floatArrayOf(0f, h, 0f), t0, t1)
            addLine(wire, c0, c1); addLine(wire, t0, t1); addLine(wire, c0, t0)
        }
        val positions = tri.toFloatArray()
        val triCount = positions.size / 9
        val normals = computeNormals(positions, triCount)
        return MeshData(positions, normals, wire.toFloatArray(), triCount, wire.size / 3)
    }

    fun buildCone(segments: Int = 16): MeshData {
        val tri = ArrayList<Float>()
        val wire = ArrayList<Float>()
        val h = 0.5f
        val apex = floatArrayOf(0f, h, 0f)
        for (i in 0 until segments) {
            val a0 = (2 * PI * i / segments).toFloat()
            val a1 = (2 * PI * (i + 1) / segments).toFloat()
            val c0 = floatArrayOf(cos(a0) * 0.5f, -h, sin(a0) * 0.5f)
            val c1 = floatArrayOf(cos(a1) * 0.5f, -h, sin(a1) * 0.5f)
            addTri(tri, c0, c1, apex)
            addTri(tri, floatArrayOf(0f, -h, 0f), c1, c0)
            addLine(wire, c0, c1); addLine(wire, c0, apex)
        }
        val positions = tri.toFloatArray()
        val triCount = positions.size / 9
        val normals = computeNormals(positions, triCount)
        return MeshData(positions, normals, wire.toFloatArray(), triCount, wire.size / 3)
    }

    fun buildTorus(rings: Int = 20, segments: Int = 12): MeshData {
        val tri = ArrayList<Float>()
        val wire = ArrayList<Float>()
        val R = 0.35f; val r = 0.15f
        for (i in 0 until rings) {
            val u0 = (2 * PI * i / rings).toFloat()
            val u1 = (2 * PI * (i + 1) / rings).toFloat()
            for (j in 0 until segments) {
                val v0 = (2 * PI * j / segments).toFloat()
                val v1 = (2 * PI * (j + 1) / segments).toFloat()
                val p00 = torusPt(R, r, u0, v0); val p01 = torusPt(R, r, u0, v1)
                val p10 = torusPt(R, r, u1, v0); val p11 = torusPt(R, r, u1, v1)
                addTri(tri, p00, p10, p11); addTri(tri, p00, p11, p01)
                if (j == 0) addLine(wire, p00, p10)
            }
        }
        val positions = tri.toFloatArray()
        val triCount = positions.size / 9
        val normals = computeNormals(positions, triCount)
        return MeshData(positions, normals, wire.toFloatArray(), triCount, wire.size / 3)
    }

    private fun torusPt(R: Float, r: Float, u: Float, v: Float): FloatArray {
        val cu = cos(u); val su = sin(u); val cv = cos(v); val sv = sin(v)
        return floatArrayOf((R + r * cv) * cu, r * sv, (R + r * cv) * su)
    }

    fun buildPlane(): MeshData {
        val tri = ArrayList<Float>()
        val wire = ArrayList<Float>()
        val h = 0.5f
        val v0 = floatArrayOf(-h, 0f, -h); val v1 = floatArrayOf(h, 0f, -h)
        val v2 = floatArrayOf(h, 0f, h); val v3 = floatArrayOf(-h, 0f, h)
        addTri(tri, v0, v1, v2); addTri(tri, v0, v2, v3)
        addLine(wire, v0, v1); addLine(wire, v1, v2); addLine(wire, v2, v3); addLine(wire, v3, v0)
        val positions = tri.toFloatArray()
        val normals = computeNormals(positions, 2)
        return MeshData(positions, normals, wire.toFloatArray(), 2, 8)
    }

    fun buildPyramid(): MeshData {
        val tri = ArrayList<Float>()
        val wire = ArrayList<Float>()
        val h = 0.5f
        val apex = floatArrayOf(0f, h, 0f)
        val b0 = floatArrayOf(-h, -h, h); val b1 = floatArrayOf(h, -h, h)
        val b2 = floatArrayOf(h, -h, -h); val b3 = floatArrayOf(-h, -h, -h)
        addTri(tri, b0, b1, apex); addTri(tri, b1, b2, apex)
        addTri(tri, b2, b3, apex); addTri(tri, b3, b0, apex)
        addTri(tri, b0, b2, b1); addTri(tri, b0, b3, b2)
        addLine(wire, b0, b1); addLine(wire, b1, b2); addLine(wire, b2, b3); addLine(wire, b3, b0)
        addLine(wire, b0, apex); addLine(wire, b1, apex); addLine(wire, b2, apex); addLine(wire, b3, apex)
        val positions = tri.toFloatArray()
        val triCount = positions.size / 9
        val normals = computeNormals(positions, triCount)
        return MeshData(positions, normals, wire.toFloatArray(), triCount, wire.size / 3)
    }

    fun buildCapsule(rings: Int = 16, segments: Int = 24): MeshData {
        val tri = ArrayList<Float>()
        val wire = ArrayList<Float>()
        val cylH = 0.3f; val r = 0.4f
        for (ring in 0 until rings) {
            val lat0 = -PI / 2 + PI * ring / rings
            val lat1 = -PI / 2 + PI * (ring + 1) / rings
            val yOffset = if (lat0 < 0) -cylH else cylH
            val yOffset1 = if (lat1 < 0) -cylH else cylH
            for (seg in 0 until segments) {
                val lon0 = 2 * PI * seg / segments
                val lon1 = 2 * PI * (seg + 1) / segments
                val v00 = capPt(lat0, lon0, r, yOffset); val v01 = capPt(lat0, lon1, r, yOffset)
                val v10 = capPt(lat1, lon0, r, yOffset1); val v11 = capPt(lat1, lon1, r, yOffset1)
                addTri(tri, v00, v10, v11); addTri(tri, v00, v11, v01)
            }
        }
        for (seg in 0 until segments) {
            val a = (2 * PI * seg / segments).toFloat()
            addLine(wire, floatArrayOf(cos(a) * r, -cylH, sin(a) * r), floatArrayOf(cos(a) * r, cylH, sin(a) * r))
        }
        val positions = tri.toFloatArray()
        val triCount = positions.size / 9
        val normals = computeNormals(positions, triCount)
        return MeshData(positions, normals, wire.toFloatArray(), triCount, wire.size / 3)
    }

    private fun capPt(lat: Double, lon: Double, r: Float, yOff: Float): FloatArray =
        floatArrayOf((cos(lat) * cos(lon)).toFloat() * r, sin(lat).toFloat() * r + yOff, (cos(lat) * sin(lon)).toFloat() * r)

    fun buildIcoSphere(subdivisions: Int = 1): MeshData {
        val t = (1.0 + Math.sqrt(5.0)) / 2.0
        val verts = mutableListOf(
            norm3(floatArrayOf(-1f, t.toFloat(), 0f)), norm3(floatArrayOf(1f, t.toFloat(), 0f)),
            norm3(floatArrayOf(-1f, -t.toFloat(), 0f)), norm3(floatArrayOf(1f, -t.toFloat(), 0f)),
            norm3(floatArrayOf(0f, -1f, t.toFloat())), norm3(floatArrayOf(0f, 1f, t.toFloat())),
            norm3(floatArrayOf(0f, -1f, -t.toFloat())), norm3(floatArrayOf(0f, 1f, -t.toFloat())),
            norm3(floatArrayOf(t.toFloat(), 0f, -1f)), norm3(floatArrayOf(t.toFloat(), 0f, 1f)),
            norm3(floatArrayOf(-t.toFloat(), 0f, -1f)), norm3(floatArrayOf(-t.toFloat(), 0f, 1f))
        )
        var faces = mutableListOf(
            intArrayOf(0,11,5), intArrayOf(0,5,1), intArrayOf(0,1,7), intArrayOf(0,7,10), intArrayOf(0,10,11),
            intArrayOf(1,5,9), intArrayOf(5,11,4), intArrayOf(11,10,2), intArrayOf(10,7,6), intArrayOf(7,1,8),
            intArrayOf(3,9,4), intArrayOf(3,4,2), intArrayOf(3,2,6), intArrayOf(3,6,8), intArrayOf(3,8,9),
            intArrayOf(4,9,5), intArrayOf(2,4,11), intArrayOf(6,2,10), intArrayOf(8,6,7), intArrayOf(9,8,1)
        )
        val midCache = HashMap<Long, Int>()
        fun mid(a: Int, b: Int): Int {
            val key = if (a < b) a.toLong() * 10000 + b else b.toLong() * 10000 + a
            midCache[key]?.let { return it }
            val p0 = verts[a]; val p1 = verts[b]
            val midPt = norm3(floatArrayOf((p0[0] + p1[0]) / 2f, (p0[1] + p1[1]) / 2f, (p0[2] + p1[2]) / 2f))
            verts.add(midPt)
            val idx = verts.size - 1
            midCache[key] = idx
            return idx
        }
        repeat(subdivisions) {
            val newFaces = mutableListOf<IntArray>()
            for (f in faces) {
                val a = mid(f[0], f[1]); val b = mid(f[1], f[2]); val c = mid(f[2], f[0])
                newFaces.add(intArrayOf(f[0], a, c)); newFaces.add(intArrayOf(f[1], b, a))
                newFaces.add(intArrayOf(f[2], c, b)); newFaces.add(intArrayOf(a, b, c))
            }
            faces = newFaces
        }
        val tri = ArrayList<Float>()
        val wire = ArrayList<Float>()
        for (f in faces) {
            val v0 = verts[f[0]]; val v1 = verts[f[1]]; val v2 = verts[f[2]]
            addTri(tri, v0, v1, v2); addLine(wire, v0, v1)
        }
        val positions = tri.toFloatArray()
        val triCount = faces.size
        val normals = FloatArray(positions.size)
        for (i in 0 until positions.size / 3) {
            normals[i * 3] = positions[i * 3]
            normals[i * 3 + 1] = positions[i * 3 + 1]
            normals[i * 3 + 2] = positions[i * 3 + 2]
            val len = sqrt(normals[i*3] * normals[i*3] + normals[i*3+1] * normals[i*3+1] + normals[i*3+2] * normals[i*3+2]).coerceAtLeast(0.0001f)
            normals[i * 3] /= len; normals[i * 3 + 1] /= len; normals[i * 3 + 2] /= len
        }
        return MeshData(positions, normals, wire.toFloatArray(), triCount, wire.size / 3)
    }

    private fun norm3(p: FloatArray): FloatArray {
        val len = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]).coerceAtLeast(0.0001f)
        return floatArrayOf(p[0] / len * 0.5f, p[1] / len * 0.5f, p[2] / len * 0.5f)
    }

    private fun addTri(out: MutableList<Float>, a: FloatArray, b: FloatArray, c: FloatArray) {
        out.add(a[0]); out.add(a[1]); out.add(a[2])
        out.add(b[0]); out.add(b[1]); out.add(b[2])
        out.add(c[0]); out.add(c[1]); out.add(c[2])
    }

    private fun addLine(out: MutableList<Float>, a: FloatArray, b: FloatArray) {
        out.add(a[0]); out.add(a[1]); out.add(a[2])
        out.add(b[0]); out.add(b[1]); out.add(b[2])
    }

    fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(values); position(0) }
}

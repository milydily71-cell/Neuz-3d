package com.neuz3d.editor

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView.Renderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : Activity() {
    private external fun nativeEngineInfo(): String
    private lateinit var viewport: GLSurfaceView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        System.loadLibrary("neuz3d")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 20, 27))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
        }
        val title = TextView(this).apply {
            text = "NEUZ 3D"
            textSize = 20f
            setTextColor(Color.WHITE)
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val rendererPicker = Spinner(this)
        val choices = listOf("Automatic", "OpenGL ES 3.2", "Vulkan")
        rendererPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices)
        rendererPicker.setSelection(0)
        rendererPicker.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position == 2) {
                    Toast.makeText(this@MainActivity, "Vulkan backend is planned; using the available GLES preview for this bootstrap.", Toast.LENGTH_LONG).show()
                    rendererPicker.setSelection(0)
                } else if (position == 1) {
                    Toast.makeText(this@MainActivity, "Requesting an OpenGL ES 3 context; exact 3.2 support is checked at runtime.", Toast.LENGTH_SHORT).show()
                }
            }
        })
        header.addView(rendererPicker)
        root.addView(header)

        viewport = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(EditorRenderer { version ->
                runOnUiThread {
                    status.text = "GPU: $version"
                }
            })
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root.addView(viewport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(12, 8, 12, 8)
            text = "Checking graphics capabilities…"
        }
        root.addView(status)
        val footer = TextView(this).apply {
            setTextColor(Color.WHITE)
            setPadding(16, 10, 16, 14)
            text = "Scene 01  •  Viewport preview  •  " + nativeEngineInfo()
            textSize = 11f
        }
        root.addView(footer)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); if (::viewport.isInitialized) viewport.onResume() }
    override fun onPause() { if (::viewport.isInitialized) viewport.onPause(); super.onPause() }

    private class EditorRenderer(private val onVersion: (String) -> Unit) : Renderer {
        private var program = 0
        private var position = 0
        private var color = 0
        private var reported = false
        private val vertices = floatBuffer(floatArrayOf(
            -0.75f, -0.55f, 0f, 0.25f, 0.75f, 1f,
             0.75f, -0.55f, 0f, 0.25f, 0.75f, 1f,
             0.00f,  0.70f, 0f, 0.25f, 0.75f, 1f
        ))

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.055f, 0.065f, 0.09f, 1f)
            val version = GLES20.glGetString(GLES20.GL_VERSION) ?: "unknown"
            val shading = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION) ?: "unknown"
            if (!reported) { reported = true; onVersion("$version • GLSL $shading") }
            val vertexShader = compile(GLES20.GL_VERTEX_SHADER,
                "attribute vec3 aPosition; attribute vec3 aColor; varying vec3 vColor; void main(){ gl_Position=vec4(aPosition,1.0); vColor=aColor; }")
            val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER,
                "precision mediump float; varying vec3 vColor; void main(){ gl_FragColor=vec4(vColor,1.0); }")
            program = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vertexShader); GLES20.glAttachShader(it, fragmentShader)
                GLES20.glLinkProgram(it)
            }
            position = GLES20.glGetAttribLocation(program, "aPosition")
            color = GLES20.glGetAttribLocation(program, "aColor")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (program == 0) return
            GLES20.glUseProgram(program)
            vertices.position(0)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 24, vertices)
            vertices.position(3)
            GLES20.glEnableVertexAttribArray(color)
            GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT, false, 24, vertices)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
            GLES20.glDisableVertexAttribArray(position)
            GLES20.glDisableVertexAttribArray(color)
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
}

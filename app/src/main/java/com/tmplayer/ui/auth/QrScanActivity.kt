package com.tmplayer.ui.auth

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Camera
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.tmplayer.data.QrLoginLinks
import com.tmplayer.data.Td
import com.tmplayer.data.guardedLifecycleScope as lifecycleScope
import com.tmplayer.i18n.L
import com.tmplayer.ui.theme.TMPlayerTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The camera scanner that confirms a television's QR sign in from inside J Player (item 6,
 * v1.0.15), the confirm side of the sign in the television's pane asks for.
 *
 * The route is the lightest one the build already carries: ZXing core is already a dependency
 * (it reads back the QR codes the app draws), and the platform camera needs no new library at
 * all, so nothing heavy was added for one screen. Frames are decoded from the preview's own
 * luminance on one background thread, throttled, and only a text [QrLoginLinks] accepts is
 * handed to TDLib; any other code is named as not a sign in code and scanning goes on.
 */
class QrScanActivity : ComponentActivity() {

    private var status by mutableStateOf("")
    private var camera: Camera? = null
    private var holder: SurfaceHolder? = null
    private val decodeBusy = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val decoder = Executors.newSingleThreadExecutor()
    private val reader = QRCodeReader()

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status = L.loginQrScanPermission
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = L.loginQrScanBlurb
        setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { context ->
                            SurfaceView(context).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        this@QrScanActivity.holder = holder
                                        if (camera == null) startCamera() else attachPreview()
                                    }
                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                                    override fun surfaceDestroyed(holder: SurfaceHolder) = stopCamera()
                                })
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color(0xB3000000)).padding(20.dp)) {
                        Text(L.loginQrScanTitle, style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text(status, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                        TextButton(onClick = { finish() }) { Text(L.commonClose, color = Color.White) }
                    }
                }
            }
        }
        // The platform way: the permission is asked for on first use, here, not at install.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        stopCamera()
        decoder.shutdownNow()
        super.onDestroy()
    }

    private fun startCamera() {
        if (camera != null || finished.get()) return
        val opened = runCatching { Camera.open() }.getOrNull() ?: return
        camera = opened
        runCatching {
            val params = opened.parameters
            val size = params.supportedPreviewSizes
                ?.filter { it.width in 640..1920 }
                ?.maxByOrNull { it.width * it.height }
            if (size != null) params.setPreviewSize(size.width, size.height)
            params.previewFormat = ImageFormat.NV21
            if (params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) == true) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            }
            opened.parameters = params
            opened.setDisplayOrientation(90)
        }
        attachPreview()
    }

    /**
     * Attaches the open camera to the surface the view reported. Called from both ends of the
     * race between the surface arriving and the camera permission arriving: whichever comes
     * second is the one that starts the picture.
     */
    private fun attachPreview() {
        val opened = camera ?: return
        val surface = holder ?: return
        runCatching {
            opened.setPreviewDisplay(surface)
            opened.setPreviewCallback { data, from ->
                val size = from.parameters.previewSize
                if (data != null && size != null) onFrame(data, size.width, size.height)
            }
            opened.startPreview()
        }
    }

    private fun stopCamera() {
        runCatching {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
            camera?.release()
        }
        camera = null
    }

    /** One preview frame: decode it on the background thread, never two at once. */
    private fun onFrame(data: ByteArray, width: Int, height: Int) {
        if (finished.get() || !decodeBusy.compareAndSet(false, true)) return
        val frame = data.copyOf(width * height)
        decoder.execute {
            try {
                val text = decode(frame, width, height)
                if (text != null) onCode(text)
            } finally {
                decodeBusy.set(false)
            }
        }
    }

    /**
     * The code in this frame, read as drawn and turned either way: the camera's own frame is
     * landscape however the phone is held, and a scanner that only reads one way round is a
     * scanner that fails in the hand. Null when the frame holds no code.
     */
    private fun decode(yPlane: ByteArray, width: Int, height: Int): String? {
        val quarter = rotateY(yPlane, width, height)
        val threeQuarters = rotateY(rotateY(quarter, height, width), width, height)
        val attempts = listOf(
            Triple(yPlane, width, height),
            Triple(quarter, height, width),
            Triple(threeQuarters, height, width),
        )
        for ((bytes, w, h) in attempts) {
            val found = runCatching {
                reader.decode(
                    BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(bytes, w, h, 0, 0, w, h, false))),
                ).text
            }.getOrNull()
            if (!found.isNullOrBlank()) return found
            runCatching { reader.reset() }
        }
        return null
    }

    /** [yPlane] (width by height, luminance only) turned a quarter turn clockwise. */
    private fun rotateY(yPlane: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(yPlane.size)
        for (y in 0 until height) for (x in 0 until width) {
            out[x * height + (height - 1 - y)] = yPlane[y * width + x]
        }
        return out
    }

    private fun onCode(text: String) {
        val link = QrLoginLinks.parse(text)
        if (link == null) {
            runOnUiThread { if (!finished.get()) status = L.loginQrScanNotSignIn }
            return
        }
        if (!finished.compareAndSet(false, true)) return
        runOnUiThread {
            status = L.loginQrScanConfirming
            stopCamera()
        }
        lifecycleScope.launch {
            val error = runCatching { Td.confirmQrCode(link) }.getOrElse { L.errorsNoAnswer }
            if (error == null) {
                status = L.loginQrScanDone
                delay(CLOSE_DELAY_MS)
                finish()
            } else {
                status = error
                finished.set(false)
                startCamera()
            }
        }
    }

    private companion object {
        const val CLOSE_DELAY_MS = 1200L
    }
}

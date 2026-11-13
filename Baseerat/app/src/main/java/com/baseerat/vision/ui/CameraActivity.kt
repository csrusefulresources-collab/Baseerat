package com.baseerat.vision.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.baseerat.vision.R
import com.baseerat.vision.ui.components.BigButton
import com.baseerat.vision.ui.components.BodyText
import com.baseerat.vision.ui.theme.AppTheme
import com.baseerat.vision.util.BitmapUtil
import com.baseerat.vision.util.LocaleHelper
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Real in-app camera. Big capture button, torch, and volume keys also take the picture
 * (easy for blind users who cannot locate an on-screen button).
 */
class CameraActivity : ComponentActivity() {

    private val trigger = mutableIntStateOf(0)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val mode = intent.getStringExtra(EXTRA_MODE) ?: "describe"
        val returnPath = intent.getBooleanExtra(EXTRA_RETURN, false)
        setContent {
            AppTheme {
                CameraScreen(
                    mode = mode,
                    trigger = trigger.intValue,
                    onCaptured = { path ->
                        if (returnPath) {
                            // used by the chat tab: hand the picture back instead of opening the Describe screen
                            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PATH, path))
                        } else {
                            startActivity(DescribeActivity.intent(this, path, mode, "camera"))
                        }
                        finish()
                    },
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event?.repeatCount == 0) trigger.intValue = trigger.intValue + 1
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_RETURN = "return_path"
        const val EXTRA_PATH = "path"
    }
}

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider = suspendCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({
        try {
            cont.resume(future.get())
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }, ContextCompat.getMainExecutor(this))
}

@Composable
private fun CameraScreen(mode: String, trigger: Int, onCaptured: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    if (!hasPermission) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            BodyText(stringResource(R.string.camera_permission_needed))
            BigButton(
                text = stringResource(R.string.camera_grant),
                icon = Icons.Filled.CameraAlt,
                onClick = { launcher.launch(Manifest.permission.CAMERA) }
            )
            BigButton(
                text = stringResource(R.string.btn_close),
                icon = Icons.Filled.Close,
                outlined = true,
                onClick = onClose
            )
        }
    } else {
        CameraContent(mode, trigger, onCaptured, onClose)
    }
}

@Composable
private fun CameraContent(mode: String, trigger: Int, onCaptured: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current


    val previewView = remember {
        PreviewView(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    val readyMsg = stringResource(R.string.camera_ready)
    val hint = stringResource(if (mode == "read") R.string.camera_hint_read else R.string.camera_hint_describe)
    val errMsg = stringResource(R.string.camera_error)
    val busyMsg = stringResource(R.string.camera_busy)

    LaunchedEffect(Unit) {
        try {
            val provider = context.awaitCameraProvider()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            ready = true
        } catch (e: Exception) {
            failed = true
        }
    }
    LaunchedEffect(ready) { if (ready) view.announceForAccessibility(readyMsg) }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }

    fun capture() {
        if (busy || !ready) return
        busy = true
        view.announceForAccessibility(busyMsg)
        val file = File(BitmapUtil.inputsDir(context), "cam_${System.currentTimeMillis()}.jpg")
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onCaptured(file.absolutePath)
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    failed = true
                }
            }
        )
    }
    LaunchedEffect(trigger) { if (trigger > 0) capture() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Surface(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = if (failed) errMsg else hint,
                modifier = Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        ) {
            Column(
                Modifier.navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BigButton(
                    text = stringResource(R.string.camera_capture),
                    icon = Icons.Filled.CameraAlt,
                    enabled = ready && !busy,
                    onClick = { capture() }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val hasTorch = camera?.cameraInfo?.hasFlashUnit() == true
                    if (hasTorch) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = { torch = !torch },
                            modifier = Modifier.weight(1f)
                        ) {
                            androidx.compose.material3.Icon(
                                if (torch) Icons.Filled.FlashOff else Icons.Filled.FlashOn,
                                contentDescription = null
                            )
                            Text(
                                stringResource(if (torch) R.string.camera_torch_off else R.string.camera_torch_on),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                    androidx.compose.material3.OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                        androidx.compose.material3.Icon(Icons.Filled.Close, contentDescription = null)
                        Text(stringResource(R.string.btn_close), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

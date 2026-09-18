package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.example.camera.CameraManager
import com.example.settings.SettingsRepository
import com.example.streaming.StreamingManager
import com.example.studio.StudioCompositor
import com.example.studio.StudioManager
import com.example.ui.screens.MainStreamingScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ObsidianBg

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var streamingManager: StreamingManager
    private lateinit var studioManager: StudioManager
    private lateinit var studioCompositor: StudioCompositor
    private var cameraManager: CameraManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settingsRepository = SettingsRepository(this)
        streamingManager = StreamingManager(this)
        studioManager = StudioManager()
        studioCompositor = StudioCompositor(
            studioStateFlow = studioManager.studioStateFlow,
            frameConsumer = { frameData, width, height ->
                streamingManager.onCameraFrame(frameData, width, height)
            }
        )

        val config = settingsRepository.getConfig()
        if (config.keepScreenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        cameraManager = CameraManager(
            context = this,
            lifecycleOwner = this,
            listener = object : CameraManager.FrameListener {
                override fun onFrameAvailable(
                    yuvData: ByteArray,
                    width: Int,
                    height: Int,
                    rotationDegrees: Int
                ) {
                    studioCompositor.onCameraFrame(yuvData, width, height)
                }

                override fun onFpsUpdated(fps: Double) {
                    streamingManager.updateCameraFps(fps)
                }

                override fun onCameraError(error: String) {
                    // camera error handled
                }
            }
        ).apply {
            setTargetResolution(config.videoPreset.width, config.videoPreset.height)
        }

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ObsidianBg
                ) {
                    var hasCameraPermission by remember {
                        mutableStateOf(
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                        )
                    }

                    var hasAudioPermission by remember {
                        mutableStateOf(
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        )
                    }

                    val permissionsLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions ->
                        hasCameraPermission = permissions[Manifest.permission.CAMERA] ?: hasCameraPermission
                        hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] ?: hasAudioPermission
                        if (hasCameraPermission && config.autoStartCamera) {
                            cameraManager?.startCamera(config.isFrontCamera)
                        }
                        if (hasAudioPermission && config.audioConfig.enabled) {
                            streamingManager.startAudioCapture()
                        }
                    }

                    val audioPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                        hasAudioPermission = isGranted
                        if (isGranted && config.audioConfig.enabled) {
                            streamingManager.startAudioCapture()
                        }
                    }

                    fun requestAllPermissions() {
                        val toRequest = mutableListOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            toRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permissionsLauncher.launch(toRequest.toTypedArray())
                    }

                    LaunchedEffect(Unit) {
                        if (!hasCameraPermission || !hasAudioPermission) {
                            requestAllPermissions()
                        } else {
                            if (config.autoStartCamera) {
                                cameraManager?.startCamera(config.isFrontCamera)
                            }
                            if (config.audioConfig.enabled) {
                                streamingManager.startAudioCapture()
                            }
                        }
                    }

                    MainStreamingScreen(
                        cameraManager = cameraManager,
                        streamingManager = streamingManager,
                        settingsRepository = settingsRepository,
                        studioManager = studioManager,
                        hasCameraPermission = hasCameraPermission,
                        hasAudioPermission = hasAudioPermission,
                        onRequestPermissions = { requestAllPermissions() },
                        onRequestAudioPermission = { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val config = settingsRepository.getConfig()
            if (config.audioConfig.enabled) {
                streamingManager.startAudioCapture()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!streamingManager.statusFlow.value.isStreaming) {
            streamingManager.stopAudioCapture()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        streamingManager.release()
        cameraManager?.release()
        cameraManager = null
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyApplicationTheme { Greeting("Android") }
}

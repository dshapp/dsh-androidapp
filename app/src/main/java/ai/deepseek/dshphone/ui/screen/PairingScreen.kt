package ai.deepseek.dshphone.ui.screen

import ai.deepseek.dshphone.i18n.Strings
import ai.deepseek.dshphone.state.PairingPayload
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/** 扫码配对：CameraX + ML Kit，无相机或无权限时退回粘贴配对串。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(onDismiss: () -> Unit, onPaired: (String, String) -> Unit) {
    val context = LocalContext.current
    var manual by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // 扫码提示改为进入时 toast 一次，避免常驻浮层被其它 UI 遮挡。
    LaunchedEffect(granted, manual) {
        if (granted && !manual) {
            Toast.makeText(context, Strings.scanHint, Toast.LENGTH_SHORT).show()
        }
    }

    fun handle(text: String) {
        if (PairingPayload.parse(text) == null) {
            error = Strings.pairInvalid
            return
        }
        name = PairingPayload.suggestedName(text)
        pending = text.trim()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(Strings.pairTitle) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, Strings.cancel) }
                        },
                        actions = {
                            TextButton(onClick = { manual = !manual }) {
                                Text(if (manual) Strings.scanQR else Strings.pastePairing)
                            }
                        },
                    )
                }
            ) { padding ->
                Box(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                ) {
                    if (granted && !manual) {
                        QrScanner(onScan = { handle(it) })
                    } else {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = pasted,
                                onValueChange = { pasted = it; error = null },
                                label = { Text(Strings.pastePairing) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                            )
                            if (!granted) {
                                Text(Strings.scanUnavailable,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            error?.let {
                                Text(it, color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            Button(
                                onClick = { handle(pasted) },
                                enabled = pasted.isNotBlank(),
                            ) { Text(Strings.connect) }
                        }
                    }
                }
            }
        }
    }

    if (pending != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(Strings.serverName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
                    Text(Strings.serverNameFooter, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pending?.let { onPaired(name.trim().ifEmpty { Strings.appName }, it) }
                    pending = null
                }) { Text(Strings.connect) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(Strings.cancel) } },
        )
    }
}

/**
 * 扫码页在组合期持有的相机资源。
 *
 * 单独用普通类而不是 mutableStateOf：这些引用只在监听器里读写，不需要参与重组。
 */
private class ScannerResources {
    var provider: ProcessCameraProvider? = null
    var analysis: ImageAnalysis? = null
    var scanner: BarcodeScanner? = null
    var disposed = false

    /** 停掉分析器并把相机还给系统；重复调用无副作用。 */
    fun release() {
        disposed = true
        // 先清分析器：清掉之后 CameraX 不会再往 executor 上投递，最后一步关线程池就不会
        // 撞上"往已 shutdown 的 executor execute"。
        runCatching { analysis?.clearAnalyzer() }
        runCatching { provider?.unbindAll() }
        runCatching { scanner?.close() }
        analysis = null
        provider = null
        scanner = null
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun QrScanner(onScan: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val resources = remember { ScannerResources() }
    var settled by remember { mutableStateOf(false) }
    // 绑定挂在 Activity 生命周期上，Activity 在本页关掉后仍是 RESUMED：不主动 unbind，
    // 相机就一直开着（指示灯常亮、持续耗电），分析器也还会被调用——而它的 executor 已经
    // 关停，于是 execute 抛 RejectedExecutionException。离开组合时按"清分析器 → 解绑 →
    // 关扫描器 → 关线程池"的顺序收干净。
    DisposableEffect(Unit) {
        onDispose {
            resources.release()
            executor.shutdown()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                // 相机 provider 是异步就绪的：这张页面可能已经被关掉了，别再绑一次。
                if (resources.disposed) return@addListener
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val scanner = BarcodeScanning.getClient()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                resources.provider = provider
                resources.scanner = scanner
                resources.analysis = analysis
                analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                    val media = proxy.image
                    if (media == null || settled) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { codes ->
                            val payload = codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                            if (payload != null && !settled) {
                                settled = true
                                onScan(payload)
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

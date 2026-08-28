package com.rokid.aranswerer.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.nio.ByteBuffer

/**
 * 专为 Rokid Glasses 裸机定制的纯原生 Camera2 硬件驱动器
 * 1. 自动选择硬件支持的最佳高清分辨率 (优先 1920x1080 / 1280x960)；
 * 2. 注入等比 CenterCrop 变换矩阵；
 * 3. 独立后台线程抓帧，高稳定性无死锁。
 */
class NativeCamera2Helper(
    private val context: Context,
    private val textureView: TextureView,
    private val onFrameCaptured: (ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraId = "0"
    private var previewSize = Size(640, 480)
    private var captureSize = Size(1920, 1080) // 提升为高清拍摄分辨率

    fun start() {
        startBackgroundThread()
        if (textureView.isAvailable) {
            configureTransform(textureView.width, textureView.height)
            openCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    configureTransform(width, height)
                    openCamera()
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    configureTransform(width, height)
                }
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
    }

    fun stop() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e("NativeCamera2", "stop error", e)
        }
        stopBackgroundThread()
    }

    fun takePicture() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        val handler = backgroundHandler ?: return

        try {
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {}, handler)
        } catch (e: Exception) {
            Log.e("NativeCamera2", "takePicture error", e)
            onError("拍照异常: ${e.message}")
        }
    }

    private fun openCamera() {
        try {
            val cameraIds = cameraManager.cameraIdList
            if (cameraIds.isEmpty()) {
                onError("未找到可用摄像头硬件")
                return
            }
            cameraId = cameraIds[0]

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                val outputSizes = map.getOutputSizes(SurfaceTexture::class.java)
                if (!outputSizes.isNullOrEmpty()) {
                    previewSize = outputSizes.firstOrNull { it.width == 640 && it.height == 480 } ?: outputSizes[0]
                }
                val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
                if (!jpegSizes.isNullOrEmpty()) {
                    // 挑选设备支持的最佳高清拍摄分辨率 (优先选择 1920x1080 -> 1280x960 -> 1280x720 -> 最大尺寸)
                    captureSize = jpegSizes.firstOrNull { it.width == 1920 && it.height == 1080 }
                        ?: jpegSizes.firstOrNull { it.width == 1280 && it.height == 960 }
                        ?: jpegSizes.firstOrNull { it.width == 1280 && it.height == 720 }
                        ?: jpegSizes[0]
                }
                Log.d("NativeCamera2", "Selected Capture Size: ${captureSize.width}x${captureSize.height}, Preview Size: ${previewSize.width}x${previewSize.height}")
            }

            imageReader = ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    try {
                        val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                        val buffer: ByteBuffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        image.close()

                        onFrameCaptured(bytes)
                    } catch (e: Exception) {
                        Log.e("NativeCamera2", "ImageReader error", e)
                    }
                }, backgroundHandler)
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    onError("相机打开失败 Code: $error")
                }
            }, backgroundHandler)

        } catch (e: SecurityException) {
            onError("相机权限被拒绝")
        } catch (e: Exception) {
            onError("相机启动异常: ${e.message}")
        }
    }

    private fun startPreview() {
        val device = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return
        val reader = imageReader ?: return
        val handler = backgroundHandler ?: return

        try {
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val surface = Surface(texture)

            val previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            device.createCaptureSession(listOf(surface, reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        session.setRepeatingRequest(previewRequestBuilder.build(), null, handler)
                    } catch (e: Exception) {
                        Log.e("NativeCamera2", "setRepeatingRequest error", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onError("预览会话创建失败")
                }
            }, handler)

        } catch (e: Exception) {
            onError("开启预览失败: ${e.message}")
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth == 0 || viewHeight == 0) return
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)

        val scale = Math.max(
            viewHeight.toFloat() / previewSize.height,
            viewWidth.toFloat() / previewSize.width
        )
        matrix.postScale(scale, scale, centerX, centerY)
        textureView.setTransform(matrix)
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("NativeCamera2Thread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (_: Exception) {}
    }
}

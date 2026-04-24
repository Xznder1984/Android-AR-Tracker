package com.artracer.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import com.artracer.app.databinding.ActivityMainBinding
import com.google.android.material.slider.Slider
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Single-screen AR tracing app:
 *   • CameraX live preview (back camera)
 *   • Image overlay (drag/scale/rotate/flip/opacity/lock)
 *   • Optional grid, fullscreen, screenshot capture
 *   • Last-loaded image is restored on next launch
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    private var fullscreen: Boolean = false

    // ---- Activity result contracts ---------------------------------------------------------

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                bindCamera()
            } else if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                showPermanentlyDeniedDialog()
            } else {
                Toast.makeText(this, R.string.permission_camera_denied, Toast.LENGTH_LONG).show()
            }
        }

    /** Photo Picker (Android 13+ uses the system picker; older versions fall back to GET_CONTENT). */
    private val photoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) loadImage(uri)
        }

    private val getContentLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) loadImage(uri)
        }

    // ---- Lifecycle -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        setupSystemUi()
        setupInsets()
        setupControls()

        binding.overlayView.setOpacity(binding.opacitySlider.value / 100f)
        updateOpacityLabel(binding.opacitySlider.value)

        ensureCameraPermission()
        restoreLastImage()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    // ---- Setup -----------------------------------------------------------------------------

    private fun setupSystemUi() {
        // Edge-to-edge by default; the fullscreen toggle hides bars on demand.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    private fun setupInsets() {
        // Push chrome away from the status/nav bars; the camera preview still bleeds underneath.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                topMargin = sys.top + dp(10)
            }
            binding.bottomPanel.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                bottomMargin = sys.bottom + dp(14)
            }
            insets
        }
    }

    private fun setupControls() {
        binding.btnPickImage.setOnClickListener { launchImagePicker() }

        binding.opacitySlider.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            binding.overlayView.setOpacity(value / 100f)
            updateOpacityLabel(value)
        })

        binding.btnLock.setOnClickListener {
            val nowLocked = !binding.overlayView.isLocked()
            binding.overlayView.setLocked(nowLocked)
            applyLockUi(nowLocked)
        }

        binding.btnGrid.setOnClickListener {
            val show = binding.gridOverlay.visibility != View.VISIBLE
            binding.gridOverlay.visibility = if (show) View.VISIBLE else View.GONE
            applyToggleUi(binding.btnGrid, show)
        }

        binding.btnFlip.setOnClickListener {
            if (!binding.overlayView.hasBitmap()) {
                Toast.makeText(this, R.string.hint_no_image, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.overlayView.flipHorizontally()
        }

        binding.btnReset.setOnClickListener {
            if (!binding.overlayView.hasBitmap()) {
                Toast.makeText(this, R.string.hint_no_image, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.overlayView.resetTransform()
            binding.opacitySlider.value = 60f
        }

        binding.btnFullscreen.setOnClickListener { toggleFullscreen() }

        binding.btnCapture.setOnClickListener { captureScreenshot() }

        binding.overlayView.setOnTransformChangedListener {
            // Hint disappears as soon as the user starts interacting.
            if (binding.overlayView.hasBitmap()) binding.hintLabel.visibility = View.GONE
        }
    }

    private fun applyLockUi(locked: Boolean) {
        binding.btnLock.setIconResource(if (locked) R.drawable.ic_lock else R.drawable.ic_unlock)
        binding.btnLock.text = getString(if (locked) R.string.action_lock else R.string.action_unlock)
        applyToggleUi(binding.btnLock, locked)
    }

    private fun applyToggleUi(button: com.google.android.material.button.MaterialButton, active: Boolean) {
        if (active) {
            button.setBackgroundResource(R.drawable.bg_chip_primary)
            button.setTextColor(ContextCompat.getColor(this, R.color.fg_on_accent))
            button.iconTint = ContextCompat.getColorStateList(this, R.color.fg_on_accent)
        } else {
            button.setBackgroundResource(R.drawable.bg_chip)
            button.setTextColor(ContextCompat.getColor(this, R.color.fg_primary))
            button.iconTint = ContextCompat.getColorStateList(this, R.color.fg_primary)
        }
    }

    private fun updateOpacityLabel(value: Float) {
        binding.opacityValue.text = "${value.toInt()}%"
    }

    // ---- Camera ----------------------------------------------------------------------------

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            bindCamera()
        } else if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            AlertDialog.Builder(this)
                .setMessage(R.string.permission_camera_rationale)
                .setPositiveButton(R.string.grant) { _, _ ->
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showPermanentlyDeniedDialog() {
        AlertDialog.Builder(this)
            .setMessage(R.string.permission_camera_denied)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(i)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                provider.unbindAll()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }

                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview
                )
                binding.cameraPreview.implementationMode =
                    PreviewView.ImplementationMode.PERFORMANCE
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to bind camera", t)
                Toast.makeText(this, "Camera unavailable", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- Image loading ---------------------------------------------------------------------

    private fun launchImagePicker() {
        // The Photo Picker is preferred on Android 13+; on older devices it transparently
        // delegates to the legacy "Documents" UI without needing storage permission.
        try {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (_: Throwable) {
            getContentLauncher.launch("image/*")
        }
    }

    private fun loadImage(uri: Uri) {
        cameraExecutor.execute {
            val bitmap = decodeBitmap(uri)
            runOnUiThread {
                if (bitmap == null) {
                    Toast.makeText(this, R.string.image_load_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                binding.overlayView.setBitmap(bitmap, resetTransform = true)
                binding.hintLabel.visibility = View.GONE
                persistLastImage(uri)
            }
        }
    }

    /**
     * Decodes the image with downsampling. We cap the longest edge at ~2048 px to keep memory
     * and per-frame draw cost low even on entry-level devices.
     */
    private fun decodeBitmap(uri: Uri): Bitmap? = try {
        contentResolver.openInputStream(uri).use { input ->
            input ?: return@use null
            val markable = if (input.markSupported()) input else java.io.BufferedInputStream(input)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            markable.mark(MAX_DECODE_HEADER)
            BitmapFactory.decodeStream(markable, null, bounds)
            markable.reset()

            val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_EDGE)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeStream(markable, null, opts)
        }
    } catch (e: Exception) {
        Log.w(TAG, "decodeBitmap failed", e)
        null
    }

    private fun computeInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxEdge || h / 2 >= maxEdge) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample
    }

    // ---- Persistence -----------------------------------------------------------------------

    private fun persistLastImage(uri: Uri) {
        // Take a long-lived URI permission when the source supports it; otherwise we silently
        // continue (the URI may still resolve next launch via the Photo Picker provider).
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Photo Picker URIs grant a one-shot read; nothing to persist.
        }
        prefs.edit().putString(KEY_LAST_IMAGE_URI, uri.toString()).apply()
    }

    private fun restoreLastImage() {
        val raw = prefs.getString(KEY_LAST_IMAGE_URI, null) ?: return
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
        cameraExecutor.execute {
            val bitmap = decodeBitmap(uri) ?: run {
                // Stale reference (revoked permission, deleted file) — clear it.
                prefs.edit().remove(KEY_LAST_IMAGE_URI).apply()
                return@execute
            }
            runOnUiThread {
                binding.overlayView.setBitmap(bitmap, resetTransform = true)
                binding.hintLabel.visibility = View.GONE
            }
        }
    }

    // ---- Fullscreen ------------------------------------------------------------------------

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            binding.topBar.visibility = View.GONE
            binding.bottomPanel.visibility = View.GONE
            binding.btnFullscreen.setIconResource(R.drawable.ic_fullscreen_exit)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            binding.topBar.visibility = View.VISIBLE
            binding.bottomPanel.visibility = View.VISIBLE
            binding.btnFullscreen.setIconResource(R.drawable.ic_fullscreen)
        }
    }

    override fun onBackPressed() {
        if (fullscreen) {
            toggleFullscreen()
            return
        }
        super.onBackPressed()
    }

    // ---- Screenshot capture ----------------------------------------------------------------

    /**
     * Composites the live camera frame (via PreviewView.bitmap) with the overlay so the
     * exported PNG matches what the user sees. Saved into Pictures/AR Tracer via MediaStore
     * (no storage permission needed on Q+).
     */
    private fun captureScreenshot() {
        val cameraBitmap = binding.cameraPreview.bitmap
        if (cameraBitmap == null) {
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
            return
        }
        cameraExecutor.execute {
            try {
                val composite = Bitmap.createBitmap(
                    cameraBitmap.width,
                    cameraBitmap.height,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(composite)
                canvas.drawBitmap(cameraBitmap, 0f, 0f, null)
                if (binding.overlayView.hasBitmap()) {
                    binding.overlayView.drawOnto(canvas, composite.width, composite.height)
                }
                val saved = saveBitmapToGallery(composite)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (saved) R.string.screenshot_saved else R.string.screenshot_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                composite.recycle()
            } catch (t: Throwable) {
                Log.e(TAG, "screenshot failed", t)
                runOnUiThread {
                    Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap): Boolean {
        val filename = "ARTracer_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AR Tracer")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val item = resolver.insert(collection, values) ?: return false
            try {
                resolver.openOutputStream(item).use { os: OutputStream? ->
                    os ?: return false
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)) return false
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(item, values, null, null)
                true
            } catch (e: IOException) {
                Log.w(TAG, "save to MediaStore failed", e)
                resolver.delete(item, null, null)
                false
            }
        } else {
            @Suppress("DEPRECATION")
            val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES
            )
            val outDir = java.io.File(picturesDir, "AR Tracer")
            if (!outDir.exists() && !outDir.mkdirs()) return false
            val file = java.io.File(outDir, filename)
            try {
                file.outputStream().use { os ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)) return false
                }
                // Make it visible to the gallery on legacy devices.
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.insertImage(contentResolver, file.absolutePath, filename, null)
                true
            } catch (e: IOException) {
                Log.w(TAG, "save to file failed", e)
                false
            }
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "ARTracer"
        private const val PREFS_NAME = "ar_tracer_prefs"
        private const val KEY_LAST_IMAGE_URI = "last_image_uri"
        private const val MAX_IMAGE_EDGE = 2048
        private const val MAX_DECODE_HEADER = 1 shl 20 // 1 MB header window for inJustDecodeBounds
    }
}

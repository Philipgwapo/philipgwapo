@file:Suppress("DEPRECATION")

package com.philip.nokiamode

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Low-resolution camera feed for the LCD viewfinder. Uses the legacy camera API because it hands
 * us raw NV21 frames whose first plane is exactly the luminance we dither.
 */
class LcdCamera(private val onFrame: (IntArray, Int, Int) -> Unit) {
    val outW = 120
    val outH = 90
    private val lum = IntArray(outW * outH)
    private var cam: Camera? = null
    private var texture: SurfaceTexture? = null
    private var buffer: ByteArray? = null
    private var rotation = 0
    private var mirror = false
    private var pw = 0
    private var ph = 0
    private var lastFrame = 0L

    val isOpen get() = cam != null

    fun open(back: Boolean): Boolean {
        release()
        val n = Camera.getNumberOfCameras()
        if (n == 0) return false
        val info = Camera.CameraInfo()
        val want = if (back) Camera.CameraInfo.CAMERA_FACING_BACK else Camera.CameraInfo.CAMERA_FACING_FRONT
        var id = (0 until n).firstOrNull { Camera.getCameraInfo(it, info); info.facing == want } ?: 0
        Camera.getCameraInfo(id, info)
        return try {
            val c = Camera.open(id)
            rotation = info.orientation
            mirror = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
            val p = c.parameters
            val preview = p.supportedPreviewSizes.filter { it.width >= 320 }.minByOrNull { it.width * it.height }
                ?: p.supportedPreviewSizes.first()
            p.setPreviewSize(preview.width, preview.height)
            p.previewFormat = ImageFormat.NV21
            val pic = p.supportedPictureSizes.filter { it.width * it.height <= 8_000_000 }.maxByOrNull { it.width * it.height }
                ?: p.supportedPictureSizes.first()
            p.setPictureSize(pic.width, pic.height)
            p.setRotation(rotation)
            if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in p.supportedFocusModes) {
                p.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            }
            c.parameters = p
            pw = preview.width
            ph = preview.height
            val tex = SurfaceTexture(10)
            c.setPreviewTexture(tex)
            texture = tex
            buffer = ByteArray(pw * ph * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8)
            c.setPreviewCallbackWithBuffer { data, camera -> frame(data); camera.addCallbackBuffer(data) }
            c.addCallbackBuffer(buffer)
            c.startPreview()
            cam = c
            true
        } catch (e: Exception) {
            release()
            false
        }
    }

    private fun frame(data: ByteArray) {
        val now = System.currentTimeMillis()
        if (now - lastFrame < 70) return
        lastFrame = now
        val rotated = rotation == 90 || rotation == 270
        val rw = if (rotated) ph else pw
        val rh = if (rotated) pw else ph
        val scale = minOf(rw.toFloat() / outW, rh.toFloat() / outH)
        val ox = (rw - outW * scale) / 2f
        val oy = (rh - outH * scale) / 2f
        for (y in 0 until outH) for (x in 0 until outW) {
            var dx = (ox + (x + 0.5f) * scale).toInt().coerceIn(0, rw - 1)
            val dy = (oy + (y + 0.5f) * scale).toInt().coerceIn(0, rh - 1)
            if (mirror) dx = rw - 1 - dx
            // map the upright display pixel back to the sensor's native orientation
            val sx: Int
            val sy: Int
            when (rotation) {
                90 -> { sx = dy; sy = rw - 1 - dx }
                180 -> { sx = pw - 1 - dx; sy = ph - 1 - dy }
                270 -> { sx = pw - 1 - dy; sy = dx }
                else -> { sx = dx; sy = dy }
            }
            lum[y * outW + x] = data[sy * pw + sx].toInt() and 0xFF
        }
        onFrame(lum, outW, outH)
    }

    fun takePicture(done: (ByteArray?) -> Unit) {
        val c = cam ?: return done(null)
        try {
            c.takePicture(null, null) { data, camera ->
                done(data)
                try {
                    camera.addCallbackBuffer(buffer)
                    camera.startPreview()
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            done(null)
        }
    }

    fun release() {
        cam?.let {
            try {
                it.setPreviewCallbackWithBuffer(null)
                it.stopPreview()
            } catch (e: Exception) {
            }
            it.release()
        }
        cam = null
        texture?.release()
        texture = null
    }
}

/** Photos taken in Nokia Mode live in Pictures/NokiaMode so the normal gallery app sees them too. */
object Photos {
    private const val DIR = "NokiaMode"

    fun save(context: Context, jpeg: ByteArray): Boolean = try {
        val name = "NOKIA_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$DIR")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val cr = context.contentResolver
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
            cr.openOutputStream(uri)?.use { it.write(jpeg) } ?: error("no stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            cr.update(uri, values, null, null)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), DIR)
            dir.mkdirs()
            val f = File(dir, name)
            f.writeBytes(jpeg)
            MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null)
        }
        true
    } catch (e: Exception) {
        false
    }

    fun list(context: Context): List<Uri> {
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val (sel, arg) = if (Build.VERSION.SDK_INT >= 29) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to "${Environment.DIRECTORY_PICTURES}/$DIR%"
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ?" to "%/${Environment.DIRECTORY_PICTURES}/$DIR/%"
        }
        val out = ArrayList<Uri>()
        try {
            context.contentResolver.query(base, arrayOf(MediaStore.Images.Media._ID), sel, arrayOf(arg),
                "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) out.add(ContentUris.withAppendedId(base, c.getLong(0)))
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** Decodes a photo at roughly [maxDim] pixels, upright. */
    fun load(context: Context, uri: Uri, maxDim: Int = 400): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val big = maxOf(info.size.width, info.size.height)
                decoder.setTargetSampleSize(maxOf(1, big / maxDim))
            }
        } else {
            val cr = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / maxDim)
            }
            val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val deg = cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            if (bmp != null && deg != 0f) {
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
            } else {
                bmp
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Decodes a just-taken JPEG small and upright (the legacy camera only tags rotation in EXIF). */
    fun decodeJpeg(jpeg: ByteArray, maxDim: Int = 400): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / maxDim) }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        val deg = when (ExifInterface(jpeg.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (bmp != null && deg != 0f) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true) else bmp
    } catch (e: Exception) {
        null
    }

    fun delete(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.delete(uri, null, null) > 0
    } catch (e: Exception) {
        false
    }
}

private fun storagePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT < 29) arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
    else emptyArray()

class CameraScreen(host: MainActivity, private val forWallpaper: Boolean = false) : Screen(host) {
    override val title = if (forWallpaper) "Wallpaper" else "Camera"
    override val leftSoft = "Options"
    private val viewfinder = PixelBitmapView(host)
    private var frame: Bitmap? = null
    private var error: String? = null
    private var busy = false
    private val camera = LcdCamera { lum, w, h ->
        frame = Dither.toBitmap(lum, w, h, host.fg, host.bg, frame)
        viewfinder.bitmap = frame
    }

    init {
        val bracket = Paint().apply { strokeWidth = host.dp(2.5f) }
        viewfinder.overlay = { c, r ->
            bracket.color = host.bg
            val inset = r.width() * 0.12f
            val len = r.width() * 0.09f
            val l = r.left + inset
            val t = r.top + inset
            val rr = r.right - inset
            val b = r.bottom - inset
            c.drawLine(l, t, l + len, t, bracket); c.drawLine(l, t, l, t + len, bracket)
            c.drawLine(rr, t, rr - len, t, bracket); c.drawLine(rr, t, rr, t + len, bracket)
            c.drawLine(l, b, l + len, b, bracket); c.drawLine(l, b, l, b - len, bracket)
            c.drawLine(rr, b, rr - len, b, bracket); c.drawLine(rr, b, rr, b - len, bracket)
        }
    }

    override fun render(): View = column().apply {
        (viewfinder.parent as? android.view.ViewGroup)?.removeView(viewfinder)
        val msg = error
        if (msg != null) {
            add(text(msg, 16f, Gravity.CENTER, multiline = true).apply { gravity = Gravity.CENTER }, 1f)
            return@apply
        }
        viewfinder.setBackgroundColor(host.fg)
        add(viewfinder, 1f)
        val bar = row().apply {
            gravity = Gravity.CENTER
            setPadding(0, host.dpi(3f), 0, 0)
            addView(icon(Icons.CAMERA), LinearLayout.LayoutParams(host.dpi(18f), host.dpi(15f)).apply { marginEnd = host.dpi(6f) })
            addView(text(if (busy) "Saving..." else "Capture", 15f, bold = true))
        }
        add(bar)
    }

    override fun onShow() {
        val needed = arrayOf(Manifest.permission.CAMERA) + storagePermissions()
        if (needed.any { !host.hasPermission(it) }) {
            error = "Allow camera access\nto take photos"
            host.ask(*needed)
            return
        }
        error = if (camera.isOpen || camera.open(host.prefs.backCamera)) null else "Camera not available"
    }

    override fun onHide() = camera.release()

    private fun capture() {
        if (busy || !camera.isOpen) return
        busy = true
        host.refresh()
        camera.takePicture { data ->
            if (data == null) {
                busy = false
                host.note("Capture failed")
                return@takePicture
            }
            Thread {
                val ok = Photos.save(host, data)
                val wall = if (forWallpaper) Photos.decodeJpeg(data)?.let { Wallpaper.setPhoto(host, it) } == true else false
                host.runOnUiThread {
                    busy = false
                    if (forWallpaper) {
                        if (wall) {
                            host.prefs.wallpaper = "photo"
                            host.close(this)
                            host.note("Wallpaper set")
                        } else {
                            host.note("Failed")
                        }
                    } else if (host.current === this) {
                        host.note(if (ok) "Photo saved" else "Save failed", 900)
                    }
                }
            }.start()
        }
    }

    override fun onKey(k: Key): Boolean {
        when (k) {
            Key.OK, Key.CALL, Key.D5 -> capture()
            Key.SOFT_L -> host.push(SimpleList(host, "Options") {
                listOf(
                    ListScreen.Item("Gallery", Icons.GALLERY, sub = true) { host.replace(GalleryScreen(host)) },
                    ListScreen.Item(if (host.prefs.backCamera) "Use front camera" else "Use back camera", Icons.CAMERA) {
                        host.prefs.backCamera = !host.prefs.backCamera
                        camera.release()
                        host.pop()
                    },
                )
            })
            else -> return false
        }
        return true
    }
}

class GalleryScreen(host: MainActivity, private val forWallpaper: Boolean = false) : Screen(host) {
    override val title = if (forWallpaper) "Pick wallpaper" else "Gallery"
    override val leftSoft get() = if (uris.isEmpty()) "" else if (forWallpaper) "Use" else "Options"
    override val centerSoft get() = if (uris.isEmpty()) "" else "${idx + 1}/${uris.size}"

    private var uris: List<Uri> = emptyList()
    private var idx = 0
    private var photo: Bitmap? = null
    private var loading = 0

    override fun onShow() {
        val needed = storagePermissions()
        if (needed.any { !host.hasPermission(it) }) host.ask(*needed)
        uris = Photos.list(host)
        idx = idx.coerceIn(0, (uris.size - 1).coerceAtLeast(0))
        load()
    }

    private fun load() {
        photo = null
        val uri = uris.getOrNull(idx) ?: return
        val token = ++loading
        Thread {
            val src = Photos.load(host, uri)
            val small = src?.let { Bitmap.createScaledBitmap(it, 160, maxOf(1, it.height * 160 / it.width), true) }
            host.runOnUiThread {
                if (token == loading) {
                    photo = small
                    if (host.current === this) host.refresh()
                }
            }
        }.start()
    }

    override fun render(): View = column(Gravity.CENTER).apply {
        if (uris.isEmpty()) {
            add(icon(Icons.GALLERY), height = host.dpi(40f))
            add(text("No photos yet.\nTake some with\nthe Camera.", 16f, Gravity.CENTER, multiline = true))
            return@apply
        }
        val p = photo
        if (p == null) {
            add(text("Loading...", 16f, Gravity.CENTER))
            return@apply
        }
        val v = PixelBitmapView(host)
        v.bitmap = Dither.photo(p, 128, host.fg, host.bg)
        add(v, 1f)
    }

    private fun setWallpaper(uri: Uri) {
        val bmp = Photos.load(host, uri)
        if (bmp != null && Wallpaper.setPhoto(host, bmp)) {
            host.prefs.wallpaper = "photo"
            if (forWallpaper) host.close(this)
            host.note("Wallpaper set")
        } else {
            host.note("Failed")
        }
    }

    override fun onKey(k: Key): Boolean {
        val n = uris.size
        if (n == 0) return false
        when (k) {
            Key.LEFT, Key.UP -> { idx = (idx - 1 + n) % n; load() }
            Key.RIGHT, Key.DOWN -> { idx = (idx + 1) % n; load() }
            Key.SOFT_L -> {
                val uri = uris[idx]
                if (forWallpaper) {
                    setWallpaper(uri)
                    return true
                }
                host.push(SimpleList(host, "Options") {
                    listOf(
                        ListScreen.Item("Set as wallpaper", Icons.GALLERY) { host.pop(); setWallpaper(uri) },
                        ListScreen.Item("Delete photo", Icons.EXIT) {
                            host.pop()
                            host.note(if (Photos.delete(host, uri)) "Deleted" else "Can't delete")
                        },
                    )
                })
                return true
            }
            else -> return false
        }
        host.refresh()
        return true
    }
}

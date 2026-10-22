package com.supplyroom.app

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/* ===================== Saved settings on the phone ===================== */

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    private fun read(k: String): String = sp.getString(k, "") ?: ""
    private fun write(k: String, v: String) { sp.edit().putString(k, v).apply() }

    var url: String get() = read("url"); set(v) = write("url", v)
    var key: String get() = read("key"); set(v) = write("key", v)
    var org: String get() = read("org"); set(v) = write("org", v)
    var user: String get() = read("user"); set(v) = write("user", v)
    var role: String get() = read("role"); set(v) = write("role", v)
    var lastEmail: String get() = read("lastEmail"); set(v) = write("lastEmail", v)
    var snapshot: String get() = read("snapshot"); set(v) = write("snapshot", v)

    val configured: Boolean get() = url.isNotBlank() && key.isNotBlank()

    var queue: JSONArray
        get() = try { JSONArray(sp.getString("queue", "[]")) } catch (e: JSONException) { JSONArray() }
        set(v) = write("queue", v.toString())

    fun recents(): List<String> = read("recents").split('\n').filter { it.isNotBlank() }
    fun addRecent(name: String) {
        val list = (listOf(name) + recents().filter { !it.equals(name, ignoreCase = true) }).take(12)
        write("recents", list.joinToString("\n"))
    }
}

/* ===================== Server calls ===================== */

/** A "business" error from the server (bad state, not authorized...). Not retried. */
class ApiError(msg: String) : Exception(msg)

object Api {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /** POSTs to the Apps Script web app. Network problems throw IOException. */
    suspend fun call(fn: String, vararg args: Any?): Any? = withContext(Dispatchers.IO) {
        val argArr = JSONArray()
        args.forEach { argArr.put(it ?: JSONObject.NULL) }
        val body = JSONObject()
            .put("key", Prefs.key)
            .put("user", Prefs.user)
            .put("fn", fn)
            .put("args", argArr)
        val req = Request.Builder().url(Prefs.url).post(body.toString().toRequestBody(JSON_TYPE)).build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            val j = try {
                JSONObject(txt)
            } catch (e: JSONException) {
                if (!r.isSuccessful) throw IOException("Server error ${r.code}")
                throw ApiError("Unexpected reply from the server. The admin may need to re-deploy the web app with access set to \"Anyone\".")
            }
            if (!j.optBoolean("ok")) throw ApiError(j.optString("error", "Unknown error"))
            j.opt("data")
        }
    }

    /** Returns (versionTag, downloadUrl) if GitHub has a newer release than this app. */
    suspend fun newerRelease(): Pair<String, String>? = withContext(Dispatchers.IO) {
        val repo = BuildConfig.GITHUB_REPO
        if (repo.isBlank()) return@withContext null
        val req = Request.Builder().url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            val j = JSONObject(r.body?.string().orEmpty())
            val tag = j.optString("tag_name")
            if (compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) return@withContext null
            val assets = j.optJSONArray("assets") ?: JSONArray()
            var url = j.optString("html_url")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
            }
            tag to url
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        val x = a.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }
}

fun friendly(e: Throwable): String = when (e) {
    is IOException -> "No connection to the server. Check your signal and try again."
    is ApiError -> e.message ?: "Error"
    else -> e.message ?: e.javaClass.simpleName
}

/** Try to upload submits that were saved while offline. Returns a message to show, or null. */
suspend fun flushQueue(): String? {
    val q = Prefs.queue
    if (q.length() == 0) return null
    val left = JSONArray()
    var sent = 0
    val problems = mutableListOf<String>()
    for (i in 0 until q.length()) {
        val p = q.getJSONObject(i)
        try {
            Api.call("submit", p); sent++
        } catch (e: IOException) {
            left.put(p)
        } catch (e: Exception) {
            problems.add(friendly(e)) // rejected by the server: don't retry forever
        }
    }
    Prefs.queue = left
    return buildString {
        if (sent > 0) append("Uploaded $sent saved item list(s). ")
        if (problems.isNotEmpty()) append("Could not upload: ${problems.joinToString("; ")}. Tell an admin.")
    }.ifBlank { null }
}

/* ===================== Item data ===================== */

data class Item(val id: String, val name: String, val serialized: Boolean, val barcode: String, val unit: String)
data class UnitRec(val tag: String, val serial: String, val itemId: String, val status: String, val assignedTo: String)

sealed class Hit {
    data class OfUnit(val unit: UnitRec, val item: Item?) : Hit()
    data class OfItem(val item: Item) : Hit()
    data class NotFound(val code: String) : Hit()
}

class Snapshot(val items: List<Item>, val units: List<UnitRec>, val defaultLocation: String, val role: String) {
    fun item(id: String): Item? = items.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun find(code: String): Hit {
        val c = code.trim()
        units.firstOrNull { it.tag.equals(c, ignoreCase = true) }?.let { return Hit.OfUnit(it, item(it.itemId)) }
        units.firstOrNull { it.serial.isNotBlank() && it.serial.equals(c, ignoreCase = true) }?.let { return Hit.OfUnit(it, item(it.itemId)) }
        items.firstOrNull { it.barcode.isNotBlank() && it.barcode.equals(c, ignoreCase = true) }?.let { return Hit.OfItem(it) }
        items.firstOrNull { it.id.equals(c, ignoreCase = true) }?.let { return Hit.OfItem(it) }
        return Hit.NotFound(c)
    }

    companion object {
        fun parse(j: JSONObject): Snapshot {
            val cat = j.optJSONArray("catalog") ?: JSONArray()
            val items = (0 until cat.length()).map {
                val o = cat.getJSONObject(it)
                Item(
                    id = o.optString("ItemID"), name = o.optString("Name"),
                    serialized = o.optString("Tracking").trim().equals("serialized", ignoreCase = true),
                    barcode = o.optString("Barcode"), unit = o.optString("Unit").ifBlank { "ea" },
                )
            }
            val us = j.optJSONArray("units") ?: JSONArray()
            val units = (0 until us.length()).map {
                val o = us.getJSONObject(it)
                UnitRec(o.optString("t"), o.optString("s"), o.optString("i"), o.optString("st"), o.optString("a"))
            }
            val role = j.optJSONObject("me")?.optString("role") ?: "user"
            return Snapshot(items, units, j.optString("defaultLocation"), role)
        }
    }
}

suspend fun loadSnapshot(): Snapshot {
    val d = Api.call("bootstrap") as JSONObject
    Prefs.snapshot = d.toString()
    val s = Snapshot.parse(d)
    Prefs.role = s.role
    return s
}

fun cachedSnapshot(): Snapshot? = try {
    Prefs.snapshot.takeIf { it.isNotBlank() }?.let { Snapshot.parse(JSONObject(it)) }
} catch (e: Exception) { null }

/* ===================== Scanner & feedback ===================== */

/** Opens Google's full-screen scanner. Returns the code, or null if the person backed out. */
suspend fun scanCode(ctx: Context): String? = suspendCancellableCoroutine { cont ->
    val opts = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
        .enableAutoZoom()
        .build()
    GmsBarcodeScanning.getClient(ctx, opts).startScan()
        .addOnSuccessListener { b -> if (cont.isActive) cont.resume(b.rawValue?.trim()) }
        .addOnCanceledListener { if (cont.isActive) cont.resume(null) }
        .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
}

private val tone: ToneGenerator? by lazy {
    try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) } catch (e: Exception) { null }
}

/** Beep + buzz: one short for good, a double for a problem. */
fun feedback(ctx: Context, ok: Boolean) {
    try { tone?.startTone(if (ok) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_NACK, 150) } catch (_: Exception) {}
    try {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val effect = if (ok) VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
        else VibrationEffect.createWaveform(longArrayOf(0, 90, 80, 90), -1)
        v.vibrate(effect)
    } catch (_: Exception) {}
}

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

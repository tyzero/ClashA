package com.github.cgg.clasha.utils

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.preference.Preference
import com.blankj.utilcode.util.LogUtils
import com.crashlytics.android.Crashlytics
import com.github.cgg.clasha.App
import com.github.cgg.clasha.bg.BaseService
import com.github.cgg.clasha.data.ConfigManager
import com.github.cgg.clasha.data.DataStore
import com.google.gson.*
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.lang.reflect.Type
import java.net.InetAddress
import java.net.Socket
import java.net.URLConnection
import java.util.*
import java.util.concurrent.TimeUnit


val Throwable.readableMessage get() = localizedMessage ?: javaClass.name

private val parseNumericAddress by lazy @SuppressLint("DiscouragedPrivateApi") {
    InetAddress::class.java.getDeclaredMethod("parseNumericAddress", String::class.java).apply {
        isAccessible = true
    }
}

/**
 * A slightly more performant variant of InetAddress.parseNumericAddress.
 *
 * Bug: https://issuetracker.google.com/issues/123456213
 */
fun String?.parseNumericAddress(): InetAddress? = Os.inet_pton(OsConstants.AF_INET, this)
    ?: Os.inet_pton(OsConstants.AF_INET6, this)?.let { parseNumericAddress.invoke(null, this) as InetAddress }

fun parsePort(str: String?, default: Int, min: Int = 1025): Int {
    val value = str?.toIntOrNull() ?: default
    return if (value < min || value > 65535) default else value
}

fun broadcastReceiver(callback: (Context, Intent) -> Unit): BroadcastReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = callback(context, intent)
}

/**
 * Wrapper for kotlin.concurrent.thread that tracks uncaught exceptions.
 */
fun thread(
    name: String? = null, start: Boolean = true, isDaemon: Boolean = false,
    contextClassLoader: ClassLoader? = null, priority: Int = -1, block: () -> Unit
): Thread {
    val thread = kotlin.concurrent.thread(false, isDaemon, contextClassLoader, name, priority, block)
    thread.setUncaughtExceptionHandler { _, t -> printLog(t) }
    if (start) thread.start()
    return thread
}

val URLConnection.responseLength: Long
    get() = if (Build.VERSION.SDK_INT >= 24) contentLengthLong else contentLength.toLong()

fun ContentResolver.openBitmap(uri: Uri) =
    if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(this, uri))
    else BitmapFactory.decodeStream(openInputStream(uri))

val PackageInfo.signaturesCompat
    get() =
        if (Build.VERSION.SDK_INT >= 28) signingInfo.apkContentsSigners else @Suppress("DEPRECATION") signatures

/**
 * Based on: https://stackoverflow.com/a/26348729/2245107
 */
fun Resources.Theme.resolveResourceId(@AttrRes resId: Int): Int {
    val typedValue = TypedValue()
    if (!resolveAttribute(resId, typedValue, true)) throw Resources.NotFoundException()
    return typedValue.resourceId
}

val Intent.datas get() = listOfNotNull(data) + (clipData?.asIterable()?.mapNotNull { it.uri } ?: emptyList())

fun printLog(t: Throwable) {
    t.printStackTrace()
}

fun getSelectProxys() {
    App.app.mAppExecutors.networkIO.execute {
        try {
            val mOkHttpClient = OkHttpClient.Builder()
                .connectTimeout(400, TimeUnit.MILLISECONDS)
                .readTimeout(1500, TimeUnit.MILLISECONDS)
                .build()

            val host = "http://127.0.0.1:${DataStore.portApi}"
            val request = Request.Builder().url("$host/proxies").get().build()
            val call = mOkHttpClient.newCall(request)
            val response = call.execute()
            if (response.isSuccessful) {
                val result = response.body()?.string()
                val resultObj = JSONObject(result).optJSONObject("proxies")
                var endObj = JSONObject()
                //遍历
                val iterator = resultObj.keys().iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    val iterm = resultObj.get(key) as JSONObject
                    if (iterm.has("type")) {
                        val type = iterm.optString("type")
                        if ("Selector".equals(type)) {
                            iterm.remove("all")
                            iterm.remove("history")
                            endObj.putOpt(key, iterm)
                        }
                    }
                }
                LogUtils.iTag("proxies", "筛选后的数据 ")
                LogUtils.json(endObj.toString())

                var profile = App.app.currentProfileConfig
                profile?.selector = endObj.toString()
                ConfigManager.updateProfileConfig(profile!!)
            } else {
            }
        } catch (e: Exception) {
            LogUtils.e(e)
        }
    }
}

fun setSelectorProxy(state: BaseService.State) = when {
    state == BaseService.State.Connected -> {
        App.app.mAppExecutors.networkIO.execute {
            var tries = 0
            while (true) try {
                Thread.sleep(50L shl tries)

                val mOkHttpClient = OkHttpClient.Builder()
                    .connectTimeout(100, TimeUnit.MILLISECONDS)
                    .writeTimeout(400, TimeUnit.MILLISECONDS)
                    .build()
                val host = "http://127.0.0.1:${DataStore.portApi}"

                val profile = App.app.currentProfileConfig
                if (!TextUtils.isEmpty(profile?.selector)) {
                    val jsonObj = JSONObject(profile?.selector)
                    val iterator = jsonObj.keys().iterator()
                    while (iterator.hasNext()) {
                        val proxyName = iterator.next()
                        val iterm = jsonObj.optJSONObject(proxyName)
                        val nodeName = iterm.optString("now")
                        val nodeNameJson = JSONObject().putOpt("name", nodeName)
                        putRequest(mOkHttpClient, host, proxyName, nodeNameJson.toString())
                    }
                }

                val request = Request.Builder().url("$host/proxies").get().build()
                val call = mOkHttpClient.newCall(request)
                val response = call.execute()
                if ((response.isSuccessful)) {
                    LogUtils.d("set selector Proxy successful")
                    return@execute
                }
                LogUtils.i("maybe repeat work")
            } catch (e: Exception) {
                LogUtils.e(e)
                LogUtils.d("set selector Proxy fail, fail count:$tries")
                if (tries > 5) return@execute
                tries += 1
            }
        }
    }
    else -> {
    }
}

private inline fun putRequest(client: OkHttpClient, host: String, proxyName: String, nodeNameJSON: String) {
    val JSON = MediaType.parse("application/json; charset=utf-8")
    val body = RequestBody.create(JSON, nodeNameJSON)
    val request = Request.Builder().url("$host/proxies/$proxyName").put(body).build()
    client.newCall(request).execute()
}

fun isPortUsing(host: String, port: Int): Boolean {

    var flag = false
    val address = InetAddress.getByName(host)
    try {
        val socket = Socket(address, port)
        flag = true
        socket.close()
    } catch (e: Exception) {
        LogUtils.e(e)
    }
    return flag
}

fun loadPortFromConfig(value: Any?) {
    try {
        value as Map<String, Objects>

        if (value.containsKey("socks-port")) {
            val port = value.get("socks-port") as Int
            DataStore.portProxy = port
        }

        if (value.containsKey("external-controller")) {
            val hostAndPort = value.get("external-controller") as String
            val port = hostAndPort.split(":")[1]
            DataStore.portApi = port.toInt()
        }

        if (value.containsKey("port")) {
            val i = value.get("port") as Int
            DataStore.portHttpProxy = i
        }


    } catch (e: Exception) {
        //xiaomi xposed crash
        Crashlytics.log(Log.ERROR, App.TAG, e.localizedMessage)
    }
}

fun addTempDNS(): JSONObject {
    return JSONObject("{\"nameserver\":[\"1.2.4.8\",\"114.114.114.114\",\"223.5.5.5\",\"tls://dns.rubyfish.cn:853\"],\"enhanced-mode\":\"redir-host\",\"fallback\":[\"tls://dns.rubyfish.cn:853\",\"tls://dns.google\"],\"enable\":true,\"ipv6\":false,\"listen\":\"0.0.0.0:5450\"}")
}

fun toMap(jsonObject: JSONObject): Map<String, Objects> {
    val result = HashMap<String, Objects>()
    val iterator = jsonObject.keys()
    var key: String? = null
    var value: Objects? = null
    while (iterator.hasNext()) {
        key = iterator.next()
        try {
            value = jsonObject.get(key) as Objects?
        } catch (e: JSONException) {
            LogUtils.e(e)
        }
        result[key] = value!!
    }
    return result
}

fun getGson(): Gson {
    return GsonBuilder().registerTypeAdapter(JSONObject::class.java, JSONObjectAdapter())
        .registerTypeAdapter(JSONArray::class.java, JSONArrayAdapter()).create()
}

open class JSONObjectAdapter : JsonSerializer<JSONObject>, JsonDeserializer<JSONObject> {
    override fun serialize(src: JSONObject?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement? {
        if (src == null) {
            return null
        }

        var jsonObject = JsonObject()
        val keys = src.keys()
        while (keys.hasNext()) {
            var key = keys.next()
            var value = src.opt(key)
            var jsonElement = context?.serialize(value, value::class.java)
            jsonObject.add(key, jsonElement)
        }

        return jsonObject
    }

    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): JSONObject? {
        if (json == null) {
            return null
        }
        try {
            return JSONObject(json.toString())
        } catch (e: JSONException) {
            throw  JsonParseException(e)
        }
    }

}

class JSONArrayAdapter : JsonSerializer<JSONArray>, JsonDeserializer<JSONArray> {
    override fun serialize(src: JSONArray?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement? {
        if (src == null) {
            return null
        }

        var jsonArray = JsonArray()
        for (i in 0 until src.length()) {
            var obj = src.opt(i)
            var jsonElement = context?.serialize(obj, obj::class.java)
            jsonArray.add(jsonElement)
        }

        return jsonArray
    }

    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): JSONArray? {
        if (json == null) {
            return null
        }
        try {
            return JSONArray(json.toString())
        } catch (e: JSONException) {
            throw  JsonParseException(e)
        }
    }

}

fun Preference.remove() = parent!!.removePreference(this)

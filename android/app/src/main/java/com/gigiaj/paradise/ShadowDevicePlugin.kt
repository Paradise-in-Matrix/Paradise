package com.gigiaj.paradise

import android.content.SharedPreferences
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.PusherIdentifiers
import org.matrix.rustcomponents.sdk.PusherKind
import org.matrix.rustcomponents.sdk.HttpPusherData
import org.matrix.rustcomponents.sdk.PushFormat
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.Client


@CapacitorPlugin(name = "ShadowDevice")
class ShadowDevicePlugin : Plugin() {

    private suspend fun buildMatrixClient(homeserverUrl: String, ssStr: String?): Client {
        val dataPath = context.filesDir.absolutePath + "/matrix-shadow/data"
        val cachePath = context.filesDir.absolutePath + "/matrix-shadow/cache"

        val ssBuilder = when (ssStr) {
            "NATIVE" -> SlidingSyncVersionBuilder.NATIVE
            "NONE" -> SlidingSyncVersionBuilder.NONE
            else -> SlidingSyncVersionBuilder.DISCOVER_NATIVE
        }

        return ClientBuilder()
            .homeserverUrl(homeserverUrl)
            .sessionPaths(dataPath, cachePath)
            .systemIsMemoryConstrained()
            .slidingSyncVersionBuilder(ssBuilder) 
            .build()
    }

    private suspend fun restoreShadowSession(client: Client, sharedPrefs: SharedPreferences) {
        val accessToken = sharedPrefs.getString("access_token", null)
        val userId = sharedPrefs.getString("user_id", null)
        val deviceId = sharedPrefs.getString("device_id", null)
        val homeserverUrl = sharedPrefs.getString("homeserver_url", null)
        val slidingSyncStr = sharedPrefs.getString("sliding_sync_version", "NONE")

        if (accessToken != null && userId != null && deviceId != null && homeserverUrl != null) {
            
            val ssVersion = when (slidingSyncStr) {
                "NATIVE" -> SlidingSyncVersion.NATIVE
                else -> SlidingSyncVersion.NONE
            }

            val session = Session(
                accessToken = accessToken,
                refreshToken = null,
                userId = userId,
                deviceId = deviceId,
                homeserverUrl = homeserverUrl,
                oidcData = null,
                slidingSyncVersion = ssVersion
            )
            client.restoreSession(session)
        } else {
            throw Exception("Missing session credentials in SharedPreferences.")
        }
    }

    @PluginMethod
    fun createSleepyShadow(call: PluginCall) {
        val homeserver = call.getString("homeserverUrl") ?: return call.reject("Missing homeserverUrl")
        val username = call.getString("username") ?: return call.reject("Missing username")
        val password = call.getString("password") ?: return call.reject("Missing password")

        val sharedPrefs = context.getSharedPreferences("ParadiseShadow", android.content.Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("homeserver_url", homeserver).apply()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = buildMatrixClient(homeserver, null)
                client.login(username, password, "Paradise Background Sync", null)
                
                val session = client.session()
                if (session != null) {
                    sharedPrefs.edit()
                        .putString("access_token", session.accessToken)
                        .putString("user_id", session.userId)
                        .putString("device_id", session.deviceId)
                        .putString("sliding_sync_version", session.slidingSyncVersion.name)
                        .apply()
                }
                
                val ret = com.getcapacitor.JSObject()
                ret.put("status", "sleepy_shadow_created")
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Failed to create Sleepy Shadow", e)
            }
        }
    }

    @PluginMethod
    fun activateShadow(call: PluginCall) {
        val fcmToken = call.getString("pushToken") ?: return call.reject("Missing pushToken")
        val pushUrl = call.getString("pushUrl") ?: return call.reject("Missing pushUrl")
        
        val sharedPrefs = context.getSharedPreferences("ParadiseShadow", android.content.Context.MODE_PRIVATE)
        val homeserver = sharedPrefs.getString("homeserver_url", null) ?: return call.reject("No homeserver saved")
        val ssStr = sharedPrefs.getString("sliding_sync_version", "NONE")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = buildMatrixClient(homeserver, ssStr)
                restoreShadowSession(client, sharedPrefs)
                
                val identifiers = PusherIdentifiers(
                    pushkey = fcmToken,
                    appId = "moi.paradise.android"
                )

                val httpData = HttpPusherData(
                    url = pushUrl,
                    format = PushFormat.EVENT_ID_ONLY,
                    defaultPayload = null
                )

                client.setPusher(
                    identifiers = identifiers,
                    kind = PusherKind.Http(httpData),
                    appDisplayName = "Paradise",
                    deviceDisplayName = "Android Device",
                    profileTag = "primary",
                    lang = "en"
                )

                val ret = com.getcapacitor.JSObject()
                ret.put("status", "shadow_activated")
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Failed to activate Shadow Device", e)
            }
        }
    }

    @PluginMethod
    fun deactivateShadow(call: PluginCall) {
        val fcmToken = call.getString("pushToken") ?: return call.reject("Missing pushToken")
        val sharedPrefs = context.getSharedPreferences("ParadiseShadow", android.content.Context.MODE_PRIVATE)
        val homeserver = sharedPrefs.getString("homeserver_url", null) ?: return call.reject("No homeserver saved")
        val ssStr = sharedPrefs.getString("sliding_sync_version", "NONE")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = buildMatrixClient(homeserver, ssStr)
                restoreShadowSession(client, sharedPrefs)
                
                val identifiers = PusherIdentifiers(
                    pushkey = fcmToken,
                    appId = "moi.paradise.android"
                )
                
                client.deletePusher(identifiers)
                
                val ret = com.getcapacitor.JSObject()
                ret.put("status", "shadow_deactivated")
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Failed to deactivate Shadow Device", e)
            }
        }
    }
}

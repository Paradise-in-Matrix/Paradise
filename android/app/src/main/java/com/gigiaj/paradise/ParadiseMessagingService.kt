package com.gigiaj.paradise

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder 
import org.matrix.rustcomponents.sdk.NotificationProcessSetup 
import org.matrix.rustcomponents.sdk.NotificationStatus
import org.matrix.rustcomponents.sdk.NotificationEvent
import org.matrix.rustcomponents.sdk.TimelineEventContent
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MessageLikeEventContent


class ParadiseMessagingService : FirebaseMessagingService() {

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

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val roomId = data["room_id"]
        val eventId = data["event_id"]

        val sharedPrefs = applicationContext.getSharedPreferences("ParadiseShadow", Context.MODE_PRIVATE)
        val homeserverUrl = sharedPrefs.getString("homeserver_url", null)
        val slidingSyncStr = sharedPrefs.getString("sliding_sync_version", "NONE")

        if (homeserverUrl == null || roomId == null || eventId == null) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dataPath = applicationContext.filesDir.absolutePath + "/matrix-shadow/data"
                val cachePath = applicationContext.filesDir.absolutePath + "/matrix-shadow/cache"

                val ssBuilder = when (slidingSyncStr) {
                    "NATIVE" -> SlidingSyncVersionBuilder.NATIVE
                    "NONE" -> SlidingSyncVersionBuilder.NONE
                    else -> SlidingSyncVersionBuilder.DISCOVER_NATIVE
                }

                val client = ClientBuilder()
                    .homeserverUrl(homeserverUrl)
                    .sessionPaths(dataPath, cachePath)
                    .systemIsMemoryConstrained()
                    .slidingSyncVersionBuilder(ssBuilder)
                    .build()

                restoreShadowSession(client, sharedPrefs)

                val pushClient = client.notificationClient(NotificationProcessSetup.MultipleProcesses)
                val status = pushClient.getNotification(roomId, eventId)

                when (status) {
                    is NotificationStatus.Event -> {
                        val item = status.item
                        val senderName = item.senderInfo.displayName ?: "Unknown Sender"
                        val roomName = item.roomInfo.displayName ?: "Unknown Room"

                        val messageBody = when (val notifEvent = item.event) {
                            is NotificationEvent.Timeline -> {
        
                when (val eventContent = notifEvent.event.content()) {
                    is TimelineEventContent.MessageLike -> {
                
                  when (val msgLikeContent = eventContent.content) {
                         is org.matrix.rustcomponents.sdk.MessageLikeEventContent.RoomMessage -> {
                          when (val msgType = msgLikeContent.messageType) {
                            is MessageType.Text -> msgType.content.body
                            is MessageType.Image -> "📸 Sent an image"
                            is MessageType.Video -> "🎥 Sent a video"
                            is MessageType.Audio -> "🎵 Sent an audio clip"
                            is MessageType.File -> "📁 Sent a file"
                            is MessageType.Emote -> "* $senderName ${msgType.content.body}"
                            is MessageType.Notice -> msgType.content.body
                            else -> "Sent a message"
                        }

                    }
                    is MessageLikeEventContent.ReactionContent -> "Reacted to a message"
                    is MessageLikeEventContent.Sticker -> "Sent a sticker"
                    is MessageLikeEventContent.CallInvite -> "📞 Incoming call"
                    else -> "New activity in $roomName"
                }
            }
            is TimelineEventContent.State -> "Room settings changed"
        }
    }
    is NotificationEvent.Invite -> {
        "Invited you to the room"
    }
    }









                        showNotification(roomId, senderName, messageBody)
                    }
                    is NotificationStatus.EventNotFound -> {
                        showNotification(roomId, "New Message", "Open Paradise to view.")
                    }
                    is NotificationStatus.EventFilteredOut -> {
                        Log.d("ParadisePush", "Event filtered out by push rules. Ignoring.")
                    }
                    is NotificationStatus.EventRedacted -> {
                        Log.d("ParadisePush", "Event was redacted. Ignoring.")
                    }
                }

            } catch (e: Exception) {
                Log.e("ParadisePush", "Decryption failed", e)
                showNotification(roomId, "New Message", "Open Paradise to view.")
            }
        }
    }

    private fun showNotification(roomId: String, title: String, body: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "paradise_messages"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        val notificationId = roomId.hashCode()

        notificationManager.notify(notificationId, notificationBuilder.build())
    }
}

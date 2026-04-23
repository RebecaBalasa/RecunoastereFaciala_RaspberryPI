package com.example.facerecognitionmobileapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val CHANNEL_ID   = "face_detection_channel"
        private const val CHANNEL_NAME = "Face Detection Alerts"
        private const val TAG          = "FCM"
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(TAG, "Mesaj primit de la: ${remoteMessage.from}")
        Log.d(TAG, "Data payload: ${remoteMessage.data}")

        val title       = remoteMessage.data["title"]     ?: remoteMessage.notification?.title ?: "Alerta Securitate"
        val body        = remoteMessage.data["body"]       ?: remoteMessage.notification?.body  ?: "Activitate detectata!"
        val httpUrl     = remoteMessage.data["http_url"]
        val imagePath   = remoteMessage.data["image_url"]
        val isEmergency = remoteMessage.data["emergency"] == "true"

        runBlocking {
            val bitmap = when {

                !httpUrl.isNullOrBlank() -> {
                    Log.d(TAG, "Descarc poza din http_url: $httpUrl")
                    downloadBitmap(httpUrl)
                }
                // 2. Fallback: rezolva URL din Firebase Storage SDK dupa cale relativa
                !imagePath.isNullOrBlank() -> {
                    Log.d(TAG, "Rezolv URL din Storage path: $imagePath")
                    resolveAndDownload(imagePath)
                }
                else -> null
            }
            showNotification(title, body, bitmap, isEmergency)
        }
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "Token FCM nou: $token")
    }

    // metoda de descarcare a imaginii daca nu avem url direct
    private suspend fun resolveAndDownload(path: String): Bitmap? {
        return try {
            val ref = when {
                path.startsWith("gs://") ->
                    FirebaseStorage.getInstance().getReferenceFromUrl(path)
                else ->
                    FirebaseStorage.getInstance().reference.child(path)
            }
            val uri = ref.downloadUrl.await()
            Log.d(TAG, "URL rezolvat din Storage: $uri")
            downloadBitmap(uri.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Eroare la rezolvarea URL-ului din Storage", e)
            null
        }
    }

    // metoda de descarcare a imaginii din url
    private suspend fun downloadBitmap(url: String): Bitmap? {
        return try {
            val loader  = ImageLoader(this)
            val request = ImageRequest.Builder(this)
                .data(url)
                .allowHardware(false) // necesar pentru NotificationCompat
                .build()

            val result = loader.execute(request)
            if (result is SuccessResult) {
                (result.drawable as? BitmapDrawable)?.bitmap
            } else {
                Log.w(TAG, "Download bitmap esuat pentru: $url")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exceptie la downloadBitmap: ${e.message}")
            null
        }
    }

    // Afisarea notificarilor
    private fun showNotification(
        title:       String,
        body:        String,
        bitmap:      Bitmap?,
        isEmergency: Boolean
    ) {
        // daca e apasata notificarea, se va sterge din bara de notificari
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        // construirea notificarii in functie de prioritate data de variabila isEmergency
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (isEmergency) android.R.drawable.ic_dialog_alert
                else             android.R.drawable.ic_dialog_info
            )
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSound(soundUri)
            .setPriority(
                if (isEmergency) NotificationCompat.PRIORITY_MAX
                else             NotificationCompat.PRIORITY_DEFAULT
            )
            .setCategory(
                if (isEmergency) NotificationCompat.CATEGORY_ALARM
                else             NotificationCompat.CATEGORY_MESSAGE
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)

        // Daca avem bitmap trimitem notificare cu poza, in caz contrar apare doar cu text
        if (bitmap != null) {
            builder
                .setLargeIcon(bitmap)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap)
                        .bigLargeIcon(null as Bitmap?) // ascunde icon mare cand e extinsa
                )
            Log.d(TAG, "Notificare cu poza afisata.")
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
            Log.d(TAG, "Notificare fara poza afisata.")
        }
        // Serviciul de notificare
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                if (isEmergency) NotificationManager.IMPORTANCE_HIGH
                else             NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificari detectie faciala"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Transmiterea notificarii prin sistemul de notificari
        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
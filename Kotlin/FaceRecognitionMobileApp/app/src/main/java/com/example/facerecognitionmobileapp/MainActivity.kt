package com.example.facerecognitionmobileapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.facerecognitionmobileapp.ui.theme.FaceRecognitionMobileAppTheme
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging

@Keep
@IgnoreExtraProperties
data class DetectionEvent(
    val id: String = "",
    val timestamp: Long = 0,
    val status: String = ""
)

class MainActivity : ComponentActivity() {
    private lateinit var database: DatabaseReference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        database = FirebaseDatabase.getInstance(
            "https://facerecognition-41bc6-default-rtdb.europe-west1.firebasedatabase.app/"
        ).reference.child("detections")

        // ✅ Abonare la topic
        FirebaseMessaging.getInstance().subscribeToTopic("alerts")
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d("FCM", "✅ Subscribed to alerts topic")
                } else {
                    Log.e("FCM", "❌ Subscription failed: ${task.exception?.message}")
                }
            }

        // ✅ Afișează tokenul FCM în Logcat pentru testare manuală
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    Log.d("FCM_TOKEN", "📱 Token: $token")
                } else {
                    Log.e("FCM_TOKEN", "❌ Token fetch failed: ${task.exception?.message}")
                }
            }

        setContent {
            FaceRecognitionMobileAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NotificationPermissionHandler()
                    DetectionList(database)
                }
            }
        }
    }
}

@Composable
fun NotificationPermissionHandler() {
    val context = LocalContext.current

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                Toast.makeText(context, "✅ Notificările sunt activate!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    context,
                    "⚠️ Notificările sunt dezactivate. Activează-le din Setări > Aplicații.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        LaunchedEffect(Unit) {
            val permissionStatus = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectionList(database: DatabaseReference) {
    var detections by remember { mutableStateOf(listOf<DetectionEvent>()) }
    var isLoading by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Log.d("FirebaseData", "Data changed: ${snapshot.childrenCount} items")
                val list = mutableListOf<DetectionEvent>()
                snapshot.children.forEach { child ->
                    try {
                        val event = child.getValue(DetectionEvent::class.java)
                        if (event != null) {
                            list.add(event.copy(id = child.key ?: ""))
                        }
                    } catch (e: Exception) {
                        Log.e("FirebaseData", "Error parsing item", e)
                    }
                }
                detections = list.sortedByDescending { it.timestamp }
                isLoading = false
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("FirebaseData", "Database error: ${error.message}")
                isLoading = false
            }
        }
        database.addValueEventListener(listener)
        onDispose { database.removeEventListener(listener) }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Face Detection Alerts") })
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            detections.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No data.")
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    items(detections, key = { it.id }) { detection ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Status: ${detection.status}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Time: ${java.util.Date(detection.timestamp)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
package com.example.facerecognitionmobileapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.SubcomposeAsyncImage
import com.example.facerecognitionmobileapp.ui.theme.FaceRecognitionMobileAppTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.*
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import java.text.SimpleDateFormat
import java.util.*

@Keep
data class User(
    val uid:   String = "",
    val email: String = "",
    val name:  String = ""
)

@Keep
data class DetectionEvent(
    val id:        String  = "",
    val timestamp: Long    = 0,
    val status:    String  = "",
    val image_url: String? = null
)


class MainActivity : ComponentActivity() {
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        auth = Firebase.auth

        val rootRef = FirebaseDatabase.getInstance(
            "https://facerecognition-41bc6-default-rtdb.europe-west1.firebasedatabase.app/"
        ).reference

        FirebaseMessaging.getInstance().subscribeToTopic("alerts")

        setContent {
            FaceRecognitionMobileAppTheme {
                var currentUser by remember { mutableStateOf(auth.currentUser) }
                var userName    by remember { mutableStateOf("Utilizator") }

                LaunchedEffect(currentUser) {
                    currentUser?.let { user ->
                        rootRef.child("users").child(user.uid).child("name")
                            .get()
                            .addOnSuccessListener {
                                userName = it.value?.toString() ?: "Utilizator"
                            }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = MaterialTheme.colorScheme.background
                ) {
                    // daca nu sunt logat ma duce pe pagina de login/register
                    if (currentUser == null) {
                        AuthScreen(rootRef, onAuthSuccess = { currentUser = auth.currentUser })
                    } else {
                        // daca sunt logat ma duce pe pagina principala
                        MainContent(
                            userName = userName,
                            database = rootRef.child("detections"),
                            onLogout = { auth.signOut(); currentUser = null }
                        )
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(userName: String, database: DatabaseReference, onLogout: () -> Unit) {
    var selectedImageUrl by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text       = "Monitorizare",
                            style      = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text  = "Salut, $userName!",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Logout")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            NotificationPermissionHandler()
            DetectionList(database) { url -> selectedImageUrl = url }
        }
    }
    // daca se da click pe poza din lista, se va deschide in noua pagina
    selectedImageUrl?.let { url ->
        FullScreenImageDialog(url) { selectedImageUrl = null }
    }
}


@Composable
fun DetectionList(database: DatabaseReference, onImageClick: (String) -> Unit) {
    var detections by remember { mutableStateOf(listOf<DetectionEvent>()) }
    var isLoading  by remember { mutableStateOf(true) }

    val listState = rememberLazyListState()

    // face ca pozele deja accesate sa nu se mai incarce din nou, apar instant
    val seenIds   = remember { mutableSetOf<String>() }

    // realizeaza conexiune la baza de date si preia toate evenimentele de detectie
    DisposableEffect(Unit) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<DetectionEvent>()
                snapshot.children.forEach { child ->
                    try {
                        child.getValue(DetectionEvent::class.java)
                            ?.let { list.add(it.copy(id = child.key ?: "")) }
                    } catch (e: Exception) {
                        Log.e("Firebase", "Eroare parsare: ${e.message}")
                    }
                }

                // ordoneaza conexiunile descrescator dupa timestamp
                detections = list.sortedByDescending { it.timestamp }
                isLoading  = false
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Firebase", "DB error: ${error.message}")
                isLoading = false
            }
        }

        // daca apar modificari la evenimente in firebase, aplicatia va primi noile date
        database.addValueEventListener(listener)
        onDispose { database.removeEventListener(listener) }
    }

    // Face scroll la primul card daca apare activitate noua
    LaunchedEffect(detections.firstOrNull()?.id) {
        if (detections.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (detections.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nicio detectie inca.", color = Color.Gray)
        }
    } else {
        LazyColumn(
            state               = listState,
            modifier            = Modifier.fillMaxSize(),
            contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(detections, key = { it.id }) {
                // daca poza/card-ul n-a fost niciodata accesat, dupa accesare imaginea/starea
                // se va salva si daca viitoare cand v-a mai fi accesat il va incarca rapid
                event ->
                val isNew = !seenIds.contains(event.id)
                LaunchedEffect(event.id) { seenIds.add(event.id) }

                // animatia care apare cand se incarca o noua detectie, adica cum apare un card
                AnimatedVisibility(
                    visible = true,
                    enter   = if (isNew)
                        fadeIn(animationSpec = tween(durationMillis = 400)) +
                                slideInVertically(
                                    initialOffsetY = { -40 },
                                    animationSpec  = tween(
                                        durationMillis = 400,
                                        easing         = EaseOutCubic
                                    )
                                )
                    else
                        EnterTransition.None
                ) {
                    DetectionCard(event, onImageClick)
                }
            }
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}


@Composable
fun DetectionCard(detection: DetectionEvent, onImageClick: (String) -> Unit) {
    var isExpanded   by remember { mutableStateOf(false) }
    // retine link-ul imaginii dupa ce a fost descarcat
    var resolvedUrl  by remember { mutableStateOf<String?>(null) }
    // daca este true, apare o iconita de incarcare pana se descarca imaginea
    var isResolving  by remember { mutableStateOf(false) }
    // daca adresa imaginii este gresita / nu exista, afiseaza eroare
    var storageError by remember { mutableStateOf<String?>(null) }

    // transforma timestamp-ul in format dd : mm : yyyy, hh : mm : ss
    val sdf      = remember { SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()) }
    // verifica daca e intrus sau nu
    val isIntrus = detection.status.contains("NEAUTORIZATA", ignoreCase = true)

    // Ruleaza doar daca extindem cardul sau se schimba adresa imaginii
    LaunchedEffect(detection.image_url, isExpanded) {
        // previne descarcarea imaginii in mod repetat, daca imaginea a fost deja descarcata
        if (!isExpanded || detection.image_url.isNullOrBlank() || resolvedUrl != null) return@LaunchedEffect

        // extragere url
        val rawUrl   = detection.image_url.trim()
        isResolving  = true
        storageError = null


        // in resolvedURL salvam url-ul care contine http
        if (rawUrl.startsWith("http")) {
            resolvedUrl = rawUrl
            isResolving = false
            return@LaunchedEffect
        }

        // in storageRef salvam o referinta a imaginii care se afla intr-un folder in firebase
        val storageRef = try {
            when {
                rawUrl.startsWith("gs://") ->
                    FirebaseStorage.getInstance().getReferenceFromUrl(rawUrl)
                else ->
                    // cautare in subfolder
                    FirebaseStorage.getInstance().reference.child(rawUrl)
            }
        } catch (e: Exception) {
            isResolving  = false
            storageError = "Referinta invalida: ${e.message}"
            Log.e("DetectionCard", "Referinta Storage invalida", e)
            return@LaunchedEffect
        }

        // se creaza un link temporar de http care contine un cod unic de acces
        storageRef.downloadUrl
            .addOnSuccessListener { uri ->
                resolvedUrl = uri.toString()
                isResolving = false
                Log.d("DetectionCard", "URL obtinut: $resolvedUrl")
            }
            .addOnFailureListener { e ->
                isResolving  = false
                storageError = e.message ?: "Eroare necunoscuta"
                Log.e("DetectionCard", "Eroare download URL", e)
            }
    }

    val cardColor = if (isIntrus)
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
    else
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier
                .clickable { isExpanded = !isExpanded }
                .animateContentSize()
                .padding(16.dp)
        ) {
            // ── Header ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text       = detection.status,
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        color      = if (isIntrus) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text  = sdf.format(Date(detection.timestamp)),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                Text(
                    text  = if (isExpanded) "Inchide ▲" else "Vezi Poza ▼",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // ── Continut expandat ──
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))

                when {
                    // ce se afiseaza cand se incarca
                    isResolving -> {
                        Box(
                            modifier         = Modifier.fillMaxWidth().height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Se incarca imaginea...",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    // ce se afieaza cand da eroare
                    storageError != null -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier            = Modifier.fillMaxWidth().padding(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint               = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text       = "Eroare la incarcare imagine",
                                color      = MaterialTheme.colorScheme.error,
                                style      = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text      = storageError!!,
                                color     = MaterialTheme.colorScheme.error,
                                style     = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text  = "Path: ${detection.image_url}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                    }

                    // ce se afiseaza cand imaginea a fost descarcata
                    resolvedUrl != null -> {
                        SubcomposeAsyncImage(
                            model              = resolvedUrl,
                            contentDescription = "Detectie",
                            loading = {
                                Box(
                                    modifier         = Modifier.fillMaxWidth().height(200.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            },
                            error = {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier            = Modifier.fillMaxWidth().padding(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint               = MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        "Imaginea nu s-a putut afisa",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            },
                            modifier     = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onImageClick(resolvedUrl!!) },
                            contentScale = ContentScale.FillWidth
                        )
                        Text(
                            text      = "Apasa pe imagine pentru marire",
                            style     = MaterialTheme.typography.labelSmall,
                            modifier  = Modifier.fillMaxWidth().padding(top = 4.dp),
                            textAlign = TextAlign.Center,
                            color     = Color.Gray
                        )
                    }

                    detection.image_url.isNullOrBlank() -> {
                        Text(
                            text  = "Fara imagine pentru aceasta detectie.",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun FullScreenImageDialog(imageUrl: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss, // permite inchiderea dialogului daca este apasat back
        properties       = DialogProperties(usePlatformDefaultWidth = false) // permite ocuparea totala a ecranului cu imaginea
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            SubcomposeAsyncImage(
                model              = imageUrl,
                contentDescription = null,
                modifier           = Modifier.fillMaxSize(),
                contentScale       = ContentScale.Fit, // afisarea imaginii pe tot ecranul
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                },
                error = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Eroare la afisare", color = Color.White)
                    }
                }
            )
            // buton care permite inchiderea imaginii
            IconButton(
                onClick  = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) {
                Icon(
                    imageVector        = Icons.Default.Close,
                    contentDescription = "Inchide",
                    tint               = Color.White,
                    modifier           = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
fun AuthScreen(rootRef: DatabaseReference, onAuthSuccess: () -> Unit) {
    var email        by remember { mutableStateOf("") }
    var password     by remember { mutableStateOf("") }
    var name         by remember { mutableStateOf("") }
    var isLogin      by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val auth         = Firebase.auth

    Column(
        modifier            = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text       = if (isLogin) "Bine ai revenit!" else "Cont Nou",
            style      = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (!isLogin) {
            OutlinedTextField(
                value         = name,
                onValueChange = { name = it },
                label         = { Text("Nume Complet") },
                modifier      = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        OutlinedTextField(
            value         = email,
            onValueChange = { email = it },
            label         = { Text("Email") },
            modifier      = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value                = password,
            onValueChange        = { password = it },
            label                = { Text("Parola") },
            visualTransformation = PasswordVisualTransformation(),
            modifier             = Modifier.fillMaxWidth()
        )

        errorMessage?.let {
            Text(
                text     = it,
                color    = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
                style    = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick  = {
                errorMessage = null
                if (isLogin) {
                    auth.signInWithEmailAndPassword(email, password)
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful) onAuthSuccess()
                            else errorMessage = task.exception?.message
                        }
                } else {
                    auth.createUserWithEmailAndPassword(email, password)
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                val uid  = auth.currentUser?.uid ?: ""
                                val user = User(uid, email, name)
                                rootRef.child("users").child(uid).setValue(user)
                                    .addOnCompleteListener { onAuthSuccess() }
                            } else {
                                errorMessage = task.exception?.message
                            }
                        }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isLogin) "LOGIN" else "SIGN UP")
        }

        TextButton(onClick = { isLogin = !isLogin; errorMessage = null }) {
            Text(if (isLogin) "Nu ai cont? Inregistreaza-te" else "Ai deja cont? Logheaza-te")
        }
    }
}


@Composable
fun NotificationPermissionHandler() {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
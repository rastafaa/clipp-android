package net.clipp.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    // Re-checked on every resume (the user may have just granted something in Settings).
    private var resumeTick by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ClippTheme {
                App(resumeTick)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        if (ClippNative.keyStatus().hasKey && !ClippService.running) {
            runCatching { ClippService.start(this) }
        }
    }
}

@Composable
private fun ClippTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun App(resumeTick: Int) {
    var status by remember { mutableStateOf(ClippNative.keyStatus()) }
    if (!status.hasKey) {
        SetupScreen(initialName = status.networkName) { status = ClippNative.keyStatus() }
    } else {
        HomeScreen(status, resumeTick) { status = ClippNative.keyStatus() }
    }
}

// ---------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------
@Composable
private fun SetupScreen(initialName: String, onPaired: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialName) }
    var secret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Join your Clipp group", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Use the same group name and passphrase as your other devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it; error = null },
                label = { Text("Group name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = secret, onValueChange = { secret = it; error = null },
                label = { Text("Passphrase") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
            Button(
                enabled = !busy && name.isNotBlank() && secret.length >= 8,
                onClick = {
                    busy = true
                    scope.launch {
                        // Key derivation is deliberately slow; keep it off the UI thread.
                        val result = withContext(Dispatchers.Default) { ClippNative.pair(name.trim(), secret) }
                        busy = false
                        if (result == null) {
                            secret = ""
                            ClippService.start(context)
                            onPaired()
                        } else {
                            error = result
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(if (busy) "Joining…" else "Join") }
        }
    }
}

// ---------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------
@SuppressLint("BatteryLife")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(status: ClippNative.KeyStatus, resumeTick: Int, onUnpaired: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var items by remember { mutableStateOf(ClippNative.items()) }
    var peers by remember { mutableStateOf(ClippNative.peers()) }
    DisposableEffect(Unit) {
        val onItems: () -> Unit = { scope.launch { items = withContext(Dispatchers.IO) { ClippNative.items() } } }
        val onPeers: () -> Unit = { scope.launch { peers = withContext(Dispatchers.IO) { ClippNative.peers() } } }
        ClippNative.activityListeners += onItems
        ClippNative.peerListeners += onPeers
        onItems(); onPeers()
        onDispose {
            ClippNative.activityListeners -= onItems
            ClippNative.peerListeners -= onPeers
        }
    }

    // Background reliability: notifications (to keep the service visible) and battery exemption.
    val power = context.getSystemService(PowerManager::class.java)
    val ignoringBattery = remember(resumeTick) { power.isIgnoringBatteryOptimizations(context.packageName) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var autoCopy by remember { mutableStateOf(Prefs.autoCopy(context)) }
    val connected = peers.filter { it.connected }

    fun sendClipboard() {
        val content = ClipboardBridge.read(context)
        if (content == null) {
            scope.launch { snackbar.showSnackbar("Clipboard is empty") }
            return
        }
        scope.launch {
            val message = withContext(Dispatchers.IO) { ClipboardBridge.send(content) }
            snackbar.showSnackbar(message)
        }
    }

    fun copy(item: ClippNative.Item) {
        scope.launch {
            withContext(Dispatchers.IO) {
                ClippNative.nativeReshare(item.id)
                ClipboardBridge.write(context, item.id)
            }
            // Android 13+ shows its own "Copied" confirmation.
            if (Build.VERSION.SDK_INT < 33) snackbar.showSnackbar("Copied")
        }
    }

    fun delete(item: ClippNative.Item) {
        scope.launch {
            val deleted = withContext(Dispatchers.IO) { ClippNative.nativeDelete(item.id) }
            if (!deleted) return@launch
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("Deleted on all devices", "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) {
                withContext(Dispatchers.IO) { ClippNative.nativeUndoDelete() }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Clipp")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(8.dp).clip(CircleShape).background(
                                    if (connected.isNotEmpty()) Color(0xFF34A853) else MaterialTheme.colorScheme.outline
                                )
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when (connected.size) {
                                    0 -> "Looking for devices"
                                    1 -> connected.first().name
                                    else -> "${connected.size} devices connected"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Copy incoming automatically") },
                            trailingIcon = { Switch(checked = autoCopy, onCheckedChange = null) },
                            onClick = { autoCopy = !autoCopy; Prefs.setAutoCopy(context, autoCopy) },
                        )
                        DropdownMenuItem(text = { Text("Group & devices") }, onClick = { menuOpen = false; showInfo = true })
                        DropdownMenuItem(text = { Text("Leave group") }, onClick = { menuOpen = false; confirmLeave = true })
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { sendClipboard() },
                icon = { Icon(Icons.AutoMirrored.Filled.Send, null) },
                text = { Text("Send clipboard") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!ignoringBattery) {
                item(key = "battery") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Keep syncing in the background", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Without this, Android may pause Clipp and you'll miss items from your other devices.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(12.dp))
                            FilledTonalButton(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                                )
                            }) { Text("Allow") }
                        }
                    }
                }
            }
            if (items.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing here yet. Copy something on another device, or send this phone's clipboard.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 48.dp, horizontal = 8.dp),
                    )
                }
            }
            items(items, key = { it.id }) { item ->
                SwipeToDelete(onDelete = { delete(item) }) {
                    ItemCard(item, onClick = { if (item.hasContent) copy(item) }, onDelete = { delete(item) })
                }
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Done") } },
            title = { Text(status.networkName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column {
                        Text("Fingerprint", style = MaterialTheme.typography.labelMedium)
                        Text(status.fingerprint ?: "—", fontFamily = FontFamily.Monospace)
                        Text(
                            "Should match on every device in the group.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column {
                        Text("This device", style = MaterialTheme.typography.labelMedium)
                        Text(status.deviceName)
                    }
                    Column {
                        Text("Devices", style = MaterialTheme.typography.labelMedium)
                        if (peers.isEmpty()) Text("None found yet")
                        peers.forEach { Text("${it.name}${if (it.connected) "" else " (offline)"}") }
                    }
                }
            },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave group?") },
            text = { Text("This phone stops syncing. You can rejoin with the group name and passphrase.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    ClippService.stop(context)
                    scope.launch {
                        withContext(Dispatchers.IO) { ClippNative.nativeUnpair() }
                        onUnpaired()
                    }
                }) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.onErrorContainer) }
        },
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemCard(item: ClippNative.Item, onClick: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true }),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (item.isImage) {
                Thumbnail(item.id)
            } else {
                Row(verticalAlignment = Alignment.Top) {
                    if (item.isPrivate) {
                        Icon(
                            Icons.Filled.Lock, "Private", Modifier.size(16.dp).padding(top = 2.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        item.preview.ifEmpty { if (item.kind == "placeholder") "Private item" else "Unsupported item" },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${if (item.outgoing) "This phone" else item.device} · " +
                    DateUtils.getRelativeTimeSpanString(item.timestampMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (item.hasContent) DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; onClick() })
            DropdownMenuItem(text = { Text("Delete on all devices") }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun Thumbnail(id: Long) {
    val bitmap by produceState<Bitmap?>(null, id) {
        value = withContext(Dispatchers.IO) {
            val data = ClippNative.nativeItemData(id) ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 720) sample *= 2
            BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
    val image = bitmap
    if (image == null) {
        Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
    } else {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = "Image",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp)),
        )
    }
}

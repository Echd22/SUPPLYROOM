package com.supplyroom.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/* ===================== Navigation ===================== */

sealed interface Screen {
    data object Setup : Screen
    data object Home : Screen
    data class Flow(val act: Act) : Screen
    data object Stock : Screen
    data object Recent : Screen
}

enum class Act(val code: String, val title: String, val hint: String, val submit: String) {
    OUT("ISSUE", "Check Out", "Scan each item you are handing out.", "Check out"),
    IN("RETURN", "Check In", "Scan each item coming back.", "Check in"),
    RECEIVE("RECEIVE", "Receive New", "Scan the box barcode, then the item's serial number.", "Add to stock"),
    COUNT("COUNT", "Shelf Count", "Scan every item on the shelf, one at a time.", "Finish count"),
}

@Composable
fun AppRoot() {
    var screen by remember {
        mutableStateOf<Screen>(if (Prefs.configured && Prefs.user.isNotBlank()) Screen.Home else Screen.Setup)
    }
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF8AB4F8))
    else lightColorScheme(primary = Color(0xFF1F5FA8))
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val s = screen) {
                Screen.Setup -> SetupScreen(onDone = { screen = Screen.Home })
                Screen.Home -> HomeScreen(go = { screen = it })
                is Screen.Flow -> FlowScreen(s.act, onExit = { screen = Screen.Home })
                Screen.Stock -> StockScreen(onBack = { screen = Screen.Home })
                Screen.Recent -> RecentScreen(onBack = { screen = Screen.Home })
            }
        }
    }
}

/* ===================== Shared pieces ===================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    bottom: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onBack != null) BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { if (onBack != null) TextButton(onClick = onBack) { Text("‹ Back", fontSize = 18.sp) } },
                actions = actions,
            )
        },
        bottomBar = { Box(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) { bottom() } },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun BigButton(text: String, sub: String? = null, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 68.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, fontSize = 13.sp)
        }
    }
}

@Composable
fun MessageDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK", fontSize = 18.sp) } },
        title = { Text(title) },
        text = { Text(text, fontSize = 17.sp) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Banner(text: String, color: Color, onClick: (() -> Unit)? = null) {
    val c = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f))
    if (onClick != null) {
        Card(onClick = onClick, colors = c, modifier = Modifier.fillMaxWidth()) { Text(text, Modifier.padding(14.dp), fontSize = 16.sp) }
    } else {
        Card(colors = c, modifier = Modifier.fillMaxWidth()) { Text(text, Modifier.padding(14.dp), fontSize = 16.sp) }
    }
}

/* ===================== Setup ===================== */

@Composable
fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadUsers() {
        scope.launch {
            busy = true; error = null
            try {
                val arr = Api.call("users") as JSONArray
                users = (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getString("name") to o.optString("role", "user") } }
                if (users.isNullOrEmpty()) error = "No people are listed yet. Ask your admin to add you to the Users sheet."
            } catch (e: Exception) {
                error = friendly(e)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { if (Prefs.configured) loadUsers() }

    Page(if (users == null) "Welcome" else "Who are you?", onBack = null) {
        val list = users
        if (list == null) {
            Text("Set up this phone", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Ask your admin to show the setup code on their computer. Tap the button below and point your camera at it.",
                fontSize = 17.sp,
            )
            Spacer(Modifier.height(8.dp))
            BigButton("Scan setup code", enabled = !busy) {
                scope.launch {
                    val code = try { scanCode(ctx) } catch (e: Exception) { error = "Scanner problem: ${e.message}"; null }
                        ?: return@launch
                    try {
                        val j = JSONObject(code)
                        require(j.optString("t") == "supplyroom")
                        Prefs.url = j.getString("u")
                        Prefs.key = j.getString("k")
                        Prefs.org = j.optString("o", "Supply Room")
                        loadUsers()
                    } catch (e: Exception) {
                        error = "That isn't a Supply Room setup code. Try again."
                    }
                }
            }
        } else {
            Text("Tap your name. You only do this once.", fontSize = 17.sp)
            list.forEach { (name, role) ->
                OutlinedButton(
                    onClick = { Prefs.user = name; Prefs.role = role; onDone() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(name, fontSize = 20.sp) }
            }
            TextButton(onClick = { users = null }) { Text("Scan a different setup code") }
        }
        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 16.sp) }
    }
}

/* ===================== Home ===================== */

@Composable
fun HomeScreen(go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var queued by remember { mutableIntStateOf(Prefs.queue.length()) }
    var update by remember { mutableStateOf<Pair<String, String>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (queued > 0) { note = flushQueue(); queued = Prefs.queue.length() }
        update = try { Api.newerRelease() } catch (e: Exception) { null }
        try { loadSnapshot() } catch (_: Exception) { }
    }

    Page(
        Prefs.org.ifBlank { "Supply Room" }, onBack = null,
        actions = {
            Box {
                TextButton(onClick = { menu = true }) { Text(Prefs.user, fontSize = 16.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Switch person") }, onClick = { menu = false; Prefs.user = ""; go(Screen.Setup) })
                    DropdownMenuItem(text = { Text("Scan new setup code") }, onClick = { menu = false; Prefs.key = ""; Prefs.user = ""; go(Screen.Setup) })
                    DropdownMenuItem(text = { Text("Version ${BuildConfig.VERSION_NAME}") }, onClick = { menu = false })
                }
            }
        },
    ) {
        update?.let { (tag, url) ->
            Banner("⬇  Update available ($tag). Tap to download, then open the file to install.", MaterialTheme.colorScheme.primary) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        if (queued > 0) {
            Banner("📶  $queued list(s) saved on this phone, not uploaded yet. Tap to try again.", Color(0xFFB26A00)) {
                scope.launch { note = flushQueue() ?: "Still no connection."; queued = Prefs.queue.length() }
            }
        }
        note?.let { Text(it, fontSize = 15.sp) }

        BigButton("Check Out", "Hand equipment to someone") { go(Screen.Flow(Act.OUT)) }
        BigButton("Check In", "Equipment coming back") { go(Screen.Flow(Act.IN)) }
        BigButton("Receive New", "New stock arrived") { go(Screen.Flow(Act.RECEIVE)) }
        BigButton("Shelf Count", "Check the shelf against the records") { go(Screen.Flow(Act.COUNT)) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { go(Screen.Stock) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("What's in stock", fontSize = 16.sp) }
            OutlinedButton(onClick = { go(Screen.Recent) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Recent", fontSize = 16.sp) }
        }
    }
}

package com.supplyroom.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate

data class Line(
    val key: Long,
    val itemId: String,
    val name: String,
    val serialized: Boolean,
    val tag: String = "",
    val serial: String = "",
    val qty: Int = 1,
    val unit: String = "ea",
    val condition: String = "Serviceable",
    val needsSerial: Boolean = false,
    val expires: String = "",
)

private const val STEP_SCAN = 0
private const val STEP_DETAILS = 1
private const val STEP_DONE = 2

@Composable
fun FlowScreen(act: Act, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var snap by remember { mutableStateOf(cachedSnapshot()) }
    var loading by remember { mutableStateOf(true) }
    val lines = remember { mutableStateListOf<Line>() }
    val counts = remember { mutableStateListOf<String>() }
    var step by remember { mutableIntStateOf(STEP_SCAN) }
    var busy by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var unknown by remember { mutableStateOf<String?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var savedOffline by remember { mutableStateOf(false) }
    var countResult by remember { mutableStateOf<JSONObject?>(null) }
    var nextKey by remember { mutableLongStateOf(1L) }
    var typed by remember { mutableStateOf("") }

    var recipient by remember { mutableStateOf("") }
    var dueDays by remember { mutableIntStateOf(0) }
    var location by remember { mutableStateOf(snap?.defaultLocation ?: "") }
    var notes by remember { mutableStateOf("") }
    var sendEmail by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(Prefs.lastEmail) }
    val sig = remember { SigState() }

    LaunchedEffect(Unit) {
        try {
            snap = loadSnapshot()
        } catch (e: Exception) {
            if (snap == null) dialog = friendly(e) + "\n\nThe item list couldn't be loaded."
        }
        if (location.isBlank()) location = snap?.defaultLocation ?: ""
        loading = false
    }

    /** Handles one scanned/typed code. Returns true to keep the scanner going. */
    fun handle(raw: String): Boolean {
        val code = raw.trim()
        if (code.isEmpty()) return true
        val s = snap ?: run { dialog = "The item list hasn't loaded yet. Check your signal."; return false }
        if (act == Act.COUNT) {
            counts.add(code); feedback(ctx, true); return true
        }
        val waiting = lines.indexOfFirst { it.needsSerial && it.serial.isBlank() }
        when (val h = s.find(code)) {
            is Hit.OfUnit -> {
                val u = h.unit
                val name = h.item?.name ?: u.itemId
                if (act == Act.RECEIVE) {
                    feedback(ctx, false)
                    dialog = if (waiting >= 0) "Serial $code is already in the system as ${u.tag}." else "$name ${u.tag} is already in the system. Use Check In if it's coming back from the field."
                    return false
                }
                if (lines.any { it.tag.equals(u.tag, ignoreCase = true) }) {
                    feedback(ctx, false); toast(ctx, "${u.tag} is already on the list"); return true
                }
                val problem = when (act) {
                    Act.OUT -> when (u.status) {
                        "In Stock" -> null
                        "Issued" -> "${u.tag} ($name) is already checked out to ${u.assignedTo}. Check it in first."
                        else -> "${u.tag} ($name) is marked \"${u.status}\" and can't be checked out."
                    }
                    Act.IN -> if (u.status == "Issued") null else "${u.tag} ($name) isn't checked out (it's \"${u.status}\")."
                    else -> null
                }
                if (problem != null) { feedback(ctx, false); dialog = problem; return false }
                lines.add(Line(nextKey++, u.itemId, name, true, tag = u.tag, serial = u.serial))
                feedback(ctx, true); toast(ctx, "$name ${u.tag}")
            }
            is Hit.OfItem -> {
                val item = h.item
                if (item.serialized) {
                    if (act != Act.RECEIVE) {
                        feedback(ctx, false)
                        dialog = "That's the general barcode for \"${item.name}\".\n\nScan the label on the item itself (the QR sticker)."
                        return false
                    }
                    lines.add(Line(nextKey++, item.id, item.name, true, needsSerial = true, unit = item.unit))
                    feedback(ctx, true); toast(ctx, "${item.name}: now scan its serial number")
                } else {
                    val i = lines.indexOfFirst { !it.serialized && it.itemId == item.id }
                    if (i >= 0) lines[i] = lines[i].copy(qty = lines[i].qty + 1)
                    else lines.add(Line(nextKey++, item.id, item.name, false, unit = item.unit))
                    feedback(ctx, true); toast(ctx, "${item.name} +1")
                }
            }
            is Hit.NotFound -> {
                if (act == Act.RECEIVE && waiting >= 0) {
                    lines[waiting] = lines[waiting].copy(serial = code)
                    feedback(ctx, true); toast(ctx, "Serial $code saved")
                } else {
                    feedback(ctx, false); unknown = code; return false
                }
            }
        }
        return true
    }

    fun scanLoop() {
        scope.launch {
            while (true) {
                val code = try { scanCode(ctx) } catch (e: Exception) { dialog = "Scanner problem: ${e.message}"; null } ?: break
                if (!handle(code)) break
                delay(350)
            }
        }
    }

    fun submit() {
        if (act == Act.OUT && recipient.isBlank()) { dialog = "Enter who this equipment is for."; return }
        if (sendEmail && !email.contains('@')) { dialog = "Enter an email address for the copy, or turn the email switch off."; return }
        val p = JSONObject()
            .put("clientId", "B" + System.currentTimeMillis().toString(36) + "-" + (1000..9999).random())
            .put("action", act.code)
            .put("lines", JSONArray().apply {
                lines.forEach { l ->
                    put(
                        JSONObject().put("itemId", l.itemId).put("tag", l.tag).put("serial", l.serial)
                            .put("qty", if (l.serialized) 1 else l.qty)
                            .put("condition", if (act == Act.IN || act == Act.RECEIVE) l.condition else "")
                            .put("expires", l.expires.trim()),
                    )
                }
            })
            .put("recipient", recipient.trim())
            .put("dueBack", if (act == Act.OUT && dueDays > 0) LocalDate.now().plusDays(dueDays.toLong()).toString() else "")
            .put("to", if (act == Act.RECEIVE) location.trim() else "")
            .put("notes", notes.trim())
            .put("sendForm", sendEmail)
            .put("emailTo", if (sendEmail) email.trim() else "")
            .put("signature", if (act == Act.OUT && !sig.isEmpty) sig.toDataUrl() else "")
        busy = true
        scope.launch {
            try {
                result = Api.call("submit", p) as JSONObject
                feedback(ctx, true); step = STEP_DONE
            } catch (e: IOException) {
                Prefs.queue = Prefs.queue.apply { put(p) }
                savedOffline = true; step = STEP_DONE
            } catch (e: Exception) {
                feedback(ctx, false); dialog = friendly(e)
            } finally {
                busy = false
            }
            if (act == Act.OUT && recipient.isNotBlank()) Prefs.addRecent(recipient.trim())
            if (sendEmail) Prefs.lastEmail = email.trim()
        }
    }

    fun finishCount() {
        busy = true
        scope.launch {
            try {
                countResult = Api.call("cycleCount", location.trim(), JSONArray(counts.toList())) as JSONObject
                step = STEP_DONE
            } catch (e: Exception) {
                dialog = friendly(e)
            } finally {
                busy = false
            }
        }
    }

    fun goBack() {
        if (step == STEP_DETAILS) step = STEP_SCAN
        else if (step == STEP_DONE || (lines.isEmpty() && counts.isEmpty())) onExit()
        else confirmExit = true
    }

    Page(
        title = act.title,
        onBack = { goBack() },
        bottom = {
            when (step) {
                STEP_SCAN -> if (act == Act.COUNT) {
                    BigButton("${act.submit} (${counts.size} scanned)", enabled = counts.isNotEmpty() && !busy) { finishCount() }
                } else {
                    BigButton("Next  ›   (${lines.size} on list)", enabled = lines.isNotEmpty()) {
                        val missing = lines.firstOrNull { it.needsSerial && it.serial.isBlank() }
                        if (missing != null) dialog = "${missing.name} still needs its serial number. Scan it, type it, or remove that line."
                        else step = STEP_DETAILS
                    }
                }
                STEP_DETAILS -> BigButton(
                    if (busy) "Saving…" else "${act.submit} ${lines.sumOf { if (it.serialized) 1 else it.qty }} item(s)",
                    enabled = !busy,
                ) { submit() }
                else -> BigButton("Done") { onExit() }
            }
        },
    ) {
        if (loading && snap == null) CircularProgressIndicator()
        when (step) {
            STEP_SCAN -> ScanStep(
                act = act, lines = lines, counts = counts, typed = typed, onTyped = { typed = it },
                location = location, onLocation = { location = it },
                onScan = { scanLoop() },
                onAddTyped = { if (typed.isNotBlank()) { handle(typed); typed = "" } },
            )
            STEP_DETAILS -> DetailsStep(
                act = act, lines = lines,
                recipient = recipient, onRecipient = { recipient = it },
                dueDays = dueDays, onDueDays = { dueDays = it },
                location = location, onLocation = { location = it },
                notes = notes, onNotes = { notes = it },
                sendEmail = sendEmail, onSendEmail = { sendEmail = it },
                email = email, onEmail = { email = it },
                sig = sig,
            )
            else -> if (act == Act.COUNT) CountResult(countResult) else DoneStep(result, savedOffline)
        }
    }

    dialog?.let { MessageDialog("Heads up", it) { dialog = null } }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Leave without saving?") },
            text = { Text("The items you scanned will not be recorded.", fontSize = 17.sp) },
            confirmButton = { TextButton(onClick = { confirmExit = false; onExit() }) { Text("Leave", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Stay") } },
        )
    }
    unknown?.let { code ->
        if ((snap?.role ?: Prefs.role) == "admin") {
            AddItemDialog(code, onDismiss = { unknown = null }, onAdded = {
                scope.launch {
                    try { snap = loadSnapshot() } catch (_: Exception) { }
                    unknown = null
                    handle(code)
                }
            })
        } else {
            MessageDialog("Not in the system", "Nothing matches \"$code\".\n\nSet the item aside and ask an admin to add it.") { unknown = null }
        }
    }
}

/* ===================== Step 1: scanning ===================== */

@Composable
private fun ScanStep(
    act: Act, lines: MutableList<Line>, counts: MutableList<String>,
    typed: String, onTyped: (String) -> Unit,
    location: String, onLocation: (String) -> Unit,
    onScan: () -> Unit, onAddTyped: () -> Unit,
) {
    Text(act.hint, fontSize = 17.sp)
    BigButton("📷   Scan", "Keeps scanning. Press Back when finished.", onClick = onScan)
    OutlinedTextField(
        value = typed, onValueChange = onTyped, singleLine = true,
        label = { Text("Or type a code") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onAddTyped() }),
        trailingIcon = { TextButton(onClick = onAddTyped) { Text("Add") } },
        modifier = Modifier.fillMaxWidth(),
    )

    if (act == Act.COUNT) {
        OutlinedTextField(
            value = location, onValueChange = onLocation, singleLine = true,
            label = { Text("Which shelf/location? (blank = everything)") },
            modifier = Modifier.fillMaxWidth(),
        )
        val tally = counts.groupingBy { it }.eachCount()
        if (tally.isEmpty()) Text("Nothing scanned yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        tally.forEach { (code, n) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(code + if (n > 1) "   ×$n" else "", Modifier.weight(1f), fontSize = 17.sp)
                TextButton(onClick = { counts.removeAt(counts.lastIndexOf(code)) }) { Text("−1", fontSize = 18.sp) }
            }
        }
        return
    }

    if (lines.isEmpty()) Text("Nothing on the list yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    lines.forEach { l ->
        LineCard(
            l, act,
            onChange = { changed -> val j = lines.indexOfFirst { it.key == l.key }; if (j >= 0) lines[j] = changed },
            onRemove = { lines.removeAll { it.key == l.key } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineCard(l: Line, act: Act, onChange: (Line) -> Unit, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.name, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    val sub = when {
                        l.tag.isNotBlank() -> "${l.tag}  ·  S/N ${l.serial}"
                        l.needsSerial && l.serial.isBlank() -> "Waiting for serial number…"
                        l.needsSerial -> "S/N ${l.serial}"
                        else -> ""
                    }
                    if (sub.isNotEmpty()) Text(
                        sub, fontSize = 15.sp,
                        color = if (l.needsSerial && l.serial.isBlank()) Color(0xFFB26A00) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            }
            if (!l.serialized) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { if (l.qty > 1) onChange(l.copy(qty = l.qty - 1)) }) { Text("−", fontSize = 22.sp) }
                    Text("${l.qty} ${l.unit}", Modifier.padding(horizontal = 18.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { onChange(l.copy(qty = l.qty + 1)) }) { Text("+", fontSize = 22.sp) }
                }
            }
            if (l.needsSerial) {
                OutlinedTextField(
                    value = l.serial, onValueChange = { onChange(l.copy(serial = it.trim())) }, singleLine = true,
                    label = { Text("Serial number") }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = l.expires, onValueChange = { onChange(l.copy(expires = it)) }, singleLine = true,
                    label = { Text("Expires (optional, like 2031-06-30)") }, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (act == Act.IN || act == Act.RECEIVE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = l.condition == "Serviceable", onClick = { onChange(l.copy(condition = "Serviceable")) },
                        label = { Text("Good condition") })
                    FilterChip(selected = l.condition != "Serviceable", onClick = { onChange(l.copy(condition = "Needs Inspection")) },
                        label = { Text("Needs a look") })
                }
            }
        }
    }
}

/* ===================== Step 2: details ===================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsStep(
    act: Act, lines: List<Line>,
    recipient: String, onRecipient: (String) -> Unit,
    dueDays: Int, onDueDays: (Int) -> Unit,
    location: String, onLocation: (String) -> Unit,
    notes: String, onNotes: (String) -> Unit,
    sendEmail: Boolean, onSendEmail: (Boolean) -> Unit,
    email: String, onEmail: (String) -> Unit,
    sig: SigState,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("On this list", fontWeight = FontWeight.SemiBold)
            lines.forEach { l -> Text("• ${l.name}  " + if (l.serialized) l.tag.ifBlank { "S/N ${l.serial}" } else "×${l.qty}", fontSize = 16.sp) }
        }
    }

    if (act == Act.OUT) {
        Text("Who is this for?", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        OutlinedTextField(
            value = recipient, onValueChange = onRecipient, singleLine = true,
            label = { Text("Name and unit") }, modifier = Modifier.fillMaxWidth(),
        )
        val recents = Prefs.recents().filter { recipient.isBlank() || it.contains(recipient, ignoreCase = true) }.take(8)
        if (recents.isNotEmpty() && recents.none { it == recipient }) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recents.forEach { r -> FilterChip(selected = false, onClick = { onRecipient(r) }, label = { Text(r) }) }
            }
        }
        Text("Is this a loaner?", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "No, it's theirs", 7 to "Back in 1 week", 14 to "2 weeks", 30 to "30 days").forEach { (d, label) ->
                FilterChip(selected = dueDays == d, onClick = { onDueDays(d) }, label = { Text(label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Signature", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { sig.clear() }) { Text("Clear") }
        }
        SignaturePad(sig, Modifier.fillMaxWidth().height(170.dp))
        Text("Hand the phone over and have them sign with a finger.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (act == Act.RECEIVE) {
        OutlinedTextField(
            value = location, onValueChange = onLocation, singleLine = true,
            label = { Text("Where is it being stored?") }, modifier = Modifier.fillMaxWidth(),
        )
    }

    OutlinedTextField(
        value = notes, onValueChange = onNotes,
        label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth(),
    )

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Email a copy of the form", fontSize = 17.sp, modifier = Modifier.weight(1f))
        Switch(checked = sendEmail, onCheckedChange = onSendEmail)
    }
    if (sendEmail) {
        OutlinedTextField(
            value = email, onValueChange = onEmail, singleLine = true,
            label = { Text("Send to (email)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(12.dp))
}

/* ===================== Step 3: results ===================== */

@Composable
private fun DoneStep(r: JSONObject?, savedOffline: Boolean) {
    if (savedOffline) {
        Text("📶  Saved on this phone", style = MaterialTheme.typography.headlineSmall)
        Text(
            "There's no signal right now. This list will upload by itself the next time you open the app with signal. You don't need to do anything else.",
            fontSize = 17.sp,
        )
        return
    }
    if (r == null) return
    Text("✓  All set", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
    if (r.optBoolean("duplicate")) { Text("This list was already saved earlier.", fontSize = 17.sp); return }
    val ls = r.optJSONArray("lines") ?: JSONArray()
    var anyNew = false
    for (i in 0 until ls.length()) {
        val o = ls.getJSONObject(i)
        if (o.optBoolean("isNew")) anyNew = true
        Text("• ${o.optString("name")}  " + if (o.has("tag")) o.optString("tag") else "×${o.optInt("qty")}", fontSize = 17.sp)
    }
    if (anyNew) Text(
        "New control numbers were given out above. Ask an admin to print their QR labels, then stick them on the items.",
        fontSize = 15.sp, color = Color(0xFFB26A00),
    )
    if (r.optBoolean("emailed")) Text("Copy of the form emailed.", fontSize = 16.sp)
    r.optString("emailError").takeIf { it.isNotBlank() }?.let { Text("Email didn't send: $it", color = MaterialTheme.colorScheme.error) }
    val low = r.optJSONArray("low") ?: JSONArray()
    for (i in 0 until low.length()) {
        val o = low.getJSONObject(i)
        Text("Running low: ${o.optString("name")} (${o.optInt("onHand")} left, minimum ${o.optInt("min")})", color = Color(0xFFB26A00), fontSize = 15.sp)
    }
}

@Composable
private fun CountResult(r: JSONObject?) {
    if (r == null) return
    Text("Count for ${r.optString("location")}", style = MaterialTheme.typography.headlineSmall)
    Text("${r.optInt("matched")} of ${r.optInt("expected")} tagged items found", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

    CountSection("Missing (should be here, not scanned)", r.optJSONArray("missing")) { "${it.optString("tag")} ${it.optString("name")} · S/N ${it.optString("serial")}" }
    CountSection("Records disagree (scanned, but listed elsewhere)", r.optJSONArray("unexpected")) {
        "${it.optString("tag")} ${it.optString("name")}: " + if (it.optString("status") == "Issued") "checked out to ${it.optString("assignedTo")}" else "${it.optString("status")} at ${it.optString("location")}"
    }
    CountSection("Counted items", r.optJSONArray("counted")) {
        val d = it.optInt("scanned") - it.optInt("system")
        "${it.optString("name")}: counted ${it.optInt("scanned")}, records say ${it.optInt("system")}" + if (d == 0) "  ✓" else "  (${if (d > 0) "+" else ""}$d)"
    }
    val unk = r.optJSONArray("unknown") ?: JSONArray()
    Text("Unknown codes", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))
    if (unk.length() == 0) Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (i in 0 until unk.length()) Text("• " + unk.optString(i), fontSize = 16.sp)
    Text("Give these results to an admin to fix any differences.", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CountSection(title: String, arr: JSONArray?, line: (JSONObject) -> String) {
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))
    if (arr == null || arr.length() == 0) {
        Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        for (i in 0 until arr.length()) Text("• " + line(arr.getJSONObject(i)), fontSize = 16.sp)
    }
}

/* ===================== Admin: add an unknown code ===================== */

@Composable
private fun AddItemDialog(code: String, onDismiss: () -> Unit, onAdded: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var serialized by remember { mutableStateOf(false) }
    var min by remember { mutableStateOf("0") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add new item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Code: $code")
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Item name") })
                listOf(false to "Count by quantity", true to "Track each one by serial").forEach { (v, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).selectable(selected = serialized == v, onClick = { serialized = v }),
                    ) {
                        RadioButton(selected = serialized == v, onClick = { serialized = v })
                        Spacer(Modifier.width(6.dp))
                        Text(label)
                    }
                }
                OutlinedTextField(
                    value = min, onValueChange = { min = it.filter(Char::isDigit) }, singleLine = true,
                    label = { Text("Warn when stock is below") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    try {
                        Api.call(
                            "addItem",
                            JSONObject().put("name", name.trim()).put("tracking", if (serialized) "serialized" else "counted")
                                .put("barcode", code).put("minStock", min.toIntOrNull() ?: 0),
                        )
                        onAdded()
                    } catch (e: Exception) {
                        err = friendly(e)
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

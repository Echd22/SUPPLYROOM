package com.supplyroom.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

/* ===================== What's in stock ===================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<JSONObject>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    var units by remember { mutableStateOf<List<JSONObject>?>(null) }

    LaunchedEffect(Unit) {
        try { rows = (Api.call("stock") as JSONArray).objects() } catch (e: Exception) { error = friendly(e) }
    }

    Page("What's in stock", onBack = onBack) {
        OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, label = { Text("Search") }, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val list = rows
        if (list == null && error == null) CircularProgressIndicator()
        list?.filter { q.isBlank() || it.optString("name").contains(q, true) || it.optString("category").contains(q, true) }
            ?.sortedWith(compareByDescending<JSONObject> { it.optBoolean("low") }.thenBy { it.optString("name").lowercase() })
            ?.forEach { r ->
                val id = r.optString("itemId")
                val ser = r.optString("tracking") == "serialized"
                val low = r.optBoolean("low")
                Card(
                    onClick = {
                        if (!ser) return@Card
                        if (open == id) { open = null } else {
                            open = id; units = null
                            scope.launch {
                                try { units = (Api.call("units", id) as JSONArray).objects() } catch (e: Exception) { error = friendly(e) }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.optString("name"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            Text(
                                "${r.optInt("onHand")} ${r.optString("unit")}", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (low) Text("Below minimum of ${r.optInt("min")}", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                        if (ser) Text(
                            "${r.optInt("issued")} checked out · ${r.optInt("inspect")} need a look · tap to see each",
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (open == id) {
                            val us = units
                            if (us == null) CircularProgressIndicator()
                            else if (us.isEmpty()) Text("None yet")
                            else us.forEach { u ->
                                val who = u.optString("AssignedTo")
                                Text(
                                    "${u.optString("Tag")}  ${u.optString("Status")}" + (if (who.isNotBlank()) " → $who" else "") +
                                        (u.optString("Expires").takeIf { it.isNotBlank() }?.let { " · exp $it" } ?: ""),
                                    fontSize = 15.sp,
                                )
                            }
                        }
                    }
                }
            }
    }
}

/* ===================== Recent activity ===================== */

private val ACTION_WORDS = mapOf(
    "ISSUE" to "Checked out", "RETURN" to "Checked in", "RECEIVE" to "Received",
    "TRANSFER" to "Moved", "DISPOSE" to "Disposed", "ADJUST" to "Adjusted",
)

@Composable
fun RecentScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var batches by remember { mutableStateOf<List<List<JSONObject>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var mailFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val rows = (Api.call("history", 200) as JSONArray).objects()
            val grouped = LinkedHashMap<String, MutableList<JSONObject>>()
            rows.forEach { grouped.getOrPut(it.optString("BatchID")) { mutableListOf() }.add(it) }
            batches = grouped.values.toList()
        } catch (e: Exception) {
            error = friendly(e)
        }
    }

    Page("Recent", onBack = onBack) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val list = batches
        if (list == null && error == null) CircularProgressIndicator()
        if (list != null && list.isEmpty()) Text("Nothing yet.")
        list?.forEach { b ->
            val t = b.first()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ACTION_WORDS[t.optString("Action")] ?: t.optString("Action"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(t.optString("Timestamp"), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "by ${t.optString("User")}" + t.optString("Recipient").takeIf { it.isNotBlank() }?.let { "  →  $it" }.orEmpty(),
                        fontSize = 15.sp,
                    )
                    b.forEach { l ->
                        Text(
                            "• ${l.optString("ItemName")}  " + l.optString("Tag").ifBlank { "×" + l.optString("Qty") },
                            fontSize = 15.sp,
                            textDecoration = if (l.optBoolean("Voided")) TextDecoration.LineThrough else null,
                        )
                    }
                    OutlinedButton(onClick = { mailFor = t.optString("BatchID") }, modifier = Modifier.padding(top = 6.dp)) { Text("Email the form") }
                }
            }
        }
    }

    mailFor?.let { batch ->
        var to by remember(batch) { mutableStateOf(Prefs.lastEmail) }
        var busy by remember(batch) { mutableStateOf(false) }
        var err by remember(batch) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { mailFor = null },
            title = { Text("Email the form") },
            text = {
                Column {
                    OutlinedTextField(
                        value = to, onValueChange = { to = it }, singleLine = true, label = { Text("Send to (email)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && to.contains('@'), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            Api.call("sendForm", batch, to.trim())
                            Prefs.lastEmail = to.trim()
                            toast(ctx, "Form sent")
                            mailFor = null
                        } catch (e: Exception) {
                            err = friendly(e)
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(if (busy) "Sending…" else "Send") }
            },
            dismissButton = { TextButton(onClick = { mailFor = null }) { Text("Cancel") } },
        )
    }
}

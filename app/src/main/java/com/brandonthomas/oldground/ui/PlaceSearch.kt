package com.brandonthomas.oldground.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brandonthomas.oldground.geo.LngLat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Place search with Photon (OpenStreetMap-based, free). Also takes "lat, lng". */
@Composable
fun PlaceSearch(near: LngLat?, onPick: (LngLat) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<String, LngLat>>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }

    fun search() {
        val text = q.trim()
        if (text.isEmpty()) return
        val ll = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*[, ]\s*(-?\d+(?:\.\d+)?)\s*$""").find(text)
        if (ll != null) {
            onPick(LngLat(ll.groupValues[2].toDouble(), ll.groupValues[1].toDouble()))
            results = emptyList()
            focus.clearFocus()
            return
        }
        scope.launch {
            err = null
            results = runCatching {
                withContext(Dispatchers.IO) {
                    val bias = near?.let { "&lat=${it.lat}&lon=${it.lng}" } ?: ""
                    val c = URL("https://photon.komoot.io/api/?q=${URLEncoder.encode(text, "UTF-8")}&limit=6$bias").openConnection() as HttpURLConnection
                    c.setRequestProperty("User-Agent", "OldGround-Android/0.1")
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    val body = c.inputStream.bufferedReader().use { it.readText() }
                    Json.parseToJsonElement(body).jsonObject["features"]!!.jsonArray.map { f ->
                        val o = f.jsonObject
                        val p = o["properties"] as JsonObject
                        val coords = o["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
                        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: text
                        val detail = listOf("city", "county", "state").mapNotNull { p[it]?.jsonPrimitive?.contentOrNull }.joinToString(", ")
                        (if (detail.isEmpty()) name else "$name — $detail") to LngLat(coords[0].jsonPrimitive.double, coords[1].jsonPrimitive.double)
                    }
                }
            }.getOrElse { err = "Search needs a connection."; emptyList() }
            if (results.isEmpty() && err == null) err = "No places found."
        }
    }

    Column(modifier) {
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            placeholder = { Text("Search a place or type lat, lng") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = C.panel, unfocusedContainerColor = C.panel),
            shape = RoundedCornerShape(10.dp),
        )
        err?.let { Text(it, color = C.bad, modifier = Modifier.background(C.panel).padding(4.dp)) }
        if (results.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(10.dp)).background(C.panel).verticalScroll(rememberScrollState())) {
                for ((label, geo) in results) {
                    Text(label, Modifier.fillMaxWidth().clickable {
                        onPick(geo)
                        results = emptyList()
                        focus.clearFocus()
                    }.padding(12.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = C.ink)
                }
            }
        }
    }
}

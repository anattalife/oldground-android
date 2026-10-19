package com.brandonthomas.oldground.community

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.OffsetDateTime

// ---------- what comes back from the server ----------

@Serializable
data class CPost(
    val id: String,
    @SerialName("author_id") val authorId: String,
    val kind: String,
    val title: String,
    val body: String? = null,
    val category: String? = null,
    val material: String? = null,
    @SerialName("depth_in") val depthIn: Double? = null,
    val detector: String? = null,
    val state: String? = null,
    val photos: List<String> = emptyList(),
    @SerialName("video_url") val videoUrl: String? = null,
    @SerialName("video_provider") val videoProvider: String? = null,
    @SerialName("video_id") val videoId: String? = null,
    @SerialName("video_channel") val videoChannel: String? = null,
    @SerialName("video_thumb") val videoThumb: String? = null,
    val score: Int = 0,
    @SerialName("comment_count") val commentCount: Int = 0,
    val pinned: Boolean = false,
    val sponsored: Boolean = false,
    @SerialName("created_at") val createdAt: String,
    /** The poster's name, or the channel's name for a video. */
    @SerialName("shown_name") val shownName: String? = null,
) {
    val isVideo get() = kind == "video"
}

@Serializable data class Who(val username: String)

@Serializable
data class CComment(
    val id: String,
    @SerialName("post_id") val postId: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("parent_id") val parentId: String? = null,
    val body: String,
    @SerialName("created_at") val createdAt: String,
    val profiles: Who? = null,
)

@Serializable
data class Profile(
    val id: String,
    val username: String,
    @SerialName("is_staff") val isStaff: Boolean = false,
    val banned: Boolean = false,
)

/** A signed-in session on oldgroundapp.com. */
@Serializable
private data class Session(val token: String, val userId: String)

@Serializable private data class Me(val id: String, val profile: Profile? = null, val blocked: List<String> = emptyList())
@Serializable private data class Verified(val token: String, val user: SessionUser, val profile: Profile? = null)
@Serializable private data class SessionUser(val id: String, val email: String? = null)

enum class Sort(val label: String) { HOT("Hot"), NEW("New"), TOP("Top") }

/** What a found video link points at. */
data class VideoInfo(val url: String, val provider: String, val id: String?, val title: String?, val channel: String?, val thumb: String?)

class CommunityError(message: String) : Exception(message)

/**
 * The OldGround community, on oldgroundapp.com (Cloudflare): sign in with an emailed code,
 * read the feed, post finds and videos, upvote, comment, report and block.
 */
object Community {
    /** The website, which also serves the Community API (/api/...) and photos (/photos/...). */
    const val SITE = "https://oldgroundapp.com"
    const val URL_BASE = SITE

    val REPORT_REASONS = listOf("Spam or advertising", "Not about metal detecting", "Shows a private location", "Rude or offensive", "Stolen photo or video", "Something else")
    val STATES = listOf(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
        "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
        "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
    )
    private val STATE_NAMES = mapOf(
        "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas", "CA" to "California", "CO" to "Colorado",
        "CT" to "Connecticut", "DE" to "Delaware", "FL" to "Florida", "GA" to "Georgia", "HI" to "Hawaii", "ID" to "Idaho",
        "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas", "KY" to "Kentucky", "LA" to "Louisiana",
        "ME" to "Maine", "MD" to "Maryland", "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota", "MS" to "Mississippi",
        "MO" to "Missouri", "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada", "NH" to "New Hampshire", "NJ" to "New Jersey",
        "NM" to "New Mexico", "NY" to "New York", "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio", "OK" to "Oklahoma",
        "OR" to "Oregon", "PA" to "Pennsylvania", "RI" to "Rhode Island", "SC" to "South Carolina", "SD" to "South Dakota",
        "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah", "VT" to "Vermont", "VA" to "Virginia", "WA" to "Washington",
        "WV" to "West Virginia", "WI" to "Wisconsin", "WY" to "Wyoming",
    )
    fun stateName(code: String?) = code?.let { STATE_NAMES[it] ?: it }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private lateinit var prefs: SharedPreferences
    private var session: Session? = null

    private val _me = MutableStateFlow<Profile?>(null)
    /** The signed-in member, once they've picked a username. */
    val me: StateFlow<Profile?> = _me
    private val _signedIn = MutableStateFlow(false)
    /** Signed in (may still need a username). */
    val signedIn: StateFlow<Boolean> = _signedIn
    private val _blocked = MutableStateFlow<Set<String>>(emptySet())
    val blocked: StateFlow<Set<String>> = _blocked

    /** Screenshots on the test emulator use made-up posts and never touch the server. */
    var demo = false
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("community", Context.MODE_PRIVATE)
        // Sessions from before the move to oldgroundapp.com don't work there: sign in again.
        session = prefs.getString("session", null)?.let { runCatching { json.decodeFromString<Session>(it) }.getOrNull() }
        if (session == null) prefs.edit().remove("session").remove("me").apply()
        _signedIn.value = session != null
        if (session != null) prefs.getString("me", null)?.let { s -> _me.value = runCatching { json.decodeFromString<Profile>(s) }.getOrNull() }
    }

    fun startDemo() {
        demo = true
        _signedIn.value = true
        _me.value = Profile("demo-me", "OldGround", isStaff = true)
    }

    val myId get() = session?.userId?.ifBlank { null } ?: _me.value?.id

    // ---------- HTTP ----------

    private suspend fun call(method: String, path: String, body: String? = null, bytes: ByteArray? = null, contentType: String = "application/json"): String =
        withContext(Dispatchers.IO) {
            val c = URL(URL_BASE + path).openConnection() as HttpURLConnection
            c.requestMethod = method // Android's HttpURLConnection accepts PATCH
            c.connectTimeout = 15000
            c.readTimeout = 30000
            session?.let { c.setRequestProperty("Authorization", "Bearer ${it.token}") }
            c.setRequestProperty("Accept", "application/json")
            val payload = bytes ?: body?.toByteArray()
            if (payload != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(payload) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
            c.disconnect()
            if (code == 401 && session != null) withContext(Dispatchers.Main) { clearSession() }
            if (code !in 200..299) throw CommunityError(errorText(code, text))
            text
        }

    private fun errorText(code: Int, text: String): String {
        val m = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
        return when {
            m != null -> m
            code == 429 -> "Too many tries. Wait a minute and try again."
            else -> "Something went wrong ($code). Check your connection and try again."
        }
    }

    private fun saveSession(s: Session) {
        session = s
        prefs.edit().putString("session", json.encodeToString(Session.serializer(), s)).apply()
        _signedIn.value = true
    }

    private fun clearSession() {
        session = null
        prefs.edit().remove("session").remove("me").apply()
        _signedIn.value = false
        _me.value = null
        _blocked.value = emptySet()
    }

    // ---------- signing in ----------

    /** Emails a sign-in code (and makes the account the first time). */
    suspend fun sendCode(email: String) {
        if (demo) return
        call("POST", "/api/auth/code", buildJsonObject { put("email", email.trim()) }.toString())
    }

    /** Checks the emailed code. Returns true when this person still needs to pick a username. */
    suspend fun verify(email: String, code: String): Boolean {
        if (demo) return false
        val v = json.decodeFromString<Verified>(call("POST", "/api/auth/verify", buildJsonObject { put("email", email.trim()); put("code", code.trim()) }.toString()))
        saveSession(Session(v.token, v.user.id))
        return loadMe() == null
    }

    /** Loads this member's profile and block list; null when they have no username yet. */
    suspend fun loadMe(): Profile? {
        if (demo) return _me.value
        if (session == null) return null
        val m = json.decodeFromString<Me>(call("GET", "/api/me"))
        setMe(m.profile)
        _blocked.value = m.blocked.toSet()
        return m.profile
    }

    private fun setMe(p: Profile?) {
        _me.value = p
        if (p == null) prefs.edit().remove("me").apply() else prefs.edit().putString("me", json.encodeToString(Profile.serializer(), p)).apply()
    }

    suspend fun pickUsername(name: String) {
        val clean = name.trim()
        if (!Regex("^[A-Za-z0-9_]{3,20}$").matches(clean)) throw CommunityError("Use 3 to 20 letters, numbers or underscores.")
        if (demo) { _me.value = Profile("demo-me", clean); return }
        if (session == null) throw CommunityError("Sign in first.")
        setMe(json.decodeFromString<Profile>(call("POST", "/api/me/username", buildJsonObject { put("username", clean) }.toString())))
    }

    fun signOut() {
        if (demo) return
        clearSession()
    }

    /** Deletes the account and everything it posted, photos included. */
    suspend fun deleteAccount() {
        if (demo) return
        call("DELETE", "/api/me")
        clearSession()
    }

    // ---------- reading ----------

    suspend fun feed(sort: Sort, filter: String?): List<CPost> {
        if (demo) return Demo.posts.filter { filter == null || (filter == "Videos" && it.isVideo) || it.category == filter }
            .let { l -> when (sort) { Sort.NEW -> l.sortedByDescending { it.createdAt }; Sort.TOP -> l.sortedByDescending { it.score }; Sort.HOT -> l } }
        val f = filter?.let { "&type=${enc(it)}" } ?: ""
        val list = json.decodeFromString<List<CPost>>(call("GET", "/api/posts?sort=${sort.name.lowercase()}&limit=60$f"))
        val b = _blocked.value
        return list.filter { it.authorId !in b }
    }

    suspend fun post(id: String): CPost? {
        if (demo) return Demo.posts.firstOrNull { it.id == id }
        return try {
            json.decodeFromString<CPost>(call("GET", "/api/posts/${enc(id)}"))
        } catch (e: CommunityError) {
            if (e.message?.contains("gone") == true) null else throw e
        }
    }

    suspend fun comments(postId: String): List<CComment> {
        if (demo) return Demo.comments.filter { it.postId == postId }
        val list = json.decodeFromString<List<CComment>>(call("GET", "/api/posts/${enc(postId)}/comments"))
        val b = _blocked.value
        return list.filter { it.authorId !in b }
    }

    /** Which of these posts I've upvoted. */
    suspend fun myVotes(ids: List<String>): Set<String> {
        if (demo) return setOf("d1")
        if (ids.isEmpty() || _me.value == null || session == null) return emptySet()
        val o = json.parseToJsonElement(call("GET", "/api/votes?ids=${ids.joinToString(",") { enc(it) }}")).jsonObject
        return (o["ids"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
    }

    // ---------- writing ----------

    private fun needSignIn() { if (session == null) throw CommunityError("Sign in first.") }

    suspend fun vote(postId: String, on: Boolean) {
        if (demo) return
        needSignIn()
        call(if (on) "POST" else "DELETE", "/api/posts/${enc(postId)}/vote")
    }

    suspend fun comment(postId: String, body: String, parentId: String?): CComment {
        if (demo) return CComment("c${System.nanoTime()}", postId, "demo-me", parentId, body, OffsetDateTime.now().toString(), Who(_me.value?.username ?: "you"))
        needSignIn()
        val text = call("POST", "/api/posts/${enc(postId)}/comments", buildJsonObject { put("body", body.trim()); parentId?.let { put("parent_id", it) } }.toString())
        return json.decodeFromString<CComment>(text)
    }

    suspend fun report(postId: String?, commentId: String?, reason: String) {
        if (demo) return
        needSignIn()
        call("POST", "/api/reports", buildJsonObject {
            put("reason", reason)
            postId?.let { put("post_id", it) }; commentId?.let { put("comment_id", it) }
        }.toString())
    }

    suspend fun block(userId: String) {
        _blocked.value = _blocked.value + userId
        if (demo) return
        needSignIn()
        call("POST", "/api/blocks", buildJsonObject { put("blocked_id", userId) }.toString())
    }

    /** Deletes a post (its photos go with it). */
    suspend fun deletePost(id: String) { if (!demo) call("DELETE", "/api/posts/${enc(id)}") }
    suspend fun deleteComment(id: String) { if (!demo) call("DELETE", "/api/comments/${enc(id)}") }

    /** Staff only: take a post down or put it back. */
    suspend fun setRemoved(id: String, removed: Boolean) { if (!demo) call("PATCH", "/api/posts/${enc(id)}", """{"removed":$removed}""") }
    /** Staff only: keep a post at the top of Hot. */
    suspend fun setPinned(id: String, pinned: Boolean) { if (!demo) call("PATCH", "/api/posts/${enc(id)}", """{"pinned":$pinned}""") }

    /** Posts a find: uploads the photos (already shrunk and free of GPS data), then the post. Returns its id. */
    suspend fun postFind(
        title: String, body: String, category: String?, material: String?, depthIn: Double?, detector: String?, state: String?, photos: List<File>,
    ): String {
        if (demo) return "d1"
        needSignIn()
        if (photos.isEmpty()) throw CommunityError("Add at least one photo.")
        val paths = photos.take(6).map { f ->
            json.parseToJsonElement(call("POST", "/api/photos", bytes = f.readBytes(), contentType = "image/jpeg")).jsonObject["path"]!!.jsonPrimitive.content
        }
        val o = buildJsonObject {
            put("kind", "find"); put("title", title.trim())
            if (body.isNotBlank()) put("body", body.trim())
            category?.let { put("category", it) }; material?.let { put("material", it) }
            depthIn?.let { put("depth_in", it) }; detector?.takeIf { it.isNotBlank() }?.let { put("detector", it) }
            state?.let { put("state", it) }
            putJsonArray("photos") { paths.forEach { add(it) } }
        }
        return insertPost(o)
    }

    /** Posts a YouTube or Facebook video link. Returns its id. */
    suspend fun postVideo(v: VideoInfo, title: String, body: String): String {
        if (demo) return "d2"
        needSignIn()
        val o = buildJsonObject {
            put("kind", "video"); put("title", title.trim())
            if (body.isNotBlank()) put("body", body.trim())
            put("video_url", v.url); put("video_provider", v.provider)
            v.id?.let { put("video_id", it) }; v.channel?.let { put("video_channel", it) }; v.thumb?.let { put("video_thumb", it) }
        }
        return insertPost(o)
    }

    private suspend fun insertPost(o: JsonObject): String =
        json.parseToJsonElement(call("POST", "/api/posts", o.toString())).jsonObject["id"]!!.jsonPrimitive.content

    // ---------- videos ----------

    fun youtubeId(url: String): String? {
        val u = url.trim()
        Regex("""(?:youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|embed/|live/)|youtu\.be/)([A-Za-z0-9_-]{11})""").find(u)?.let { return it.groupValues[1] }
        return null
    }

    /** Reads the title and channel of a video link (YouTube's public oEmbed; no key needed). */
    suspend fun lookUpVideo(url: String): VideoInfo = withContext(Dispatchers.IO) {
        val u = url.trim()
        val yt = youtubeId(u)
        when {
            yt != null -> {
                val canonical = "https://www.youtube.com/watch?v=$yt"
                val meta = runCatching {
                    val c = URL("https://www.youtube.com/oembed?format=json&url=${enc(canonical)}").openConnection() as HttpURLConnection
                    c.connectTimeout = 10000; c.readTimeout = 10000
                    c.inputStream.use { json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
                }.getOrNull()
                VideoInfo(
                    canonical, "youtube", yt,
                    meta?.get("title")?.jsonPrimitive?.content, meta?.get("author_name")?.jsonPrimitive?.content,
                    "https://i.ytimg.com/vi/$yt/hqdefault.jpg",
                )
            }
            Regex("""^https?://(www\.|m\.|web\.)?(facebook\.com|fb\.watch)/""").containsMatchIn(u) -> VideoInfo(u, "facebook", null, null, null, null)
            else -> throw CommunityError("Paste a YouTube or Facebook video link.")
        }
    }

    // ---------- helpers ----------

    fun photoUrl(path: String) = "$SITE/photos/$path"

    /** "2h", "3d", "Oct 4". */
    fun ago(iso: String): String = runCatching {
        val t = OffsetDateTime.parse(iso)
        val d = Duration.between(t, OffsetDateTime.now())
        when {
            d.toMinutes() < 1 -> "now"
            d.toMinutes() < 60 -> "${d.toMinutes()}m"
            d.toHours() < 24 -> "${d.toHours()}h"
            d.toDays() < 7 -> "${d.toDays()}d"
            else -> t.toLocalDate().let { "${it.month.name.take(3).lowercase().replaceFirstChar(Char::uppercase)} ${it.dayOfMonth}" }
        }
    }.getOrDefault("")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/** Made-up posts for the test emulator's screenshots (photos are the test photos). */
private object Demo {
    private fun at(h: Long) = OffsetDateTime.now().minusHours(h).toString()
    val posts = listOf(
        CPost("d1", "demo-me", "find", "Seated half dime on the plow line behind an old house site", "Came up on the edge of the plowed field, about 40 yards behind where the house stood. Soft high tone, 6 inches.",
            "Coin", "Silver", 6.0, "XP Deus II", "OH", listOf("local:ci-dime.jpg", "local:ci-tray1.jpg"), score = 24, commentCount = 2, pinned = true, createdAt = at(2), shownName = "OldGround"),
        CPost("d2", "demo-me", "video", "A day on an 1840s farmstead", null, videoUrl = "https://www.youtube.com/watch?v=xxxxxxxxxxx", videoProvider = "youtube",
            videoChannel = "Sample channel", score = 11, commentCount = 0, createdAt = at(5), shownName = "Sample channel"),
        CPost("d3", "demo-2", "find", "Flat button and a large cent from the same hole", null, "Button", "Copper", 8.0, null, "PA",
            listOf("local:ci-button.jpg", "local:ci-cent.jpg"), score = 8, commentCount = 1, createdAt = at(26), shownName = "ridge_digger"),
        CPost("d4", "demo-3", "find", "Ring from the old swimming hole", null, "Jewelry", "Gold", 4.0, null, "IN",
            listOf("local:ci-ring.jpg"), score = 5, createdAt = at(50), shownName = "creekbed_kate"),
    )
    val comments = listOf(
        CComment("c1", "d1", "demo-2", null, "Great find. Was it near the old lane or out in the field?", at(1), Who("ridge_digger")),
        CComment("c2", "d1", "demo-me", "c1", "Out in the field, about where the garden would have been.", at(0), Who("OldGround")),
        CComment("c3", "d3", "demo-me", null, "Nice pair.", at(20), Who("OldGround")),
    )
}

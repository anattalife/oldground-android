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

@Serializable
private data class Session(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    val user: SessionUser? = null,
    /** Filled in here: when the access token runs out (unix seconds). */
    val expiresAtSec: Long = 0,
    val userId: String = "",
)

@Serializable private data class SessionUser(val id: String, val email: String? = null)

enum class Sort(val label: String) { HOT("Hot"), NEW("New"), TOP("Top") }

/** What a found video link points at. */
data class VideoInfo(val url: String, val provider: String, val id: String?, val title: String?, val channel: String?, val thumb: String?)

class CommunityError(message: String) : Exception(message)

/**
 * The OldGround community, on Supabase: sign in with an emailed code, read the feed,
 * post finds and videos, upvote, comment, report and block.
 */
object Community {
    const val URL_BASE = "https://kxppiixlbnoytxnkipvh.supabase.co"
    private const val KEY = "sb_publishable_qAm1vsQ-eB9gKAjoMZcEng_eiJ1Lf9c"
    /** Where the website shows a post, once it exists (blank: share text only). */
    const val SITE = "https://oldgroundapp.com"

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
        session = prefs.getString("session", null)?.let { runCatching { json.decodeFromString<Session>(it) }.getOrNull() }
        _signedIn.value = session != null
        prefs.getString("me", null)?.let { s -> _me.value = runCatching { json.decodeFromString<Profile>(s) }.getOrNull() }
    }

    fun startDemo() {
        demo = true
        _signedIn.value = true
        _me.value = Profile("demo-me", "OldGround", isStaff = true)
    }

    val myId get() = session?.userId?.ifBlank { null } ?: _me.value?.id

    // ---------- HTTP ----------

    private suspend fun call(method: String, path: String, body: String? = null, bytes: ByteArray? = null, contentType: String = "application/json", prefer: String? = null, signedIn: Boolean = true): String =
        withContext(Dispatchers.IO) {
            val token = if (signedIn) accessToken() else null
            val c = URL(URL_BASE + path).openConnection() as HttpURLConnection
            c.requestMethod = method // Android's HttpURLConnection accepts PATCH
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("apikey", KEY)
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/json")
            prefer?.let { c.setRequestProperty("Prefer", it) }
            val payload = bytes ?: body?.toByteArray()
            if (payload != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(payload) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
            c.disconnect()
            if (code !in 200..299) throw CommunityError(errorText(code, text))
            text
        }

    private fun errorText(code: Int, text: String): String {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val m = o?.let { it["msg"] ?: it["message"] ?: it["error_description"] ?: it["error"] }?.jsonPrimitive?.content
        return when {
            code == 429 -> "Too many tries. Wait a minute and try again."
            m?.contains("duplicate key") == true && m.contains("username") -> "That name is taken. Try another."
            m?.contains("profiles_username_lower") == true -> "That name is taken. Try another."
            m?.contains("expired") == true || m?.contains("invalid", true) == true && text.contains("otp", true) -> "That code didn't work. Check it, or send a new one."
            m != null -> m
            else -> "Something went wrong ($code). Check your connection and try again."
        }
    }

    private suspend fun accessToken(): String? {
        val s = session ?: return null
        if (s.expiresAtSec - 60 > System.currentTimeMillis() / 1000) return s.accessToken
        return runCatching {
            val text = call("POST", "/auth/v1/token?grant_type=refresh_token", """{"refresh_token":"${s.refreshToken}"}""", signedIn = false)
            saveSession(json.decodeFromString<Session>(text)).accessToken
        }.getOrElse {
            if (it is CommunityError) { clearSession(); null } else s.accessToken
        }
    }

    private fun saveSession(s: Session): Session {
        val keep = s.copy(expiresAtSec = System.currentTimeMillis() / 1000 + s.expiresIn, userId = s.user?.id ?: session?.userId ?: "")
        session = keep
        prefs.edit().putString("session", json.encodeToString(Session.serializer(), keep)).apply()
        _signedIn.value = true
        return keep
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
        call("POST", "/auth/v1/otp", buildJsonObject { put("email", email.trim()); put("create_user", true) }.toString(), signedIn = false)
    }

    /** Checks the emailed code. Returns true when this person still needs to pick a username. */
    suspend fun verify(email: String, code: String): Boolean {
        if (demo) return false
        val text = call("POST", "/auth/v1/verify", buildJsonObject { put("type", "email"); put("email", email.trim()); put("token", code.trim()) }.toString(), signedIn = false)
        saveSession(json.decodeFromString<Session>(text))
        return loadMe() == null
    }

    /** Loads this member's profile and block list; null when they have no username yet. */
    suspend fun loadMe(): Profile? {
        if (demo) return _me.value
        val id = myId ?: return null
        val p = json.decodeFromString<List<Profile>>(call("GET", "/rest/v1/profiles?select=*&id=eq.$id")).firstOrNull()
        setMe(p)
        if (p != null) {
            val rows = json.parseToJsonElement(call("GET", "/rest/v1/blocks?select=blocked_id&blocker_id=eq.$id"))
            _blocked.value = (rows as kotlinx.serialization.json.JsonArray).map { it.jsonObject["blocked_id"]!!.jsonPrimitive.content }.toSet()
        }
        return p
    }

    private fun setMe(p: Profile?) {
        _me.value = p
        if (p == null) prefs.edit().remove("me").apply() else prefs.edit().putString("me", json.encodeToString(Profile.serializer(), p)).apply()
    }

    suspend fun pickUsername(name: String) {
        val clean = name.trim()
        if (!Regex("^[A-Za-z0-9_]{3,20}$").matches(clean)) throw CommunityError("Use 3 to 20 letters, numbers or underscores.")
        if (demo) { _me.value = Profile("demo-me", clean); return }
        val id = myId ?: throw CommunityError("Sign in first.")
        val text = call("POST", "/rest/v1/profiles", buildJsonObject { put("id", id); put("username", clean) }.toString(), prefer = "return=representation")
        setMe(json.decodeFromString<List<Profile>>(text).first())
    }

    fun signOut() {
        if (demo) return
        clearSession()
    }

    /** Deletes the account and everything it posted, photos included. */
    suspend fun deleteAccount() {
        if (demo) return
        myId?.let { me ->
            runCatching {
                val body = buildJsonObject { put("prefix", me); put("limit", 1000) }.toString()
                val list = json.parseToJsonElement(call("POST", "/storage/v1/object/list/post-photos", body))
                deletePhotos((list as kotlinx.serialization.json.JsonArray).map { "$me/" + it.jsonObject["name"]!!.jsonPrimitive.content })
            }
        }
        call("POST", "/rest/v1/rpc/delete_my_account", "{}")
        clearSession()
    }

    private suspend fun deletePhotos(paths: List<String>) {
        for (p in paths) runCatching { call("DELETE", "/storage/v1/object/post-photos/$p") }
    }

    // ---------- reading ----------

    suspend fun feed(sort: Sort, filter: String?): List<CPost> {
        if (demo) return Demo.posts.filter { filter == null || (filter == "Videos" && it.isVideo) || it.category == filter }
            .let { l -> when (sort) { Sort.NEW -> l.sortedByDescending { it.createdAt }; Sort.TOP -> l.sortedByDescending { it.score }; Sort.HOT -> l } }
        val order = when (sort) {
            Sort.HOT -> "pinned.desc,hot.desc"
            Sort.NEW -> "created_at.desc"
            Sort.TOP -> "score.desc,created_at.desc"
        }
        val f = when (filter) {
            null -> ""
            "Videos" -> "&kind=eq.video"
            else -> "&category=eq.${enc(filter)}"
        }
        val list = json.decodeFromString<List<CPost>>(call("GET", "/rest/v1/feed?select=*&order=$order&limit=60$f", signedIn = session != null))
        val b = _blocked.value
        return list.filter { it.authorId !in b }
    }

    suspend fun post(id: String): CPost? {
        if (demo) return Demo.posts.firstOrNull { it.id == id }
        return json.decodeFromString<List<CPost>>(call("GET", "/rest/v1/feed?select=*&id=eq.$id", signedIn = session != null)).firstOrNull()
    }

    suspend fun comments(postId: String): List<CComment> {
        if (demo) return Demo.comments.filter { it.postId == postId }
        val list = json.decodeFromString<List<CComment>>(
            call("GET", "/rest/v1/comments?select=id,post_id,author_id,parent_id,body,created_at,profiles(username)&post_id=eq.$postId&removed=eq.false&order=created_at.asc", signedIn = session != null),
        )
        val b = _blocked.value
        return list.filter { it.authorId !in b }
    }

    /** Which of these posts I've upvoted. */
    suspend fun myVotes(ids: List<String>): Set<String> {
        if (demo) return setOf("d1")
        val me = myId ?: return emptySet()
        if (ids.isEmpty() || _me.value == null) return emptySet()
        val rows = json.parseToJsonElement(call("GET", "/rest/v1/votes?select=post_id&user_id=eq.$me&post_id=in.(${ids.joinToString(",")})"))
        return (rows as kotlinx.serialization.json.JsonArray).map { it.jsonObject["post_id"]!!.jsonPrimitive.content }.toSet()
    }

    // ---------- writing ----------

    suspend fun vote(postId: String, on: Boolean) {
        if (demo) return
        val me = myId ?: throw CommunityError("Sign in first.")
        if (on) call("POST", "/rest/v1/votes", buildJsonObject { put("post_id", postId); put("user_id", me) }.toString(), prefer = "resolution=ignore-duplicates")
        else call("DELETE", "/rest/v1/votes?post_id=eq.$postId&user_id=eq.$me")
    }

    suspend fun comment(postId: String, body: String, parentId: String?): CComment {
        if (demo) return CComment("c${System.nanoTime()}", postId, "demo-me", parentId, body, OffsetDateTime.now().toString(), Who(_me.value?.username ?: "you"))
        val me = myId ?: throw CommunityError("Sign in first.")
        val text = call(
            "POST", "/rest/v1/comments?select=id,post_id,author_id,parent_id,body,created_at,profiles(username)",
            buildJsonObject { put("post_id", postId); put("author_id", me); put("body", body.trim()); parentId?.let { put("parent_id", it) } }.toString(),
            prefer = "return=representation",
        )
        return json.decodeFromString<List<CComment>>(text).first()
    }

    suspend fun report(postId: String?, commentId: String?, reason: String) {
        if (demo) return
        val me = myId ?: throw CommunityError("Sign in first.")
        call("POST", "/rest/v1/reports", buildJsonObject {
            put("reporter_id", me); put("reason", reason)
            postId?.let { put("post_id", it) }; commentId?.let { put("comment_id", it) }
        }.toString())
    }

    suspend fun block(userId: String) {
        _blocked.value = _blocked.value + userId
        if (demo) return
        val me = myId ?: throw CommunityError("Sign in first.")
        call("POST", "/rest/v1/blocks", buildJsonObject { put("blocker_id", me); put("blocked_id", userId) }.toString(), prefer = "resolution=ignore-duplicates")
    }

    suspend fun deletePost(id: String) {
        if (demo) return
        val photos = runCatching { post(id)?.photos }.getOrNull().orEmpty()
        call("DELETE", "/rest/v1/posts?id=eq.$id")
        deletePhotos(photos)
    }
    suspend fun deleteComment(id: String) { if (!demo) call("DELETE", "/rest/v1/comments?id=eq.$id") }

    /** Staff only: take a post down or put it back. */
    suspend fun setRemoved(id: String, removed: Boolean) { if (!demo) call("PATCH", "/rest/v1/posts?id=eq.$id", """{"removed":$removed}""") }
    /** Staff only: keep a post at the top of Hot. */
    suspend fun setPinned(id: String, pinned: Boolean) { if (!demo) call("PATCH", "/rest/v1/posts?id=eq.$id", """{"pinned":$pinned}""") }

    /** Posts a find: uploads the photos (already shrunk and free of GPS data), then the post. Returns its id. */
    suspend fun postFind(
        title: String, body: String, category: String?, material: String?, depthIn: Double?, detector: String?, state: String?, photos: List<File>,
    ): String {
        if (demo) return "d1"
        val me = myId ?: throw CommunityError("Sign in first.")
        if (photos.isEmpty()) throw CommunityError("Add at least one photo.")
        val paths = photos.take(6).mapIndexed { i, f ->
            val path = "$me/${System.currentTimeMillis()}-$i.jpg"
            call("POST", "/storage/v1/object/post-photos/$path", bytes = f.readBytes(), contentType = "image/jpeg")
            path
        }
        val o = buildJsonObject {
            put("author_id", me); put("kind", "find"); put("title", title.trim())
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
        val me = myId ?: throw CommunityError("Sign in first.")
        val o = buildJsonObject {
            put("author_id", me); put("kind", "video"); put("title", title.trim())
            if (body.isNotBlank()) put("body", body.trim())
            put("video_url", v.url); put("video_provider", v.provider)
            v.id?.let { put("video_id", it) }; v.channel?.let { put("video_channel", it) }; v.thumb?.let { put("video_thumb", it) }
        }
        return insertPost(o)
    }

    private suspend fun insertPost(o: JsonObject): String {
        val text = call("POST", "/rest/v1/posts?select=id", o.toString(), prefer = "return=representation")
        return json.parseToJsonElement(text).let { (it as kotlinx.serialization.json.JsonArray).first().jsonObject["id"]!!.jsonPrimitive.content }
    }

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

    fun photoUrl(path: String) = "$URL_BASE/storage/v1/object/public/post-photos/$path"

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

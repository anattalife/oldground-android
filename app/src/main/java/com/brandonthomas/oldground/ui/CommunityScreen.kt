package com.brandonthomas.oldground.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.brandonthomas.oldground.Nav
import com.brandonthomas.oldground.Screen
import com.brandonthomas.oldground.community.CComment
import com.brandonthomas.oldground.community.CPost
import com.brandonthomas.oldground.community.Community
import com.brandonthomas.oldground.community.Sort
import com.brandonthomas.oldground.community.VideoInfo
import com.brandonthomas.oldground.data.FIND_CATEGORIES
import com.brandonthomas.oldground.data.FIND_MATERIALS
import com.brandonthomas.oldground.data.PinKind
import com.brandonthomas.oldground.data.Photos
import com.brandonthomas.oldground.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BROWN = Color(0xFF5E4A35)
private val VIDEO_BG = Color(0xFF2B3A36)

/** Signed in with a username? If not, open the join page and say no. */
private fun member(nav: Nav): Boolean {
    if (Community.me.value != null) return true
    nav.go(Screen.JoinPage())
    return false
}

// ======================= feed =======================

@Composable
fun CommunityRoot(nav: Nav, debug: String? = null) {
    val scope = rememberCoroutineScope()
    var sort by rememberSaveable { mutableStateOf(Sort.HOT) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var posts by remember { mutableStateOf<List<CPost>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var pickFilter by remember { mutableStateOf(debug == "filter") }
    val votes = remember { mutableStateMapOf<String, Boolean>() }
    val blocked by Community.blocked.collectAsState()

    // Refresh who I am (e.g. made staff, or blocked someone on another phone).
    LaunchedEffect(Unit) { if (Community.signedIn.value && !Community.demo) runCatching { Community.loadMe() } }
    LaunchedEffect(sort, filter, tick, blocked) {
        error = null
        runCatching { Community.feed(sort, filter) }
            .onSuccess { list ->
                posts = list
                runCatching { Community.myVotes(list.map { it.id }) }.onSuccess { mine -> list.forEach { votes[it.id] = it.id in mine } }
            }
            .onFailure { if (posts == null) error = it.message }
    }

    Column(Modifier.fillMaxSize().background(C.bg)) {
        TabHeader("Community", nav) {
            Pill("+ Post", { if (member(nav)) nav.go(Screen.Compose()) }, kind = "primary", small = true)
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (s in Sort.entries) SortChip(s.label, sort == s) { sort = s }
            Spacer(Modifier.weight(1f))
            Text(
                (filter ?: "All posts") + " ▾",
                Modifier.clip(RoundedCornerShape(50)).border(1.dp, C.line, RoundedCornerShape(50)).background(C.panel).clickable { pickFilter = true }.padding(horizontal = 11.dp, vertical = 6.dp),
                fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = C.ink,
            )
        }
        val list = posts
        when {
            list == null && error != null -> Message("Can't load the community", error!!, "Try again") { tick++ }
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.accent) }
            list.isEmpty() -> Message(
                if (filter == null) "Nothing here yet" else "No ${filter!!.lowercase()} yet",
                "Share a find you're proud of, or a detecting video worth watching.", "+ Post",
            ) { if (member(nav)) nav.go(Screen.Compose()) }
            else -> LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 90.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { p ->
                    val voted = votes[p.id] == true
                    PostCard(
                        p, voted,
                        onOpen = { nav.go(Screen.CommunityPost(p.id)) },
                        onVote = {
                            if (member(nav)) {
                                val on = !voted
                                votes[p.id] = on
                                posts = posts?.map { if (it.id == p.id) it.copy(score = it.score + if (on) 1 else -1) else it }
                                scope.launch { runCatching { Community.vote(p.id, on) }.onFailure { votes[p.id] = !on; tick++ } }
                            }
                        },
                    )
                }
            }
        }
    }
    if (pickFilter) ChoiceSheet(
        "Show",
        listOf<String?>(null, "Coin", "Relic", "Button", "Jewelry", "Token", "Videos").map { f -> Choice(f ?: "All posts", filter == f) { filter = f } },
    ) { pickFilter = false }
}

@Composable
private fun SortChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clip(RoundedCornerShape(50)).background(if (on) C.accentSoft else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        fontSize = 13.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold, color = if (on) C.accent else C.muted,
    )
}

@Composable
private fun Message(title: String, text: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
        Text(text, fontSize = 14.sp, color = C.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Pill(action, onAction, kind = "tint", small = true)
    }
}

@Composable
private fun PostCard(p: CPost, voted: Boolean, onOpen: () -> Unit, onVote: () -> Unit) {
    Column(Modifier.fillMaxWidth().card(16, 3).clickable(onClick = onOpen)) {
        Byline(p, Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp))
        Text(p.title, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp), fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = C.ink, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (p.isVideo) VideoBox(p, Modifier.fillMaxWidth().height(170.dp))
        else p.photos.firstOrNull()?.let { PostPhoto(it, Modifier.fillMaxWidth().height(200.dp)) }
        FindChips(p, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            VoteButton(p.score, voted, onVote)
            Row(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpen).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ChatIcon(C.muted)
                Text("${p.commentCount}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.muted)
            }
            if (p.pinned) {
                Spacer(Modifier.weight(1f))
                Text("Pinned", Modifier.padding(end = 8.dp), fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, color = C.accent)
            }
        }
    }
}

@Composable
private fun Byline(p: CPost, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Avatar(p.shownName ?: "", video = p.isVideo, staff = p.shownName == "OldGround")
        Text(p.shownName ?: "", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        val where = if (p.isVideo) (if (p.videoProvider == "facebook") "Facebook" else "YouTube") else Community.stateName(p.state)
        Text(listOfNotNull(where, Community.ago(p.createdAt)).joinToString(" · ", prefix = "· "), fontSize = 12.sp, color = C.muted, maxLines = 1)
    }
}

@Composable
private fun Avatar(name: String, video: Boolean = false, staff: Boolean = false, size: Int = 26) {
    val bg = when { video -> Color(0xFFC0392B); staff -> C.accent; else -> Color(0xFF8A7458) }
    Box(Modifier.size(size.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        when {
            video -> PlayIcon(Color.White, (size * 0.45f).dp)
            staff -> GlyphIcon(Glyph.MAP, Color.White, Modifier.size((size * 0.58f).dp))
            else -> Text(name.take(1).uppercase(), fontSize = (size * 0.48f).sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
    }
}

@Composable
private fun FindChips(p: CPost, modifier: Modifier = Modifier) {
    val metric = Repo.current.settings.metric
    val chips = listOfNotNull(
        p.category, p.material,
        p.depthIn?.let { if (metric) "${(it * 2.54).toInt()} cm" else "${if (it % 1.0 == 0.0) it.toInt() else it} in" },
    )
    if (chips.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (c in chips) SmallChip(c) }
}

@Composable
private fun SmallChip(text: String) {
    Text(text, Modifier.clip(RoundedCornerShape(50)).background(C.soft).padding(horizontal = 9.dp, vertical = 3.dp), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = C.muted)
}

@Composable
private fun VoteButton(score: Int, voted: Boolean, onVote: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (voted) C.accentSoft else Color.Transparent).clickable(onClickLabel = if (voted) "Take back upvote" else "Upvote", onClick = onVote)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        UpIcon(if (voted) C.accent else C.muted)
        Text("$score", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = if (voted) C.accent else C.muted)
    }
}

@Composable
private fun photoModel(path: String): Any {
    val ctx = LocalContext.current
    return if (path.startsWith("local:")) Photos.file(ctx, path.removePrefix("local:")) else Community.photoUrl(path)
}

@Composable
private fun PostPhoto(path: String, modifier: Modifier) {
    Box(modifier.background(BROWN)) {
        AsyncImage(photoModel(path), contentDescription = "Find photo", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun VideoBox(p: CPost, modifier: Modifier) {
    Box(modifier.background(VIDEO_BG), contentAlignment = Alignment.Center) {
        p.videoThumb?.let { AsyncImage(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0xEBFFFFFF)), contentAlignment = Alignment.Center) { PlayIcon(C.ink, 20.dp) }
    }
}

// ---------- small icons ----------

@Composable
private fun UpIcon(color: Color) = androidx.compose.foundation.Canvas(Modifier.size(14.dp)) {
    val w = size.width; val s = w * 0.15f
    drawLine(color, androidx.compose.ui.geometry.Offset(w / 2, w * 0.88f), androidx.compose.ui.geometry.Offset(w / 2, w * 0.14f), s, androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.16f, w * 0.46f), androidx.compose.ui.geometry.Offset(w / 2, w * 0.12f), s, androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.84f, w * 0.46f), androidx.compose.ui.geometry.Offset(w / 2, w * 0.12f), s, androidx.compose.ui.graphics.StrokeCap.Round)
}

@Composable
private fun ChatIcon(color: Color) = GlyphIcon(Glyph.CHAT, color, Modifier.size(16.dp))

@Composable
private fun PlayIcon(color: Color, size: androidx.compose.ui.unit.Dp) = androidx.compose.foundation.Canvas(Modifier.size(size)) {
    val w = this.size.width
    drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.28f, w * 0.15f); lineTo(w * 0.88f, w * 0.5f); lineTo(w * 0.28f, w * 0.85f); close() }, color)
}

// ======================= one post =======================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommunityPostScreen(nav: Nav, id: String, debug: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by Community.me.collectAsState()
    var post by remember { mutableStateOf<CPost?>(null) }
    var missing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val comments = remember { mutableStateListOf<CComment>() }
    var voted by remember { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<CComment?>(null) }
    var sending by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(debug == "menu") }
    var commentMenu by remember { mutableStateOf<CComment?>(null) }
    var reporting by remember { mutableStateOf<Pair<String?, String?>?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(id, tick) {
        runCatching {
            val p = Community.post(id)
            if (p == null) missing = true else {
                post = p
                comments.clear(); comments.addAll(Community.comments(id))
                voted = runCatching { id in Community.myVotes(listOf(id)) }.getOrDefault(false)
            }
        }.onFailure { error = it.message }
    }

    val p = post
    Column(Modifier.fillMaxSize().background(C.panel).imePadding()) {
        if (p == null) {
            Row(Modifier.statusBarsPadding().padding(12.dp)) { RoundButton({ nav.back() }, "Back", size = 38.dp) { GlyphIcon(Glyph.BACK, C.ink, Modifier.size(20.dp)) } }
            when {
                missing -> Message("This post is gone", "It was deleted or taken down.", "Back") { nav.back() }
                error != null -> Message("Can't load this post", error!!, "Try again") { error = null; tick++ }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.accent) }
            }
            return@Column
        }
        LazyColumn(Modifier.weight(1f)) {
            item {
                Box {
                    if (p.isVideo) {
                        VideoBox(p, Modifier.fillMaxWidth().height(250.dp).clickable { p.videoUrl?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } })
                    } else {
                        val w = LocalConfiguration.current.screenWidthDp.dp
                        val state = rememberLazyListState()
                        val page by remember { derivedStateOf { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > 0 && state.layoutInfo.visibleItemsInfo.size > 1) 1 else 0 } }
                        LazyRow(state = state, flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state)) {
                            items(p.photos) { ph -> PostPhoto(ph, Modifier.width(w).height(300.dp)) }
                        }
                        if (p.photos.size > 1) Text(
                            "${(page + 1).coerceAtMost(p.photos.size)} / ${p.photos.size}",
                            Modifier.align(Alignment.BottomEnd).padding(12.dp).clip(RoundedCornerShape(50)).background(Color(0xB314201D)).padding(horizontal = 9.dp, vertical = 3.dp),
                            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        RoundButton({ nav.back() }, "Back", size = 38.dp) { GlyphIcon(Glyph.BACK, C.ink, Modifier.size(20.dp)) }
                        RoundButton({ menu = true }, "More: report or block", size = 38.dp) { GlyphIcon(Glyph.MORE, C.ink, Modifier.size(20.dp)) }
                    }
                }
            }
            item {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Byline(p)
                    Text(p.title, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = C.ink, lineHeight = 24.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FindChips(p)
                        p.detector?.let { SmallChip(it) }
                    }
                    p.body?.let { Text(it, fontSize = 14.5.sp, color = Color(0xFF2A3733), lineHeight = 21.sp) }
                    if (p.isVideo) Text("Watch on ${if (p.videoProvider == "facebook") "Facebook" else "YouTube"} ›", Modifier.clickable { p.videoUrl?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }, color = C.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Divider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VoteButton(p.score, voted) {
                            if (member(nav)) {
                                val on = !voted
                                voted = on
                                post = p.copy(score = p.score + if (on) 1 else -1)
                                scope.launch { runCatching { Community.vote(p.id, on) }.onFailure { voted = !on; tick++ } }
                            }
                        }
                        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            ChatIcon(C.muted)
                            Text("${comments.size} comment${if (comments.size == 1) "" else "s"}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.muted)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("Share", Modifier.clip(RoundedCornerShape(50)).clickable {
                            val link = if (Community.SITE.isNotBlank()) "${Community.SITE}/p/${p.id}" else p.videoUrl ?: ""
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, listOf(p.title, link).filter { it.isNotBlank() }.joinToString("\n")), "Share"))
                        }.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.muted)
                    }
                    Divider()
                    if (comments.isEmpty()) Text("No comments yet.", fontSize = 13.5.sp, color = C.muted)
                }
            }
            val top = comments.filter { it.parentId == null || comments.none { c -> c.id == it.parentId } }
            for (c in top) {
                item(key = c.id) { CommentRow(c, 0, onReply = { if (member(nav)) replyTo = c }, onMore = { commentMenu = c }) }
                for (r in comments.filter { it.parentId == c.id }) item(key = r.id) { CommentRow(r, 1, onReply = { if (member(nav)) replyTo = c }, onMore = { commentMenu = r }) }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        // comment box
        Column(Modifier.fillMaxWidth().background(C.panel)) {
            Divider()
            replyTo?.let { r ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Replying to ${r.profiles?.username ?: "comment"}", Modifier.weight(1f), fontSize = 12.5.sp, color = C.muted)
                    Text("Cancel", Modifier.clickable { replyTo = null }.padding(6.dp), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = C.accent)
                }
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text, { text = it.take(2000) }, Modifier.weight(1f),
                    placeholder = { Text(if (me == null) "Join to comment" else "Add a comment", fontSize = 14.sp) },
                    shape = RoundedCornerShape(22.dp), maxLines = 4, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontFamily = Jakarta),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = C.line, focusedBorderColor = C.accent, unfocusedContainerColor = C.bg, focusedContainerColor = C.panel),
                    enabled = me != null || Community.demo,
                )
                if (me == null) Pill("Join", { nav.go(Screen.JoinPage()) }, kind = "primary", small = true)
                else Pill(if (sending) "…" else "Send", {
                    val body = text.trim()
                    if (body.isEmpty() || sending) return@Pill
                    sending = true
                    scope.launch {
                        runCatching { Community.comment(p.id, body, replyTo?.id) }
                            .onSuccess { comments.add(it); text = ""; replyTo = null }
                            .onFailure { message = it.message }
                        sending = false
                    }
                }, kind = "primary", small = true, enabled = text.isNotBlank())
            }
        }
    }

    val mine = p != null && p.authorId == Community.myId
    if (menu && p != null) ChoiceSheet(
        "Post",
        buildList {
            if (!mine) add(Choice("Report this post") { if (member(nav)) reporting = p.id to null })
            if (!mine) add(Choice(if (p.isVideo) "Block whoever shared this" else "Block ${p.shownName ?: "this member"}") {
                if (member(nav)) confirm = "Block them? You won't see their posts or comments." to {
                    scope.launch { runCatching { Community.block(p.authorId) }; nav.back() }
                }
            })
            if (mine) add(Choice("Delete my post", danger = true) {
                confirm = "Delete this post? It can't be undone." to { scope.launch { runCatching { Community.deletePost(p.id) }.onSuccess { nav.back() }.onFailure { message = it.message } } }
            })
            if (me?.isStaff == true) {
                add(Choice(if (p.pinned) "Unpin from the top" else "Pin to the top of Hot") {
                    scope.launch { runCatching { Community.setPinned(p.id, !p.pinned) }.onSuccess { post = p.copy(pinned = !p.pinned) }.onFailure { message = it.message } }
                })
                if (!mine) add(Choice("Take this post down", danger = true) {
                    scope.launch { runCatching { Community.setRemoved(p.id, true) }.onSuccess { nav.back() }.onFailure { message = it.message } }
                })
            }
        },
    ) { menu = false }

    commentMenu?.let { c ->
        val myComment = c.authorId == Community.myId
        ChoiceSheet(
            "Comment",
            buildList {
                if (!myComment) add(Choice("Report this comment") { if (member(nav)) reporting = null to c.id })
                if (!myComment) add(Choice("Block ${c.profiles?.username ?: "this member"}") {
                    if (member(nav)) scope.launch { runCatching { Community.block(c.authorId) }; comments.removeAll { it.authorId == c.authorId } }
                })
                if (myComment || me?.isStaff == true) add(Choice(if (myComment) "Delete my comment" else "Take this comment down", danger = true) {
                    scope.launch { runCatching { Community.deleteComment(c.id) }.onSuccess { comments.removeAll { it.id == c.id || it.parentId == c.id } }.onFailure { message = it.message } }
                })
            },
        ) { commentMenu = null }
    }

    reporting?.let { (postId, commentId) ->
        ChoiceSheet("Why are you reporting this?", Community.REPORT_REASONS.map { r ->
            Choice(r) {
                scope.launch {
                    runCatching { Community.report(postId, commentId, r) }
                        .onSuccess { message = "Thanks. We'll look at it within a day." }
                        .onFailure { message = it.message }
                }
            }
        }) { reporting = null }
    }
    confirm?.let { (q, go) -> ConfirmDialog(q, "", "Yes", { confirm = null }) { confirm = null; go() } }
    message?.let { MessageDialog("", it) { message = null } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommentRow(c: CComment, depth: Int, onReply: () -> Unit, onMore: () -> Unit) {
    val name = c.profiles?.username ?: "member"
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = onMore).padding(start = (16 + depth * 34).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Avatar(name, staff = name == "OldGround", size = if (depth == 0) 26 else 22)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = C.ink)
                Text(" · ${Community.ago(c.createdAt)}", fontSize = 12.5.sp, color = C.muted)
            }
            Text(c.body, fontSize = 14.sp, color = C.ink, lineHeight = 20.sp)
            Text("Reply", Modifier.clickable(onClick = onReply).padding(top = 3.dp, bottom = 2.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = C.muted)
        }
        Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClickLabel = "Report, block or delete", onClick = onMore), contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.MORE, C.faint, Modifier.size(16.dp))
        }
    }
}

// ======================= new post =======================

@Composable
fun ComposeScreen(nav: Nav, pinId: String? = null, debug: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by Repo.data.collectAsState()
    var video by rememberSaveable { mutableStateOf(debug == "video") }
    val photos = remember { mutableStateListOf<String>() } // file names in the app's photos folder
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var material by rememberSaveable { mutableStateOf<String?>(null) }
    var depth by rememberSaveable { mutableStateOf("") }
    var detector by rememberSaveable { mutableStateOf(data.settings.lastDetector) }
    var state by rememberSaveable { mutableStateOf<String?>(null) }
    var pickState by remember { mutableStateOf(false) }
    var link by rememberSaveable { mutableStateOf(if (debug == "video") "https://youtu.be/xxxxxxxxxxx" else "") }
    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var looking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val myFinds = remember(data.pins) { data.pins.filter { it.kind == PinKind.FIND && it.photos.isNotEmpty() }.sortedByDescending { it.createdAt } }
    fun useFind(id: String) {
        val f = data.pins.firstOrNull { it.id == id } ?: return
        photos.clear(); photos.addAll(f.photos.take(6))
        title = f.name; body = f.note
        category = f.category; material = f.material
        depth = f.depthIn?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: ""
        if (!f.detector.isNullOrBlank()) detector = f.detector
    }
    LaunchedEffect(pinId) { pinId?.let { useFind(it) } }
    LaunchedEffect(debug) { if (debug == "find") myFinds.firstOrNull()?.let { useFind(it.id) } }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val n = withContext(Dispatchers.IO) { Photos.import(ctx, uri) }
            if (n != null && photos.size < 6) photos.add(n)
        }
    }

    val ready = !busy && if (video) info != null && title.isNotBlank() else photos.isNotEmpty() && title.trim().length >= 3
    fun submit() {
        if (!member(nav)) return
        busy = true; error = null
        scope.launch {
            runCatching {
                if (video) Community.postVideo(info!!, title, body)
                else Community.postFind(
                    title, body, category, material, depth.toDoubleOrNull(), detector, state,
                    photos.map { Photos.file(ctx, it) },
                )
            }.onSuccess { id -> nav.replace(Screen.CommunityPost(id)) }
                .onFailure { error = it.message }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(C.bg).imePadding()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton({ nav.back() }, "Cancel", size = 38.dp) { GlyphIcon(Glyph.CLOSE, C.ink, Modifier.size(20.dp)) }
            Text("New post", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
            Pill(if (busy) "Posting…" else "Post", { submit() }, kind = "primary", small = true, enabled = ready)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Segmented(video, listOf(false to "A find", true to "A video"), { video = it; error = null })
            error?.let { Text(it, color = C.badInk, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold) }
            if (!video) {
                if (myFinds.isNotEmpty()) {
                    Label("From your finds")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(myFinds, key = { it.id }) { f ->
                            Column(Modifier.width(76.dp).clickable { useFind(f.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                PhotoThumb(f.photos.first(), Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)))
                                Text(f.name, fontSize = 11.sp, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Label("Photos")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((i, ph) in photos.withIndex()) Box {
                        PhotoThumb(ph, Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)))
                        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(Color(0xB314201D)).clickable { photos.removeAt(i) }.semantics { contentDescription = "Remove photo" }, contentAlignment = Alignment.Center) {
                            GlyphIcon(Glyph.CLOSE, Color.White, Modifier.size(14.dp))
                        }
                    }
                    if (photos.size < 6) Box(
                        Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).border(1.5.dp, Color(0xFF9FB3AD), RoundedCornerShape(12.dp)).background(C.panel)
                            .clickable { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        contentAlignment = Alignment.Center,
                    ) { GlyphIcon(Glyph.PLUS, C.accent, Modifier.size(24.dp)) }
                }
                Field("Title", title, { title = it.take(140) }, "e.g. Seated dime from a cellar hole")
                Field("Say something (optional)", body, { body = it.take(4000) }, "The signal, the dig, the story", lines = 3)
                Label("Type")
                ChipRow(FIND_CATEGORIES, category) { category = if (category == it) null else it }
                Label("Metal")
                ChipRow(FIND_MATERIALS, material) { material = if (material == it) null else it }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { Field("Depth (inches)", depth, { depth = it.filter { ch -> ch.isDigit() || ch == '.' }.take(5) }, "6", number = true) }
                    Column(Modifier.weight(1f)) {
                        Label("State")
                        Text(
                            Community.stateName(state) ?: "Don't show ▾",
                            Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, C.line, RoundedCornerShape(12.dp)).background(C.panel).clickable { pickState = true }.padding(horizontal = 12.dp, vertical = 15.dp),
                            fontSize = 14.5.sp, color = if (state == null) C.muted else C.ink,
                        )
                    }
                }
                Field("Detector (optional)", detector, { detector = it.take(80) }, "e.g. XP Deus II")
                Text("Your map location is never shared. Only the state shows, if you pick one, and photos carry no GPS data.", fontSize = 12.5.sp, color = C.muted, lineHeight = 17.sp)
            } else {
                Field("YouTube or Facebook link", link, { link = it.trim(); info = null }, "Paste the video link")
                Pill(if (looking) "Looking it up…" else "Look it up", {
                    looking = true; error = null
                    scope.launch {
                        runCatching { Community.lookUpVideo(link) }
                            .onSuccess { info = it; if (title.isBlank()) title = it.title?.take(140) ?: "" }
                            .onFailure { error = it.message }
                        looking = false
                    }
                }, kind = "tint", small = true, enabled = link.isNotBlank() && !looking)
                info?.let { v ->
                    Column(Modifier.fillMaxWidth().card(14, 2)) {
                        Box(Modifier.fillMaxWidth().height(170.dp).background(VIDEO_BG), contentAlignment = Alignment.Center) {
                            v.thumb?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xEBFFFFFF)), contentAlignment = Alignment.Center) { PlayIcon(C.ink, 18.dp) }
                        }
                        Text(
                            (v.channel ?: if (v.provider == "facebook") "Facebook video" else "YouTube video") + " · shown as the source",
                            Modifier.padding(10.dp), fontSize = 12.5.sp, color = C.muted,
                        )
                    }
                    Field("Title", title, { title = it.take(140) }, "What's the video about?")
                    Field("Say something (optional)", body, { body = it.take(4000) }, "Why it's worth watching", lines = 3)
                }
                Text("Share videos by linking them. The post shows the channel's name, and plays on YouTube or Facebook.", fontSize = 12.5.sp, color = C.muted, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
    if (pickState) ChoiceSheet("State", listOf(Choice("Don't show", state == null) { state = null }) + Community.STATES.map { s -> Choice(Community.stateName(s)!!, state == s) { state = s } }) { pickState = false }
}

@Composable
private fun Label(t: String) = Text(t, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = C.muted)

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String, lines: Int = 1, number: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(label)
        OutlinedTextField(
            value, onChange, Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = C.faint, fontSize = 14.5.sp) },
            singleLine = lines == 1, minLines = lines, shape = RoundedCornerShape(12.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.5.sp, fontFamily = Jakarta, color = C.ink),
            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = C.line, focusedBorderColor = C.accent, unfocusedContainerColor = C.panel, focusedContainerColor = C.panel),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(options: List<String>, value: String?, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (o in options) {
            val on = o == value
            Text(o, Modifier.clip(RoundedCornerShape(50)).background(if (on) C.accent else C.panel).border(1.dp, if (on) C.accent else C.line, RoundedCornerShape(50)).clickable { onPick(o) }.padding(horizontal = 12.dp, vertical = 7.dp),
                fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, color = if (on) Color.White else C.ink)
        }
    }
}

// ======================= joining =======================

/** Sign in with a code sent by email, then pick a name and agree to the rules. */
@Composable
fun JoinScreen(nav: Nav, debug: String? = null) {
    val scope = rememberCoroutineScope()
    val signedIn by Community.signedIn.collectAsState()
    val me by Community.me.collectAsState()
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf(debug == "code") }
    var name by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Signed in but no name yet (or came back after closing the app at that step).
    var needName by remember { mutableStateOf(debug == "name") }
    LaunchedEffect(signedIn) { if (signedIn && me == null && !Community.demo) needName = runCatching { Community.loadMe() == null }.getOrDefault(true) }
    LaunchedEffect(me) { if (me != null && debug == null) nav.back() }

    fun run(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch { runCatching { block() }.onFailure { error = it.message }; busy = false }
    }

    Column(Modifier.fillMaxSize().background(C.bg).imePadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.statusBarsPadding().padding(12.dp)) { RoundButton({ nav.back() }, "Close", size = 38.dp) { GlyphIcon(Glyph.CLOSE, C.ink, Modifier.size(20.dp)) } }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (needName) "Pick a name" else "Join the community", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
            error?.let { Text(it, color = C.badInk, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold) }
            when {
                needName -> {
                    Text("This is how other detectorists see you. Letters, numbers and underscores.", fontSize = 14.sp, color = C.muted)
                    Field("Name", name, { name = it.filter { c -> c.isLetterOrDigit() || c == '_' }.take(20) }, "e.g. ridge_digger")
                    Column(Modifier.fillMaxWidth().card(14, 2).padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Community rules", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
                        for (r in listOf(
                            "Only dig where you have permission.",
                            "Never post exact locations, or anything that gives away someone's spot.",
                            "Post your own photos. Link videos rather than re-uploading them.",
                            "No harassment, hate or adult content. It gets removed and the account closed.",
                        )) Text("•  $r", fontSize = 13.5.sp, color = Color(0xFF2A3733), lineHeight = 19.sp)
                    }
                    Pill(if (busy) "Joining…" else "Agree and join", { run { Community.pickUsername(name); if (debug != null) nav.back() } }, Modifier.fillMaxWidth(), kind = "primary", enabled = name.length >= 3 && !busy)
                }
                !sent -> {
                    Text("Post your finds, upvote and comment. We'll email you a code to sign in; no password needed.", fontSize = 14.sp, color = C.muted)
                    Field("Email", email, { email = it.trim() }, "you@example.com")
                    Pill(if (busy) "Sending…" else "Send me a code", { run { Community.sendCode(email); sent = true } }, Modifier.fillMaxWidth(), kind = "primary", enabled = email.contains("@") && !busy)
                    Text("Reading the community doesn't need an account.", fontSize = 12.5.sp, color = C.muted)
                }
                else -> {
                    Text("We sent a code to ${email.ifBlank { "your email" }}. It can take a minute; check spam too.", fontSize = 14.sp, color = C.muted)
                    Field("Code", code, { code = it.filter(Char::isDigit).take(8) }, "123456", number = true)
                    Pill(if (busy) "Checking…" else "Sign in", { run { needName = Community.verify(email, code) } }, Modifier.fillMaxWidth(), kind = "primary", enabled = code.length >= 6 && !busy)
                    Text("Use a different email", Modifier.clickable { sent = false; code = "" }.padding(vertical = 6.dp), color = C.accent, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                }
            }
        }
    }
}

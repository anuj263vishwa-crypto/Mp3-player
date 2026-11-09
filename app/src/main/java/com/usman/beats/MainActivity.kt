package com.usman.beats

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay

data class Song(val id: Long, val title: String, val artist: String, val dur: Long, val folder: String) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
}

fun loadSongs(ctx: Context): List<Song> {
    val out = mutableListOf<Song>()
    val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.RELATIVE_PATH)
    ctx.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
        "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.MIME_TYPE} = 'audio/mpeg'", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE"
    )?.use { c ->
        while (c.moveToNext()) out += Song(
            c.getLong(0), c.getString(1) ?: "Unknown",
            c.getString(2)?.takeIf { it != "<unknown>" } ?: "Unknown artist",
            c.getLong(3),
            (c.getString(4) ?: "").trimEnd('/').substringAfterLast('/').ifEmpty { "Internal storage" }
        )
    }
    return out
}

fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF9B83FF))) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("beats", 0) }
    val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var songs by remember { mutableStateOf(listOf<Song>()) }
    var granted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[audioPerm] == true) { granted = true; songs = loadSongs(ctx) }
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, audioPerm) == PackageManager.PERMISSION_GRANTED) {
            granted = true; songs = loadSongs(ctx)
        } else launcher.launch(
            if (Build.VERSION.SDK_INT >= 33) arrayOf(audioPerm, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(audioPerm)
        )
    }

    var ctl by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val f = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync()
        f.addListener({ ctl = f.get() }, ContextCompat.getMainExecutor(ctx))
        onDispose { MediaController.releaseFuture(f) }
    }

    var recent by remember { mutableStateOf(prefs.getString("rec", "")!!.split(",").filter { it.isNotEmpty() }) }
    var favs by remember { mutableStateOf(prefs.getString("fav", "")!!.split(",").filter { it.isNotEmpty() }.toSet()) }
    var playing by remember { mutableStateOf(false) }
    var curId by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var shuf by remember { mutableStateOf(false) }
    var rep by remember { mutableStateOf(0) }

    DisposableEffect(ctl) {
        val c = ctl
        if (c == null) onDispose { } else {
            val l = object : Player.Listener {
                override fun onIsPlayingChanged(p: Boolean) { playing = p }
                override fun onMediaItemTransition(m: MediaItem?, r: Int) {
                    curId = m?.mediaId ?: ""
                    title = m?.mediaMetadata?.title?.toString() ?: ""
                    artist = m?.mediaMetadata?.artist?.toString() ?: ""
                    if (curId.isNotEmpty()) {
                        recent = (listOf(curId) + recent.filter { it != curId }).take(50)
                        prefs.edit().putString("rec", recent.joinToString(",")).apply()
                    }
                }
            }
            c.addListener(l)
            playing = c.isPlaying
            curId = c.currentMediaItem?.mediaId ?: ""
            title = c.currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
            artist = c.currentMediaItem?.mediaMetadata?.artist?.toString() ?: ""
            onDispose { c.removeListener(l) }
        }
    }
    LaunchedEffect(ctl) {
        while (true) { ctl?.let { pos = it.currentPosition; dur = it.duration.coerceAtLeast(0) }; delay(500) }
    }

    fun play(list: List<Song>, i: Int) {
        val c = ctl ?: return
        c.setMediaItems(list.map {
            MediaItem.Builder().setMediaId(it.id.toString()).setUri(it.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist).build()).build()
        }, i, 0)
        c.prepare(); c.play()
    }
    fun toggleFav(id: String) {
        favs = if (id in favs) favs - id else favs + id
        prefs.edit().putString("fav", favs.joinToString(",")).apply()
    }

    var tab by remember { mutableStateOf(0) }
    var folder by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }

    val base = when (tab) {
        0 -> songs
        1 -> songs.filter { it.folder == folder }
        2 -> recent.mapNotNull { id -> songs.find { it.id.toString() == id } }
        else -> songs.filter { it.id.toString() in favs }
    }
    val list = if (q.isBlank()) base else base.filter { it.title.contains(q, true) || it.artist.contains(q, true) }

    Column(Modifier.fillMaxSize()) {
        Text("🎧 Beats", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
        OutlinedTextField(q, { q = it }, placeholder = { Text("Search songs…") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        TabRow(selectedTabIndex = tab) {
            listOf("All", "Folders", "Recent", "Favorites").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i; folder = null }, text = { Text(t, maxLines = 1, fontSize = 13.sp) })
            }
        }
        Box(Modifier.weight(1f)) {
            when {
                !granted -> Text("Storage / audio permission do taaki saare MP3 files dikh sakein.", Modifier.padding(24.dp))
                tab == 1 && folder == null -> {
                    val groups = songs.groupBy { it.folder }.toSortedMap()
                    LazyColumn { items(groups.entries.toList()) { (f, l) ->
                        Row(Modifier.fillMaxWidth().clickable { folder = f }.padding(16.dp, 14.dp)) {
                            Text("📁  $f", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text("${l.size}", color = Color.Gray)
                        }
                    } }
                }
                list.isEmpty() -> Text(if (songs.isEmpty()) "Koi MP3 nahi mila." else "Kuch nahi mila.", Modifier.padding(24.dp), color = Color.Gray)
                else -> LazyColumn {
                    if (tab == 1) item { Text("‹ $folder", Modifier.clickable { folder = null }.padding(16.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                    items(list.size) { i ->
                        val s = list[i]; val on = s.id.toString() == curId
                        Row(Modifier.fillMaxWidth().clickable { play(list, i) }.padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (on && playing) "🔊" else "♪", fontSize = 22.sp, modifier = Modifier.width(36.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                                    color = if (on) MaterialTheme.colorScheme.primary else Color.Unspecified)
                                Text("${s.artist} • ${fmt(s.dur)}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = Color.Gray)
                            }
                            val fv = s.id.toString() in favs
                            Text(if (fv) "♥" else "♡", fontSize = 22.sp, color = Color(0xFFFF5C9D),
                                modifier = Modifier.clickable { toggleFav(s.id.toString()) }.padding(8.dp))
                        }
                    }
                }
            }
        }
        if (curId.isNotEmpty()) Surface(tonalElevation = 6.dp) {
            Column(Modifier.padding(12.dp, 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                        Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = Color.Gray)
                    }
                    val m = Modifier.padding(8.dp)
                    Text("🔀", fontSize = 20.sp, color = if (shuf) MaterialTheme.colorScheme.primary else Color.Gray,
                        modifier = Modifier.clickable { shuf = !shuf; ctl?.shuffleModeEnabled = shuf }.padding(6.dp))
                    Text(if (rep == 2) "🔂" else "🔁", fontSize = 20.sp, color = if (rep > 0) MaterialTheme.colorScheme.primary else Color.Gray,
                        modifier = Modifier.clickable { rep = (rep + 1) % 3; ctl?.repeatMode = intArrayOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE)[rep] }.padding(6.dp))
                    Text("⏮", fontSize = 24.sp, modifier = Modifier.clickable { ctl?.seekToPrevious() }.then(m))
                    Text(if (playing) "⏸" else "▶", fontSize = 26.sp, modifier = Modifier.clickable { ctl?.let { if (it.isPlaying) it.pause() else it.play() } }.then(m))
                    Text("⏭", fontSize = 24.sp, modifier = Modifier.clickable { ctl?.seekToNext() }.then(m))
                }
                Slider(value = pos.toFloat().coerceAtMost(dur.coerceAtLeast(1).toFloat()), valueRange = 0f..dur.coerceAtLeast(1).toFloat(),
                    onValueChange = { pos = it.toLong(); ctl?.seekTo(it.toLong()) })
                Row { Text(fmt(pos), fontSize = 11.sp, color = Color.Gray); Spacer(Modifier.weight(1f)); Text(fmt(dur), fontSize = 11.sp, color = Color.Gray) }
            }
        }
    }
}

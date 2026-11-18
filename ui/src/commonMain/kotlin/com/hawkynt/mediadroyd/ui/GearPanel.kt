package com.hawkynt.mediadroyd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroyd.player.PictureAdjustment
import com.hawkynt.mediadroyd.player.PictureFit

/**
 * The gear's side panel: speed, this video's picture, subtitles and tracks. Picture changes show
 * live and are remembered for this one video when a slider is let go.
 */
@Composable
fun GearPanel(
    speed: Float,
    onSpeed: (Float) -> Unit,
    fit: PictureFit,
    onFit: (PictureFit) -> Unit,
    subtitlesOn: Boolean,
    onSubtitles: (Boolean) -> Unit,
    picture: PictureAdjustment,
    onPicture: (PictureAdjustment) -> Unit,
    onPictureDone: () -> Unit,
    onResetPicture: () -> Unit,
    /** Tries a choice of deinterlacing, upscaling or smooth motion before it is taken (see pictureCheck). */
    check: PictureTry = { _, _ -> PictureVerdict(true) },
    /** The colourisers offered for this video (see rememberColorizers). */
    colorizers: List<PictureAdjustment.Colorize> = listOf(PictureAdjustment.Colorize.OFF),
    onAudioTrack: () -> Unit,
    onSubtitleTrack: () -> Unit,
    /** Picks a second subtitle track, shown at the top beside the first. */
    onSecondSubtitleTrack: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    sections: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
    afterPictureSize: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
    /** The folder this video is in, and the one whose picture it starts with, if any has one (FolderDefaults). */
    folder: String = "",
    pictureFolder: String? = null,
    onUseForFolder: () -> Unit = {},
    onForgetFolder: () -> Unit = {},
    /** Below the picture sizes: how else the picture is sized here (the phone's pinch), empty for nothing more. */
    fitHint: String = "Pinch with two fingers to zoom; choosing a size resets the zoom.",
    /** Which upscalers offer their networks' models here (PictureSliders). */
    neuralModels: (PictureAdjustment.Upscaler) -> Boolean = { it.isEnlarger },
    /** After the subtitles and sound: what only this platform's player offers. */
    end: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .widthIn(max = 400.dp)
            .fillMaxWidth(0.9f)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .clickable(interactionSource = null, indication = null) {}
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Playback & picture", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        }

        PanelTitle("Speed")
        SpeedSlider(speed, onSpeed, Modifier.fillMaxWidth())

        sections()

        PanelTitle("Picture size")
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PictureFit.entries.forEach { option ->
                FilterChip(
                    selected = fit == option,
                    onClick = { onFit(option) },
                    label = { Text(option.label, fontSize = 16.sp) },
                )
            }
        }
        val panoramic = "A narrow picture fills a wide screen: the middle as it is, the sides stretched. "
        val fitText = (if (fit == PictureFit.PANORAMIC) panoramic else "") + fitHint
        if (fitText.isNotBlank()) {
            Text(
                fitText.trim(),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // The frame's shape, for a file whose flag is wrong (PictureShape); remembered with this video's picture.
        Text("Shape", fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.hawkynt.mediadroyd.player.AspectOverride.entries.forEach { option ->
                FilterChip(
                    selected = picture.aspect == option,
                    onClick = {
                        onPicture(picture.copy(aspect = option))
                        onPictureDone()
                    },
                    label = { Text(option.label, fontSize = 16.sp) },
                )
            }
        }
        Text(
            "Only for a picture that looks squeezed or stretched: its file says the wrong shape.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        afterPictureSize()

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 20.dp)) {
            Text(
                "Picture for this video",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onResetPicture) { Text("Reset", fontSize = 16.sp) }
        }
        PictureSliders(
            picture, onChange = onPicture, onDone = onPictureDone, stereo = true, logo = true, colourOffset = true,
            check = check,
            colorizers = colorizers, neuralModels = neuralModels,
        )
        // The picture for every video in the folder, as one: a folder of VHS rips starting deinterlaced.
        OutlinedButton(onClick = onUseForFolder, modifier = Modifier.padding(top = 8.dp)) {
            Text("Use for the whole folder", fontSize = 16.sp)
        }
        Text(
            if (pictureFolder != null) {
                "Videos in $pictureFolder start with that folder's picture; a video changed here keeps its own."
            } else {
                "Every video in $folder will start with this picture; each can still be changed on its own."
            },
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pictureFolder != null) {
            TextButton(onClick = onForgetFolder) { Text("Forget the folder's picture", fontSize = 16.sp) }
        }

        PanelTitle("Language and sound")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Subtitles", fontSize = 17.sp, modifier = Modifier.weight(1f))
            Switch(checked = subtitlesOn, onCheckedChange = onSubtitles)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = onAudioTrack) { Text("Audio track…", fontSize = 16.sp) }
            OutlinedButton(onClick = onSubtitleTrack) { Text("Subtitle track…", fontSize = 16.sp) }
        }
        OutlinedButton(onClick = onSecondSubtitleTrack, modifier = Modifier.padding(top = 8.dp)) {
            Text("Second subtitles…", fontSize = 16.sp)
        }
        end()
    }
}

/**
 * The sleep timer (PlayerSession): off, after a number of minutes from [nowMs] (the screen's monotonic clock), or at
 * the end of this video.
 */
@Composable
fun SleepChoice(session: PlayerSession, nowMs: () -> Long) {
    PanelTitle("Sleep timer")
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = session.sleepAtMs == null && !session.sleepAtEnd,
            onClick = { session.sleepOff() },
            label = { Text("Off", fontSize = 16.sp) },
        )
        com.hawkynt.mediadroyd.player.PlaybackMath.SLEEP_MINUTES.forEach { minutes ->
            FilterChip(
                selected = session.sleepAtMs != null && session.sleepMinutes == minutes,
                onClick = { session.sleepIn(minutes, nowMs()) },
                label = { Text("$minutes min", fontSize = 16.sp) },
            )
        }
        FilterChip(
            selected = session.sleepAtEnd,
            onClick = { session.sleepAtEndOfVideo() },
            label = { Text("End of this video", fontSize = 16.sp) },
        )
    }
}

/** What the sleep timer does now, for the player's top bar; null while it is off. */
fun sleepNote(session: PlayerSession): String? = when {
    session.sleepAtEnd -> "Sleep at the end"
    session.sleepAtMs != null ->
        "Sleep in ${com.hawkynt.mediadroyd.player.PlaybackMath.sleepMinutesLeft(session.sleepLeftMs)} min"
    else -> null
}

/**
 * How this video's subtitles look ([look], kept with it): their delay, size, raised above burned-in text, a dark box
 * behind them; [onReset] takes the household's look again.
 */
@Composable
fun SubtitleLookRows(
    look: com.hawkynt.mediadroyd.player.SubtitleLook,
    onLook: (com.hawkynt.mediadroyd.player.SubtitleLook) -> Unit,
    onReset: () -> Unit,
    /** Moves a subtitle file's lines onto the speech (SubtitleSync); null where the track is not a file beside it. */
    onSync: (() -> Unit)? = null,
    /** What Sync is doing, or why it found nothing. */
    syncing: String? = null,
) {
    PanelTitle("Subtitle look and timing")
    fun changeLook(changed: com.hawkynt.mediadroyd.player.SubtitleLook) = onLook(changed)
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Delay", fontSize = 16.sp, modifier = Modifier.widthIn(min = 56.dp))
        androidx.compose.material3.OutlinedButton(
            onClick = { changeLook(look.nudgeDelay(-1)) },
        ) {
            Text("Sooner", fontSize = 16.sp)
        }
        Text(
            if (look.delayMs == 0) {
                "in sync"
            } else {
                "%+.1f s".format(look.delayMs / 1000f)
            },
            fontSize = 18.sp,
            modifier = Modifier.widthIn(min = 80.dp),
        )
        androidx.compose.material3.OutlinedButton(
            onClick = { changeLook(look.nudgeDelay(1)) },
        ) {
            Text("Later", fontSize = 16.sp)
        }
    }
    Text(
        com.hawkynt.mediadroyd.player.PlaybackMath.syncText("subtitles", look.delayMs)
            .replaceFirstChar { it.uppercase() } +
            ". Later when a line shows before it is spoken, sooner when after." +
            if (look.drift != 1.0) " Stretched to the film's speed by %.2f %%.".format((look.drift - 1) * 100) else "",
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (onSync != null) {
        androidx.compose.material3.OutlinedButton(
            onClick = onSync,
            enabled = syncing == null || !syncing.endsWith("…"),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            Text("Sync to the speech", fontSize = 16.sp)
        }
        Text(
            syncing ?: "Listens to the film and moves the lines onto what is said, and stretches them where the file " +
                "was timed for another frame rate.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        com.hawkynt.mediadroyd.player.SubtitleLook.Size.entries.forEach { size ->
            FilterChip(
                selected = look.size == size,
                onClick = { changeLook(look.copy(size = size)) },
                label = { Text(size.label, fontSize = 16.sp) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Raised above burned-in text", fontSize = 16.sp, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(
            checked = look.raised,
            onCheckedChange = { changeLook(look.copy(raised = it)) },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Dark box behind the text", fontSize = 16.sp, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(
            checked = look.box,
            onCheckedChange = { changeLook(look.copy(box = it)) },
        )
    }
    androidx.compose.material3.TextButton(onClick = onReset) { Text("Back to the household's look") }
}

/** What plays again once a video ends: nothing, this video, or the whole queue (PlayerSession.repeat). */
@Composable
fun RepeatChoice(
    repeat: com.hawkynt.mediadroyd.player.PlaybackMath.Repeat,
    onRepeat: (com.hawkynt.mediadroyd.player.PlaybackMath.Repeat) -> Unit,
) {
    PanelTitle("Repeat")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        com.hawkynt.mediadroyd.player.PlaybackMath.Repeat.entries.forEach { option ->
            FilterChip(
                selected = repeat == option,
                onClick = { onRepeat(option) },
                label = { Text(option.label, fontSize = 16.sp) },
            )
        }
    }
}

@Composable
fun PanelTitle(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}

/**
 * Subtitles made from the speech (#529), in the gear's subtitles: on or off, the language they are in (#530) — as
 * spoken, or translated on the device into English or a language OPUS-MT writes — and the Whisper model that hears:
 * larger hears better and needs a faster device. With what it is doing.
 */
@Composable
fun CaptionRows(
    on: Boolean,
    onOn: (Boolean) -> Unit,
    target: String,
    onTarget: (String) -> Unit,
    model: com.hawkynt.mediadroyd.player.WhisperModels.Entry,
    onModel: (com.hawkynt.mediadroyd.player.WhisperModels.Entry) -> Unit,
    status: String?,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Subtitles from the speech", fontSize = 16.sp)
            Text(
                status ?: "Heard on this device as the film plays, a minute ahead — for films without subtitles.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.Switch(checked = on, onCheckedChange = onOn)
    }
    if (!on) return
    Text("Translated into", fontSize = 15.sp)
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val languages = listOf("" to "As spoken", "en" to "English") +
            com.hawkynt.mediadroyd.player.TranslationModels.ALL.map { it.language to it.label }
        languages.forEach { (code, label) ->
            FilterChip(
                selected = target == code,
                onClick = { onTarget(code) },
                label = { Text(label, fontSize = 15.sp) },
            )
        }
    }
    Text("Heard by", fontSize = 15.sp)
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        com.hawkynt.mediadroyd.player.WhisperModels.ALL.forEach { entry ->
            FilterChip(
                selected = entry == model,
                onClick = { onModel(entry) },
                label = {
                    val size = "${entry.download.bytes / 1_000_000} MB"
                    Text("${entry.label} · $size" + if (entry.desktop) " · for desktops" else "", fontSize = 15.sp)
                },
            )
        }
    }
}

/**
 * The voice-over (Dub, #531, #534), in the gear's language: on or off, the voice that speaks — every line of the
 * subtitles, a file's or those made from the speech, in their language, keeping each line's tone — and what the film's
 * own voices do meanwhile: their dialogue channel removed (offered only where the sound has one), their voice band
 * suppressed, or all of the sound muted. Not shown where no voice speaks these subtitles.
 */
@Composable
fun VoiceOverRows(
    voices: List<com.hawkynt.mediadroyd.player.PiperVoices.Entry>,
    voice: com.hawkynt.mediadroyd.player.PiperVoices.Entry?,
    dubbing: Boolean,
    onDubbing: (Boolean) -> Unit,
    onVoice: (com.hawkynt.mediadroyd.player.PiperVoices.Entry) -> Unit,
    dubStatus: String?,
    original: com.hawkynt.mediadroyd.player.Dub.Original,
    /** Whether the sound has a dialogue channel of its own to remove (5.1 and the like). */
    centreChannel: Boolean,
    onOriginal: (com.hawkynt.mediadroyd.player.Dub.Original) -> Unit,
) {
    if (voices.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Voice-over", fontSize = 16.sp)
                Text(
                    dubStatus
                        ?: "A voice on this device speaks every line in the subtitles' language — the film re-voiced.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(checked = dubbing, onCheckedChange = onDubbing)
        }
        if (dubbing) {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                voices.forEach { entry ->
                    FilterChip(
                        selected = entry == voice,
                        onClick = { onVoice(entry) },
                        label = { Text("${entry.label} · ${entry.language}", fontSize = 15.sp) },
                    )
                }
            }
            Text("The film's own voices while a line speaks", fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val choices = buildList {
                    if (centreChannel) {
                        add(com.hawkynt.mediadroyd.player.Dub.Original.CHANNEL to "Remove the dialogue channel")
                    }
                    add(com.hawkynt.mediadroyd.player.Dub.Original.BAND to "Suppress the voice band")
                    add(com.hawkynt.mediadroyd.player.Dub.Original.MUTE to "Mute the sound")
                }
                // Removing the channel where there is none works as suppressing the band, and shows so.
                val shown = if (original == com.hawkynt.mediadroyd.player.Dub.Original.CHANNEL && !centreChannel) {
                    com.hawkynt.mediadroyd.player.Dub.Original.BAND
                } else {
                    original
                }
                choices.forEach { (choice, label) ->
                    FilterChip(
                        selected = shown == choice,
                        onClick = { onOriginal(choice) },
                        label = { Text(label, fontSize = 15.sp) },
                    )
                }
            }
            voice?.let {
                Text(
                    "${it.label}: ${it.licence}, ${it.download.bytes / 1_000_000} MB, fetched once.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

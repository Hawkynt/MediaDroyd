package com.hawkynt.mediadroyd.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroyd.engine.MediaRequest
import com.hawkynt.mediadroyd.engine.PlaybackEngine
import com.hawkynt.mediadroyd.engine.Track
import com.hawkynt.mediadroyd.engine.Tracks
import com.hawkynt.mediadroyd.library.Chapters
import com.hawkynt.mediadroyd.library.FsFile
import com.hawkynt.mediadroyd.library.Sidecars
import com.hawkynt.mediadroyd.library.readTextOrNull
import com.hawkynt.mediadroyd.player.SubtitleSync
import com.hawkynt.mediadroyd.player.Dub
import com.hawkynt.mediadroyd.player.PiperVoices
import com.hawkynt.mediadroyd.player.LiveCaptions
import com.hawkynt.mediadroyd.player.NativeWhisper
import com.hawkynt.mediadroyd.player.WhisperModels
import com.hawkynt.mediadroyd.player.Prosody
import com.hawkynt.mediadroyd.player.CaptionRoute
import com.hawkynt.mediadroyd.player.MarianTranslator
import com.hawkynt.mediadroyd.player.NeuralModels
import com.hawkynt.mediadroyd.player.ModelFolder
import com.hawkynt.mediadroyd.player.TranslationModels
import com.hawkynt.mediadroyd.player.ChapterSteps
import com.hawkynt.mediadroyd.player.CreditText
import com.hawkynt.mediadroyd.player.IntroCredits
import com.hawkynt.mediadroyd.player.PictureAdjustment
import com.hawkynt.mediadroyd.player.PictureCues
import com.hawkynt.mediadroyd.player.PictureFit
import com.hawkynt.mediadroyd.player.PlaybackChain
import com.hawkynt.mediadroyd.player.PlaybackMath
import com.hawkynt.mediadroyd.state.SkipFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the player screen needs of its platform: the picture stage it draws the video on (the phone's player panel with
 * its own controls, the desktop's frame loop with the stage's own window), the window around it (full screen, the
 * screen kept on, insets, picture in picture) and the media session, which each app keeps beside the screen; the rows
 * of the gear only one platform offers; how the platform opens a video and what it adds to the info sheet.
 */
interface PlayerPlatform {
    /** The keys (PlayerKeys) this player answers; the rest go to the platform's own controls. */
    val commands: Set<PlayerCommand>

    /**
     * A fingerprint of [location]'s sound for finding its intro and credits (IntroCredits, SkipFinder): its opening
     * (IntroCredits.openingMs), or where [closing] its last IntroCredits.CLOSING_MS; null where the platform cannot
     * read or fingerprint it. Blocks: called off the main thread.
     */
    fun soundPrint(location: String, closing: Boolean): IntroCredits.SoundPrint? = null

    /**
     * Gray frames of [location] for finding its intro and credits by their pictures where the sound does not
     * (PictureCues, SkipFinder): its opening (IntroCredits.openingMs) at PictureCues' hash size every OPENING_STEP_MS,
     * or where [closing] its last IntroCredits.CLOSING_MS at the look size every CLOSING_STEP_MS; null where the
     * platform cannot read them. Blocks: called off the main thread.
     */
    fun pictures(location: String, closing: Boolean): PictureCues.Frames? = null

    /**
     * [location]'s frames read for their text, for credits recognised by it (CreditText, SkipFinder): at each time [at]
     * gives from its length, the lines the platform's text recogniser read there; null where it cannot read them.
     * Blocks: called off the main thread.
     */
    fun readText(location: String, at: (durationMs: Long) -> List<Long>): List<Pair<Long, List<CreditText.Line>>>? =
        null

    /**
     * Where [location]'s sound speaks, for moving subtitles onto it (SubtitleSync, #450): the whole sound decoded and
     * judged, SubtitleSync.RATE verdicts a second ([SubtitleSync.Speech]); null where it cannot be read. Blocks for as
     * long as decoding the sound takes: called off the main thread.
     */
    fun speech(location: String): BooleanArray? = null

    /**
     * [location]'s sound from [fromMs] to [toMs] as Whisper hears it — mono floats at NativeWhisper.SAMPLE_RATE, the
     * first at [fromMs] — for subtitles made from the speech (#529); null where it cannot be read. Blocks.
     */
    fun soundAt(location: String, fromMs: Long, toMs: Long): FloatArray? = null

    /** The folder [download]'s files are kept in (models fetched when chosen); null where this build keeps none. */
    fun modelFolder(download: NeuralModels.Download): java.io.File? = null

    /** [model] loaded into the native library (NativeWhisper.open); 0 where it cannot here. Blocks. */
    fun openWhisper(model: WhisperModels.Entry): Long = 0L

    /** Whether this build phonemises with espeak-ng: Piper's own voices (builds for one's own devices, #531). */
    val espeak: Boolean get() = false

    /** Whether [voice]'s files are in place. */
    fun voiceReady(voice: PiperVoices.Entry): Boolean = false

    /** Fetches [voice]'s files, telling [progress] (0 … 1); whether they are in place. Blocks. */
    fun fetchVoice(voice: PiperVoices.Entry, progress: (Float) -> Unit): Boolean = false

    /** [voice] ready to speak lines (Dub.LineSpeaker), or null where it cannot here. Blocks. */
    fun speaker(voice: PiperVoices.Entry): Dub.LineSpeaker? = null

    /**
     * Subtitles switched by picking a track — none being off, the one picked the household's language from then on —
     * as the desktop does; otherwise, as on the phone, a switch of their own beside the track picked.
     */
    val subtitlesByTrack: Boolean

    /** Only the picture shows: picture in picture, or the player small in a corner. */
    val pictureOnly: Boolean get() = false

    /** Whether the player's controls are up; the top bar comes and goes with them. */
    val controlsVisible: Boolean

    /** The controls up for a while (a choice made on them), or gone (a panel or dialog opened over them). */
    fun showControls()
    fun hideControls()

    /** The file behind [location], for its chapters and the files beside it; null where none can be had. */
    fun fileOf(location: String): FsFile?

    /** Opens [request] as the platform's engine wants it opened (on its thread), and plays it. */
    suspend fun open(request: MediaRequest)

    /** The picture on screen saved. */
    fun snapshot()

    /** Full screen on or off (F). */
    fun fullScreen() {}

    /** The top bar's back and "All done": the player left. */
    fun leave()

    /** Esc: out of full screen first where the platform has it, else the player left. */
    fun back() = leave()

    /** A key before the player's own ones (a child lock, a remote's held skip): true when it was taken. */
    fun onKey(event: KeyEvent): Boolean = false

    /** The facts the info sheet draws the chain from: this platform's decoders, screen and sound. */
    fun facts(): PlaybackChain.Facts

    /** How a track picker names [track]; null for the picker's own count. */
    fun trackName(track: Track): String? = null

    /** A picture size chosen (the phone's zoom starts over). */
    fun onFit(fit: PictureFit) {}

    /** A speed chosen: the one played, as the platform keeps it. */
    fun onSpeed(speed: Float): Float = speed

    /** What the speed is multiplied by to play (Even frames). */
    val speedFactor: Float get() = 1f

    /** Tries a choice of deinterlacing, upscaling or smooth motion before it is taken. */
    val pictureCheck: PictureTry

    /** The colourisers offered here. */
    val colorizers: List<PictureAdjustment.Colorize>

    /** Which upscalers offer their networks' models here. */
    val neuralModels: (PictureAdjustment.Upscaler) -> Boolean get() = { it.isEnlarger }

    /** Below the picture sizes: how else the picture is sized here. */
    val fitHint: String get() = ""

    /** Where the controls keep clear of (a camera cutout, a mirrored TV's crop). */
    val safeArea: Modifier get() = Modifier

    /** Where the statistics and the histogram sit over the picture (moved by burn-in care's drift). */
    val overlayPlace: Modifier

    /** What shows where no picture is drawn: black, or all but clear over a picture window of the stage's own. */
    val background: Color get() = Color.Black

    /** The whole screen's own handling: focus, a pointer moved. */
    val screenModifier: Modifier get() = Modifier

    /** The picture, beneath everything: the video drawn, its gestures, and the controls that come with it. */
    @Composable
    fun BoxScope.Stage()

    /** The platform's own controls over the picture, with the top bar (the desktop's bottom bar). */
    @Composable
    fun BoxScope.Controls() {}

    /** The top bar's buttons only this platform has (the phone's second screen). */
    @Composable
    fun RowScope.TopBarButtons() {}

    /** The gear's rows after the speed only this platform has (the phone's lock and rotation, sound). */
    @Composable
    fun ColumnScope.GearSections() {}

    /** The gear's rows after the picture sizes (the phone's camera cutout, the desktop's histogram). */
    @Composable
    fun ColumnScope.GearAfterPictureSize() {}

    /** The gear's last rows (the desktop's decoding and its screen's setup). */
    @Composable
    fun ColumnScope.GearEnd() {}

    /** Over everything: a snapshot's note, a child lock, the corner player's marks, the darkened phone. */
    @Composable
    fun BoxScope.Overlays() {}
}

/**
 * The player, the same on every platform: the picture its [platform] draws, the top bar with what plays, the gear with
 * speed, picture, sound and subtitles, the info sheet, the chapter and track pickers, confirmations, "All done" at the
 * queue's end and why a video cannot play; the keys (PlayerKeys); the queue played by the [state]'s session — each
 * video opened with its chapters and the files beside it, resumed, its progress kept, intros skipped, the sleep timer,
 * the part repeated.
 */
@Composable
fun PlayerScreen(state: PlayerScreenState, platform: PlayerPlatform, modifier: Modifier = Modifier.fillMaxSize()) {
    val session = state.session
    val engine = state.engine
    val scope = rememberCoroutineScope()

    // Each video: its picture and look, its chapters (intros and credits with them) and the files beside it, then
    // opened where it was left — never back into a known intro.
    LaunchedEffect(session.index) {
        val playable = session.current
        state.videoChanged()
        state.statistics.clear()
        val (chapters, sidecars) = withContext(Dispatchers.IO) {
            val video = platform.fileOf(playable.videoPath)
            val chapters = video?.let { runCatching { Chapters.read(it) }.getOrNull() }.orEmpty()
            val folder = video?.parentFile
            val names = folder?.listFiles()?.map { it.name }.orEmpty()
            val sidecars = if (folder == null) {
                emptyList()
            } else {
                Sidecars.of(video.name, names).map { it to folder.child(it.name).path }
            }
            chapters to sidecars
        }
        state.sidecars = sidecars
        val startMs = session.begin(chapters)
        runCatching {
            platform.open(
                PlayerScreenState.request(
                    playable.videoPath, sidecars, startMs, state.store.data.settings.audioLanguage,
                ),
            )
        }.onFailure { state.error = it.message ?: it.toString() }
    }

    // Intros and credits found by their sound (#480), in the background from the moment the episode is chosen: its
    // intro first, from its opening and a neighbour's alone, applied at once — playback jumps to the intro's end if it
    // is in the intro already, else skips it once it starts — then its credits, then the next episode's, so
    // binge-watching meets its intro known. Cut files and chapters still come first.
    LaunchedEffect(session.index) {
        if (!session.skipping || state.demo) return@LaunchedEffect
        val finder = SkipFinder(
            state.store,
            pictures = platform::pictures,
            text = platform::readText,
            print = platform::soundPrint,
        )
        for (step in finder.steps(session.queue.map { it.videoPath }, session.index)) {
            val found = withContext(Dispatchers.Default) { runCatching { finder.find(step) }.getOrNull() } ?: continue
            finder.keep(step, found)
            session.applySkips()
        }
    }

    // Subtitles made from the speech (#529): the sound ahead of the position heard by Whisper (the household's model,
    // fetched the first time); in the language wanted (#530) — as spoken, Whisper's English, or that English translated
    // by OPUS-MT (CaptionRoute); shown over the picture, and spoken where the dub is on.
    LaunchedEffect(state.captioning, state.captionTarget, state.captionModel, session.index) {
        state.captions = null
        state.captionText = null
        state.captionSpoken = null
        if (!state.captioning) {
            state.captionStatus = null
            return@LaunchedEffect
        }
        val model = WhisperModels.of(state.captionModel)
        if (!fetched(platform, model.download, "Whisper ${model.label}") { state.captionStatus = it }) {
            return@LaunchedEffect
        }
        val target = state.captionTarget
        val translation = TranslationModels.into(target)
        if (translation != null &&
            !fetched(platform, translation.download, "the ${translation.label} translator") { state.captionStatus = it }
        ) {
            return@LaunchedEffect
        }
        val handle = withContext(Dispatchers.Default) { platform.openWhisper(model) }
        if (handle == 0L) {
            state.captionStatus = "Whisper cannot run in this build."
            return@LaunchedEffect
        }
        val translator = translation?.let { entry ->
            withContext(Dispatchers.Default) {
                platform.modelFolder(entry.download)?.let { runCatching { MarianTranslator(it) }.getOrNull() }
            }
        }
        val video = session.current.videoPath
        val threads = maxOf(1, Runtime.getRuntime().availableProcessors() - 1)
        var spoken: String? = null
        val captions = LiveCaptions(
            hear = hear@{ fromMs, toMs ->
                val pcm = platform.soundAt(video, fromMs, toMs) ?: return@hear null
                fun whisper(translate: Boolean) =
                    NativeWhisper.transcribe(handle, pcm, spoken ?: "auto", translate, threads)?.let(::heardOf)
                // The language spoken from the first window, written as spoken; then by the route to the target.
                var heard = if (spoken == null) whisper(false)?.also { spoken = it.language } else null
                val route = CaptionRoute.of(target, spoken)
                if (heard == null || route.whisperTranslates) {
                    heard = whisper(route.whisperTranslates) ?: return@hear null
                }
                // Each line's tone from its own sound, before any translation, for the voice to keep (#534).
                heard = heard.copy(
                    segments = heard.segments.map { segment ->
                        val rate = NativeWhisper.SAMPLE_RATE
                        val from = (segment.startMs * rate / 1000).toInt().coerceIn(0, pcm.size)
                        val to = (segment.endMs * rate / 1000).toInt().coerceIn(from, pcm.size)
                        val letters = segment.text.count { it.isLetter() }
                        segment.copy(tone = Prosody.measure(pcm.copyOfRange(from, to), rate, letters))
                    },
                )
                if (route.translator != null && translator != null) {
                    heard = heard.copy(
                        segments = heard.segments.map { segment ->
                            segment.copy(text = translator.translate(segment.text.trim()) ?: segment.text)
                        },
                    )
                }
                heard.copy(language = spoken ?: heard.language)
            },
            durationMs = engine.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE,
        )
        state.captions = captions
        state.captionStatus = "Listening…"
        try {
            coroutineScope {
                launch {
                    while (true) {
                        state.captionText = captions.lineAt(state.positionMs)?.text
                        delay(CAPTION_PACE_MS)
                    }
                }
                while (true) {
                    val heard = withContext(Dispatchers.Default) {
                        runCatching { captions.ahead(state.positionMs) }
                            .onFailure { state.captionStatus = "The speech could not be heard: ${it.message ?: it}" }
                            .getOrDefault(false)
                    }
                    if (heard) {
                        state.captionStatus = null
                        state.captionSpoken = spoken
                        state.captionLanguage = target.ifEmpty { spoken }
                    }
                    delay(DUB_PACE_MS)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Default) {
                NativeWhisper.close(handle)
                translator?.close()
            }
        }
    }

    // Spoken subtitles (Dub, #531): the lines of the subtitle file playing, spoken just ahead of the position by the
    // household's voice (fetched the first time), at the times its delay and drift give them; afresh when the file,
    // the voice or the timing changes.
    val dubFile = syncableSubtitles(state)
    val dubCaptions = state.captions.takeIf { dubFile == null }
    LaunchedEffect(state.dubbing, dubFile, dubCaptions, state.dubVoiceId, state.subtitleLook, session.index) {
        state.dubTrack.clear()
        if (!state.dubbing) {
            state.dubStatus = null
            return@LaunchedEffect
        }
        if (dubFile == null && dubCaptions == null) {
            state.dubStatus = "Pick a subtitle file beside the video, or turn on subtitles made from the speech."
            return@LaunchedEffect
        }
        val language = if (dubCaptions != null) {
            state.captionLanguage ?: state.captionTarget.ifEmpty { null }
        } else {
            state.tracks.firstOrNull { it.kind == Track.Kind.TEXT && it.selected && !it.second }?.language
        }
        val voice = dubVoice(state, platform, language) ?: run {
            state.dubStatus = "This build has no voice for these subtitles."
            return@LaunchedEffect
        }
        if (!platform.voiceReady(voice)) {
            state.dubStatus = "Fetching ${voice.label}…"
            val fetched = withContext(Dispatchers.IO) {
                platform.fetchVoice(voice) { done ->
                    state.dubStatus = "Fetching ${voice.label}… ${(done * 100).toInt()} %"
                }
            }
            if (!fetched) {
                state.dubStatus = "${voice.label} could not be fetched."
                return@LaunchedEffect
            }
        }
        val speaker = withContext(Dispatchers.Default) { platform.speaker(voice) } ?: run {
            state.dubStatus = "${voice.label} cannot speak in this build."
            return@LaunchedEffect
        }
        // A file's lines at the times its delay and drift give them; made ones as they are heard.
        val look = state.subtitleLook
        val fileLines = if (dubFile != null) {
            withContext(Dispatchers.IO) { platform.fileOf(dubFile)?.readTextOrNull()?.let(Dub::lines) }.orEmpty()
                .map { line ->
                    fun at(ms: Long) = (ms * look.drift).toLong() + look.delayMs
                    line.copy(startMs = at(line.startMs), endMs = at(line.endMs))
                }
        } else {
            emptyList()
        }
        state.dubStatus = null
        // Every line on the film's own speech once that is found (decoding its sound takes a while, kept for Sync
        // too): until then the lines' own times (Dub.align).
        val video = session.current.videoPath
        val found = async(Dispatchers.Default) { state.speechFor(video) { platform.speech(video) } }
        val lines: () -> List<Dub.Line> = {
            val raw = dubCaptions?.lines()?.mapNotNull { line ->
                // The tone heard in the line, its text's own hints on top (#534).
                Dub.clean(line.text).takeIf { it.text.isNotEmpty() }?.let {
                    val delivery = Prosody.combine(line.delivery, it.delivery)
                    line.copy(text = it.text, speaker = it.speaker, delivery = delivery)
                }
            } ?: fileLines
            val speech = if (found.isCompleted) runCatching { found.getCompleted() }.getOrNull() else null
            if (speech != null) Dub.align(raw, speech) else raw
        }
        val speaking = Dub.Speaking(lines, speaker, state.dubTrack)
        var aligned = false
        try {
            while (true) {
                if (!aligned && found.isCompleted) {
                    // The lines moved: what was spoken at the cues' times is spoken again on the speech.
                    aligned = true
                    state.dubTrack.clear()
                }
                // A line that cannot be spoken says so in the gear; it never takes the player down.
                withContext(Dispatchers.Default) {
                    runCatching { speaking.ahead(state.positionMs) }
                        .onFailure { state.dubStatus = "A line could not be spoken: ${it.message ?: it}" }
                }
                delay(DUB_PACE_MS)
            }
        } finally {
            found.cancel()
            speaker.close()
        }
    }

    // What the engine tells, taken on this screen's thread: the end, a seek, the tracks, playing or not.
    DisposableEffect(engine) {
        val listener = object : PlaybackEngine.Listener {
            override fun onEnded() {
                scope.launch { session.finishCurrent() }
            }

            // Skipping forward into the tail or the credits finishes the item as well; a seek is no frame time.
            override fun onSeeked(positionMs: Long) {
                state.statistics.interrupted()
                scope.launch { session.saveProgress(positionMs) }
            }

            override fun onTracksChanged(tracks: List<Track>) {
                scope.launch {
                    state.tracks = tracks
                    session.tracksChanged(tracks, state.householdSubtitleLanguage(platform.subtitlesByTrack))
                }
            }

            override fun onPlayingChanged(playing: Boolean) {
                if (!playing) state.statistics.interrupted()
                scope.launch { state.playing = playing }
            }
        }
        engine.addListener(listener)
        onDispose {
            engine.removeListener(listener)
            session.saveProgress()
        }
    }

    // The speed chosen, times Even frames' small change; subtitles on or off.
    LaunchedEffect(state.speed, platform.speedFactor, state.subtitlesOn) {
        engine.speed = state.speed * platform.speedFactor
        if (!platform.subtitlesByTrack) engine.subtitlesOn = state.subtitlesOn
    }

    // Intros skipped, the credits ending the video, the progress kept (PlayerSession.tick); the time played counted at
    // its speed, for whether a video is worth a resume point.
    LaunchedEffect(engine) {
        var last = System.currentTimeMillis()
        while (true) {
            delay(TICK_MS)
            val now = System.currentTimeMillis()
            session.countPlayed(now - last, state.speed)
            last = now
            session.tick()
            state.positionMs = engine.positionMs
        }
    }

    // Back to the part's start once its end is reached, checked often enough to land within a few frames.
    LaunchedEffect(session.abLoop) {
        if (!session.abLoop.repeating) return@LaunchedEffect
        while (true) {
            delay(AB_CHECK_MS)
            session.keepRepeating()
        }
    }

    // The sleep timer: the sound fades over its last seconds, then the video pauses; never kept.
    val baseVolume = remember { engine.volume }
    LaunchedEffect(session.sleepAtMs) {
        if (session.sleepAtMs == null) {
            engine.volume = baseVolume
            return@LaunchedEffect
        }
        while (true) {
            val ranOut = session.sleepTick(monotonicMs())
            engine.volume = baseVolume * session.sleepVolume
            if (ranOut) {
                state.show("Sleep timer: paused")
                break
            }
            delay(SLEEP_TICK_MS)
        }
    }

    LaunchedEffect(state.feedbackStamp) {
        delay(FEEDBACK_MS)
        state.hideFeedback()
    }

    // Only the picture: no panel or dialog stays open over it.
    LaunchedEffect(platform.pictureOnly) {
        if (platform.pictureOnly) {
            state.panelOpen = false
            state.infoOpen = false
            state.chapterPicker = false
            state.trackPicker = null
        }
    }

    fun togglePlay() = if (engine.isPlaying) engine.pause() else engine.play()

    fun jumpChapter(forward: Boolean) {
        session.jumpChapter(forward)?.let { state.show(it) }
    }

    fun changeSpeed(speed: Float) {
        state.speed = platform.onSpeed(speed)
    }

    // S: the next subtitle track, after the last one off again.
    fun nextSubtitles() {
        val text = engine.tracks.filter { it.kind == Track.Kind.TEXT }
        val at = text.indexOfFirst { it.selected }
        state.pick(Track.Kind.TEXT, text.getOrNull(at + 1)?.id, platform.subtitlesByTrack)
    }

    fun run(command: PlayerCommand) {
        when (command) {
            PlayerCommand.PLAY_PAUSE -> togglePlay()
            PlayerCommand.SEEK_FORWARD -> engine.seekTo(PlaybackMath.seekBy(engine.positionMs, engine.durationMs, true))
            PlayerCommand.SEEK_BACK -> engine.seekTo(PlaybackMath.seekBy(engine.positionMs, engine.durationMs, false))
            PlayerCommand.FULL_SCREEN -> platform.fullScreen()
            PlayerCommand.NEXT -> session.next()
            PlayerCommand.PANEL -> state.panelOpen = !state.panelOpen
            PlayerCommand.NEXT_SUBTITLES -> nextSubtitles()
            PlayerCommand.SNAPSHOT -> platform.snapshot()
            PlayerCommand.FRAME_BACK -> session.stepFrame(false)
            PlayerCommand.FRAME_FORWARD -> session.stepFrame(true)
            PlayerCommand.CHAPTER_NEXT -> jumpChapter(true)
            PlayerCommand.CHAPTER_BACK -> jumpChapter(false)
            PlayerCommand.REPEAT -> state.show(session.markRepeat())
            PlayerCommand.SLOWER -> changeSpeed(state.steppedSpeed(-1))
            PlayerCommand.FASTER -> changeSpeed(state.steppedSpeed(+1))
            PlayerCommand.LEAVE -> platform.back()
        }
    }

    val current = session.current
    val chapters = session.chapters
    Box(
        modifier = modifier
            .background(platform.background)
            .onPreviewKeyEvent { event ->
                if (platform.onKey(event)) return@onPreviewKeyEvent true
                val command = PlayerKeys.commandOf(event.key)?.takeIf { it in platform.commands }
                    ?: return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) run(command)
                true
            }
            .then(platform.screenModifier),
    ) {
        with(platform) { Stage() }
        state.feedback?.takeIf { !platform.pictureOnly }?.let {
            FeedbackNote(it, Modifier.align(Alignment.Center), accent = state.feedbackBoosted)
        }
        state.level?.takeIf { !platform.pictureOnly }?.let { SwipeLevel(it, state.levelSide) }
        // The top bar comes and goes with the platform's own controls.
        AnimatedVisibility(
            visible = platform.controlsVisible && !platform.pictureOnly,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).then(platform.safeArea),
        ) {
            PlayerTopBar(
                // What plays, and the chapter it is in.
                title = current.title + (current.subtitle?.let { "  ·  $it" } ?: "") +
                    (ChapterSteps.current(chapters, state.positionMs)?.let { "  ·  " + ChapterSteps.name(chapters, it) }
                        ?: ""),
                note = sleepNote(session),
                paused = !state.playing,
                abLoop = session.abLoop,
                hasChapters = chapters.isNotEmpty(),
                onBack = platform::leave,
                onStepFrame = { forward ->
                    session.stepFrame(forward)
                    platform.showControls()
                },
                onRepeat = {
                    state.show(session.markRepeat())
                    platform.showControls()
                },
                onChapters = {
                    state.chapterPicker = true
                    platform.hideControls()
                },
                onSnapshot = {
                    platform.snapshot()
                    platform.showControls()
                },
                onInfo = {
                    state.infoOpen = true
                    platform.hideControls()
                },
                onGear = {
                    state.panelOpen = !state.panelOpen
                    platform.hideControls()
                },
                screens = { with(platform) { TopBarButtons() } },
            )
        }
        // Subtitles made from the speech (#529), over the picture where the household reads subtitles.
        state.captionText?.takeIf { !platform.pictureOnly }?.let { text ->
            Text(
                text,
                fontSize = 22.sp,
                color = androidx.compose.ui.graphics.Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp, start = 32.dp, end = 32.dp)
                    .background(androidx.compose.ui.graphics.Color(0x99000000))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (!platform.pictureOnly) with(platform) { Controls() }
        if (state.panelOpen) {
            // Tapping beside the panel closes it, like the players everyone knows.
            Box(
                Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) {
                    state.panelOpen = false
                },
            )
            Gear(state, platform, Modifier.align(Alignment.CenterEnd).then(platform.safeArea))
        }
        if (state.statisticsShown || state.histogramShown) {
            // The statistics, and beneath them the histogram — on the left, beside the gear's picture controls.
            Column(Modifier.align(Alignment.TopStart).then(platform.overlayPlace)) {
                if (state.statisticsShown) StatisticsOverlay(state.statistics, Modifier)
                if (state.histogramShown) {
                    HistogramOverlay(state.histogram, Modifier.padding(top = if (state.statisticsShown) 8.dp else 0.dp))
                }
            }
        }
        Dialogs(state, platform)
        // Not over the open panel: the household is still choosing there; the prompt shows once it is closed.
        if (session.finished && !state.panelOpen && !platform.pictureOnly) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = platform::leave) { Text("All done — back to the library", fontSize = 22.sp) }
            }
        }
        state.error?.takeIf { !platform.pictureOnly }?.let { why ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = platform::leave) {
                    Text("Cannot play this file ($why) — back to the library", fontSize = 22.sp)
                }
            }
        }
        with(platform) { Overlays() }
    }
}

/** The gear: speed, picture size, this video's picture, subtitles and sound, and what only the platform offers. */
@Composable
private fun Gear(state: PlayerScreenState, platform: PlayerPlatform, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val byTrack = platform.subtitlesByTrack
    GearPanel(
        speed = state.speed,
        onSpeed = { state.speed = platform.onSpeed(it) },
        fit = state.fit,
        onFit = {
            state.fit = it
            platform.onFit(it)
        },
        fitHint = platform.fitHint,
        subtitlesOn = if (byTrack) {
            state.tracks.any { it.kind == Track.Kind.TEXT && it.selected }
        } else {
            state.subtitlesOn
        },
        onSubtitles = { on ->
            if (byTrack) {
                val text = Tracks.of(state.engine.tracks, Track.Kind.TEXT)
                val chosen = if (on) {
                    Tracks.preferredText(text, state.store.data.settings.subtitleLanguage) ?: text.firstOrNull()
                } else {
                    null
                }
                state.pick(Track.Kind.TEXT, chosen?.id, byTrack = true)
            } else {
                state.subtitlesOn = on
            }
        },
        // This video's picture: live while a slider moves, kept when it is let go. The household's lives in Settings.
        picture = state.picture,
        onPicture = { state.picture = it },
        onPictureDone = state::keepPicture,
        onResetPicture = state::resetPicture,
        check = platform.pictureCheck,
        colorizers = platform.colorizers,
        neuralModels = platform.neuralModels,
        folder = state.folderLabel,
        pictureFolder = state.pictureFolderLabel,
        onUseForFolder = state::useForFolder,
        onForgetFolder = state::forgetFolder,
        onAudioTrack = { state.trackPicker = TrackPicker.SOUND },
        onSubtitleTrack = { state.trackPicker = TrackPicker.SUBTITLES },
        onSecondSubtitleTrack = { state.trackPicker = TrackPicker.SECOND },
        onClose = { state.panelOpen = false },
        modifier = modifier,
        sections = { with(platform) { GearSections() } },
        afterPictureSize = { with(platform) { GearAfterPictureSize() } },
        end = {
            CaptionRows(
                on = state.captioning,
                onOn = { state.captioning = it },
                target = state.captionTarget,
                onTarget = state::chooseCaptionTarget,
                model = WhisperModels.of(state.captionModel),
                onModel = { state.chooseCaptionModel(it.id) },
                status = state.captionStatus,
            )
            SubtitleLookRows(
                state.subtitleLook, onLook = state::look, onReset = state::resetLook,
                // Sync for a subtitle file beside the video (SubtitleSync, #450): its cues laid over the speech.
                onSync = syncableSubtitles(state)?.let { file ->
                    {
                        scope.launch {
                            state.syncing = "Listening to the sound…"
                            val video = state.session.current.videoPath
                            val fit = withContext(Dispatchers.Default) {
                                val cues = platform.fileOf(file)?.readTextOrNull()?.let(SubtitleSync::cues)
                                val speech = state.speechFor(video) { platform.speech(video) }
                                if (cues != null && speech != null) SubtitleSync.fit(speech, cues) else null
                            }
                            state.syncing = if (fit == null) "No speech or no lines found to match." else null
                            if (fit != null) {
                                state.look(state.subtitleLook.copy(delayMs = fit.offsetMs.toInt(), drift = fit.ratio))
                            }
                        }
                    }
                },
                syncing = state.syncing,
                // Spoken subtitles (Dub, #531): for a file beside the video, where the build has a voice.
                voices = if (syncableSubtitles(state) != null || state.captioning) {
                    PiperVoices.forLanguage(dubLanguage(state), platform.espeak)
                } else {
                    emptyList()
                },
                voice = dubVoice(state, platform, dubLanguage(state)),
                dubbing = state.dubbing,
                onDubbing = { state.dubbing = it },
                onVoice = { voice -> state.chooseDubVoice(voice.id) },
                original = state.dubOriginal,
                centreChannel = com.hawkynt.mediadroyd.player.Dub.hasCentre(state.dubChannels),
                onOriginal = state::chooseDubOriginal,
                dubStatus = state.dubStatus,
            )
            SleepChoice(state.session, ::monotonicMs)
            RepeatChoice(state.session.repeat) { state.session.repeat = it }
            with(platform) { GearEnd() }
        },
    )
}

/** What is playing, the chapters and the track pickers, each over the player while open. */
@Composable
private fun Dialogs(state: PlayerScreenState, platform: PlayerPlatform) {
    val engine = state.engine
    val session = state.session
    if (state.infoOpen) {
        PlaybackInfoDialog(
            playing = session.current,
            report = engine.report,
            tracks = engine.tracks,
            videoPath = session.current.videoPath,
            graph = PlaybackChain.of(
                platform.facts().copy(
                    made = PlaybackChain.Made(
                        WhisperModels.of(state.captionModel).label, state.captionSpoken,
                        translation = captionTranslation(state),
                    ).takeIf { state.captioning },
                    voiceOver = if (state.dubbing) {
                        val voice = dubVoice(state, platform, dubLanguage(state))?.label ?: "no voice"
                        val original = when (state.dubOriginal) {
                            Dub.Original.CHANNEL -> if (Dub.hasCentre(state.dubChannels)) {
                                "the dialogue channel removed"
                            } else {
                                "the voice band suppressed"
                            }
                            Dub.Original.BAND -> "the voice band suppressed"
                            Dub.Original.MUTE -> "the sound muted"
                        }
                        "$voice (Piper), $original under each line"
                    } else {
                        null
                    },
                ),
            ),
            statisticsShown = state.statisticsShown,
            onStatistics = { state.statisticsShown = it },
            onDismiss = { state.infoOpen = false },
            histogramShown = state.histogramShown,
            onHistogram = {
                state.histogramShown = it
                if (!it) state.histogram = null
            },
        )
    }
    if (state.chapterPicker) {
        ChapterDialog(
            chapters = session.chapters,
            current = ChapterSteps.current(session.chapters, engine.positionMs),
            onDismiss = { state.chapterPicker = false },
            onPick = { start ->
                engine.seekTo(start)
                state.chapterPicker = false
            },
        )
    }
    val byTrack = platform.subtitlesByTrack
    when (state.trackPicker) {
        // Sound files beside the video are merged in as sources 1, 2 …: their names say what they are.
        TrackPicker.SOUND -> {
            val soundFiles = state.sidecars.filter { it.first.kind == Sidecars.Kind.AUDIO }
            TrackDialog(
                tracks = engine.tracks,
                kind = Track.Kind.AUDIO,
                labelFor = { track ->
                    track.source.takeIf { it >= 1 }?.let { soundFiles.getOrNull(it - 1)?.first?.describe() }
                        ?: platform.trackName(track)
                },
                marked = { it.selected },
                onDismiss = { state.trackPicker = null },
                onPick = { picked ->
                    // By track, "Automatic" leaves the sound as it is.
                    if (picked != null || !byTrack) state.pick(Track.Kind.AUDIO, picked?.id, byTrack)
                    state.trackPicker = null
                },
            )
        }
        TrackPicker.SUBTITLES -> TrackDialog(
            tracks = engine.tracks,
            kind = Track.Kind.TEXT,
            labelFor = platform::trackName,
            noneLabel = if (byTrack) "Off" else "Automatic",
            marked = { byTrack && it.selected },
            onDismiss = { state.trackPicker = null },
            onPick = { picked ->
                state.pick(Track.Kind.TEXT, picked?.id, byTrack)
                state.trackPicker = null
            },
        )
        // A second track at the top: a language to learn by, or the signs beside the dialogue.
        TrackPicker.SECOND -> TrackDialog(
            tracks = Tracks.secondText(engine.tracks),
            kind = Track.Kind.TEXT,
            labelFor = platform::trackName,
            noneLabel = "None",
            marked = { it.second },
            onDismiss = { state.trackPicker = null },
            onPick = { picked ->
                engine.secondSubtitle = picked?.id
                state.trackPicker = null
            },
        )
        null -> Unit
    }
}

/** A clock that only moves forward, for the sleep timer. */
private fun monotonicMs(): Long = System.nanoTime() / 1_000_000

/** How often the session looks at the position (intros, credits, progress). */
private const val TICK_MS = 500L

/** How often a repeated part's end is looked for. */
private const val AB_CHECK_MS = 40L

/** How often the sleep timer counts down. */
private const val SLEEP_TICK_MS = 250L

/** How long a confirmation stays over the picture. */
private const val FEEDBACK_MS = 800L

/** A swipe's level bar (LevelBar) where it belongs: on the side swiped, in the middle of the height. */
@Composable
fun BoxScope.SwipeLevel(level: Float, side: Side) {
    LevelBar(
        level,
        Modifier
            .align(if (side == Side.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
            .padding(horizontal = 32.dp),
    )
}

/** The subtitle file beside the video whose track is playing, which Sync can move; null for a track in the video. */
internal fun syncableSubtitles(state: PlayerScreenState): String? {
    val track = state.tracks.firstOrNull { it.kind == Track.Kind.TEXT && it.selected && !it.second } ?: return null
    if (track.source <= 0) return null
    return state.sidecars.filter { it.first.kind == Sidecars.Kind.SUBTITLES }.getOrNull(track.source - 1)?.second
}

/** How often the lines ahead of the position are looked at for speaking. */
private const val DUB_PACE_MS = 500L

/** The voice subtitles in [language] are spoken in: the household's where this build has it, else the first offered. */
internal fun dubVoice(state: PlayerScreenState, platform: PlayerPlatform, language: String?): PiperVoices.Entry? {
    val offered = PiperVoices.forLanguage(language, platform.espeak)
    return offered.firstOrNull { it.id == state.dubVoiceId } ?: offered.firstOrNull()
}

/** The language of the subtitle track playing, as the engine names it. */
internal fun dubLanguage(state: PlayerScreenState): String? =
    if (syncableSubtitles(state) == null && state.captioning) {
        state.captionLanguage ?: state.captionTarget.ifEmpty { null }
    } else {
        state.tracks.firstOrNull { it.kind == Track.Kind.TEXT && it.selected && !it.second }?.language
    }

/** How often the made subtitle on screen follows the position. */
private const val CAPTION_PACE_MS = 100L

/** What NativeWhisper.transcribe wrote, read back: the language, then a segment a line. */
internal fun heardOf(text: String): LiveCaptions.Heard {
    val lines = text.lines()
    val segments = lines.drop(1).mapNotNull { line ->
        val parts = line.split('\t', limit = 3)
        val start = parts.getOrNull(0)?.toLongOrNull()
        val end = parts.getOrNull(1)?.toLongOrNull()
        if (start == null || end == null) null else LiveCaptions.Segment(start, end, parts.getOrElse(2) { "" })
    }
    return LiveCaptions.Heard(lines.firstOrNull().orEmpty(), segments)
}

/**
 * Whether [download]'s files are in place, fetched now where not (telling [say] how far, as [what]); false where they
 * could not be — [say] has said why.
 */
internal suspend fun fetched(
    platform: PlayerPlatform,
    download: NeuralModels.Download,
    what: String,
    say: (String?) -> Unit,
): Boolean {
    val dir = platform.modelFolder(download) ?: run {
        say("This build cannot keep $what.")
        return false
    }
    if (ModelFolder.ready(dir, download)) return true
    say("Fetching $what…")
    val done = withContext(Dispatchers.IO) {
        ModelFolder.fetch(dir, download, { part -> say("Fetching $what… ${(part * 100).toInt()} %") })
    }
    if (!done) say("$what could not be fetched.")
    return done
}

/** How the made subtitles are translated, as the info graph names it; null where they are as spoken. */
internal fun captionTranslation(state: PlayerScreenState): String? {
    val route = CaptionRoute.of(state.captionTarget, state.captionSpoken)
    val into = TranslationModels.into(route.translator)
    return when {
        route.translator != null && route.whisperTranslates -> "Whisper into English, then OPUS-MT into ${into?.label}"
        route.translator != null -> "OPUS-MT, English into ${into?.label}"
        route.whisperTranslates -> "Whisper, into English"
        else -> null
    }
}

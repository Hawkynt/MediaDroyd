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
import com.hawkynt.mediadroyd.player.Loops
import com.hawkynt.mediadroyd.player.NativeWhisper
import com.hawkynt.mediadroyd.player.WhisperModels
import com.hawkynt.mediadroyd.player.Prosody
import com.hawkynt.mediadroyd.player.FilmSound
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
     * or where [closing] its last IntroCredits.CLOSING_MS at the look size every CLOSING_STEP_MS — decoded once, the
     * text reader's frames kept (PictureCues.closing, #535) where [recognise] can read them; null where the platform
     * cannot read them. Blocks: called off the main thread.
     */
    fun pictures(location: String, closing: Boolean): PictureCues.Frames? = null

    /** The lines the platform's text recogniser reads in [gray] (CreditText's size); null where it cannot. Blocks. */
    fun recognise(gray: ByteArray): List<CreditText.Line>? = null

    /**
     * [location]'s frames read for their text, for credits recognised by it (CreditText, SkipFinder): at each time [at]
     * gives from its length, the lines the platform's text recogniser read there; null where it cannot read them.
     * Blocks: called off the main thread.
     */
    fun readText(location: String, at: (durationMs: Long) -> List<Long>): List<Pair<Long, List<CreditText.Line>>>? =
        null

    /**
     * [location]'s sound read from [fromMs] on, mono floats at FilmSound.RATE, the first at [fromMs] — the one reader
     * every analysis of a film's sound shares (FilmSound, #535): Whisper's windows, the speech found, each line's tone;
     * where [near], served from the packets playback reads ahead where the platform can (ReadAhead), waiting where
     * playback has not read yet; else from the file itself. Null where it cannot be read. Blocks: off the main thread.
     */
    fun openSound(location: String, fromMs: Long, near: Boolean): FilmSound.Stream? = null

    /** A line for the platform's diagnostics log (how the hearing and the voice keep up); nowhere by default. */
    fun log(message: String) {}

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

    /** The gear's Playback rows after the speed only this platform has (the phone's lock and rotation). */
    @Composable
    fun ColumnScope.GearSections() {}

    /** The gear's Sound rows after the audio track only this platform has (night mode, delay, equalizer). */
    @Composable
    fun ColumnScope.GearSound() {}

    /** The gear's rows after the picture sizes (the phone's camera cutout, the desktop's histogram). */
    @Composable
    fun ColumnScope.GearAfterPictureSize() {}

    /** The gear's last Picture rows (the desktop's decoding and its screen's setup). */
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
            recognise = platform::recognise,
            print = platform::soundPrint,
        )
        val queue = session.queue.map { it.videoPath }
        for (step in finder.steps(queue, session.index)) {
            val began = System.nanoTime()
            val found = withContext(Dispatchers.Default) { runCatching { finder.find(step) }.getOrNull() }
            // Which episode and what was found, by place in the queue rather than by name (#548).
            val which = if (step.a == session.current.videoPath) {
                "this episode"
            } else {
                "episode +${queue.indexOf(step.a) - session.index}"
            }
            val what = found?.get(step.a)?.let { f ->
                f.intro?.let { "intro ${it.startMs / 1000}…${it.endMs / 1000} s" }
                    ?: f.credits.joinToString { "credits ${it.startMs / 1000}…${it.endMs / 1000} s" }.ifEmpty { "none" }
            } ?: "not readable"
            platform.log(
                "skips: ${step.part} of $which in ${(System.nanoTime() - began) / 1_000_000} ms at " +
                    "${engine.positionMs / 1000} s: $what",
            )
            found ?: continue
            finder.keep(step, found)
            session.applySkips()
        }
    }

    // Subtitles made from the speech (#529): the sound ahead of the position heard by Whisper (the household's model,
    // fetched the first time); in the language wanted (#530) — as spoken, Whisper's English, or that English translated
    // by OPUS-MT (CaptionRoute) — Chinese and Japanese written as spoken and translated by their own (#546); shown over
    // the picture, and spoken where the dub is on.
    LaunchedEffect(state.captioning, state.captionTarget, state.captionModel, session.index) {
        state.captions = null
        state.captionText = null
        state.captionSpoken = null
        state.captionFrom = null
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
        val sound = state.soundOf(video, engine.durationMs) { from, near -> platform.openSound(video, from, near) }
        // The fast cores only: a phone's little ones slow ggml down (FastCores).
        val threads = com.hawkynt.mediadroyd.player.FastCores.count()
        var spoken: String? = null
        // The spoken language's own translator into English, where it has one (#546): fetched once the language is
        // heard; until then Whisper's English.
        var fromTranslator: MarianTranslator? = null
        var fromAsked = false
        // How long the last window took to hear, for telling the household when the model cannot keep up.
        var tookMs = 0L
        var windowMs = 0L
        val captions = LiveCaptions(
            hear = hear@{ fromMs, toMs ->
                val began = System.nanoTime()
                val startedAt = state.positionMs
                platform.log("hearing ${fromMs / 1000}…${toMs / 1000} s asked for at ${startedAt / 1000} s")
                val pcm = sound.samples(fromMs, toMs) ?: return@hear null.also { platform.log("no sound there") }
                val readMs = (System.nanoTime() - began) / 1_000_000
                var whisperMs = 0L
                fun whisper(translate: Boolean): LiveCaptions.Heard? {
                    val t = System.nanoTime()
                    return NativeWhisper.transcribe(handle, pcm, spoken ?: "auto", translate, threads)?.let(::heardOf)
                        .also { whisperMs += (System.nanoTime() - t) / 1_000_000 }
                }
                // The language spoken from the first window, written as spoken; then by the route to the target.
                var heard = if (spoken == null) whisper(false)?.also { spoken = it.language } else null
                val route = CaptionRoute.of(target, spoken) { fromTranslator != null }
                if (heard == null || route.whisperTranslates) {
                    heard = whisper(route.whisperTranslates) ?: return@hear null
                }
                // A model caught in a loop heard once, before the loop is translated (Loops, #541); then each line's
                // tone from its own sound, before any translation, for the voice to keep (#534).
                heard = heard.copy(
                    segments = Loops.drop(heard.segments).map { segment ->
                        val rate = NativeWhisper.SAMPLE_RATE
                        val from = (segment.startMs * rate / 1000).toInt().coerceIn(0, pcm.size)
                        val to = (segment.endMs * rate / 1000).toInt().coerceIn(from, pcm.size)
                        val letters = segment.text.count { it.isLetter() }
                        segment.copy(tone = Prosody.measure(pcm.copyOfRange(from, to), rate, letters))
                    },
                )
                val translated = System.nanoTime()
                // As spoken into English first, where the spoken language has its own translator (#546).
                val toEnglish = fromTranslator
                if (route.from != null && toEnglish != null) {
                    val texts = toEnglish.translateAll(heard.segments.map { it.text.trim() })
                    heard = heard.copy(
                        segments = heard.segments.mapIndexed { i, segment ->
                            segment.copy(text = texts?.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: segment.text)
                        },
                    )
                }
                // The window's lines translated together (MarianTranslator.translateAll): the steps of the longest.
                if (route.translator != null && translator != null) {
                    val texts = translator.translateAll(heard.segments.map { it.text.trim() })
                    heard = heard.copy(
                        segments = heard.segments.mapIndexed { i, segment ->
                            segment.copy(text = texts?.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: segment.text)
                        },
                    )
                }
                // A translator caught in a loop of its own ("Nein, nein, nein, …") is left out too (#546).
                heard = heard.copy(segments = heard.segments.filterNot { Loops.repeats(it.text) })
                tookMs = (System.nanoTime() - began) / 1_000_000
                windowMs = toMs - fromMs
                platform.log(
                    "hearing ${fromMs / 1000}…${toMs / 1000} s: read ${readMs} ms, whisper ${whisperMs} ms, " +
                        "translate ${(System.nanoTime() - translated) / 1_000_000} ms, " +
                        "${heard.segments.size} lines, ${heard.segments.sumOf { it.text.split(' ').size }} words; " +
                        "${(fromMs - startedAt) / 1000} s ahead when begun, " +
                        "${(toMs - state.positionMs) / 1000} s at the end",
                )
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
                        // Behind the film: what plays is not heard yet — say how slow the model is here.
                        val heardNow = captions.heardAt(state.positionMs)
                        if (!heardNow && tookMs > 0) {
                            state.captionStatus = "Behind the film: Whisper ${model.label} took ${tookMs / 1000} s " +
                                "for ${windowMs / 1000} s of sound here — a smaller model keeps up."
                        } else if (heardNow && state.captionStatus?.startsWith("Behind") == true) {
                            state.captionStatus = null
                        }
                        delay(CAPTION_PACE_MS)
                    }
                }
                var told = ""
                while (true) {
                    val at = state.positionMs
                    val heard = withContext(Dispatchers.Default) {
                        runCatching { captions.ahead(at) }
                            .onFailure { state.captionStatus = "The speech could not be heard: ${it.message ?: it}" }
                            .getOrDefault(false)
                    }
                    val round = "hearing round at ${at / 1000} s of ${engine.durationMs / 1000} s: " +
                        if (heard) "heard" else "nothing to hear (heard there: ${captions.heardAt(at)})"
                    if (round.substringAfter(": ") != told) platform.log(round)
                    told = round.substringAfter(": ")
                    if (heard) {
                        state.captionStatus = null
                        state.captionSpoken = spoken
                        state.captionLanguage = target.ifEmpty { spoken }
                    }
                    // The spoken language heard: its own translator into English fetched, where it has one (#546).
                    val source = TranslationModels.from(spoken)
                    if (source != null && !fromAsked && CaptionRoute.of(target, spoken).from != null) {
                        fromAsked = true
                        if (fetched(platform, source.download, "the ${source.label} translator") {
                                state.captionStatus = it
                            }
                        ) {
                            fromTranslator = withContext(Dispatchers.Default) {
                                platform.modelFolder(source.download)
                                    ?.let { runCatching { MarianTranslator(it) }.getOrNull() }
                            }
                            state.captionFrom = fromTranslator?.let { source.source }
                            state.captionStatus = null
                        }
                    }
                    delay(DUB_PACE_MS)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Default) {
                NativeWhisper.close(handle)
                translator?.close()
                fromTranslator?.close()
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
        // Auto: a man's and a woman's voice, each line spoken by the one its speaker's voice calls for (#532);
        // else the one voice chosen.
        val pair = dubPair(state, platform, language)
        val voices = pair?.toList() ?: listOfNotNull(dubVoice(state, platform, language))
        if (voices.isEmpty()) {
            state.dubStatus = "This build has no voice for these subtitles."
            return@LaunchedEffect
        }
        for (voice in voices) {
            if (platform.voiceReady(voice)) continue
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
        val speakers = voices.map { voice ->
            withContext(Dispatchers.Default) { platform.speaker(voice) } ?: run {
                state.dubStatus = "${voice.label} cannot speak in this build."
                return@LaunchedEffect
            }
        }
        val speakerFor: (Dub.Line) -> Dub.LineSpeaker = if (speakers.size == 2) {
            { line -> if (Dub.womanSpoke(line) == true) speakers[1] else speakers[0] }
        } else {
            { speakers[0] }
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
        // Every line on the film's own speech (Dub.align), found in the film's shared sound (FilmSound, #535) — kept
        // decoded a minute ahead of the position, so each line is placed before it is spoken.
        val video = session.current.videoPath
        val sound = state.soundOf(video, engine.durationMs) { from, near -> platform.openSound(video, from, near) }
        val lines: () -> List<Dub.Line> = {
            val raw = dubCaptions?.lines()?.mapNotNull { line ->
                // The tone heard in the line, its text's own hints on top (#534).
                Dub.clean(line.text).takeIf { it.text.isNotEmpty() }?.let {
                    val delivery = Prosody.combine(line.delivery, it.delivery)
                    line.copy(text = it.text, speaker = it.speaker, delivery = delivery)
                }
            } ?: fileLines
            Dub.align(raw, sound.speech())
        }
        val speaking = Dub.Speaking(lines, speakerFor, state.dubTrack).apply {
            onSpoken = { line, took, said ->
                val lead = line.startMs - state.positionMs
                platform.log(
                    "voice ${line.startMs / 1000.0} s: ${if (said) "spoken" else "not spoken"} in $took ms, " +
                        if (lead >= 0) "${lead / 1000.0} s before it is due" else "${-lead / 1000.0} s late",
                )
            }
        }
        try {
            while (true) {
                // A line that cannot be spoken says so in the gear; it never takes the player down.
                withContext(Dispatchers.Default) {
                    runCatching {
                        // The sound a minute ahead decoded (once, shared), so the lines due are placed on the speech.
                        sound.samples(state.positionMs, state.positionMs + LiveCaptions.AHEAD_MS)
                        speaking.ahead(state.positionMs)
                    }.onFailure { state.dubStatus = "A line could not be spoken: ${it.message ?: it}" }
                }
                delay(DUB_PACE_MS)
            }
        } finally {
            speakers.forEach { it.close() }
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
        // Subtitles made from the speech (#529), over the picture where the household reads subtitles; while what plays
        // is not heard yet, what the hearing is doing instead — listening, or running behind with a model too slow.
        // Hidden while the voice-over speaks them, where the household chose so (#542).
        val captionLine = state.captionText?.takeIf { !state.linesHidden }
        val captionShown = captionLine ?: state.captionStatus?.takeIf { state.captioning && !state.panelOpen }
        captionShown?.takeIf { !platform.pictureOnly }?.let { text ->
            Text(
                text,
                fontSize = if (captionLine != null) 22.sp else 15.sp,
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
        page = state.gearPage,
        onPage = { state.gearPage = it },
        playback = {
            with(platform) { GearSections() }
            SleepChoice(state.session, ::monotonicMs)
            RepeatChoice(state.session.repeat) { state.session.repeat = it }
        },
        afterPictureSize = { with(platform) { GearAfterPictureSize() } },
        pictureEnd = { with(platform) { GearEnd() } },
        sound = {
            with(platform) { GearSound() }
            VoiceOverRows(
                voices = if (syncableSubtitles(state) != null || state.captioning) {
                    PiperVoices.forLanguage(dubLanguage(state), platform.espeak)
                } else {
                    emptyList()
                },
                voice = dubVoice(state, platform, dubLanguage(state))
                    .takeIf { dubPair(state, platform, dubLanguage(state)) == null },
                pair = PiperVoices.pair(dubLanguage(state), platform.espeak).let { (man, woman) ->
                    if (man != null && woman != null) man to woman else null
                },
                auto = dubPair(state, platform, dubLanguage(state)) != null,
                onAuto = { state.chooseDubVoice(PiperVoices.AUTO) },
                linesShown = state.dubLinesShown,
                onLinesShown = state::chooseDubLinesShown,
                dubbing = state.dubbing,
                onDubbing = { state.dubbing = it },
                onVoice = { voice -> state.chooseDubVoice(voice.id) },
                original = state.dubOriginal,
                centreChannel = com.hawkynt.mediadroyd.player.Dub.hasCentre(state.dubChannels),
                onOriginal = state::chooseDubOriginal,
                dubStatus = state.dubStatus,
            )
        },
        subtitles = {
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
                                // All of the film's speech, from its shared sound: only what is not decoded yet is.
                                val sound = state.soundOf(video, state.engine.durationMs) { from, near ->
                                    platform.openSound(video, from, near)
                                }
                                sound.decodeAll { part ->
                                    state.syncing = "Listening to the sound… ${(part * 100).toInt()} %"
                                }
                                val speech = sound.speech()
                                if (cues != null && speech.any { it }) SubtitleSync.fit(speech, cues) else null
                            }
                            state.syncing = if (fit == null) "No speech or no lines found to match." else null
                            if (fit != null) {
                                state.look(state.subtitleLook.copy(delayMs = fit.offsetMs.toInt(), drift = fit.ratio))
                            }
                        }
                    }
                },
                syncing = state.syncing,
            )
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
                        val voice = dubPair(state, platform, dubLanguage(state))
                            ?.let { (man, woman) -> "Auto: ${man.label} for men, ${woman.label} for women" }
                            ?: dubVoice(state, platform, dubLanguage(state))?.label ?: "no voice"
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

/**
 * The man's and the woman's voice when the household speaks the subtitles automatically by the speaker (#532) — Auto,
 * the default before any voice is chosen — and the language has both; null for one voice.
 */
internal fun dubPair(
    state: PlayerScreenState,
    platform: PlayerPlatform,
    language: String?,
): Pair<PiperVoices.Entry, PiperVoices.Entry>? {
    if (state.dubVoiceId.isNotEmpty() && state.dubVoiceId != PiperVoices.AUTO) return null
    val (male, female) = PiperVoices.pair(language, platform.espeak)
    return if (male != null && female != null) male to female else null
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
    val route = CaptionRoute.of(state.captionTarget, state.captionSpoken) { it == state.captionFrom }
    val into = TranslationModels.into(route.translator)
    val from = TranslationModels.from(route.from)
    return when {
        from != null && route.translator != null ->
            "Whisper as spoken, OPUS-MT ${from.label} into English, then into ${into?.label}"
        from != null -> "Whisper as spoken, OPUS-MT ${from.label} into English"
        route.translator != null && route.whisperTranslates -> "Whisper into English, then OPUS-MT into ${into?.label}"
        route.translator != null -> "OPUS-MT, English into ${into?.label}"
        route.whisperTranslates -> "Whisper, into English"
        else -> null
    }
}

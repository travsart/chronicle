package local.oss.chronicle.features.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import local.oss.chronicle.R
import local.oss.chronicle.injection.scopes.ServiceScope
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject

/**
 * Provides immediate audio feedback for Android Auto voice commands using TextToSpeech.
 *
 * When users issue voice commands like "Hey Google, play [audiobook] on Chronicle",
 * there's typically a 6-7 second delay before audio starts due to:
 * - Database queries
 * - Network API calls to Plex
 * - URL resolution
 * - ExoPlayer initialization
 *
 * This class bridges that gap by speaking a quick confirmation ("Getting ready to play your
 * audiobook") immediately when the voice command is received, providing instant feedback
 * and meeting Google's 2-3 second response requirement for Android Auto.
 *
 * **CRITICAL**: This should ONLY be triggered for Android Auto voice commands (via
 * [android.support.v4.media.session.MediaSessionCompat.Callback.onPlayFromSearch]),
 * NOT for regular UI playback, taps, or other sources.
 *
 * @see android.support.v4.media.session.MediaSessionCompat.Callback.onPlayFromSearch
 */
@ServiceScope
class VoiceCommandBridgeAudio @Inject constructor(
    private val context: Context,
    private val serviceScope: CoroutineScope,
) : TextToSpeech.OnInitListener {


    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingSpeak: String? = null
    private var isSpeaking = false
    private var completionCallback: (() -> Unit)? = null
    private var timeoutJob: kotlinx.coroutines.Job? = null
    
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    
    // Handler for dispatching callbacks to main thread (TTS callbacks run on binder thread)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    companion object {
        private const val TAG = "VoiceCommandBridgeAudio"
        private const val UTTERANCE_ID = "bridge_audio"
        private const val TTS_TIMEOUT_MS = 3000L // 3 seconds timeout
    }

    /**
     * Initialize the TextToSpeech engine.
     * Should be called early in [MediaPlayerService.onCreate] so TTS is ready when needed.
     */
    fun initialize() {
        try {
            Timber.i("[$TAG] Initializing TextToSpeech engine with context: ${context.javaClass.simpleName}")
            tts = TextToSpeech(context.applicationContext, this)
            Timber.d("[$TAG] TextToSpeech constructor completed, waiting for onInit callback...")
        } catch (e: Exception) {
            Timber.e(e, "[$TAG] Failed to create TextToSpeech instance")
            isReady = false
        }
    }

    /**
     * Called when TextToSpeech initialization completes.
     * This callback is invoked asynchronously after TextToSpeech() constructor.
     */
    override fun onInit(status: Int) {
        Timber.i("[$TAG] onInit callback received with status: $status")
        
        if (status == TextToSpeech.SUCCESS) {
            try {
                val result = tts?.setLanguage(Locale.getDefault())
                Timber.d("[$TAG] setLanguage result: $result")
                
                // Configure TTS for Android Auto audio routing
                configureTtsAudioAttributes()
                
                // Set up utterance progress listener to track speaking state
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                        Timber.d("[$TAG] TTS started speaking: $utteranceId")
                        // Cancel timeout when TTS actually starts speaking
                        // MUST run on main thread to avoid threading issues
                        mainHandler.post {
                            timeoutJob?.cancel()
                            timeoutJob = null
                        }
                    }
                    
                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        Timber.d("[$TAG] TTS finished speaking: $utteranceId")
                        // CRITICAL: Dispatch to main thread - TTS callbacks run on binder thread
                        // but completionCallback accesses ExoPlayer which requires main thread
                        mainHandler.post {
                            completionCallback?.invoke()
                            completionCallback = null
                            timeoutJob?.cancel()
                            timeoutJob = null
                            abandonAudioFocus()
                        }
                    }
                    
                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        Timber.e("[$TAG] TTS error: $utteranceId")
                        // CRITICAL: Dispatch to main thread - TTS callbacks run on binder thread
                        mainHandler.post {
                            completionCallback?.invoke()
                            completionCallback = null
                            timeoutJob?.cancel()
                            timeoutJob = null
                            abandonAudioFocus()
                        }
                    }
                })
                
                isReady = true
                Timber.i("[$TAG] TextToSpeech initialized successfully and ready to use")

                // If there was a pending speak request, execute it now
                pendingSpeak?.let { message ->
                    Timber.i("[$TAG] Executing pending speak request: $message")
                    speak(message)
                }
                pendingSpeak = null
            } catch (e: Exception) {
                Timber.e(e, "[$TAG] Error during TTS initialization success path")
                isReady = false
            }
        } else {
            Timber.e("[$TAG] TextToSpeech initialization FAILED with status: $status (${statusToString(status)})")
            isReady = false
            pendingSpeak = null // Clear pending message since TTS won't work
        }
    }
    
    private fun statusToString(status: Int): String = when (status) {
        TextToSpeech.SUCCESS -> "SUCCESS"
        TextToSpeech.ERROR -> "ERROR"
        else -> "UNKNOWN($status)"
    }
    
    /**
     * Configure TTS audio attributes for Android Auto compatibility.
     * Sets the audio stream to USAGE_ASSISTANT for proper routing to Android Auto speakers.
     */
    private fun configureTtsAudioAttributes() {
        try {
            // Use USAGE_ASSISTANT for TTS - this is the appropriate usage for voice assistants
            // and ensures proper routing in Android Auto
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            tts?.setAudioAttributes(audioAttributes)
            Timber.i("[$TAG] Configured TTS AudioAttributes: USAGE_ASSISTANT, CONTENT_TYPE_SPEECH")
        } catch (e: Exception) {
            Timber.e(e, "[$TAG] Failed to set TTS audio attributes")
        }
    }
    
    /**
     * Request temporary audio focus for TTS playback.
     * This ensures TTS is audible in Android Auto by ducking the main audiobook playback.
     */
    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) {
            Timber.d("[$TAG] Already have audio focus")
            return true
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android O and above - use AudioFocusRequest
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(false)
                    .build()
                
                val result = audioManager.requestAudioFocus(audioFocusRequest!!)
                hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                Timber.i("[$TAG] Audio focus request result (O+): ${if (hasAudioFocus) "GRANTED" else "FAILED"}")
            } else {
                // Pre-Android O - use deprecated API
                @Suppress("DEPRECATION")
                val result = audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                Timber.i("[$TAG] Audio focus request result (legacy): ${if (hasAudioFocus) "GRANTED" else "FAILED"}")
            }
            
            return hasAudioFocus
        } catch (e: Exception) {
            Timber.e(e, "[$TAG] Failed to request audio focus")
            return false
        }
    }
    
    /**
     * Abandon audio focus after TTS completes.
     */
    private fun abandonAudioFocus() {
        if (!hasAudioFocus) {
            return
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                    audioFocusRequest = null
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
            hasAudioFocus = false
            Timber.d("[$TAG] Abandoned audio focus")
        } catch (e: Exception) {
            Timber.e(e, "[$TAG] Failed to abandon audio focus")
        }
    }

    /**
     * Speak the bridge message for Android Auto voice commands.
     * This provides immediate audio feedback while the actual audiobook loads.
     *
     * @param onComplete Optional callback to invoke after TTS completes or times out (3 seconds)
     */
    fun speakBridgeMessage(onComplete: (() -> Unit)? = null) {
        val message = context.getString(R.string.voice_bridge_preparing_audiobook)
        Timber.i("[$TAG] Speaking bridge message: '$message'")
        
        // Store the completion callback if provided
        if (onComplete != null) {
            completionCallback = onComplete
            
            // Start timeout fallback in case TTS fails or takes too long
            timeoutJob?.cancel() // Cancel any existing timeout
            timeoutJob = serviceScope.launch {
                delay(TTS_TIMEOUT_MS)
                if (completionCallback != null) {
                    Timber.w("[$TAG] TTS timeout reached (${TTS_TIMEOUT_MS}ms), invoking callback")
                    completionCallback?.invoke()
                    completionCallback = null
                }
            }
        }
        
        speak(message)
    }

    /**
     * Speak an error message for Android Auto voice commands.
     * Used to provide immediate audio feedback when a voice command fails
     * (e.g., user not logged in, no results found, etc.).
     *
     * @param message The error message to speak via TTS
     */
    fun speakErrorMessage(message: String) {
        Timber.i("[$TAG] Speaking error message: '$message'")
        speak(message)
    }

    /**
     * Speak an error message for Android Auto voice commands with a completion callback.
     * Used to provide immediate audio feedback when a voice command fails, then execute
     * a callback after TTS completes (or after timeout).
     *
     * This variant allows setting error state AFTER TTS completes, preventing Android Auto
     * from canceling the audio session before the message is heard.
     *
     * @param message The error message to speak via TTS
     * @param onComplete Callback to invoke after TTS completes or times out (3 seconds)
     */
    fun speakErrorMessage(message: String, onComplete: () -> Unit) {
        Timber.i("[$TAG] Speaking error message with callback: '$message'")
        
        // Store the completion callback
        completionCallback = onComplete
        
        // Start timeout fallback in case TTS fails or takes too long
        timeoutJob?.cancel() // Cancel any existing timeout
        timeoutJob = serviceScope.launch {
            delay(TTS_TIMEOUT_MS)
            if (completionCallback != null) {
                Timber.w("[$TAG] TTS timeout reached (${TTS_TIMEOUT_MS}ms), invoking callback")
                completionCallback?.invoke()
                completionCallback = null
            }
        }
        
        // Speak the message (UtteranceProgressListener will invoke callback when done)
        speak(message)
    }

    /**
     * Speak the given text using TTS with a 200ms silence prefix.
     * If TTS is not ready yet, the message will be queued and spoken when initialization completes.
     *
     * The silence prefix helps prevent Android Auto from cutting off the beginning of the message.
     */
    private fun speak(text: String) {
        if (isReady) {
            // Request audio focus before speaking
            if (!requestAudioFocus()) {
                Timber.w("[$TAG] Failed to get audio focus, speaking anyway")
            }
            
            // Add 200ms silence at beginning using SSML to prevent audio cutoff in Android Auto
            val ssmlMessage = "<speak><break time=\"200ms\"/>$text</speak>"
            tts?.speak(ssmlMessage, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            Timber.d("[$TAG] TTS speak initiated with SSML (200ms silence prefix)")
        } else {
            Timber.d("[$TAG] TTS not ready, queuing message: $text")
            pendingSpeak = text
        }
    }

    /**
     * Stop any currently speaking TTS.
     * Should be called when actual audiobook playback begins.
     *
     * Uses the UtteranceProgressListener to determine if TTS is still speaking.
     * If TTS is still speaking, we allow it to finish to prevent cutting off the message.
     */
    fun stop() {
        if (!isSpeaking) {
            // TTS already finished or never started - safe to stop
            Timber.d("[$TAG] Stopping TTS (not currently speaking)")
            tts?.stop()
        } else {
            // TTS is still speaking - let it finish naturally
            Timber.i("[$TAG] stop() called while TTS still speaking - allowing it to finish")
        }
    }
    
    /**
     * Force stop TTS immediately, even if still speaking.
     * Use this only when immediate silence is required (e.g., user manually interrupts).
     */
    fun forceStop() {
        Timber.d("[$TAG] Force stopping TTS")
        isSpeaking = false
        tts?.stop()
    }

    /**
     * Release TTS resources.
     * Should be called in [MediaPlayerService.onDestroy].
     */
    fun release() {
        Timber.i("[$TAG] Releasing TextToSpeech resources")
        timeoutJob?.cancel()
        timeoutJob = null
        completionCallback = null
        abandonAudioFocus()
        tts?.shutdown()
        tts = null
        isReady = false
        pendingSpeak = null
        isSpeaking = false
    }
}

package local.oss.chronicle.features.player

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
) : TextToSpeech.OnInitListener {


    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingSpeak: String? = null
    private var isSpeaking = false
    
    companion object {
        private const val TAG = "VoiceCommandBridgeAudio"
        private const val UTTERANCE_ID = "bridge_audio"
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
                
                // Set up utterance progress listener to track speaking state
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                        Timber.d("[$TAG] TTS started speaking: $utteranceId")
                    }
                    
                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        Timber.d("[$TAG] TTS finished speaking: $utteranceId")
                    }
                    
                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        Timber.e("[$TAG] TTS error: $utteranceId")
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
     * Speak the bridge message for Android Auto voice commands.
     * This provides immediate audio feedback while the actual audiobook loads.
     */
    fun speakBridgeMessage() {
        val message = context.getString(R.string.voice_bridge_preparing_audiobook)
        Timber.i("[$TAG] Speaking bridge message: '$message'")
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
        tts?.shutdown()
        tts = null
        isReady = false
        pendingSpeak = null
        isSpeaking = false
    }
}

package app.orchard.tv.system

import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvView
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.viewinterop.AndroidView

private const val TAG = "AerialTvPreview"

/** How long to wait before tuning: a TvView has to be attached first, or the picture stays black. */
private const val TUNE_DELAY_MS = 800L

/** Live TV is busier than a landscape, so it needs a little more darkening than a wallpaper. */
private const val SCRIM_DEFAULT = 0.30f

/**
 * A passthrough HDMI picture behind the home screen: the set-top box on HDMI 1, watched over the
 * desktop the way a TV's own elder mode does it.
 *
 * This first step only answers whether it works at all. One full-screen [TvView], no decoration,
 * no settings, no boot handling: [TvPreview.on] is flipped from a computer over ADB (see
 * ConfigReceiver) so the TV does not have to be reflashed for every try.
 *
 * Three things about a passthrough picture that shape everything around it:
 *  - it is a hardware layer, so `screencap` cannot see it, and nothing can blur or round it;
 *  - only one of them can exist at a time, so it is either the backdrop or a tile, never both;
 *  - a third-party app gets no audio, which is why the preview is silent by design.
 *
 * Verified on Xiaomi MiTV-MFTR0 (Android 14, MediaTek): tune(HW5) -> onVideoAvailable, 75s solid.
 */
object TvPreview {
    /** Whether the picture is showing. Not persisted yet; step 1 flips it from ADB. */
    var on by mutableStateOf(false)
        private set

    /** The input being watched, or null when nothing is showing. */
    var inputId: String? by mutableStateOf(null)
        private set

    /**
     * How much the picture is darkened, 0..1. [HomeBackground] reads it inside its canvas, so
     * changing it while the home screen is up redraws that canvas straight away.
     */
    var scrimAlpha by mutableStateOf(SCRIM_DEFAULT)
        private set

    fun set(context: Context, on: Boolean) {
        this.on = on
        // The box is on HDMI more often than not, and a tuner cannot be tuned by a third-party
        // app at all, so only passthrough inputs can be candidates.
        inputId = if (on) SystemProbe.sources(context).firstOrNull { it.kind == SourceKind.Hdmi }?.id else null
        Log.d(TAG, "set on=$on inputId=$inputId")
    }

    /**
     * Raised whenever the picture has to be tuned again from scratch, which is what a view that
     * lost its surface needs: without it the session stays up, `onVideoAvailable` never comes
     * again, and the screen stays black behind the home screen.
     */
    var generation by mutableStateOf(0)
        private set

    /** Darkening in percent, 0 = none. Meant for trying values out on a real TV, not for users. */
    fun setDim(percent: Int) {
        scrimAlpha = percent.coerceIn(0, 100) / 100f
        Log.d(TAG, "setDim $percent% -> $scrimAlpha")
    }

    /**
     * Re-tunes the picture after the home screen came back to the front.
     *
     * A TvView keeps its session when something else covers the screen, but the surface under it
     * is gone: coming back from another app it recovers on its own after a couple of seconds, yet
     * coming back from the screensaver it never does, and only a fresh tune brings the picture
     * back.
     */
    fun retune() {
        if (!on) return
        generation++
        Log.d(TAG, "retune generation=$generation")
    }
}

/**
 * The picture itself, at the very bottom of the home screen.
 *
 * It has to sit *outside* HomeScreen: that composable's root carries a RenderEffect blur for the
 * Control Center, and a SurfaceView inside such a layer is not drawn into the offscreen buffer
 * the effect renders through, so the picture would disappear whenever an overlay opened.
 */
@Composable
fun LiveTvBackdrop(modifier: Modifier = Modifier) {
    val inputId = TvPreview.inputId ?: return
    // Read so that a re-tune recomposes: the key below has to change for the view to tune again.
    val generation = TvPreview.generation
    AndroidView(
        factory = { context ->
            TvView(context).apply {
                // A TvView is focusable by default and would take the D-pad away from the grid,
                // and its SurfaceView swallows touches (as any SurfaceView does).
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setCallback(object : TvView.TvInputCallback() {
                    override fun onVideoAvailable(id: String) {
                        Log.d(TAG, "onVideoAvailable $id")
                    }

                    override fun onVideoUnavailable(id: String, reason: Int) {
                        // 2 is WEAK_SIGNAL: nothing on that port yet, e.g. the box is still booting.
                        Log.d(TAG, "onVideoUnavailable $id reason=$reason")
                    }

                    override fun onConnectionFailed(id: String) {
                        Log.d(TAG, "onConnectionFailed $id")
                    }

                    override fun onDisconnected(id: String) {
                        Log.d(TAG, "onDisconnected $id")
                    }
                })
            }
        },
        // Keeps it out of Compose's focus search as well as the View system's.
        modifier = modifier.fillMaxSize().focusProperties { canFocus = false },
        update = { view ->
            // Tag the view with what it is tuned to, and with the generation, so that switching
            // inputs and being re-tuned both take effect while nothing else does.
            val key = "$inputId#$generation"
            if (view.tag != key) {
                view.tag = key
                view.reset()
                view.postDelayed({
                    // The tag is cleared on release, so a tune that arrives late does nothing.
                    if (view.tag == key) {
                        Log.d(TAG, "tune $inputId")
                        view.tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId))
                    }
                }, TUNE_DELAY_MS)
            }
        },
        onRelease = { view ->
            view.tag = null
            view.reset()
        },
    )
}

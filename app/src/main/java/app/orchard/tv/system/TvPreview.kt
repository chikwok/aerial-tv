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

/**
 * Darkening on the top row, where the box picture is what the owner came for: light enough that
 * it still reads as television rather than as a wallpaper.
 */
private const val SCRIM_REST = 0.30f

/**
 * Darkening once the grid has come in. The same 0.55 a wallpaper gets there (see HomeBackground),
 * so the two backdrop kinds look like one feature rather than two.
 */
private const val SCRIM_GRID = 0.55f

/**
 * A passthrough picture behind the home screen: the set-top box on HDMI 1, watched over the
 * desktop the way a TV's own elder mode does it.
 *
 * Three things about a passthrough picture shape everything around it:
 *  - it is a hardware layer, so `screencap` cannot see it, and nothing can blur or round it;
 *  - only one of them can exist at a time, so it is either the backdrop or a tile, never both;
 *  - a third-party app gets no audio, which is why the picture is silent by design.
 *
 * Which input, or none, is `AerialSettings.liveTvSource`; this object only holds what the view
 * needs, and follows that setting (see MainActivity).
 *
 * Verified on Xiaomi MiTV-MFTR0 (Android 14, MediaTek): tune(HW5) -> onVideoAvailable, 75s solid;
 * 24 moves of navigation cost 3ms at the 50th percentile and 4-6ms at the 90th, against 14/15ms
 * with the picture off.
 */
object TvPreview {
    /** The input being watched, or null when nothing is showing. */
    var inputId: String? by mutableStateOf(null)
        private set

    /** Whether the picture is on at all, which is what the setting and the tile need to know. */
    val on get() = inputId != null

    /**
     * Set while something else on the home screen is using the picture's hardware path. The view
     * is dropped rather than left tuned: see [suppress].
     */
    var suppressed by mutableStateOf(false)
        private set

    /** Whether the picture is on the screen right now, which is what the backdrop needs to know. */
    val showing get() = on && !suppressed

    /** Darkening on the top row, 0..1; [HomeBackground] reads it inside its canvas. */
    var scrimRest by mutableStateOf(SCRIM_REST)
        private set

    /** Darkening once the grid has come in, 0..1. */
    var scrimGrid by mutableStateOf(SCRIM_GRID)
        private set

    /**
     * Raised whenever the picture has to be tuned again from scratch, which is what a view that
     * lost its surface needs: without it the session stays up, `onVideoAvailable` never comes
     * again, and the screen stays black behind the home screen.
     */
    var generation by mutableStateOf(0)
        private set

    /** Follows the setting, and is the only way the view learns which input to show. */
    fun setInput(id: String?) {
        if (inputId == id) return
        inputId = id
        Log.d(TAG, "input ${id ?: "none"}")
    }

    /**
     * The input the picture should default to: the first one a third-party app can tune at all.
     * A tuner is never a candidate (see [SourceKind]), so a set-top box over HDMI is the case.
     */
    fun firstPassthrough(context: Context): TvSource? =
        SystemProbe.sources(context).firstOrNull { it.kind == SourceKind.Hdmi || it.kind == SourceKind.DisplayPort }

    /** Darkening in percent, 0 = none. Meant for trying values out on a real TV, not for users. */
    fun setDim(percent: Int) {
        scrimGrid = percent.coerceIn(0, 100) / 100f
        // The top row keeps its share of the grid's darkening, so one number sets both.
        scrimRest = scrimGrid * (SCRIM_REST / SCRIM_GRID)
        Log.d(TAG, "setDim ${percent}% -> rest $scrimRest grid $scrimGrid")
    }

    /**
     * Re-tunes the picture after the home screen came back to the front.
     *
     * A TvView keeps its session when something else covers the screen, but the surface under it
     * is gone. Coming back from another app it recovers on its own after a couple of seconds;
     * asking for it brings the picture back in about one.
     */
    fun retune() {
        if (!on) return
        generation++
        Log.d(TAG, "retune generation=$generation")
    }

    /**
     * Gives the picture up, and takes it back, while the screensaver is up.
     *
     * The screensaver plays a film on a SurfaceView of its own, and on a MediaTek box the one
     * path that carries a passthrough picture and the one that carries a decoded film contend
     * for the same overlay. The film wins, and when it goes away the picture does not come back
     * on its own: the session still believes it has video, so no callback fires and the screen
     * simply stays black.
     *
     * Dropping the view while the film plays avoids the contention entirely, and putting it back
     * tunes from scratch, which is what brings the picture back.
     */
    fun suppress(value: Boolean) {
        if (suppressed == value) return
        suppressed = value
        Log.d(TAG, "suppress $value (on=$on)")
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
    // Dropped entirely while suppressed, which is also what releases the view (and so the
    // hardware path) and lets it tune from scratch when it comes back.
    val inputId = (if (TvPreview.suppressed) null else TvPreview.inputId) ?: return
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

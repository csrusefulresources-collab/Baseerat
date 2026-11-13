package com.baseerat.vision.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.accessibility.AccessibilityViewCommand
import com.baseerat.vision.BaseeratApp
import com.baseerat.vision.R
import com.baseerat.vision.ui.CameraActivity
import com.baseerat.vision.ui.MainActivity
import com.baseerat.vision.util.BitmapUtil
import com.baseerat.vision.util.Haptics
import com.baseerat.vision.util.LocaleHelper
import com.baseerat.vision.util.ShortcutActions
import com.baseerat.vision.vision.ResultFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Baseerat accessibility service.
 *  - Draggable floating button (TalkBack-operable) shown through TYPE_ACCESSIBILITY_OVERLAY,
 *    so no "draw over other apps" permission and no foreground service are needed.
 *  - Click -> action menu; long press -> describe the screen immediately.
 *  - Screenshot -> offline vision pipeline (labels, OCR, people, colours).
 *  - Lists tappable elements and guesses names for unlabeled buttons.
 *  - Optional volume-key shortcuts, accessibility-button and Quick Settings tile entry points.
 */
class BaseeratAccessibilityService : AccessibilityService() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private val app: BaseeratApp get() = application as BaseeratApp

    private var fab: ImageView? = null
    private var fabParams: WindowManager.LayoutParams? = null
    private var fabConfig: FabConfig? = null
    private var menuRoot: View? = null
    private var resultRoot: View? = null
    private var resultText: TextView? = null
    private var lastResult: String = ""
    private var taskJob: Job? = null

    private data class FabConfig(
        val enabled: Boolean,
        val size: String,
        val preset: String,
        val x: Int,
        val y: Int,
        val language: String
    )

    private class FocusSnapshot(
        val bounds: Rect,
        val label: String,
        val className: String?,
        val idHint: String,
        val clickable: Boolean,
        val checked: Boolean?,
        val pkg: String?
    )

    private class UiElement(
        val label: String,
        val idHint: String,
        val role: String,
        val bounds: Rect,
        val checked: Boolean?
    )

    private class ShotResult(val bitmap: Bitmap?, val code: Int)

    private var lastFocus: FocusSnapshot? = null

    /** Per-key state for the volume-button gestures (double press / hold). */
    private class KeyGesture {
        var lastDownTime = 0L
        var consumeRelease = false
        var volBefore = -1
        var holdRunnable: Runnable? = null
    }

    private val upGesture = KeyGesture()
    private val downGesture = KeyGesture()
    private val keyHandler = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager? by lazy { getSystemService(AudioManager::class.java) }

    // ------------------------------------------------------------------ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _running.value = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        scope.launch {
            app.settings.flow
                .map { FabConfig(it.overlayEnabled, it.overlaySize, it.overlayPreset, it.overlayX, it.overlayY, it.language) }
                .distinctUntilChanged()
                .collect { applyFab(it) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                val src = event.source ?: return
                try {
                    // Ignore focus on our own overlay windows (button / menu / result panel)
                    val isOverlay = src.window?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                    if (!isOverlay) lastFocus = snapshotOf(src)
                } catch (_: Exception) {
                } finally {
                    recycleNode(src)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // the user moved to another app: the remembered focus no longer applies
                val pkg = event.packageName?.toString()
                val f = lastFocus
                if (f != null && pkg != null && pkg != packageName && pkg != f.pkg) lastFocus = null
            }
        }
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        fabConfig?.let { if (it.enabled) positionFab(it) }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        taskJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        upGesture.holdRunnable?.let { keyHandler.removeCallbacks(it) }
        downGesture.holdRunnable?.let { keyHandler.removeCallbacks(it) }
        dismissMenu()
        removeResult()
        removeFab()
        app.speech.stop()
        if (instance === this) instance = null
        _running.value = false
    }

    // ------------------------------------------------------------------ helpers

    /** UI-language context: menus, buttons, titles. */
    private fun lctx(): Context = LocaleHelper.wrap(this)

    /** AI-response-language context: results, answers, spoken text. */
    private fun actx(): Context = LocaleHelper.wrapAi(this)

    private fun s(id: Int): String = lctx().getString(id)
    private fun s(id: Int, vararg args: Any): String = lctx().getString(id, *args)
    private fun a(id: Int): String = actx().getString(id)
    private fun a(id: Int, vararg args: Any): String = actx().getString(id, *args)
    private fun langTag(): String = LocaleHelper.aiLang(this)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun layoutDir(): Int =
        if (LocaleHelper.saved(this) == "ur") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

    private fun screenBounds(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            val dm = resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }

    private class Palette(val bg: Int, val text: Int, val button: Int, val buttonText: Int, val stroke: Int)

    private fun palette(): Palette =
        if (app.settings.current.highContrast) {
            Palette(Color.BLACK, Color.WHITE, 0xFFFFFF00.toInt(), Color.BLACK, Color.WHITE)
        } else {
            Palette(0xFF1A2332.toInt(), 0xFFE8EEF4.toInt(), 0xFF1B4F72.toInt(), Color.WHITE, 0xFF5DADE2.toInt())
        }

    @Suppress("DEPRECATION")
    private fun recycleNode(n: AccessibilityNodeInfo?) {
        if (n != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try { n.recycle() } catch (_: Exception) { }
        }
    }

    private fun snapshotOf(n: AccessibilityNodeInfo): FocusSnapshot {
        val r = Rect()
        n.getBoundsInScreen(r)
        var label = n.text?.toString()?.trim().orEmpty()
        if (label.isEmpty()) label = n.contentDescription?.toString()?.trim().orEmpty()
        if (label.isEmpty()) label = n.hintText?.toString()?.trim().orEmpty()
        if (label.isEmpty()) label = descendantText(n, 0)
        val id = n.viewIdResourceName?.substringAfterLast('/')?.replace('_', ' ')?.trim().orEmpty()
        return FocusSnapshot(
            r, label, n.className?.toString(), id,
            n.isClickable || n.isLongClickable,
            if (n.isCheckable) n.isChecked else null,
            n.packageName?.toString()
        )
    }

    private fun roleName(cls: String?): String {
        val c = cls ?: ""
        return a(
            when {
                c.contains("Switch") || c.contains("Toggle") -> R.string.role_switch
                c.contains("CheckBox") -> R.string.role_checkbox
                c.contains("Radio") -> R.string.role_radio
                c.contains("EditText") -> R.string.role_edit
                c.contains("ImageButton") || c.contains("ImageView") -> R.string.role_image
                c.contains("Button") -> R.string.role_button
                c.contains("Tab") -> R.string.role_tab
                else -> R.string.role_item
            }
        )
    }

    private fun isImageLike(cls: String?): Boolean =
        cls != null && (cls.contains("ImageView") || cls.contains("ImageButton"))

    // ------------------------------------------------------------------ floating button

    private fun applyFab(c: FabConfig) {
        val old = fabConfig
        fabConfig = c
        if (!c.enabled) {
            removeFab()
            return
        }
        if (fab == null || old == null || old.size != c.size || old.language != c.language || !old.enabled) {
            removeFab()
            addFab(c)
        } else {
            positionFab(c)
        }
    }

    private fun fabSizePx(size: String): Int = dp(
        when (size) {
            "small" -> 52
            "large" -> 88
            else -> 68
        }
    )

    private fun addFab(c: FabConfig) {
        val size = fabSizePx(c.size)
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_eye)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(size / 4, size / 4, size / 4, size / 4)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (app.settings.current.highContrast) Color.BLACK else 0xF01B4F72.toInt())
                setStroke(dp(3), if (app.settings.current.highContrast) Color.YELLOW else Color.WHITE)
            }
            contentDescription = s(R.string.fab_description)
            isClickable = true
            isLongClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            layoutDirection = layoutDir()
            setOnClickListener { onFabClick() }
            setOnLongClickListener { perform(app.settings.current.fabLong); true }
        }

        ViewCompat.replaceAccessibilityAction(view, AccessibilityActionCompat.ACTION_CLICK, s(R.string.fab_click_label), null)
        ViewCompat.replaceAccessibilityAction(view, AccessibilityActionCompat.ACTION_LONG_CLICK, s(R.string.fab_long_click_label), null)
        addAction(view, R.string.menu_describe_screen) { describeScreen() }
        addAction(view, R.string.menu_describe_focused) { describeFocused() }
        addAction(view, R.string.menu_read_text) { readScreenText() }
        addAction(view, R.string.menu_list_buttons) { listElements() }
        addAction(view, R.string.act_move_tl) { movePreset("tl") }
        addAction(view, R.string.act_move_tr) { movePreset("tr") }
        addAction(view, R.string.act_move_cr) { movePreset("cr") }
        addAction(view, R.string.act_move_bl) { movePreset("bl") }
        addAction(view, R.string.act_move_br) { movePreset("br") }

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            title = s(R.string.fab_description)
        }
        val pos = targetPosition(c, size)
        params.x = pos.first
        params.y = pos.second
        attachDrag(view, params)
        try {
            wm.addView(view, params)
            fab = view
            fabParams = params
        } catch (e: Exception) {
            fab = null
            fabParams = null
        }
    }

    private fun addAction(view: View, labelRes: Int, block: () -> Unit) {
        ViewCompat.addAccessibilityAction(view, s(labelRes), AccessibilityViewCommand { _, _ -> block(); true })
    }

    private fun removeFab() {
        fab?.let { runCatching { wm.removeView(it) } }
        fab = null
        fabParams = null
    }

    private fun targetPosition(c: FabConfig, size: Int): Pair<Int, Int> {
        val b = screenBounds()
        val w = b.width()
        val h = b.height()
        val m = dp(8)
        val right = w - size - m
        val preset = if (c.preset.isEmpty() && c.x == 0 && c.y == 0) "cr" else c.preset
        return when (preset) {
            "tl" -> m to (h * 0.12f).toInt()
            "tr" -> right to (h * 0.12f).toInt()
            "cl" -> m to (h / 2 - size / 2)
            "bl" -> m to (h * 0.78f).toInt()
            "br" -> right to (h * 0.78f).toInt()
            "" -> c.x.coerceIn(0, (w - size).coerceAtLeast(0)) to c.y.coerceIn(0, (h - size).coerceAtLeast(0))
            else -> right to (h / 2 - size / 2)
        }
    }

    private fun positionFab(c: FabConfig) {
        val v = fab ?: return
        val p = fabParams ?: return
        val pos = targetPosition(c, p.width)
        p.x = pos.first
        p.y = pos.second
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun movePreset(preset: String) {
        scope.launch { app.settings.setOverlayPreset(preset) }
        announce(s(R.string.fab_moved))
    }

    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
        val handler = Handler(Looper.getMainLooper())
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var longFired = false
        val longRunnable = Runnable {
            if (!dragging) {
                longFired = true
                view.performLongClick()
            }
        }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false; longFired = false
                    handler.postDelayed(longRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        handler.removeCallbacks(longRunnable)
                    }
                    if (dragging) {
                        val b = screenBounds()
                        params.x = (startX + dx).toInt().coerceIn(0, (b.width() - params.width).coerceAtLeast(0))
                        params.y = (startY + dy).toInt().coerceIn(0, (b.height() - params.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longRunnable)
                    if (dragging) {
                        snapToEdgeAndSave(v, params)
                    } else if (!longFired) {
                        v.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longRunnable)
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdgeAndSave(v: View, p: WindowManager.LayoutParams) {
        val b = screenBounds()
        val m = dp(8)
        p.x = if (p.x + p.width / 2 < b.width() / 2) m else b.width() - p.width - m
        runCatching { wm.updateViewLayout(v, p) }
        val x = p.x
        val y = p.y
        scope.launch { app.settings.setOverlayPosition(x, y) }
    }

    private fun onFabClick() {
        perform(app.settings.current.fabTap)
    }

    // ------------------------------------------------------------------ menu

    private class DismissFrame(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private class MaxHeightScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }

    private fun roundedBg(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(2), strokeColor)
        }

    private fun makeButton(label: String, pal: Palette, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 18f
            minHeight = dp(56)
            minimumHeight = dp(56)
            setTextColor(pal.buttonText)
            background = roundedBg(pal.button, 12, pal.stroke)
            setOnClickListener { onClick() }
        }

    private fun makeTitle(label: String, pal: Palette): TextView =
        TextView(this).apply {
            text = label
            textSize = 22f
            setTextColor(pal.text)
            setPadding(0, 0, 0, dp(8))
            ViewCompat.setAccessibilityHeading(this, true)
        }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    fun showMenu() {
        if (!::wm.isInitialized || menuRoot != null) return
        val pal = palette()
        val root = DismissFrame(this) { dismissMenu() }
        root.setBackgroundColor(0xAA000000.toInt())
        root.layoutDirection = layoutDir()
        root.setOnClickListener { dismissMenu() }
        root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(pal.bg, 20, pal.stroke)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        ViewCompat.setAccessibilityPaneTitle(card, s(R.string.menu_title))
        card.addView(makeTitle(s(R.string.menu_title), pal), lp())
        card.addView(makeButton(s(R.string.menu_describe_screen), pal) { dismissMenu(); describeScreen() }, lp(8))
        card.addView(makeButton(s(R.string.menu_describe_focused), pal) { dismissMenu(); describeFocused() }, lp(8))
        card.addView(makeButton(s(R.string.menu_read_text), pal) { dismissMenu(); readScreenText() }, lp(8))
        card.addView(makeButton(s(R.string.menu_list_buttons), pal) { dismissMenu(); listElements() }, lp(8))
        card.addView(makeButton(s(R.string.menu_chat), pal) { dismissMenu(); openChat() }, lp(8))
        card.addView(makeButton(s(R.string.menu_camera), pal) { dismissMenu(); openCamera("describe") }, lp(8))
        card.addView(makeButton(s(R.string.menu_camera_text), pal) { dismissMenu(); openCamera("read") }, lp(8))
        card.addView(makeButton(s(R.string.menu_cancel), pal) { dismissMenu() }, lp(16))

        val b = screenBounds()
        val scroll = MaxHeightScrollView(this, (b.height() * 0.9f).toInt())
        scroll.addView(card)
        root.addView(
            scroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                .apply { setMargins(dp(20), dp(20), dp(20), dp(20)) }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            title = s(R.string.menu_title)
        }
        try {
            wm.addView(root, params)
            menuRoot = root
        } catch (_: Exception) {
            menuRoot = null
        }
    }

    /** Used by the Quick Settings tile: closes the shade first, then opens the menu. */
    fun openMenuFromTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        scope.launch {
            delay(400)
            showMenu()
        }
    }

    private fun dismissMenu() {
        menuRoot?.let { runCatching { wm.removeView(it) } }
        menuRoot = null
    }

    // ------------------------------------------------------------------ result panel

    private fun showResult(text: String) {
        lastResult = text
        val tv = resultText
        if (resultRoot != null && tv != null) {
            tv.text = text            // live region announces the update
            return
        }
        val pal = palette()
        val root = DismissFrame(this) { removeResult() }
        root.layoutDirection = layoutDir()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(pal.bg, 20, pal.stroke)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        ViewCompat.setAccessibilityPaneTitle(card, s(R.string.result_title))
        card.addView(makeTitle(s(R.string.result_title), pal), lp())

        val body = TextView(this).apply {
            this.text = text
            textSize = 18f
            setTextColor(pal.text)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setLineSpacing(0f, 1.15f)
        }
        resultText = body
        val b = screenBounds()
        val scroll = MaxHeightScrollView(this, (b.height() * 0.38f).toInt())
        scroll.addView(body)
        card.addView(scroll, lp())

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun half(btn: Button, first: Boolean) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { if (!first) marginStart = dp(8) }
        val bRepeat = makeButton(s(R.string.btn_repeat), pal) { speakResult(lastResult, force = true) }
        val bStop = makeButton(s(R.string.btn_stop), pal) { app.speech.stop() }
        val bCopy = makeButton(s(R.string.btn_copy), pal) { copyResult() }
        val bClose = makeButton(s(R.string.btn_close), pal) { removeResult() }
        row1.addView(bRepeat, half(bRepeat, true)); row1.addView(bStop, half(bStop, false))
        row2.addView(bCopy, half(bCopy, true)); row2.addView(bClose, half(bClose, false))
        card.addView(row1, lp(12))
        card.addView(row2, lp(8))

        root.addView(
            card,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
                .apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            title = s(R.string.result_title)
        }
        try {
            wm.addView(root, params)
            resultRoot = root
        } catch (_: Exception) {
            resultRoot = null
            resultText = null
        }
    }

    private fun removeResult() {
        resultRoot?.let { runCatching { wm.removeView(it) } }
        resultRoot = null
        resultText = null
    }

    private fun copyResult() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("baseerat", lastResult))
        announce(s(R.string.copied))
    }

    private fun announce(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        fab?.announceForAccessibility(text)
    }

    private fun speakResult(text: String, force: Boolean = false) {
        if (force || app.settings.current.autoSpeak) app.speech.speak(text, langTag())
    }

    private fun deliver(text: String, source: String, mode: String, save: Boolean = true) {
        showResult(text)
        speakResult(text)
        if (app.settings.current.vibrate) Haptics.tick(this)
        if (save && app.settings.current.saveHistory) app.history.add(source, mode, text)
    }

    private fun launchTask(block: suspend () -> Unit) {
        taskJob?.cancel()
        taskJob = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                restoreFab()
                deliver(s(R.string.err_generic), "screen", "describe", save = false)
            }
        }
    }

    // ------------------------------------------------------------------ screenshots

    private fun restoreFab() {
        fab?.visibility = View.VISIBLE
    }

    /** Hides our own button + panels so they are not part of the screenshot. */
    private suspend fun captureHidden(): Bitmap? {
        dismissMenu()
        removeResult()
        fab?.visibility = View.INVISIBLE
        delay(350)
        val bmp = captureScreen()
        restoreFab()
        return bmp
    }

    private suspend fun captureScreen(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        for (attempt in 0 until 2) {
            val r = takeShot()
            if (r.bitmap != null) return r.bitmap
            // The system rejects screenshots taken less than ~1s apart: wait and retry once
            if (r.code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt == 0) delay(1100) else return null
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeShot(): ShotResult = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hb = screenshot.hardwareBuffer
                    var soft: Bitmap? = null
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                        // copy BEFORE closing the buffer
                        soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                    } catch (_: Exception) {
                    } finally {
                        hb.close()
                    }
                    if (cont.isActive) cont.resume(ShotResult(soft, 0))
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(ShotResult(null, errorCode))
                }
            })
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(ShotResult(null, -1))
        }
    }

    // ------------------------------------------------------------------ actions

    /** Runs any configured shortcut action (floating button, volume keys, tile). */
    fun perform(action: String) {
        when (action) {
            ShortcutActions.MENU -> showMenu()
            ShortcutActions.SCREEN -> describeScreen()
            ShortcutActions.FOCUSED -> describeFocused()
            ShortcutActions.READ -> readScreenText()
            ShortcutActions.BUTTONS -> listElements()
            ShortcutActions.CAMERA -> openCamera("describe")
            ShortcutActions.CAMERA_READ -> openCamera("read")
            ShortcutActions.CHAT -> openChat()
            ShortcutActions.REPEAT ->
                if (lastResult.isNotBlank()) speakResult(lastResult, force = true) else announce(a(R.string.nothing_to_repeat))
            ShortcutActions.STOP -> app.speech.stop()
            else -> Unit
        }
    }

    fun scheduleDescribeScreen(delayMs: Long) {
        scope.launch {
            delay(delayMs)
            describeScreen()
        }
    }

    fun describeScreen() = launchTask {
        val shot = captureHidden()
        if (shot == null) {
            deliver(treeTextFallback(), "screen", "describe")
            return@launchTask
        }
        showResult(a(R.string.analyzing))
        if (app.settings.current.autoSpeak) app.speech.speak(a(R.string.analyzing), langTag())
        val analysis = app.vision.analyze(shot)
        var text = ResultFormatter.describe(actx(), analysis, app.settings.current.detailed)
        val unlabeled = unlabeledCount()
        if (unlabeled > 0) text += "\n\n" + a(R.string.hint_unlabeled, unlabeled)
        deliver(text, "screen", "describe")
    }

    /**
     * ONLY the item that currently has accessibility focus (the one TalkBack is on):
     * its name, type and state; if it has no name, a guess from its picture.
     */
    fun describeFocused() = launchTask {
        dismissMenu()
        delay(250)
        val f = currentFocus()
        if (f == null) {
            deliver(a(R.string.no_focus_item), "focus", "describe", save = false)
            return@launchTask
        }
        val c = actx()
        val role = roleName(f.className)
        val state = when (f.checked) {
            true -> " (" + c.getString(R.string.el_state_on) + ")"
            false -> " (" + c.getString(R.string.el_state_off) + ")"
            null -> ""
        }
        if (f.label.isNotEmpty()) {
            deliver(a(R.string.fmt_focus_item, role, f.label + state), "focus", "describe")
            return@launchTask
        }

        // The focused item has no name: look at the item itself.
        val shot = captureHidden()
        val crop = shot?.let { BitmapUtil.crop(it, f.bounds.left, f.bounds.top, f.bounds.right, f.bounds.bottom) }
        if (crop == null) {
            deliver(a(R.string.focus_unreadable, role), "focus", "describe")
            return@launchTask
        }
        showResult(a(R.string.analyzing))
        val sb = screenBounds()
        val pos = ResultFormatter.positionName(
            c, f.bounds.exactCenterX(), f.bounds.exactCenterY(),
            shot?.width ?: sb.width(), shot?.height ?: sb.height()
        )
        if (isImageLike(f.className) && !f.clickable) {
            val an = app.vision.analyze(crop)
            deliver(a(R.string.fmt_focus_image, role) + "\n\n" + ResultFormatter.describe(c, an, false), "focus", "describe")
        } else {
            val g = app.vision.guessLabel(crop)
            val msg = when {
                !g.isNullOrBlank() -> a(R.string.fmt_focus_noname_guess, role, g, pos)
                f.idHint.isNotEmpty() -> a(R.string.fmt_focus_noname_id, role, f.idHint, pos)
                else -> a(R.string.fmt_focus_noname, role, pos)
            }
            deliver(msg, "focus", "describe")
        }
    }

    fun readScreenText() = launchTask {
        val shot = captureHidden()
        if (shot == null) {
            deliver(treeTextFallback(), "screen", "read")
            return@launchTask
        }
        showResult(a(R.string.analyzing))
        val o = app.vision.readText(shot)
        var text = ResultFormatter.readText(actx(), o)
        if (o.text.isBlank()) {
            val tree = treeText()
            if (tree.isNotBlank()) text = tree
        }
        deliver(text, "screen", "read")
    }

    /** ALL tappable items on the screen (the focused-only variant is [describeFocused]). */
    fun listElements() = launchTask {
        dismissMenu()
        delay(250)
        val root = rootInActiveWindow
        if (root == null) {
            deliver(a(R.string.no_screen_content), "screen", "elements", save = false)
            return@launchTask
        }
        val elements = try { collectElements(root) } finally { recycleNode(root) }
        if (elements.isEmpty()) {
            deliver(a(R.string.no_elements), "screen", "elements", save = false)
            return@launchTask
        }
        val unlabeledIdx = elements.indices.filter { elements[it].label.isBlank() }
        var shot: Bitmap? = null
        if (unlabeledIdx.isNotEmpty()) shot = captureHidden()
        showResult(a(R.string.analyzing))

        val guesses = HashMap<Int, String>()
        if (shot != null) {
            for (i in unlabeledIdx.take(12)) {
                val b = elements[i].bounds
                val crop = BitmapUtil.crop(shot, b.left, b.top, b.right, b.bottom) ?: continue
                val g = app.vision.guessLabel(crop)
                if (!g.isNullOrBlank()) guesses[i] = g
            }
        }
        val b = screenBounds()
        val w = shot?.width ?: b.width()
        val h = shot?.height ?: b.height()
        val c = actx()
        val sb = StringBuilder(c.getString(R.string.fmt_elements_header, elements.size, unlabeledIdx.size))
        elements.take(40).forEachIndexed { i, el ->
            val n = i + 1
            val pos = ResultFormatter.positionName(c, el.bounds.exactCenterX(), el.bounds.exactCenterY(), w, h)
            val state = when (el.checked) {
                true -> " (" + c.getString(R.string.el_state_on) + ")"
                false -> " (" + c.getString(R.string.el_state_off) + ")"
                null -> ""
            }
            sb.append('\n')
            when {
                el.label.isNotBlank() -> sb.append(c.getString(R.string.fmt_el_labeled, n, el.role, el.label + state))
                guesses[i] != null -> sb.append(c.getString(R.string.fmt_el_unlabeled_guess, n, el.role, guesses[i]!!, pos))
                el.idHint.isNotBlank() -> sb.append(c.getString(R.string.fmt_el_unlabeled_id, n, el.role, el.idHint, pos))
                else -> sb.append(c.getString(R.string.fmt_el_unlabeled, n, el.role, pos))
            }
        }
        if (elements.size > 40) sb.append('\n').append(c.getString(R.string.elements_truncated, elements.size - 40))
        deliver(sb.toString(), "screen", "elements")
    }

    fun openCamera(mode: String) {
        dismissMenu()
        try {
            startActivity(
                Intent(this, CameraActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(CameraActivity.EXTRA_MODE, mode)
            )
        } catch (_: Exception) {
            announce(s(R.string.err_generic))
        }
    }

    fun openChat() {
        dismissMenu()
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN, "chat")
            )
        } catch (_: Exception) {
            announce(s(R.string.err_generic))
        }
    }

    // ------------------------------------------------------------------ accessibility tree

    private fun currentFocus(): FocusSnapshot? {
        lastFocus?.let { return it }
        val root = rootInActiveWindow ?: return null
        try {
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null) {
                val snap = snapshotOf(f)
                recycleNode(f)
                return snap
            }
        } finally {
            recycleNode(root)
        }
        return null
    }

    private fun descendantText(node: AccessibilityNodeInfo, depth: Int): String {
        if (depth > 4) return ""
        val parts = ArrayList<String>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val t = child.text?.toString()?.trim().orEmpty().ifEmpty {
                child.contentDescription?.toString()?.trim().orEmpty()
            }
            if (t.isNotEmpty()) parts.add(t) else {
                val deeper = descendantText(child, depth + 1)
                if (deeper.isNotEmpty()) parts.add(deeper)
            }
            recycleNode(child)
        }
        return parts.joinToString(" ")
    }

    private fun collectElements(root: AccessibilityNodeInfo): List<UiElement> {
        val out = ArrayList<UiElement>()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || out.size > 150) return
            val interactive = node.isClickable || node.isCheckable || node.isEditable || node.isLongClickable
            if (node.isVisibleToUser && interactive) {
                val b = Rect()
                node.getBoundsInScreen(b)
                if (!b.isEmpty) {
                    var label = node.text?.toString()?.trim().orEmpty()
                    if (label.isEmpty()) label = node.contentDescription?.toString()?.trim().orEmpty()
                    if (label.isEmpty()) label = node.hintText?.toString()?.trim().orEmpty()
                    if (label.isEmpty()) label = descendantText(node, 0)
                    val id = node.viewIdResourceName?.substringAfterLast('/')?.replace('_', ' ')?.trim().orEmpty()
                    out.add(
                        UiElement(
                            label, id, roleName(node.className?.toString()), b,
                            if (node.isCheckable) node.isChecked else null
                        )
                    )
                }
                return
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
                recycleNode(child)
            }
        }
        walk(root, 0)
        return out.sortedWith(compareBy({ it.bounds.top / 48 }, { it.bounds.left }))
    }

    private fun unlabeledCount(): Int {
        val root = rootInActiveWindow ?: return 0
        return try {
            collectElements(root).count { it.label.isBlank() }
        } catch (_: Exception) {
            0
        } finally {
            recycleNode(root)
        }
    }

    private fun treeText(): String {
        val root = rootInActiveWindow ?: return ""
        val parts = ArrayList<String>()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || parts.size > 200) return
            if (node.isVisibleToUser) {
                val t = node.text?.toString()?.trim().orEmpty().ifEmpty {
                    node.contentDescription?.toString()?.trim().orEmpty()
                }
                if (t.isNotEmpty()) parts.add(t)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
                recycleNode(child)
            }
        }
        try { walk(root, 0) } finally { recycleNode(root) }
        return parts.joinToString(".\n").take(3000)
    }

    private fun treeTextFallback(): String {
        val t = treeText()
        return if (t.isBlank()) a(R.string.screen_capture_failed)
        else a(R.string.screen_capture_failed_with_text) + "\n\n" + t
    }

    // ------------------------------------------------------------------ volume keys

    private fun currentMusicVolume(): Int = try {
        audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
    } catch (_: Exception) {
        -1
    }

    /** The first press of a gesture already changed the volume by one step: put it back. */
    private fun restoreVolume(g: KeyGesture) {
        if (!app.settings.current.volumeRestore || g.volBefore < 0) return
        try {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, g.volBefore, 0)
        } catch (_: Exception) {
        }
    }

    /**
     * Volume-key gestures, each mapped to any [ShortcutActions] in settings:
     *  - double press (second press within 450 ms)
     *  - hold (about 0.6 s)
     * Keys with NO gesture configured behave normally. The first press of a gesture still reaches
     * the system (we cannot know yet that a gesture follows); the volume is restored afterwards.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val up = code == KeyEvent.KEYCODE_VOLUME_UP
        if (!up && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false

        val st = app.settings.current          // cached snapshot: never block the key thread
        val doubleAction = if (up) st.volUpDouble else st.volDownDouble
        val holdAction = if (up) st.volUpHold else st.volDownHold
        val hasDouble = doubleAction != ShortcutActions.NONE
        val hasHold = holdAction != ShortcutActions.NONE
        if (!hasDouble && !hasHold) return false
        val g = if (up) upGesture else downGesture

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // auto-repeat while held: swallow it if a hold gesture exists (no runaway volume change)
                if (event.repeatCount > 0) return hasHold || g.consumeRelease

                val now = SystemClock.uptimeMillis()
                if (hasDouble && g.lastDownTime != 0L && now - g.lastDownTime <= DOUBLE_PRESS_MS) {
                    g.lastDownTime = 0L
                    g.holdRunnable?.let { keyHandler.removeCallbacks(it) }
                    g.holdRunnable = null
                    g.consumeRelease = true
                    restoreVolume(g)
                    perform(doubleAction)
                    return true
                }
                g.lastDownTime = now
                g.volBefore = currentMusicVolume()
                g.holdRunnable?.let { keyHandler.removeCallbacks(it) }
                g.holdRunnable = null
                if (hasHold) {
                    val r = Runnable {
                        g.holdRunnable = null
                        g.lastDownTime = 0L
                        g.consumeRelease = true
                        restoreVolume(g)
                        perform(holdAction)
                    }
                    g.holdRunnable = r
                    keyHandler.postDelayed(r, HOLD_MS)
                }
                return false
            }
            KeyEvent.ACTION_UP -> {
                g.holdRunnable?.let { keyHandler.removeCallbacks(it) }
                g.holdRunnable = null
                if (g.consumeRelease) {
                    g.consumeRelease = false
                    return true
                }
                return false
            }
        }
        return false
    }

    companion object {
        private const val DOUBLE_PRESS_MS = 450L
        private const val HOLD_MS = 600L

        @Volatile
        var instance: BaseeratAccessibilityService? = null
            private set

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
    }
}

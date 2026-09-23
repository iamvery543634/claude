package com.ghost.hub

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var repo: Repo
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var subtitle: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var heroBox: LinearLayout
    private lateinit var messageBox: LinearLayout
    private lateinit var spotlightBox: LinearLayout
    private lateinit var chipsRow: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var floatBtn: TextView
    private lateinit var footer: TextView

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private var apps: List<AppStatus> = emptyList()
    private var loading = false
    private var filter = Filter.ALL
    private var animateNext = true

    /** Apps downloading right now, with their progress (0..100, -1 = starting). */
    private val busy = HashMap<String, Int>()
    /** Apps we handed to Android's installer, to celebrate when they show up installed. */
    private val installing = HashSet<String>()
    private val updateQueue = ArrayDeque<String>()
    private val rows = HashMap<String, Pair<ProgressRing, TextView>>()

    private enum class Filter(val title: String) { ALL("All"), UPDATES("Updates"), INSTALLED("Installed"), NEW("Not installed") }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        repo = Repo(this)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        swipe = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(col(R.color.accent))
            setProgressBackgroundColorSchemeColor(col(R.color.card))
            setOnRefreshListener { refresh() }
            addView(scroll)
        }
        val root = FrameLayout(this).apply { setBackgroundColor(col(R.color.bg)) }
        root.addView(swipe)

        // Floating "Update all" button, shown whenever updates are waiting.
        floatBtn = label("", 16f, col(R.color.on_accent), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(15), dp(26), dp(15))
            background = rounded(col(R.color.accent), dpf(30f))
            elevation = dpf(10f)
            visibility = View.GONE
            pressable()
            setOnClickListener {
                val updates = apps.filter { it.state == InstallState.UPDATE }
                if (updates.isNotEmpty()) updateAll(updates)
            }
        }
        root.addView(floatBtn, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(root)

        val header = buildHeader()
        body.addView(header)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), dp(28))
        }
        body.addView(content)

        heroBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(heroBox)
        messageBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(messageBox)
        spotlightBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(spotlightBox)
        chipsRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        content.addView(listBox)
        footer = label(footerText(), 12f, col(R.color.text3)).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(22), 0, dp(70))
        }
        content.addView(footer, LinearLayout.LayoutParams(MATCH, WRAP))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            header.updatePadding(top = b.top + dp(8))
            content.updatePadding(bottom = b.bottom + dp(28))
            swipe.setProgressViewOffset(false, b.top, b.top + dp(64))
            (floatBtn.layoutParams as FrameLayout.LayoutParams).bottomMargin = b.bottom + dp(20)
            floatBtn.requestLayout()
            insets
        }

        showSkeleton()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        UpdateReceiver.schedule(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (apps.isNotEmpty()) reReadInstalled()
        // "Update all": move on to the next app once Android's install screen is closed.
        if (updateQueue.isNotEmpty() && busy.isEmpty()) main.postDelayed({ nextInQueue() }, 600)
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- header

    private fun buildHeader(): View {
        val box = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF12283A.toInt(), col(R.color.bg)))
            setPadding(dp(18), dp(8), dp(12), dp(10))
        }
        box.addView(GhostField(this), FrameLayout.LayoutParams(MATCH, MATCH))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val logoBox = FrameLayout(this)
        val glow = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dpf(40f)
                colors = intArrayOf(0x5572E2F7, 0x008B7CF6)
            }
        }
        logoBox.addView(glow, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER))
        logoBox.addView(ImageView(this).apply { setImageResource(R.drawable.ic_logo) }, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))
        listOf(View.SCALE_X, View.SCALE_Y).forEach { prop ->
            ObjectAnimator.ofFloat(glow, prop, 0.85f, 1.15f).apply {
                duration = 1800; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start()
            }
        }
        top.addView(logoBox, LinearLayout.LayoutParams(dp(84), dp(84)))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(roundIcon(R.drawable.ic_settings, "Settings") { showSettings() })
        top.addView(roundIcon(R.drawable.ic_refresh, "Refresh") { swipe.isRefreshing = true; refresh() })
        column.addView(top)

        column.addView(label("Ghost Hub", 34f, col(R.color.text), bold = true).apply { setPadding(dp(2), dp(2), 0, 0) })
        subtitle = label("Your Ghost apps, always up to date", 15f, col(R.color.text2)).apply { setPadding(dp(2), 0, 0, dp(10)) }
        column.addView(subtitle)

        val pill = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0x331D2129, dpf(16f), 0x33FFFFFF, dp(1))
            setPadding(dp(12), dp(7), dp(14), dp(7))
            rippleForeground()
            setOnClickListener { showSettings() }
        }
        statusDot = View(this).apply { background = oval(col(R.color.text3)) }
        pill.addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)))
        statusText = label("Checking for updates…", 13f, col(R.color.text2)).apply { setPadding(dp(8), 0, 0, 0) }
        pill.addView(statusText)
        ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.35f).apply {
            duration = 900; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start()
        }
        column.addView(pill, LinearLayout.LayoutParams(WRAP, WRAP))
        box.addView(column)
        return box
    }

    private fun roundIcon(res: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(col(R.color.text))
        background = oval(0x331D2129)
        contentDescription = desc
        pressable(0.9f)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) }
    }

    // ---------------------------------------------------------------- loading

    private fun refresh() {
        if (loading) return
        loading = true
        setStatus(R.color.yellow, when (repo.source) {
            Repo.Source.GITHUB -> "Checking GitHub…"
            Repo.Source.PC -> if (repo.server != null) "Checking your PC…" else "Looking for your PC…"
            Repo.Source.AUTO -> "Checking for updates…"
        })
        messageBox.removeAllViews()
        pool.execute {
            val result = runCatching { repo.load() }
            main.post {
                loading = false
                swipe.isRefreshing = false
                result.onSuccess {
                    apps = it
                    setStatus(R.color.green, sourceLabel())
                    footer.text = footerText()
                    render()
                }
                result.onFailure { e ->
                    setStatus(R.color.red, "Not connected · tap to fix")
                    showMessage(e.message ?: "Couldn't get the app list.")
                    if (apps.isNotEmpty()) render() else listBox.removeAllViews()
                }
            }
        }
    }

    /** Where the list came from, for the status pill: GitHub, or the PC over Wi-Fi. */
    private fun sourceLabel(): String = when (repo.from) {
        Repo.From.PC -> "Apps from ${repo.serverName ?: "your PC"} (Wi-Fi)"
        else -> "Apps from GitHub"
    }

    private fun footerText(): String = when (repo.from) {
        Repo.From.PC -> "👻  Apps from your PC · nothing leaves your Wi-Fi"
        else -> "👻  Apps from GitHub · updates work anywhere"
    }

    private fun reReadInstalled() {
        pool.execute {
            val pm = packageManager
            val updated = apps.map { st ->
                val installed = runCatching {
                    val info = pm.getPackageInfo(st.entry.pkg, 0)
                    val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                    code to info.versionName
                }.getOrNull()
                AppStatus(st.entry, installed?.first, installed?.second)
            }
            main.post {
                val done = updated.filter { it.entry.pkg in installing && it.state == InstallState.UP_TO_DATE }
                apps = updated
                render()
                done.forEach { celebrate(it) }
            }
        }
    }

    private fun celebrate(st: AppStatus) {
        installing.remove(st.entry.pkg)
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        Toast.makeText(this, "${st.entry.name} ${st.entry.versionName} installed ✓", Toast.LENGTH_SHORT).show()
        rows[st.entry.pkg]?.second?.let { v ->
            v.scaleX = 0.6f
            v.scaleY = 0.6f
            v.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(3f)).setDuration(420).start()
        }
    }

    private fun setStatus(color: Int, text: String) {
        statusDot.background = oval(col(color))
        statusText.text = text
    }

    private fun showMessage(text: String) {
        messageBox.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(col(R.color.card), dpf(20f), col(R.color.warn_stroke), dp(1))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
        }
        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_wifi)
            imageTintList = ColorStateList.valueOf(col(R.color.yellow))
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        texts.addView(label(text, 15f, col(R.color.text)))
        val pcOnly = repo.source == Repo.Source.PC
        texts.addView(label(when (repo.source) {
            Repo.Source.PC -> "Check this phone and your PC are on the same Wi-Fi and the Ghost Hub server is running on the PC."
            Repo.Source.GITHUB -> "Check your internet connection. Choose Auto in Settings to also use your PC on home Wi-Fi."
            Repo.Source.AUTO -> "Check your internet connection. At home, the Hub can also use your PC over Wi-Fi while its server is running."
        }, 13f, col(R.color.text2)).apply { setPadding(0, dp(4), 0, dp(6)) })
        texts.addView(label(if (pcOnly) "Enter PC address" else "Settings", 14f, col(R.color.accent), bold = true).apply {
            rippleForeground(borderless = true)
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { if (pcOnly) showServerDialog() else showSettings() }
        })
        card.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        messageBox.addView(card)
    }

    private fun showSkeleton() {
        listBox.removeAllViews()
        repeat(4) {
            listBox.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(col(R.color.card), dpf(22f))
                setPadding(dp(14), dp(14), dp(14), dp(14))
                addView(View(this@MainActivity).apply { background = rounded(col(R.color.card_hi), dpf(16f)) }, LinearLayout.LayoutParams(dp(56), dp(56)))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, 0, 0)
                    addView(View(this@MainActivity).apply { background = rounded(col(R.color.card_hi), dpf(6f)) }, LinearLayout.LayoutParams(dp(140), dp(14)))
                    addView(View(this@MainActivity).apply { background = rounded(col(R.color.card_hi), dpf(6f)) },
                        LinearLayout.LayoutParams(dp(90), dp(11)).apply { topMargin = dp(8) })
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                shimmer()
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }
    }

    // ---------------------------------------------------------------- rendering

    private fun render() {
        rows.clear()
        val updates = apps.filter { it.state == InstallState.UPDATE }
        val installed = apps.filter { it.state == InstallState.UP_TO_DATE || it.state == InstallState.INSTALLED_NEWER }
        val fresh = apps.filter { it.state == InstallState.NOT_INSTALLED }
        subtitle.text = "${apps.size} Ghost apps · ${installed.size + updates.size} on this phone"

        renderHero(updates)
        renderSpotlight()
        renderChips(updates.size, installed.size, fresh.size)
        renderFloat(updates.size)

        listBox.removeAllViews()
        val shown = when (filter) {
            Filter.ALL -> updates + fresh + installed
            Filter.UPDATES -> updates
            Filter.INSTALLED -> installed
            Filter.NEW -> fresh
        }
        if (shown.isEmpty()) {
            listBox.addView(label(when (filter) {
                Filter.UPDATES -> "No updates right now ✓"
                Filter.NEW -> "You've got every Ghost app 👻"
                else -> "Nothing here yet"
            }, 15f, col(R.color.text2)).apply { gravity = Gravity.CENTER; setPadding(0, dp(28), 0, dp(10)) },
                LinearLayout.LayoutParams(MATCH, WRAP))
        }
        shown.forEachIndexed { i, st ->
            val card = appRow(st)
            listBox.addView(card)
            if (animateNext) {
                card.alpha = 0f
                card.translationY = dpf(20f)
                card.animate().alpha(1f).translationY(0f).setStartDelay(35L * i).setDuration(280)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
        animateNext = false
    }

    private fun renderFloat(updates: Int) {
        val show = updates > 0 && updateQueue.isEmpty() && busy.isEmpty()
        floatBtn.text = "⬇   Update all ($updates)"
        if (show && floatBtn.visibility != View.VISIBLE) {
            floatBtn.visibility = View.VISIBLE
            floatBtn.translationY = dpf(90f)
            floatBtn.animate().translationY(0f).setInterpolator(OvershootInterpolator(1.4f)).setDuration(380).start()
        } else if (!show && floatBtn.visibility == View.VISIBLE) {
            floatBtn.animate().translationY(dpf(120f)).setDuration(220).withEndAction { floatBtn.visibility = View.GONE }.start()
        }
    }

    private fun renderHero(updates: List<AppStatus>) {
        heroBox.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                if (updates.isNotEmpty()) intArrayOf(0xFF1C6E86.toInt(), 0xFF4B3FB0.toInt())
                else intArrayOf(0xFF1D2A33.toInt(), 0xFF22203A.toInt())).apply { cornerRadius = dpf(26f) }
            setPadding(dp(20), dp(18), dp(20), dp(18))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        }
        val title = when {
            updates.size > 1 -> "${updates.size} updates ready"
            updates.size == 1 -> "1 update ready"
            else -> "Everything's up to date"
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 22f, Color.WHITE, bold = true))
            addView(label(if (updates.isEmpty()) "Pull down any time to check again ✓"
            else updates.joinToString(" · ") { it.entry.name.removePrefix("Ghost ").ifBlank { "Ghost" } }, 13f, 0xCCFFFFFF.toInt()).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        // Overlapping icons of the apps with updates.
        val stack = FrameLayout(this)
        updates.take(4).forEachIndexed { i, st ->
            stack.addView(ImageView(this).apply {
                setImageResource(iconFor(st.entry))
                background = oval(0xFF16202A.toInt())
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }, FrameLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(24) * i })
        }
        if (updates.isNotEmpty()) top.addView(stack)
        card.addView(top)
        if (updates.isNotEmpty()) {
            val working = updateQueue.isNotEmpty() || busy.isNotEmpty()
            card.addView(label(if (working) "Updating…" else "⬇  Update all", 16f, 0xFF1B2A4A.toInt(), bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(Color.WHITE, dpf(18f))
                setPadding(0, dp(13), 0, dp(13))
                pressable()
                rippleForeground()
                setOnClickListener { if (!working) updateAll(updates) }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
        }
        heroBox.addView(card)
    }

    /** "New & updated": the apps published in the last few days, as big swipeable cards. */
    private fun renderSpotlight() {
        spotlightBox.removeAllViews()
        val now = System.currentTimeMillis() / 1000
        val recent = apps.filter { it.entry.published > 0 && now - it.entry.published < 3 * 86_400 }
            .sortedByDescending { it.entry.published }
            .take(6)
        if (recent.isEmpty()) return
        spotlightBox.addView(sectionTitle("NEW & UPDATED"))
        val row = LinearLayout(this)
        recent.forEach { st ->
            val tint = tintFor(st.entry)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf((0x55 shl 24) or (tint and 0xFFFFFF), col(R.color.card))).apply {
                    cornerRadius = dpf(24f)
                    setStroke(dp(1), (0x44 shl 24) or (tint and 0xFFFFFF))
                }
                setPadding(dp(16), dp(16), dp(16), dp(14))
                rippleForeground()
                pressable(0.97f)
                setOnClickListener { showDetails(st) }
            }
            val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(ImageView(this).apply { setImageResource(iconFor(st.entry)) }, LinearLayout.LayoutParams(dp(52), dp(52)))
            head.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(label(st.entry.name, 17f, col(R.color.text), bold = true).apply { singleLine() })
                addView(label("${st.entry.versionName} · ${ago(st.entry.published)}", 12f, col(R.color.text2)))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            card.addView(head)
            card.addView(label(st.entry.changes.firstOrNull() ?: st.entry.description, 13f, col(R.color.text2)).apply {
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(10), 0, dp(12))
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            card.addView(actionPill(st, register = false), LinearLayout.LayoutParams(MATCH, dp(42)))
            row.addView(card, LinearLayout.LayoutParams(dp(250), dp(196)).apply { marginEnd = dp(12) })
        }
        spotlightBox.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(row)
        })
    }

    private fun renderChips(updates: Int, installed: Int, fresh: Int) {
        chipsRow.removeAllViews()
        listOf(Filter.ALL to apps.size, Filter.UPDATES to updates, Filter.INSTALLED to installed, Filter.NEW to fresh).forEach { (f, n) ->
            val on = f == filter
            chipsRow.addView(label("${f.title}  $n", 14f, if (on) col(R.color.on_accent) else col(R.color.text), bold = on).apply {
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = rounded(if (on) col(R.color.accent) else col(R.color.card), dpf(18f), if (on) 0 else col(R.color.stroke), if (on) 0 else dp(1))
                pressable(0.94f)
                setOnClickListener {
                    filter = f
                    animateNext = true
                    render()
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
        }
    }

    private fun sectionTitle(t: String) = label(t, 12f, col(R.color.text3), mono = true).apply {
        letterSpacing = 0.1f
        setPadding(dp(2), dp(22), 0, dp(10))
    }

    private fun appRow(st: AppStatus): View {
        val e = st.entry
        val tint = tintFor(e)
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(col(R.color.card), dpf(22f),
                if (st.state == InstallState.UPDATE) (0x88 shl 24) or (tint and 0xFFFFFF) else col(R.color.stroke), dp(1))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
            rippleForeground()
            setOnClickListener { showDetails(st) }
        }
        // Icon with a soft glow in the app's colour and a progress ring for downloads.
        val iconBox = FrameLayout(this)
        iconBox.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dpf(32f)
                colors = intArrayOf((0x55 shl 24) or (tint and 0xFFFFFF), 0x00000000)
            }
        }, FrameLayout.LayoutParams(dp(66), dp(66), Gravity.CENTER))
        iconBox.addView(ImageView(this).apply { setImageResource(iconFor(e)) }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        val ring = ProgressRing(this).apply { visibility = View.GONE }
        iconBox.addView(ring, FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER))
        card.addView(iconBox, LinearLayout.LayoutParams(dp(66), dp(66)))

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(8), 0) }
        texts.addView(label(e.name, 17f, col(R.color.text), bold = true).apply { singleLine() })
        texts.addView(label(versionLine(st), 13f, versionColor(st)).apply { singleLine() })
        texts.addView(label(listOfNotNull(
            if (e.published > 0) "Updated ${ago(e.published)}" else null,
            if (e.size > 0) Formatter.formatShortFileSize(this, e.size) else null,
        ).joinToString(" · "), 12f, col(R.color.text3)).apply { singleLine() })
        card.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        val pill = actionPill(st, register = true)
        card.addView(pill, LinearLayout.LayoutParams(WRAP, dp(38)))
        rows[e.pkg] = ring to pill

        busy[e.pkg]?.let { p ->
            ring.visibility = View.VISIBLE
            ring.set(if (p < 0) -1f else p / 100f, tint)
        }
        return card
    }

    /** GET / UPDATE / OPEN button, which turns into a percentage while downloading. */
    private fun actionPill(st: AppStatus, register: Boolean): TextView {
        val e = st.entry
        val p = busy[e.pkg]
        val (text, filled) = when {
            p != null -> (if (p < 0) "…" else "$p%") to true
            st.state == InstallState.NOT_INSTALLED -> "GET" to true
            st.state == InstallState.UPDATE -> "UPDATE" to true
            else -> "OPEN" to false
        }
        return label(text, 14f, if (filled) col(R.color.on_accent) else col(R.color.text), bold = true).apply {
            gravity = Gravity.CENTER
            if (register) minWidth = dp(84)
            setPadding(dp(16), 0, dp(16), 0)
            letterSpacing = 0.05f
            background = rounded(if (filled) col(R.color.accent) else col(R.color.card_hi), dpf(19f))
            if (p == null) {
                pressable(0.92f)
                setOnClickListener {
                    when (st.state) {
                        InstallState.NOT_INSTALLED, InstallState.UPDATE -> downloadAndInstall(st)
                        else -> openApp(e.pkg)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- details sheet

    private fun showDetails(st: AppStatus) {
        val e = st.entry
        val tint = tintFor(e)
        val d = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(26))
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val iconBox = FrameLayout(this)
        iconBox.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dpf(46f)
                colors = intArrayOf((0x66 shl 24) or (tint and 0xFFFFFF), 0x00000000)
            }
        }, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
        iconBox.addView(ImageView(this).apply { setImageResource(iconFor(e)) }, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
        head.addView(iconBox, LinearLayout.LayoutParams(dp(96), dp(96)))
        head.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(label(e.name, 24f, col(R.color.text), bold = true))
            addView(label(versionLine(st), 14f, versionColor(st)))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(head)
        if (e.description.isNotBlank()) box.addView(label(e.description, 15f, col(R.color.text2)).apply { setPadding(0, dp(12), 0, 0) })

        val facts = LinearLayout(this).apply { setPadding(0, dp(16), 0, 0) }
        listOf(
            "VERSION" to e.versionName,
            "SIZE" to if (e.size > 0) Formatter.formatShortFileSize(this, e.size) else "—",
            "UPDATED" to if (e.published > 0) ago(e.published) else "—",
        ).forEachIndexed { i, (k, v) ->
            facts.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                background = rounded(col(R.color.card), dpf(16f))
                addView(label(v, 15f, col(R.color.text), bold = true).apply { singleLine() })
                addView(label(k, 10f, col(R.color.text3), mono = true).apply { letterSpacing = 0.1f })
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        box.addView(facts)

        if (e.changes.isNotEmpty()) {
            box.addView(sectionTitle("WHAT'S NEW"))
            e.changes.forEach { c ->
                box.addView(LinearLayout(this).apply {
                    setPadding(0, dp(3), 0, dp(3))
                    addView(label("✦", 13f, tint).apply { setPadding(0, dp(1), dp(10), 0) })
                    addView(label(c, 14f, col(R.color.text)), LinearLayout.LayoutParams(0, WRAP, 1f))
                })
            }
        }
        val (text, action) = when (st.state) {
            InstallState.NOT_INSTALLED -> "Install ${e.name}" to { downloadAndInstall(st) }
            InstallState.UPDATE -> "Update to ${e.versionName}" to { downloadAndInstall(st) }
            else -> "Open ${e.name}" to { openApp(e.pkg) }
        }
        box.addView(label(text, 16f, col(R.color.on_accent), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(15), 0, dp(15))
            background = rounded(col(R.color.accent), dpf(20f))
            pressable()
            setOnClickListener { d.dismiss(); action() }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(20) })
        d.setContentView(ScrollView(this).apply { addView(box) })
        d.show()
    }

    // ---------------------------------------------------------------- install flow

    private fun updateAll(updates: List<AppStatus>) {
        if (!ensureCanInstall()) return
        updateQueue.clear()
        // Update Ghost Hub itself last, so it can finish the others first.
        updates.sortedBy { it.entry.pkg == packageName }.forEach { updateQueue.addLast(it.entry.pkg) }
        Toast.makeText(this, "Updating ${updates.size} ${if (updates.size == 1) "app" else "apps"}: confirm each install screen", Toast.LENGTH_LONG).show()
        nextInQueue()
    }

    private fun nextInQueue() {
        while (updateQueue.isNotEmpty()) {
            val pkg = updateQueue.removeFirst()
            val st = apps.firstOrNull { it.entry.pkg == pkg && it.state == InstallState.UPDATE } ?: continue
            downloadAndInstall(st)
            return
        }
        render()
    }

    private fun downloadAndInstall(st: AppStatus) {
        if (!ensureCanInstall()) return
        val pkg = st.entry.pkg
        if (busy.containsKey(pkg)) return
        busy[pkg] = -1
        render()
        pool.execute {
            val result = runCatching {
                // Always download what the PC has right now (it may have published a newer build since
                // this screen loaded), and if it changes mid-download, re-check once and try again.
                var entry = repo.fresh(pkg) ?: st.entry
                try {
                    Installer.download(this, repo, entry) { pct -> main.post { onProgress(pkg, pct) } }
                } catch (e: Installer.CorruptDownload) {
                    main.post { onProgress(pkg, -1) }
                    entry = repo.fresh(pkg) ?: throw e
                    Installer.download(this, repo, entry) { pct -> main.post { onProgress(pkg, pct) } }
                }
            }
            main.post {
                busy.remove(pkg)
                result.onSuccess {
                    installing.add(pkg)
                    render()
                    Installer.install(this, it)
                }
                result.onFailure { err ->
                    updateQueue.clear()
                    render()
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Download didn't finish")
                        .setMessage((err.message ?: "Something went wrong.") + "\n\n" + when (repo.from) {
                            Repo.From.PC -> "Check you're still on the same Wi-Fi as your PC."
                            else -> "Check your internet connection and try again."
                        })
                        .setPositiveButton("Try again") { _, _ ->
                            refreshThen { list -> list.firstOrNull { a -> a.entry.pkg == pkg }?.let { downloadAndInstall(it) } }
                        }
                        .setNegativeButton("Close", null)
                        .show()
                }
            }
        }
    }

    /** Reloads the list, then runs [then] with it. */
    private fun refreshThen(then: (List<AppStatus>) -> Unit) {
        pool.execute {
            val r = runCatching { repo.load() }
            main.post {
                r.onSuccess { apps = it; render(); then(it) }
                r.onFailure { refresh() }
            }
        }
    }

    private fun onProgress(pkg: String, pct: Int) {
        if (!busy.containsKey(pkg)) return
        busy[pkg] = pct
        val (ring, pill) = rows[pkg] ?: return
        pill.text = if (pct < 0) "…" else "$pct%"
        ring.visibility = View.VISIBLE
        val e = apps.firstOrNull { it.entry.pkg == pkg }?.entry
        ring.set(if (pct < 0) -1f else pct / 100f, e?.let { tintFor(it) } ?: col(R.color.accent))
    }

    private fun openApp(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
            ?: Toast.makeText(this, "Can't open that app", Toast.LENGTH_SHORT).show()
    }

    private fun ensureCanInstall(): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Allow installs")
                .setMessage("Android needs your OK for Ghost Hub to install apps. On the next screen, turn on \"Allow from this source\".")
                .setPositiveButton("Open setting") { _, _ ->
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return false
        }
        return true
    }

    // ---------------------------------------------------------------- settings

    private fun showSettings() {
        val before = repo.source
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(4))
        }
        // Where the apps come from: GitHub works anywhere, the PC only on home Wi-Fi.
        box.addView(sectionTitle("GET APPS FROM"))
        val group = RadioGroup(this)
        val ids = Repo.Source.entries.associateBy { View.generateViewId() }
        ids.forEach { (id, s) ->
            group.addView(MaterialRadioButton(this).apply {
                this.id = id
                text = s.title
                setTextColor(col(R.color.text))
                isChecked = s == repo.source
            })
        }
        group.setOnCheckedChangeListener { _, id -> ids[id]?.let { repo.source = it } }
        box.addView(group)

        lateinit var dialog: androidx.appcompat.app.AlertDialog
        box.addView(sectionTitle("ADDRESSES"))
        box.addView(settingRow("GitHub", repo.githubIndex.removePrefix("https://")) { dialog.dismiss(); showGitHubDialog() })
        box.addView(settingRow("PC", repo.server ?: "found automatically on Wi-Fi") { dialog.dismiss(); showServerDialog() })

        box.addView(MaterialCheckBox(this).apply {
            text = "Check for updates in the background"
            setTextColor(col(R.color.text))
            isChecked = repo.autoCheck
            setOnCheckedChangeListener { _, on ->
                repo.autoCheck = on
                if (on) UpdateReceiver.schedule(this@MainActivity)
            }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

        dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Ghost Hub ${BuildConfig.VERSION_NAME}")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null)
            .setOnDismissListener { if (repo.source != before) refresh() }
            .show()
    }

    /** One tappable "name   value ›" line in Settings. */
    private fun settingRow(name: String, value: String, onClick: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(10), dp(8), dp(10))
        rippleForeground()
        setOnClickListener { onClick() }
        addView(label(name, 15f, col(R.color.text)), LinearLayout.LayoutParams(dp(64), WRAP))
        addView(label(value, 13f, col(R.color.text2)).apply { singleLine() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(label("›", 18f, col(R.color.text3)).apply { setPadding(dp(8), 0, 0, 0) })
    }

    private fun showGitHubDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        box.addView(label("The index.json in your GitHub builds repo. Leave it as it is unless the repo moved.", 14f, col(R.color.text2)))
        val input = EditText(this).apply {
            hint = Repo.DEFAULT_GITHUB_INDEX
            setText(repo.githubIndex)
            setHintTextColor(col(R.color.text3))
            setTextColor(col(R.color.text))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setPadding(0, dp(16), 0, 0)
        }
        box.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle("GitHub address")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                var v = input.text.toString().trim()
                if (v.isNotBlank() && !v.startsWith("http")) v = "https://$v"
                repo.githubIndex = v
                refresh()
            }
            .setNeutralButton("Reset") { _, _ ->
                repo.githubIndex = ""
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showServerDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        box.addView(label("Ghost Hub finds your PC automatically on the same Wi-Fi. If it can't, type the address shown in the PC server window.", 14f, col(R.color.text2)))
        val input = EditText(this).apply {
            hint = "http://192.168.0.78:8765"
            setText(repo.server ?: "")
            setHintTextColor(col(R.color.text3))
            setTextColor(col(R.color.text))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setPadding(0, dp(16), 0, 0)
        }
        box.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle("PC address")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                var v = input.text.toString().trim()
                if (v.isNotBlank()) {
                    if (!v.startsWith("http")) v = "http://$v"
                    repo.server = v
                    repo.serverName = null
                    refresh()
                }
            }
            .setNeutralButton("Search again") { _, _ ->
                repo.server = null
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- helpers

    private fun iconFor(e: HubEntry): Int = when (e.icon) {
        "ghost" -> R.drawable.app_ghost
        "volume" -> R.drawable.app_volume
        "terminal" -> R.drawable.app_terminal
        "weather" -> R.drawable.app_weather
        "school" -> R.drawable.app_school
        "editor" -> R.drawable.app_editor
        "vpn" -> R.drawable.app_vpn
        "browser" -> R.drawable.app_browser
        "video" -> R.drawable.app_video
        "hub" -> R.drawable.ic_logo
        else -> R.drawable.app_ghost
    }

    /** Each app's signature colour, used for glows and rings. */
    private fun tintFor(e: HubEntry): Int = when (e.icon) {
        "volume" -> 0xFF6ED39C
        "terminal" -> 0xFF8B7CF6
        "weather" -> 0xFFF5B64B
        "school" -> 0xFF9A8CFF
        "editor" -> 0xFFF27FB8
        "vpn" -> 0xFF5EC8FF
        "browser" -> 0xFF8C9BFF
        "video" -> 0xFFFF7A8A
        else -> 0xFF72E2F7
    }.toInt()

    private fun versionLine(st: AppStatus): String = when (st.state) {
        InstallState.NOT_INSTALLED -> "Not installed · ${st.entry.versionName}"
        InstallState.UP_TO_DATE -> "${st.installedName} · up to date"
        InstallState.UPDATE -> "${st.installedName} → ${st.entry.versionName}"
        InstallState.INSTALLED_NEWER -> "${st.installedName} · newer than the Hub's"
    }

    private fun versionColor(st: AppStatus): Int = col(when (st.state) {
        InstallState.UPDATE -> R.color.accent
        InstallState.UP_TO_DATE -> R.color.green
        else -> R.color.text2
    })

    private fun ago(seconds: Long): String {
        val s = System.currentTimeMillis() / 1000 - seconds
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            s < 7 * 86_400 -> "${s / 86_400} days ago"
            else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(seconds * 1000))
        }
    }

    private fun TextView.singleLine() {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private companion object {
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

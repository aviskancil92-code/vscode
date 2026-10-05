package com.vscode.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tombol & pintasan keyboard virtual untuk code-server di WebView.
 * - Bar tombol ekstra (Esc, Tab, Ctrl/Alt/Shift, panah, Enter, ...) di bawah layar.
 * - Panel lengkap pintasan VS Code (dibuka dari menu header).
 * Semua tombol non-focusable agar fokus & keyboard layar di WebView tidak hilang.
 */
class KeyPad(
    private val ctx: Activity,
    private val target: () -> WebView?,
    private val onShowPanel: () -> Unit,
    private val onToggleBar: () -> Unit = {}
) {
    companion object {
        const val C = 1  // Ctrl
        const val S = 2  // Shift
        const val A = 4  // Alt
    }

    class Key(
        val label: String,
        val code: Int = 0,
        val mods: Int = 0,
        val text: String? = null,
        val repeat: Boolean = false
    )

    private val handler = Handler(Looper.getMainLooper())
    private val dp = ctx.resources.displayMetrics.density
    private var oneShot = 0   // modifier untuk 1 ketukan berikutnya
    private var locked = 0    // modifier dikunci (tekan lama)
    private val modButtons = HashMap<Int, Button>()

    private val NORMAL = 0xFF2A2F3A.toInt()
    private val ACTIVE = 0xFF10B981.toInt()
    private val LOCKED = 0xFFF59E0B.toInt()

    // ------------------------------------------------------------ kirim input

    private fun metaOf(m: Int): Int {
        var r = 0
        if ((m and C) != 0) r = r or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if ((m and S) != 0) r = r or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if ((m and A) != 0) r = r or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        return r
    }

    // ----------------------------------------------- tombol volume (ala Termux)

    /** 0 = Termux (Vol-Bawah Ctrl, Vol-Atas Fn) · 1 = Vol-Bawah Ctrl, Vol-Atas Shift · 2 = mati */
    var volMode = 0
    private var volDown = false
    private var volUp = false
    private var volDownUsed = false
    private var volUpUsed = false
    private var fnOnce = false

    private fun fnActive() = volMode == 0 && (volUp || fnOnce)

    private fun heldMods(): Int =
        (if (volMode != 2 && volDown) C else 0) or
            (if (volMode == 1 && volUp) S else 0) or oneShot or locked

    fun hasPending() = heldMods() != 0 || fnActive()

    private fun markUsed() {
        if (volDown) volDownUsed = true
        if (volUp) volUpUsed = true
    }

    private fun consume() {
        markUsed()
        oneShot = 0
        fnOnce = false
        refreshMods()
    }

    /** Dipanggil dari Activity.dispatchKeyEvent. true = tombol volume dikonsumsi. */
    fun onVolume(keyCode: Int, down: Boolean, repeat: Int): Boolean {
        if (volMode == 2) return false
        val isDown = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (down) {
            if (repeat == 0) {
                if (isDown) { volDown = true; volDownUsed = false } else { volUp = true; volUpUsed = false }
                refreshMods()
            }
            return true
        }
        if (isDown) {
            volDown = false
            if (!volDownUsed) oneShot = oneShot xor C   // ketuk saja = Ctrl untuk tombol berikutnya
        } else {
            volUp = false
            if (!volUpUsed) {
                if (volMode == 1) oneShot = oneShot xor S else fnOnce = !fnOnce
            }
        }
        refreshMods()
        return true
    }

    /** Modifier untuk tombol non-karakter dari IME (Enter, Backspace, panah). */
    fun takeMetaForKey(): Int {
        val m = heldMods()
        if (m == 0) return 0
        consume()
        return metaOf(m)
    }

    /**
     * Terapkan modifier / lapisan Fn pada satu karakter yang diketik lewat IME.
     * true = karakter sudah ditangani (jangan commit sebagai teks biasa).
     */
    fun sendChar(c: Char): Boolean {
        val fn = fnActive()
        val mods = heldMods()
        if (!fn && mods == 0) return false
        if (fn) {
            val action = fnTable(c.lowercaseChar())
            if (action != null) {
                consume()
                action(mods)
                return true
            }
        }
        if (mods == 0) { consume(); return false }
        val evs = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            .getEvents(charArrayOf(c))
        consume()
        if (evs == null) return false
        val wv = target() ?: return true
        val extra = metaOf(mods)
        val t = SystemClock.uptimeMillis()
        for (e in evs) {
            wv.dispatchKeyEvent(
                KeyEvent(
                    t, t, e.action, e.keyCode, 0, e.metaState or extra,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
                )
            )
        }
        return true
    }

    private fun fnTable(c: Char): ((Int) -> Unit)? {
        fun act(f: (Int) -> Unit): (Int) -> Unit = f
        fun key(code: Int): (Int) -> Unit = act { m -> pressRaw(code, m) }
        return when (c) {
            'w' -> key(KeyEvent.KEYCODE_DPAD_UP)
            'a' -> key(KeyEvent.KEYCODE_DPAD_LEFT)
            's' -> key(KeyEvent.KEYCODE_DPAD_DOWN)
            'd' -> key(KeyEvent.KEYCODE_DPAD_RIGHT)
            'e' -> key(KeyEvent.KEYCODE_ESCAPE)
            't' -> key(KeyEvent.KEYCODE_TAB)
            'p' -> key(KeyEvent.KEYCODE_PAGE_UP)
            'n' -> key(KeyEvent.KEYCODE_PAGE_DOWN)
            'i' -> key(KeyEvent.KEYCODE_MOVE_HOME)
            'o' -> key(KeyEvent.KEYCODE_MOVE_END)
            'x' -> key(KeyEvent.KEYCODE_FORWARD_DEL)
            'h' -> act { typeRaw("~") }
            'u' -> act { typeRaw("_") }
            'l' -> act { typeRaw("|") }
            '.' -> act { m -> pressRaw(KeyEvent.KEYCODE_BACKSLASH, m or C) }
            'q' -> act { onToggleBar() }
            in '1'..'9' -> key(KeyEvent.KEYCODE_F1 + (c - '1'))
            '0' -> key(KeyEvent.KEYCODE_F10)
            else -> null
        }
    }

    private fun pressRaw(code: Int, mods: Int) {
        val wv = target() ?: return
        wv.requestFocus()
        val meta = metaOf(mods)
        val t = SystemClock.uptimeMillis()
        fun ev(action: Int) = KeyEvent(
            t, t, action, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
        )
        wv.dispatchKeyEvent(ev(KeyEvent.ACTION_DOWN))
        wv.dispatchKeyEvent(ev(KeyEvent.ACTION_UP))
    }

    private fun typeRaw(text: String) {
        val wv = target() ?: return
        wv.requestFocus()
        wv.onCreateInputConnection(EditorInfo())?.commitText(text, 1)
    }

    private fun press(code: Int, mods: Int) {
        pressRaw(code, mods or heldMods())
        consume()
    }

    private fun typeText(text: String) {
        val wv = target() ?: return
        wv.requestFocus()
        val ic = wv.onCreateInputConnection(EditorInfo())
        // commitText melewati pembungkus -> modifier (mis. Ctrl aktif) ikut diterapkan bila 1 karakter.
        ic?.commitText(text, 1)
        if (text.length != 1) consume()
    }

    private fun fire(k: Key) {
        if (k.text != null) typeText(k.text) else press(k.code, k.mods)
    }

    // ------------------------------------------------------------- pembuat UI

    private fun bg(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 6 * dp
    }

    private fun makeButton(label: String): Button =
        Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
            isFocusable = false
            isFocusableInTouchMode = false
            background = bg(NORMAL)
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun attach(b: Button, k: Key) {
        if (k.repeat) {
            val r = object : Runnable {
                override fun run() {
                    fire(k)
                    handler.postDelayed(this, 70)
                }
            }
            b.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        fire(k)
                        handler.postDelayed(r, 380)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        handler.removeCallbacks(r)
                    }
                }
                true
            }
        } else {
            b.setOnClickListener { fire(k) }
        }
    }

    fun refreshMods() {
        val vol = (if (volMode != 2 && volDown) C else 0) or (if (volMode == 1 && volUp) S else 0)
        for ((flag, b) in modButtons) {
            val c = when {
                (locked and flag) != 0 -> LOCKED
                ((oneShot or vol) and flag) != 0 -> ACTIVE
                else -> NORMAL
            }
            b.background = bg(c)
            b.setTextColor(if (c == NORMAL) Color.WHITE else Color.BLACK)
        }
    }

    private fun modifierButton(label: String, flag: Int): Button {
        val b = makeButton(label)
        modButtons[flag] = b
        b.setOnClickListener {
            if ((locked and flag) != 0) locked = locked and flag.inv()
            else oneShot = oneShot xor flag
            refreshMods()
        }
        b.setOnLongClickListener {
            locked = locked xor flag
            oneShot = oneShot and flag.inv()
            refreshMods()
            true
        }
        return b
    }

    private fun k(label: String, code: Int, mods: Int = 0, repeat: Boolean = false) =
        Key(label, code, mods, null, repeat)

    private fun t(text: String) = Key(text, text = text)

    // --------------------------------------------------------------- bar bawah

    fun buildBar(row: LinearLayout) {
        row.removeAllViews()
        modButtons.clear()
        fun add(b: View) {
            row.addView(
                b,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, (40 * dp).toInt()
                ).apply { setMargins((3 * dp).toInt(), (4 * dp).toInt(), (3 * dp).toInt(), (4 * dp).toInt()) }
            )
        }
        fun key(key: Key) = add(makeButton(key.label).also { attach(it, key) })

        key(k("Esc", KeyEvent.KEYCODE_ESCAPE))
        key(k("Tab", KeyEvent.KEYCODE_TAB))
        add(modifierButton("Ctrl", C))
        add(modifierButton("Alt", A))
        add(modifierButton("Shift", S))
        key(k("←", KeyEvent.KEYCODE_DPAD_LEFT, repeat = true))
        key(k("↓", KeyEvent.KEYCODE_DPAD_DOWN, repeat = true))
        key(k("↑", KeyEvent.KEYCODE_DPAD_UP, repeat = true))
        key(k("→", KeyEvent.KEYCODE_DPAD_RIGHT, repeat = true))
        key(k("Enter", KeyEvent.KEYCODE_ENTER))
        key(k("⌫", KeyEvent.KEYCODE_DEL, repeat = true))
        key(k("Del", KeyEvent.KEYCODE_FORWARD_DEL, repeat = true))
        key(k("Home", KeyEvent.KEYCODE_MOVE_HOME))
        key(k("End", KeyEvent.KEYCODE_MOVE_END))
        key(k("PgUp", KeyEvent.KEYCODE_PAGE_UP, repeat = true))
        key(k("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, repeat = true))
        key(k("Simpan", KeyEvent.KEYCODE_S, C))
        key(k("Undo", KeyEvent.KEYCODE_Z, C))
        key(k("Redo", KeyEvent.KEYCODE_Y, C))
        key(k("Palet", KeyEvent.KEYCODE_P, C or S))
        key(k("Buka", KeyEvent.KEYCODE_P, C))
        key(k("Cari", KeyEvent.KEYCODE_F, C))
        key(t("{ }").let { Key("{}", text = "{}") })
        key(t("()").let { Key("()", text = "()") })
        key(t("[]").let { Key("[]", text = "[]") })
        add(makeButton("⌨ Semua").also { it.setOnClickListener { onShowPanel() } })
    }

    // ------------------------------------------------------------ panel lengkap

    private fun sections(): List<Triple<String, Int, List<Key>>> = listOf(
        Triple("Dasar", 3, listOf(
            k("Enter", KeyEvent.KEYCODE_ENTER),
            k("Esc", KeyEvent.KEYCODE_ESCAPE),
            k("Tab", KeyEvent.KEYCODE_TAB),
            k("Shift+Tab", KeyEvent.KEYCODE_TAB, S),
            k("⌫ Backspace", KeyEvent.KEYCODE_DEL, repeat = true),
            k("Delete", KeyEvent.KEYCODE_FORWARD_DEL, repeat = true),
            k("Spasi", KeyEvent.KEYCODE_SPACE),
            k("Home", KeyEvent.KEYCODE_MOVE_HOME),
            k("End", KeyEvent.KEYCODE_MOVE_END),
            k("PgUp", KeyEvent.KEYCODE_PAGE_UP, repeat = true),
            k("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, repeat = true),
            k("Ctrl+Home", KeyEvent.KEYCODE_MOVE_HOME, C),
            k("←", KeyEvent.KEYCODE_DPAD_LEFT, repeat = true),
            k("↑", KeyEvent.KEYCODE_DPAD_UP, repeat = true),
            k("↓", KeyEvent.KEYCODE_DPAD_DOWN, repeat = true),
            k("→", KeyEvent.KEYCODE_DPAD_RIGHT, repeat = true),
            k("Ctrl+End", KeyEvent.KEYCODE_MOVE_END, C)
        )),
        Triple("Berkas", 2, listOf(
            k("Simpan · Ctrl+S", KeyEvent.KEYCODE_S, C),
            k("Simpan sebagai · Ctrl+Shift+S", KeyEvent.KEYCODE_S, C or S),
            k("Berkas baru · Ctrl+N", KeyEvent.KEYCODE_N, C),
            k("Buka berkas · Ctrl+O", KeyEvent.KEYCODE_O, C),
            k("Tutup tab · Ctrl+W", KeyEvent.KEYCODE_W, C),
            k("Tab berikut · Ctrl+PgDn", KeyEvent.KEYCODE_PAGE_DOWN, C),
            k("Tab sebelumnya · Ctrl+PgUp", KeyEvent.KEYCODE_PAGE_UP, C),
            k("Buka cepat · Ctrl+P", KeyEvent.KEYCODE_P, C)
        )),
        Triple("Edit", 2, listOf(
            k("Undo · Ctrl+Z", KeyEvent.KEYCODE_Z, C),
            k("Redo · Ctrl+Y", KeyEvent.KEYCODE_Y, C),
            k("Potong · Ctrl+X", KeyEvent.KEYCODE_X, C),
            k("Salin · Ctrl+C", KeyEvent.KEYCODE_C, C),
            k("Tempel · Ctrl+V", KeyEvent.KEYCODE_V, C),
            k("Pilih semua · Ctrl+A", KeyEvent.KEYCODE_A, C),
            k("Pilih baris · Ctrl+L", KeyEvent.KEYCODE_L, C),
            k("Pilih kata sama · Ctrl+D", KeyEvent.KEYCODE_D, C),
            k("Komentar · Ctrl+/", KeyEvent.KEYCODE_SLASH, C),
            k("Blok komentar · Shift+Alt+A", KeyEvent.KEYCODE_A, S or A),
            k("Baris baru bawah · Ctrl+Enter", KeyEvent.KEYCODE_ENTER, C),
            k("Baris baru atas · Ctrl+Shift+Enter", KeyEvent.KEYCODE_ENTER, C or S),
            k("Hapus baris · Ctrl+Shift+K", KeyEvent.KEYCODE_K, C or S),
            k("Pindah baris ↑ · Alt+↑", KeyEvent.KEYCODE_DPAD_UP, A),
            k("Pindah baris ↓ · Alt+↓", KeyEvent.KEYCODE_DPAD_DOWN, A),
            k("Duplikat ↑ · Shift+Alt+↑", KeyEvent.KEYCODE_DPAD_UP, S or A),
            k("Duplikat ↓ · Shift+Alt+↓", KeyEvent.KEYCODE_DPAD_DOWN, S or A),
            k("Indent · Ctrl+]", KeyEvent.KEYCODE_RIGHT_BRACKET, C),
            k("Outdent · Ctrl+[", KeyEvent.KEYCODE_LEFT_BRACKET, C),
            k("Format dokumen · Shift+Alt+F", KeyEvent.KEYCODE_F, S or A),
            k("Kursor ganda ↑ · Ctrl+Alt+↑", KeyEvent.KEYCODE_DPAD_UP, C or A),
            k("Kursor ganda ↓ · Ctrl+Alt+↓", KeyEvent.KEYCODE_DPAD_DOWN, C or A),
            k("Hapus kata · Ctrl+⌫", KeyEvent.KEYCODE_DEL, C, repeat = true),
            k("Lipat kode · Ctrl+Shift+[", KeyEvent.KEYCODE_LEFT_BRACKET, C or S),
            k("Buka lipatan · Ctrl+Shift+]", KeyEvent.KEYCODE_RIGHT_BRACKET, C or S)
        )),
        Triple("Cari & navigasi", 2, listOf(
            k("Palet perintah · Ctrl+Shift+P", KeyEvent.KEYCODE_P, C or S),
            k("Palet perintah · F1", KeyEvent.KEYCODE_F1),
            k("Cari · Ctrl+F", KeyEvent.KEYCODE_F, C),
            k("Ganti · Ctrl+H", KeyEvent.KEYCODE_H, C),
            k("Cari di semua berkas · Ctrl+Shift+F", KeyEvent.KEYCODE_F, C or S),
            k("Ke baris · Ctrl+G", KeyEvent.KEYCODE_G, C),
            k("Simbol di berkas · Ctrl+Shift+O", KeyEvent.KEYCODE_O, C or S),
            k("Ke definisi · F12", KeyEvent.KEYCODE_F12),
            k("Referensi · Shift+F12", KeyEvent.KEYCODE_F12, S),
            k("Ganti nama · F2", KeyEvent.KEYCODE_F2),
            k("Quick fix · Ctrl+.", KeyEvent.KEYCODE_PERIOD, C),
            k("Saran kode · Ctrl+Space", KeyEvent.KEYCODE_SPACE, C),
            k("Hint parameter · Ctrl+Shift+Space", KeyEvent.KEYCODE_SPACE, C or S),
            k("Temuan berikut · F3", KeyEvent.KEYCODE_F3),
            k("Temuan sebelumnya · Shift+F3", KeyEvent.KEYCODE_F3, S),
            k("Masalah berikut · F8", KeyEvent.KEYCODE_F8)
        )),
        Triple("Tampilan", 2, listOf(
            k("Sidebar · Ctrl+B", KeyEvent.KEYCODE_B, C),
            k("Terminal · Ctrl+`", KeyEvent.KEYCODE_GRAVE, C),
            k("Terminal baru · Ctrl+Shift+`", KeyEvent.KEYCODE_GRAVE, C or S),
            k("Explorer · Ctrl+Shift+E", KeyEvent.KEYCODE_E, C or S),
            k("Ekstensi · Ctrl+Shift+X", KeyEvent.KEYCODE_X, C or S),
            k("Git · Ctrl+Shift+G", KeyEvent.KEYCODE_G, C or S),
            k("Debug · Ctrl+Shift+D", KeyEvent.KEYCODE_D, C or S),
            k("Panel · Ctrl+J", KeyEvent.KEYCODE_J, C),
            k("Masalah · Ctrl+Shift+M", KeyEvent.KEYCODE_M, C or S),
            k("Output · Ctrl+Shift+U", KeyEvent.KEYCODE_U, C or S),
            k("Split editor · Ctrl+\\", KeyEvent.KEYCODE_BACKSLASH, C),
            k("Zoom in · Ctrl+=", KeyEvent.KEYCODE_EQUALS, C),
            k("Zoom out · Ctrl+-", KeyEvent.KEYCODE_MINUS, C)
        )),
        Triple("Run & debug", 2, listOf(
            k("Mulai debug · F5", KeyEvent.KEYCODE_F5),
            k("Jalan tanpa debug · Ctrl+F5", KeyEvent.KEYCODE_F5, C),
            k("Stop · Shift+F5", KeyEvent.KEYCODE_F5, S),
            k("Breakpoint · F9", KeyEvent.KEYCODE_F9),
            k("Step over · F10", KeyEvent.KEYCODE_F10),
            k("Step into · F11", KeyEvent.KEYCODE_F11),
            k("Step out · Shift+F11", KeyEvent.KEYCODE_F11, S),
            k("Jalankan task · Ctrl+Shift+B", KeyEvent.KEYCODE_B, C or S)
        )),
        Triple("Simbol (sulit diketik)", 5, listOf(
            "{", "}", "(", ")", "[", "]", "<", ">", ";", ":", "'", "\"", "`", "/", "\\",
            "|", "~", "_", "-", "=", "+", "!", "@", "#", "$", "%", "^", "&", "*", "?", ".", ","
        ).map { t(it) })
    )

    fun buildPanel(onClose: () -> Unit): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF14171D.toInt())
            elevation = 12 * dp
            isClickable = true
        }
        val title = TextView(ctx).apply {
            text = ctx.getString(R.string.shortcuts_title)
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), 0, 0, 0)
        }
        val close = makeButton(ctx.getString(R.string.popup_close)).apply {
            setOnClickListener { onClose() }
        }
        root.addView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(0xFF1B1E26.toInt())
                addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (42 * dp).toInt())
        )

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt())
        }
        content.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.volume_help)
            setTextColor(0xFFB8BDC7.toInt())
            textSize = 11f
            setPadding((4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
        })
        for ((name, cols, keys) in sections()) {
            content.addView(TextView(ctx).apply {
                text = name
                setTextColor(0xFF10B981.toInt())
                textSize = 12f
                setPadding((4 * dp).toInt(), (12 * dp).toInt(), 0, (4 * dp).toInt())
            })
            val grid = GridLayout(ctx).apply { columnCount = cols }
            for (key in keys) {
                val b = makeButton(key.label).apply {
                    textSize = if (cols >= 5) 15f else 12f
                    isSingleLine = cols >= 3
                }
                attach(b, key)
                grid.addView(
                    b,
                    GridLayout.LayoutParams(
                        GridLayout.spec(GridLayout.UNDEFINED),
                        GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    ).apply {
                        width = 0
                        height = (40 * dp).toInt()
                        setMargins((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt())
                    }
                )
            }
            content.addView(grid)
        }
        root.addView(
            ScrollView(ctx).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return root
    }
}

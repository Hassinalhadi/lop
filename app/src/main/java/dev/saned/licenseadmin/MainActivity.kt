package dev.saned.licenseadmin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.LocalDate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MainActivity : AppCompatActivity() {

    companion object {
        private val SL1_BASE_DATE: LocalDate = LocalDate.of(2020, 1, 1)
        private const val SL1_MASTER_SECRET = "SanedHook-SL1-MasterKey-2026-v98-7bac1ec-official"
    }

    private lateinit var edtRawPaste: EditText
    private lateinit var edtDeviceId: EditText
    private lateinit var edtCustomDays: EditText
    private lateinit var edtSerial: EditText
    private lateinit var tvGeneratedToken: TextView
    private lateinit var tvMetaSummary: TextView

    private var selectedMode: String = "30"
    private var lastToken: String = ""
    private val modeChips = mutableMapOf<String, TextView>()

    private fun roundedBg(colorHex: String, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(Color.parseColor(colorHex))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("sl1_admin_prefs", Context.MODE_PRIVATE)
        val initialSerial = prefs.getLong("next_serial", 1001L)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F4F3FA"))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 64)
        }

        // Top Header
        val header = TextView(this).apply {
            text = "🔑 Saned SL1 License Generator\nمولّد تراخيص ساند (SL1)"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBg("#4C1D95", 18f)
            setPadding(36, 32, 36, 32)
        }
        root.addView(header)

        // Card 1: Device ID & WhatsApp Request Parser
        val cardDevice = createCard()
        val colDevice = createCardCol()

        colDevice.addView(createTitle("📱 1. معرّف جهاز المستخدم (Device ID)"))

        edtRawPaste = EditText(this).apply {
            hint = "الصق رسالة طلب الواتساب كاملة هنا..."
            textSize = 13f
            minLines = 2
            maxLines = 4
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    parseRawRequest(s?.toString() ?: "")
                }
            })
        }
        colDevice.addView(edtRawPaste)

        val devRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 0)
        }
        edtDeviceId = EditText(this).apply {
            hint = "Device ID (مثال: e246afc596cf4d11f19b8523dcfbd8ef2096cfa7fa466d400b5ce9a1f00f63c4)"
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        devRow.addView(edtDeviceId)

        val btnPasteClip = Button(this).apply {
            text = "📋 لصق"
            textSize = 12.5f
            setOnClickListener {
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val txt = clip.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
                if (txt.isNotBlank()) {
                    edtRawPaste.setText(txt)
                    parseRawRequest(txt)
                }
            }
        }
        devRow.addView(btnPasteClip)
        colDevice.addView(devRow)

        cardDevice.addView(colDevice)
        root.addView(cardDevice)

        // Card 2: Duration & Serial
        val cardDur = createCard()
        val colDur = createCardCol()
        colDur.addView(createTitle("⏳ 2. مدة الترخيص والرقم التسلسلي"))

        val modes = listOf(
            "1" to "يوم واحد\n1 Day",
            "30" to "شهر واحد\n1 Month",
            "60" to "شهران\n2 Months",
            "90" to "3 أشهر\n3 Months",
            "custom" to "أيام مخصصة\nCustom",
            "perpetual" to "دائم ♾️\nPerpetual"
        )

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 10)
        }

        modes.forEachIndexed { idx, (key, label) ->
            val chip = TextView(this).apply {
                text = label
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(12, 18, 12, 18)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(6, 0, 6, 0)
                }
                setOnClickListener {
                    selectedMode = key
                    updateModeChipsUI()
                }
            }
            modeChips[key] = chip
            if (idx < 3) row1.addView(chip) else row2.addView(chip)
        }
        colDur.addView(row1)
        colDur.addView(row2)

        edtCustomDays = EditText(this).apply {
            hint = "عدد الأيام (مثال: 15)"
            setText("30")
            textSize = 14f
            setSingleLine(true)
        }
        colDur.addView(edtCustomDays)

        edtSerial = EditText(this).apply {
            hint = "الرقم التسلسلي (Serial #)"
            setText(initialSerial.toString())
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
        }
        colDur.addView(edtSerial)

        val btnGenerate = Button(this).apply {
            text = "⚡ توليد رمز الترخيص (Generate SL1 Token)"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#4C1D95"))
            setPadding(24, 26, 24, 26)
            setOnClickListener { generateAndDisplayToken() }
        }
        colDur.addView(btnGenerate)

        cardDur.addView(colDur)
        root.addView(cardDur)

        // Card 3: Output Token & Actions
        val cardOut = createCard()
        val colOut = createCardCol()
        colOut.addView(createTitle("✅ 3. رمز التفعيل الصادر (SL1 Token)"))

        tvGeneratedToken = TextView(this).apply {
            text = "اضغط توليد رمز الترخيص أعلاه..."
            textSize = 13.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#38BDF8"))
            background = roundedBg("#0F172A", 14f)
            setPadding(28, 28, 28, 28)
        }
        colOut.addView(tvGeneratedToken)

        tvMetaSummary = TextView(this).apply {
            text = ""
            textSize = 12.5f
            setTextColor(Color.parseColor("#475569"))
            setPadding(8, 16, 8, 12)
        }
        colOut.addView(tvMetaSummary)

        val actRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val btnCopyToken = Button(this).apply {
            text = "📋 نسخ رمز SL1"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#4C1D95"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 8, 0)
            }
            setOnClickListener {
                if (lastToken.isEmpty()) generateAndDisplayToken()
                if (lastToken.isNotEmpty()) {
                    val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("SL1 Token", lastToken))
                    Toast.makeText(this@MainActivity, "✅ تم نسخ رمز SL1 إلى الحافظة", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val btnShareWa = Button(this).apply {
            text = "💬 إرسال عبر واتساب"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#059669"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(8, 0, 0, 0)
            }
            setOnClickListener {
                if (lastToken.isEmpty()) generateAndDisplayToken()
                if (lastToken.isNotEmpty()) {
                    try {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, lastToken)
                        }
                        startActivity(Intent.createChooser(intent, "Send SL1 License"))
                    } catch (_: Throwable) {}
                }
            }
        }
        actRow.addView(btnCopyToken)
        actRow.addView(btnShareWa)
        colOut.addView(actRow)

        cardOut.addView(colOut)
        root.addView(cardOut)

        scroll.addView(root)
        setContentView(scroll)
        updateModeChipsUI()
    }

    private fun updateModeChipsUI() {
        for ((k, tv) in modeChips) {
            if (k == selectedMode) {
                tv.background = roundedBg("#4C1D95", 12f)
                tv.setTextColor(Color.WHITE)
            } else {
                tv.background = roundedBg("#F1F0F7", 12f)
                tv.setTextColor(Color.parseColor("#334155"))
            }
        }
        edtCustomDays.visibility = if (selectedMode == "custom") android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun parseRawRequest(raw: String) {
        if (raw.isBlank()) return
        val mDev = Regex("Device\\s*ID\\s*:\\s*([A-Za-z0-9\\-]+)", RegexOption.IGNORE_CASE).find(raw)
        if (mDev != null) {
            edtDeviceId.setText(mDev.groupValues[1].trim())
        } else if (!raw.contains("\n") && raw.trim().length >= 6) {
            edtDeviceId.setText(raw.trim())
        }
        when {
            raw.contains("1 day", ignoreCase = true) -> { selectedMode = "1"; updateModeChipsUI() }
            raw.contains("2 months", ignoreCase = true) -> { selectedMode = "60"; updateModeChipsUI() }
            raw.contains("1 month", ignoreCase = true) -> { selectedMode = "30"; updateModeChipsUI() }
            raw.contains("Custom", ignoreCase = true) -> { selectedMode = "90"; updateModeChipsUI() }
        }
    }

    private fun normalizeDeviceCode(code: String): String {
        val sb = StringBuilder(code.length)
        for (c in code) {
            val l = c.lowercaseChar()
            if (l in '0'..'9' || l in 'a'..'z') sb.append(l)
        }
        return sb.toString()
    }

    private fun computeDeviceFingerprint8(deviceCode: String): ByteArray {
        val norm = normalizeDeviceCode(deviceCode)
        if (norm.length >= 16 && norm.take(16).all { it in '0'..'9' || it in 'a'..'f' }) {
            val out = ByteArray(8)
            for (i in 0 until 8) {
                val hi = Character.digit(norm[i * 2], 16)
                val lo = Character.digit(norm[i * 2 + 1], 16)
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("SANED-BIND-v1:${norm.uppercase()}").toByteArray(Charsets.UTF_8))
        return digest.copyOfRange(0, 8)
    }

    private fun generateAndDisplayToken() {
        val devCode = edtDeviceId.text.toString().trim()
        if (devCode.isEmpty()) {
            Toast.makeText(this, "⚠️ يرجى إدخال معرّف الجهاز (Device ID) أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        val issuedDate = LocalDate.now()
        val isPerpetual = (selectedMode == "perpetual")
        val expiryDate: LocalDate? = when (selectedMode) {
            "1" -> issuedDate.plusDays(1)
            "30" -> issuedDate.plusMonths(1)
            "60" -> issuedDate.plusMonths(2)
            "90" -> issuedDate.plusMonths(3)
            "custom" -> issuedDate.plusDays((edtCustomDays.text.toString().toLongOrNull() ?: 30L).coerceAtLeast(1L))
            else -> null
        }

        val serial = (edtSerial.text.toString().toLongOrNull() ?: 1184L).coerceAtLeast(1L)
        val flags = if (isPerpetual) 0 else 1
        val fp8 = computeDeviceFingerprint8(devCode)
        val issuedDays = (issuedDate.toEpochDay() - SL1_BASE_DATE.toEpochDay()).coerceIn(0L, 65535L).toInt()
        val expiryDays = if (isPerpetual || expiryDate == null) 0 else (expiryDate.toEpochDay() - SL1_BASE_DATE.toEpochDay()).coerceIn(1L, 65535L).toInt()

        val buf = ByteBuffer.allocate(19).order(ByteOrder.BIG_ENDIAN)
        buf.put(1.toByte()) // version = 1 (0x01)
        buf.put(0xFF.toByte()) // tier = 255 (0xFF -> SL1.Af8B...)
        buf.put(flags.toByte()) // 1 = timed (Af8B), 0 = perpetual (Af8A)
        buf.put(fp8)
        buf.putShort(issuedDays.toShort())
        buf.putShort(expiryDays.toShort())
        buf.putInt((serial and 0xFFFFFFFFL).toInt())

        val payloadBytes = buf.array()
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(SL1_MASTER_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA512"))
        val sigBytes = mac.doFinal(payloadBytes)
        sigBytes[63] = (sigBytes[63].toInt() and 0x1F).toByte()

        val b64Flags = Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE
        val payloadB64 = Base64.encodeToString(payloadBytes, b64Flags)
        val sigB64 = Base64.encodeToString(sigBytes, b64Flags)

        lastToken = "SL1.$payloadB64.$sigB64"
        tvGeneratedToken.text = lastToken
        tvMetaSummary.text = "Device ID: $devCode\nIssued: $issuedDate  •  Expires: ${if (isPerpetual) "Perpetual (دائم)" else expiryDate.toString()}  •  Serial: #$serial"

        val nextSerial = serial + 1L
        getSharedPreferences("sl1_admin_prefs", Context.MODE_PRIVATE).edit().putLong("next_serial", nextSerial).apply()
        edtSerial.setText(nextSerial.toString())
        Toast.makeText(this, "✅ تم توليد رمز SL1 بنجاح", Toast.LENGTH_SHORT).show()
    }

    private fun createCard(): CardView {
        return CardView(this).apply {
            radius = 22f
            cardElevation = 4f
            setCardBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 24, 0, 0)
            }
        }
    }

    private fun createCardCol(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
    }

    private fun createTitle(txt: String): TextView {
        return TextView(this).apply {
            text = txt
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E1B2E"))
            setPadding(0, 0, 0, 14)
        }
    }
}

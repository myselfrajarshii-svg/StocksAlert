package com.stockalert

import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private val scope = MainScope()

    private lateinit var switchEnabled  : SwitchMaterial
    private lateinit var chipGroupStocks: ChipGroup
    private lateinit var chipGroupTimes : ChipGroup
    private lateinit var btnAddStock    : Button
    private lateinit var btnAddTime     : Button
    private lateinit var btnPickSound   : Button
    private lateinit var btnTestNow     : Button
    private lateinit var tvStatus       : TextView
    private lateinit var tvSoundName    : TextView
    private lateinit var fabSave        : FloatingActionButton

    private val stocks = mutableListOf<String>()
    private val times  = mutableListOf<String>()

    // ── Sound pickers ────────────────────────────────────────────

    /** System ringtone / notification picker */
    private val ringtonePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data
            ?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            ?: return@registerForActivityResult
        saveSound(uri)
    }

    /** File picker — lets user pick any audio from their gallery / Files */
    private val audioPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        // Take persistent permission so we can use it after reboot
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        saveSound(uri)
    }

    // ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        loadData()
        setupListeners()
        requestPermissions()
        NotificationHelper.createChannel(this)
        updateStatus()
        updateSoundLabel()
    }

    private fun bindViews() {
        switchEnabled   = findViewById(R.id.switchEnabled)
        chipGroupStocks = findViewById(R.id.chipGroupStocks)
        chipGroupTimes  = findViewById(R.id.chipGroupTimes)
        btnAddStock     = findViewById(R.id.btnAddStock)
        btnAddTime      = findViewById(R.id.btnAddTime)
        btnPickSound    = findViewById(R.id.btnPickSound)
        btnTestNow      = findViewById(R.id.btnTestNow)
        tvStatus        = findViewById(R.id.tvStatus)
        tvSoundName     = findViewById(R.id.tvSoundName)
        fabSave         = findViewById(R.id.fabSave)
    }

    private fun loadData() {
        stocks.clear(); stocks.addAll(PrefsManager.getStocks(this))
        times.clear();  times.addAll(PrefsManager.getTimes(this))
        switchEnabled.isChecked = PrefsManager.isEnabled(this)
        refreshChips()
    }

    private fun setupListeners() {
        switchEnabled.setOnCheckedChangeListener { _, checked ->
            PrefsManager.setEnabled(this, checked)
            if (checked) AlarmScheduler.scheduleAll(this) else AlarmScheduler.cancelAll(this)
            updateStatus()
        }

        btnAddStock.setOnClickListener  { showAddStockDialog() }
        btnAddTime.setOnClickListener   { showTimePicker() }
        btnPickSound.setOnClickListener { showSoundPickerMenu() }

        fabSave.setOnClickListener {
            PrefsManager.setStocks(this, stocks)
            PrefsManager.setTimes(this, times)
            // Recreate channel with current sound before rescheduling
            NotificationHelper.createChannel(this)
            AlarmScheduler.scheduleAll(this)
            updateStatus()
            Snackbar.make(fabSave, "✅ Saved & alarms scheduled", Snackbar.LENGTH_SHORT).show()
        }

        btnTestNow.setOnClickListener { testFetchNow() }
    }

    // ── Sound picker ─────────────────────────────────────────────

    private fun showSoundPickerMenu() {
        val options = arrayOf(
            "🎵 Pick from Gallery / Files",
            "🔔 Pick from System Notifications",
            "🔕 Silent (no sound)"
        )
        AlertDialog.Builder(this)
            .setTitle("Choose Notification Sound")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> audioPicker.launch(arrayOf("audio/*"))
                    1 -> openRingtonePicker()
                    2 -> {
                        saveSound(Uri.EMPTY)
                        Snackbar.make(fabSave, "Sound set to silent", Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun openRingtonePicker() {
        val currentUri = Uri.parse(PrefsManager.getSoundUri(this))
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,
                RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Notification Sound")
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        }
        ringtonePicker.launch(intent)
    }

    private fun saveSound(uri: Uri) {
        val uriStr = if (uri == Uri.EMPTY) "" else uri.toString()
        PrefsManager.setSoundUri(this, uriStr)
        // Recreate channel immediately so preview (test) uses new sound
        NotificationHelper.createChannel(this)
        updateSoundLabel()
        Snackbar.make(btnPickSound,
            "Sound saved — tap 🔔 Test to preview", Snackbar.LENGTH_SHORT).show()
    }

    private fun updateSoundLabel() {
        val uriStr = PrefsManager.getSoundUri(this)
        tvSoundName.text = when {
            uriStr.isBlank() || uriStr == Uri.EMPTY.toString() -> "Silent"
            uriStr == Settings.System.DEFAULT_NOTIFICATION_URI.toString() -> "Default notification sound"
            else -> {
                // Try to resolve a friendly name for the URI
                try {
                    val uri = Uri.parse(uriStr)
                    // For gallery audio: query MediaStore for the display name
                    val cursor = contentResolver.query(uri, null, null, null, null)
                    cursor?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex("_display_name")
                                .takeIf { it >= 0 }
                                ?: c.getColumnIndex("title")
                            if (idx >= 0) c.getString(idx) else uriStr.substringAfterLast("/")
                        } else uriStr.substringAfterLast("/")
                    } ?: run {
                        // Ringtone title
                        val rt = RingtoneManager.getRingtone(this, uri)
                        rt?.getTitle(this) ?: uriStr.substringAfterLast("/")
                    }
                } catch (e: Exception) {
                    uriStr.substringAfterLast("/")
                }
            }
        }
    }

    // ── Chips ─────────────────────────────────────────────────────

    private fun refreshChips() {
        chipGroupStocks.removeAllViews()
        stocks.forEach { sym ->
            chipGroupStocks.addView(makeChip(sym) { stocks.remove(sym); refreshChips() })
        }
        chipGroupTimes.removeAllViews()
        times.forEach { t ->
            chipGroupTimes.addView(makeChip(t) { times.remove(t); refreshChips() })
        }
    }

    private fun makeChip(label: String, onDelete: () -> Unit): Chip =
        Chip(this).apply {
            text = label
            isCloseIconVisible = true
            setOnCloseIconClickListener { onDelete() }
        }

    // ── Dialogs ───────────────────────────────────────────────────

    private fun showAddStockDialog() {
        val et = EditText(this).apply {
            hint = "e.g. NIFTYBEES.NS"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Add Stock Symbol")
            .setMessage("Use Yahoo Finance format: SYMBOL.NS for NSE")
            .setView(et)
            .setPositiveButton("Add") { _, _ ->
                val sym = et.text.toString().trim().uppercase()
                if (sym.isNotEmpty() && !stocks.contains(sym)) {
                    stocks.add(sym); refreshChips()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTimePicker() {
        TimePickerDialog(this, { _, hour, minute ->
            val t = "%02d:%02d".format(hour, minute)
            if (!times.contains(t)) { times.add(t); times.sort(); refreshChips() }
        }, 10, 30, true).show()
    }

    // ── Test ──────────────────────────────────────────────────────

    private fun testFetchNow() {
        tvStatus.text = "⏳ Fetching prices..."
        scope.launch {
            val result = withContext(Dispatchers.IO) { StockFetcher.fetchPrices(stocks) }
            val lines  = stocks.map { sym -> StockFetcher.formatLine(sym, result[sym]) }
            NotificationHelper.postPriceAlert(
                context   = this@MainActivity,
                timeLabel = "Test",
                lines     = lines
            )
            tvStatus.text = "✅ Test notification sent!"
        }
    }

    // ── Status ────────────────────────────────────────────────────

    private fun updateStatus() {
        if (!PrefsManager.isEnabled(this)) { tvStatus.text = "⏸ Alerts disabled"; return }
        val t = PrefsManager.getTimes(this)
        tvStatus.text = if (t.isEmpty()) "⚠️ No times set — tap Save"
                        else "✅ Active — ${t.joinToString(", ")} IST"
    }

    // ── Permissions ───────────────────────────────────────────────

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            // READ_MEDIA_AUDIO for picking gallery sounds on Android 13+
            if (ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO), 2)
            }
        } else if (Build.VERSION.SDK_INT >= 26) {
            // READ_EXTERNAL_STORAGE for Android 8–12
            if (ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 2)
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                AlertDialog.Builder(this)
                    .setTitle("Permission Required")
                    .setMessage("Enable 'Alarms & Reminders' for exact time alerts.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")))
                    }
                    .setNegativeButton("Later", null).show()
            }
        }
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}

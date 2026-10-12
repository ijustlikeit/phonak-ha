package app.phonakbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Settings and recent activity. The work happens in [BridgeService]. */
class MainActivity : Activity() {
    private lateinit var url: EditText
    private lateinit var right: Spinner
    private lateinit var left: Spinner
    private lateinit var checkIn: EditText
    private lateinit var read: EditText
    private lateinit var battery: Button
    private lateinit var log: TextView
    private var devices = emptyList<BluetoothDevice?>()
    private var ui: CoroutineScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Our own title instead of the action bar, which covered the first field
        // (apps targeting Android 15+ draw edge to edge).
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(text: String) = column.addView(TextView(this).apply {
            this.text = text
            setPadding(0, pad / 2, 0, 0)
        })

        column.addView(TextView(this).apply {
            text = "Phonak Bridge"
            textSize = 24f
        })
        label("1. Home Assistant webhook URL (shown when you add the Phonak integration)")
        val urlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        url = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://…/api/webhook/…"
        }
        urlRow.addView(url, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        urlRow.addView(Button(this).apply {
            text = "Paste"
            setOnClickListener {
                val clip = getSystemService(ClipboardManager::class.java).primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)
                if (text.isNullOrBlank()) toast("Copy the URL in Home Assistant first") else url.setText(text.trim())
            }
        })
        column.addView(urlRow)
        label("2. Right hearing aid (R-Phonak… or LE_R-…)")
        right = Spinner(this).also { column.addView(it) }
        label("   Left hearing aid (L-Phonak… or LE_L-…)")
        left = Spinner(this).also { column.addView(it) }
        label("Check in with Home Assistant every (seconds)")
        checkIn = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER }.also { column.addView(it) }
        label("Read the hearing aids every (seconds)")
        read = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER }.also { column.addView(it) }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "3. Save & start"
            setOnClickListener { saveAndStart() }
        })
        buttons.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                save(enabled = false)
                BridgeService.stop(this@MainActivity)
            }
        })
        column.addView(buttons)
        battery = Button(this).apply {
            text = "4. Allow running in the background"
            setOnClickListener { askBatteryExemption() }
        }
        column.addView(battery)

        label("Activity")
        log = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        }
        column.addView(log)
        setContentView(ScrollView(this).apply {
            addView(column)
            // Keep clear of the status and navigation bars.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })

        val settings = Settings.load(this)
        url.setText(settings.webhookUrl)
        checkIn.setText(settings.checkInSeconds.toString())
        read.setText(settings.readSeconds.toString())

        val missing = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        // Opening the app brings the bridge back if it was stopped (e.g. force-closed).
        if (settings.enabled && missing.isEmpty() && settings.aids.isNotEmpty()) BridgeService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadDevices()
    }

    override fun onStart() {
        super.onStart()
        // Also after the permission was granted in the dialog or in Settings.
        if (devices.isEmpty()) loadDevices()
        ui = CoroutineScope(SupervisorJob() + Dispatchers.Main).apply {
            launch { BridgeLog.lines.collect { log.text = it.joinToString("\n") } }
        }
        val ignoring = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        battery.visibility = if (ignoring) View.GONE else View.VISIBLE
    }

    override fun onStop() {
        ui?.cancel()
        super.onStop()
    }

    /** Paired devices for the two pickers, with a guess for each side. */
    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val bonded = getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty()
            .sortedWith(compareBy({ !(it.name ?: "").startsWith("LE_") }, { it.name ?: "" }))
        devices = listOf(null) + bonded
        val labels = devices.map { d ->
            if (d == null) "(none)" else "${d.name ?: "?"}  ${d.address}  ${typeName(d.type)}"
        }
        val settings = Settings.load(this)
        // Phonak's docs show separate LE_R-/LE_L- entries; on the S26 the aids are
        // bonded once each, as "R-Phonak hearing aid" and "L-Phonak hearing aid".
        for ((spinner, saved, side) in listOf(
            Triple(right, settings.right, "R"), Triple(left, settings.left, "L"))) {
            // The dropdown-item layout as the closed view rendered 0 px tall on the S26.
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val guess = devices.indexOfFirst { it != null && it.address == saved }.takeIf { it >= 0 }
                ?: devices.indexOfFirst { (it?.name ?: "").startsWith("LE_$side") }.takeIf { it >= 0 }
                ?: devices.indexOfFirst { (it?.name ?: "").startsWith("$side-Phonak") }.takeIf { it >= 0 }
                ?: 0
            spinner.setSelection(guess)
        }
    }

    private fun typeName(type: Int) = when (type) {
        BluetoothDevice.DEVICE_TYPE_LE -> "LE"
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
        else -> ""
    }

    private fun save(enabled: Boolean): Settings {
        val settings = Settings(
            webhookUrl = url.text.toString().trim(),
            right = devices.getOrNull(right.selectedItemPosition)?.address,
            left = devices.getOrNull(left.selectedItemPosition)?.address,
            checkInSeconds = checkIn.text.toString().toIntOrNull()?.coerceIn(5, 3600) ?: Settings.DEFAULT_CHECK_IN,
            readSeconds = read.text.toString().toIntOrNull()?.coerceIn(30, 86400) ?: Settings.DEFAULT_READ,
            enabled = enabled,
        )
        settings.save(this)
        return settings
    }

    private fun saveAndStart() {
        val settings = save(enabled = true)
        if (settings.webhookUrl.isBlank()) return toast("Enter the webhook URL first")
        if (settings.aids.isEmpty()) return toast("Pick at least one hearing aid")
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return toast("Allow the Bluetooth permission first")
        }
        // A running service picks up the new settings at its next check-in.
        BridgeService.start(this)
        toast("Started")
    }

    @SuppressLint("BatteryLife") // a personal bridge that has to keep running
    private fun askBatteryExemption() {
        startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

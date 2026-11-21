package com.mahor.lanremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

data class LanTarget(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int,
    var smoothedRssi: Double = -95.0,
    var lastSeen: Long = 0L
)

class MainActivity : AppCompatActivity() {

    private val devices = mutableMapOf<String, LanTarget>()
    private var activeDevice: LanTarget? = null
    private var isAutoMode = true

    private lateinit var statusText: TextView
    private lateinit var modeToggleBtn: Button
    private lateinit var deviceSpinner: Spinner
    private lateinit var spinnerAdapter: ArrayAdapter<String>
    private val deviceListLabels = mutableListOf<String>()
    private val deviceListIds = mutableListOf<String>()

    private val udpExecutor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())

    // Pre-seed your home devices (you can change the IPs later)
    private fun seedDefaultDevices() {
        val now = System.currentTimeMillis()
        devices["LIVING_TV"] = LanTarget("LIVING_TV", "Living Room TV", "192.168.31.101", 9876, -85.0, now)
        devices["BEDROOM_PC"] = LanTarget("BEDROOM_PC", "Windows 11 PC", "192.168.31.102", 9876, -88.0, now)
        activeDevice = devices["LIVING_TV"]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        seedDefaultDevices()
        buildGlassUi()
        requestBlePermissions()
    }

    private fun buildGlassUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1117"))
            setPadding(48, 64, 48, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "PROXIMITY LAN REMOTE"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#58A6FF"))
            letterSpacing = 0.1f
        }
        root.addView(title)

        statusText = TextView(this).apply {
            text = "Active: ${activeDevice?.name} (${activeDevice?.ip})"
            textSize = 14f
            setTextColor(Color.parseColor("#C9D1D9"))
            setPadding(0, 24, 0, 24)
        }
        root.addView(statusText)

        modeToggleBtn = Button(this).apply {
            text = "MODE: AUTO PROXIMITY"
            setTextColor(Color.WHITE)
            background = pillDrawable("#1F6FEB")
            setOnClickListener {
                isAutoMode = !isAutoMode
                text = if (isAutoMode) "MODE: AUTO PROXIMITY" else "MODE: MANUAL LOCK"
                background = pillDrawable(if (isAutoMode) "#1F6FEB" else "#8957E5")
            }
        }
        root.addView(modeToggleBtn)

        // Device selector
        val spinnerLabel = TextView(this).apply {
            text = "Select Device"
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 32, 0, 8)
        }
        root.addView(spinnerLabel)

        deviceListLabels.add("Living Room TV (192.168.31.101)")
        deviceListIds.add("LIVING_TV")
        deviceListLabels.add("Windows 11 PC (192.168.31.102)")
        deviceListIds.add("BEDROOM_PC")

        spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, deviceListLabels)
        deviceSpinner = Spinner(this).apply {
            adapter = spinnerAdapter
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (!isAutoMode && position < deviceListIds.size) {
                        activeDevice = devices[deviceListIds[position]]
                        updateStatusUi()
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        root.addView(deviceSpinner)

        // Control buttons
        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 40, 0, 0)
        }

        val buttons = listOf(
            "▶ Play / Pause" to "PLAY_PAUSE",
            "🔊 Volume Up" to "VOL_UP",
            "🔉 Volume Down" to "VOL_DOWN",
            "🔇 Mute" to "MUTE",
            "⏭ Next" to "NEXT",
            "⏮ Previous" to "PREV"
        )

        buttons.forEach { (label, command) ->
            val btn = Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                background = pillDrawable("#238636")
                setPadding(32, 24, 32, 24)
                setOnClickListener { sendUdpCommand(command) }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
            buttonContainer.addView(btn, params)
        }

        root.addView(buttonContainer)
        setContentView(root)
    }

    private fun pillDrawable(colorHex: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 48f
            setColor(Color.parseColor(colorHex))
        }
    }

    private fun requestBlePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        val needed = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
        } else {
            startBleScan()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startBleScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return
        if (!adapter.isEnabled) return

        val scanner = adapter.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val advName = result.scanRecord?.deviceName ?: return
                // Expected format example: LAN|LIVING_TV|192.168.31.101|9876
                val parts = advName.split("|")
                if (parts.size == 4 && parts[0] == "LAN") {
                    val id = parts[1]
                    val ip = parts[2]
                    val port = parts[3].toIntOrNull() ?: 9876
                    val now = System.currentTimeMillis()
                    val target = devices.getOrPut(id) {
                        LanTarget(id, id, ip, port, result.rssi.toDouble(), now)
                    }
                    target.smoothedRssi = (0.35 * result.rssi) + (0.65 * target.smoothedRssi)
                    target.lastSeen = now
                    refreshSpinner()
                    if (isAutoMode) evaluateProximity()
                }
            }
        })
    }

    private fun evaluateProximity() {
        val now = System.currentTimeMillis()
        val recent = devices.values.filter { now - it.lastSeen < 5000 }
        val strongest = recent.maxByOrNull { it.smoothedRssi } ?: return
        val current = activeDevice

        if (current == null || (strongest.id != current.id && strongest.smoothedRssi > current.smoothedRssi + 8.0)) {
            activeDevice = strongest
            updateStatusUi()
        }
    }

    private fun updateStatusUi() {
        uiHandler.post {
            val dev = activeDevice
            statusText.text = "Active: ${dev?.name} (${dev?.ip}) | Signal: ${dev?.smoothedRssi?.toInt()} dBm"
        }
    }

    private fun refreshSpinner() {
        uiHandler.post {
            deviceListLabels.clear()
            deviceListIds.clear()
            devices.values.forEach {
                deviceListLabels.add("${it.name} (${it.ip})")
                deviceListIds.add(it.id)
            }
            spinnerAdapter.notifyDataSetChanged()
        }
    }

    private fun sendUdpCommand(command: String) {
        val target = activeDevice ?: return
        udpExecutor.execute {
            try {
                val socket = DatagramSocket()
                val data = command.toByteArray(StandardCharsets.UTF_8)
                val address = InetAddress.getByName(target.ip)
                val packet = DatagramPacket(data, data.size, address, target.port)
                socket.send(packet)
                socket.close()
                uiHandler.post {
                    Toast.makeText(this, "Sent: $command → ${target.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                uiHandler.post {
                    Toast.makeText(this, "Failed to send: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

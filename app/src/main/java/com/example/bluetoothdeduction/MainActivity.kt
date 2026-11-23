package com.example.bluetoothdeduction

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import com.example.bluetoothdeduction.bluetooth.BluetoothGameClient
import com.example.bluetoothdeduction.bluetooth.BluetoothGameHost
import com.example.bluetoothdeduction.bluetooth.ClientGameSessionListener
import com.example.bluetoothdeduction.bluetooth.HostGameSessionListener
import com.example.bluetoothdeduction.core.GameEvent
import com.example.bluetoothdeduction.core.GamePhase
import com.example.bluetoothdeduction.core.PrivateRoleInfo
import com.example.bluetoothdeduction.core.PublicGameSnapshot
import com.example.bluetoothdeduction.core.PublicPlayer
import com.example.bluetoothdeduction.core.Role
import org.json.JSONObject

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_BT_PERMISSIONS = 100
        private const val REQUEST_ENABLE_BT = 101
        private const val REQUEST_DISCOVERABLE = 102
        private const val PREFS = "deduction_game_prefs"
        private const val KEY_RECONNECT_TOKEN = "reconnect_token"
    }

    private lateinit var nameInput: EditText
    private lateinit var statusText: TextView
    private lateinit var roleText: TextView
    private lateinit var playersText: TextView
    private lateinit var hostControls: LinearLayout
    private lateinit var clientControls: LinearLayout

    private var host: BluetoothGameHost? = null
    private var client: BluetoothGameClient? = null
    private var latestPlayers: List<PublicPlayer> = emptyList()
    private var currentPhase: GamePhase = GamePhase.LOBBY
    private var myPlayerId: String = ""
    private var myRole: Role? = null
    private var mafiaTeamIds: List<String> = emptyList()
    private var roleKnown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        statusText.text = "جاهز. أنشئ غرفة أو اتصل بمضيف عبر جهاز مقترن."
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        val title = TextView(this).apply {
            text = "MAFIA / DEDUCTION"
            textSize = 25f
            setPadding(0, 0, 0, 18)
        }
        nameInput = EditText(this).apply {
            hint = "اسم اللاعب"
            setSingleLine(true)
            setText("Player-${(100..999).random()}")
        }
        statusText = TextView(this).apply { textSize = 15f; setPadding(0, 14, 0, 14) }
        roleText = TextView(this).apply { textSize = 18f; setPadding(0, 10, 0, 10) }
        playersText = TextView(this).apply { textSize = 15f; setPadding(0, 10, 0, 10) }

        content.addView(title)
        content.addView(nameInput)
        content.addView(makeButton("استضافة لعبة عبر Bluetooth") { hostGame() })
        content.addView(makeButton("الاتصال بجهاز مقترن") { choosePairedDevice() })
        content.addView(statusText)
        content.addView(roleText)
        content.addView(playersText)

        hostControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hostControls.addView(sectionTitle("تحكم المضيف"))
        hostControls.addView(makeButton("بدء الجولة وتوزيع الأدوار") { safeHostAction { host?.startGame() } })
        hostControls.addView(makeButton("الإبلاغ عن جثة") { safeHostAction { host?.reportBodyAsHost() } })
        hostControls.addView(makeButton("تصويت المضيف") { chooseHostVoteTarget() })
        hostControls.addView(makeButton("بدء التصويت") { safeHostAction { host?.beginVoting() } })
        hostControls.addView(makeButton("حسم التصويت والانتقال لليل") { safeHostAction { host?.resolveVoting() } })
        hostControls.addView(makeButton("اختيار إجراء ليلي") { chooseHostNightTarget() })
        hostControls.addView(makeButton("حسم مرحلة الليل") { safeHostAction { host?.resolveNight() } })
        hostControls.addView(makeButton("إيقاف الاستضافة") { host?.stop(); host = null; status("تم إيقاف الاستضافة") })

        clientControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clientControls.addView(sectionTitle("تحكم اللاعب المتصل"))
        clientControls.addView(makeButton("الإبلاغ عن جثة") { runClientAction { client?.reportBody() } })
        clientControls.addView(makeButton("التصويت") { chooseClientVoteTarget() })
        clientControls.addView(makeButton("الإجراء الليلي") { chooseClientNightTarget() })
        clientControls.addView(makeButton("قطع الاتصال") { client?.disconnect(); client = null; status("تم قطع الاتصال") })

        content.addView(hostControls)
        content.addView(clientControls)
        root.addView(scroll)
        setContentView(root)
        hostControls.visibility = View.GONE
        clientControls.visibility = View.GONE
    }

    private fun makeButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 19f
        setPadding(0, 20, 0, 5)
    }

    private fun hostGame() {
        if (!ensureBluetoothPermissions()) return
        val adapter = bluetoothAdapter() ?: return show("هذا الجهاز لا يدعم Bluetooth")
        if (!adapter.isEnabled) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT)
            return
        }
        try {
            client?.disconnect()
            client = null
            host?.stop()
            host = BluetoothGameHost(this, playerName(), object : HostGameSessionListener {
                override fun onSnapshot(snapshot: PublicGameSnapshot) = runOnUiThread { renderSnapshot(snapshot) }
                override fun onPrivateRole(info: PrivateRoleInfo) = runOnUiThread {
                    myPlayerId = host?.hostPlayerId ?: myPlayerId
                    myRole = info.role
                    mafiaTeamIds = info.mafiaTeamIds
                    roleKnown = true
                    renderRole(info)
                }
                override fun onDetectiveResult(targetId: String, isMafia: Boolean) = runOnUiThread {
                    show("نتيجة التحقيق: ${playerNameFor(targetId)} ${if (isMafia) "من المافيا" else "ليس من المافيا"}")
                }
                override fun onNotice(message: String) = runOnUiThread { status(message) }
                override fun onError(message: String) = runOnUiThread { show(message) }
            })
            host?.startHosting()
            hostControls.visibility = View.VISIBLE
            clientControls.visibility = View.GONE
            myPlayerId = host?.hostPlayerId ?: ""
            status("الخادم يعمل. اجعل الأجهزة الأخرى تقترن بهذا الجهاز ثم تتصل به.")
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300), REQUEST_DISCOVERABLE)
        } catch (t: Throwable) {
            show("تعذر بدء الاستضافة: ${t.message}")
        }
    }

    private fun choosePairedDevice() {
        if (!ensureBluetoothPermissions()) return
        val adapter = bluetoothAdapter() ?: return show("هذا الجهاز لا يدعم Bluetooth")
        if (!adapter.isEnabled) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT)
            return
        }
        try {
            val devices = adapter.bondedDevices.toList()
            if (devices.isEmpty()) return show("لا توجد أجهزة مقترنة. اقترن بالجهاز المضيف من إعدادات Android أولاً.")
            val labels = devices.map { device ->
                val deviceName = runCatching { device.name }.getOrNull() ?: "جهاز غير مسمى"
                "$deviceName (${device.address})"
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("اختر الجهاز المضيف")
                .setItems(labels) { _, index -> connectTo(devices[index]) }
                .setNegativeButton("إلغاء", null)
                .show()
        } catch (t: Throwable) {
            show("تعذر قراءة الأجهزة المقترنة: ${t.message}")
        }
    }

    private fun connectTo(device: BluetoothDevice) {
        try {
            host?.stop()
            host = null
            client?.disconnect()
            val savedToken = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_RECONNECT_TOKEN, "") ?: ""
            client = BluetoothGameClient(this, object : ClientGameSessionListener {
                override fun onConnected(playerId: String, reconnectToken: String) = runOnUiThread {
                    myPlayerId = playerId
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_RECONNECT_TOKEN, reconnectToken).apply()
                    clientControls.visibility = View.VISIBLE
                    hostControls.visibility = View.GONE
                    status("تم الاتصال بالمضيف بنجاح")
                }
                override fun onSnapshot(snapshot: JSONObject) = runOnUiThread { renderClientSnapshot(snapshot) }
                override fun onPrivateRole(role: Role, mafiaTeamIds: List<String>) = runOnUiThread {
                    myRole = role
                    this@MainActivity.mafiaTeamIds = mafiaTeamIds
                    roleKnown = true
                    roleText.text = "دورك: ${roleArabic(role)}" +
                        if (role == Role.MAFIA) "\nفريق المافيا: ${mafiaTeamIds.joinToString()}" else ""
                    renderActionControls()
                }
                override fun onDetectiveResult(targetId: String, isMafia: Boolean) = runOnUiThread {
                    show("نتيجة التحقيق: ${playerNameFor(targetId)} ${if (isMafia) "من المافيا" else "ليس من المافيا"}")
                }
                override fun onEvent(event: JSONObject) = runOnUiThread {
                    val kind = event.optString("event")
                    if (kind.isNotBlank()) status("حدث: $kind")
                }
                override fun onDisconnected() = runOnUiThread { status("انقطع الاتصال. أعد الاتصال خلال 30 ثانية لاستعادة مقعدك.") }
                override fun onError(message: String) = runOnUiThread { show(message) }
            })
            client?.connect(device, playerName(), savedToken)
            status("جارٍ الاتصال بـ ${runCatching { device.name }.getOrNull() ?: "الجهاز المضيف"}...")
        } catch (t: Throwable) {
            show("تعذر الاتصال: ${t.message}")
        }
    }

    private fun renderSnapshot(snapshot: PublicGameSnapshot) {
        currentPhase = snapshot.phase
        latestPlayers = snapshot.players
        playersText.text = "الحالة: ${phaseArabic(snapshot.phase)}\n" + snapshot.players.joinToString("\n") {
            "${if (it.alive) "●" else "×"} ${it.name}${if (it.connected) "" else " (منقطع)"}"
        }
        renderActionControls()
    }

    private fun renderClientSnapshot(snapshot: JSONObject) {
        currentPhase = runCatching { GamePhase.valueOf(snapshot.optString("phase")) }.getOrDefault(GamePhase.LOBBY)
        val array = snapshot.optJSONArray("players")
        val list = mutableListOf<PublicPlayer>()
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                list += PublicPlayer(
                    item.optString("id"), item.optString("name"),
                    item.optBoolean("connected"), item.optBoolean("alive")
                )
            }
        }
        latestPlayers = list
        playersText.text = "الحالة: ${phaseArabic(currentPhase)}\n" + list.joinToString("\n") {
            "${if (it.alive) "●" else "×"} ${it.name}${if (it.connected) "" else " (منقطع)"}"
        }
        renderActionControls()
    }

    private fun renderRole(info: PrivateRoleInfo) {
        roleText.text = "دورك: ${roleArabic(info.role)}" +
            if (info.role == Role.MAFIA) "\nفريق المافيا: ${info.mafiaTeamIds.joinToString()}" else ""
        renderActionControls()
    }

    private fun renderActionControls() {
        // The current prototype keeps the host's phase buttons visible; core rules
        // remain authoritative and reject a command attempted in the wrong phase.
        hostControls.visibility = if (host != null) View.VISIBLE else View.GONE
        clientControls.visibility = if (client != null) View.VISIBLE else View.GONE
        roleText.text = when {
            roleKnown && myRole != null -> "دورك: ${roleArabic(myRole!!)}" +
                if (myRole == Role.MAFIA) "\nمعرّفات فريق المافيا: ${mafiaTeamIds.joinToString()}" else ""
            else -> "لم يتم توزيع الأدوار بعد"
        }
    }

    private fun chooseHostVoteTarget() {
        choosePlayer("اختر اللاعب الذي تصوّت ضده", includeSelf = false) { target ->
            safeHostAction { host?.submitHostVote(target.id) }
        }
    }

    private fun chooseHostNightTarget() {
        val role = myRole
        if (role == null || role == Role.CITIZEN) return show("ليس لديك إجراء ليلي")
        choosePlayer("اختر هدف الإجراء الليلي", includeSelf = role == Role.DOCTOR) { target ->
            safeHostAction { host?.submitHostNightAction(target.id) }
        }
    }

    private fun chooseClientNightTarget() {
        val role = myRole
        if (role == null || role == Role.CITIZEN) return show("انتظر توزيع الأدوار أو ليس لديك إجراء ليلي")
        choosePlayer("اختر هدف الإجراء الليلي", includeSelf = role == Role.DOCTOR) { target ->
            runClientAction { client?.nightAction(target.id) }
        }
    }

    private fun chooseClientVoteTarget() {
        choosePlayer("اختر اللاعب الذي تصوّت ضده", includeSelf = false) { target ->
            runClientAction { client?.castVote(target.id) }
        }
    }

    private fun choosePlayer(title: String, includeSelf: Boolean, selected: (PublicPlayer) -> Unit) {
        val available = latestPlayers.filter { it.alive && (includeSelf || it.id != myPlayerId) }
        if (available.isEmpty()) return show("لا يوجد لاعبون متاحون")
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(available.map { it.name }.toTypedArray()) { _, index -> selected(available[index]) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun safeHostAction(action: () -> Unit) {
        try { action() } catch (t: Throwable) { show(t.message ?: "تعذر تنفيذ الأمر") }
    }

    private fun runClientAction(action: () -> Unit) {
        try { action() } catch (t: Throwable) { show(t.message ?: "تعذر تنفيذ الأمر") }
    }

    private fun playerNameFor(id: String): String = latestPlayers.firstOrNull { it.id == id }?.name ?: id
    private fun playerName(): String = nameInput.text.toString().trim().ifBlank { "Player" }
    private fun status(message: String) { statusText.text = message }
    private fun show(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun bluetoothAdapter(): BluetoothAdapter? =
        getSystemService(BluetoothManager::class.java)?.adapter

    private fun ensureBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val required = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_BT_PERMISSIONS)
            return false
        }
        return true
    }

    @Deprecated("Legacy activity result callback retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            status(if (resultCode == RESULT_OK) "تم تشغيل Bluetooth. أعد الضغط على الاستضافة أو الاتصال." else "لم يتم تشغيل Bluetooth")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT_PERMISSIONS && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            status("تم منح صلاحيات Bluetooth. أعد اختيار الاستضافة أو الاتصال.")
        } else if (requestCode == REQUEST_BT_PERMISSIONS) {
            show("يلزم منح صلاحيات Bluetooth لتشغيل اللعبة")
        }
    }

    override fun onDestroy() {
        host?.stop()
        client?.disconnect()
        super.onDestroy()
    }

    private fun roleArabic(role: Role): String = when (role) {
        Role.MAFIA -> "المافيا"
        Role.CITIZEN -> "المواطن"
        Role.BULLET -> "الطلقة"
        Role.DETECTIVE -> "المحقق"
        Role.DOCTOR -> "الدكتور"
    }

    private fun phaseArabic(phase: GamePhase): String = when (phase) {
        GamePhase.LOBBY -> "الردهة"
        GamePhase.ROLE_ASSIGNMENT -> "توزيع الأدوار"
        GamePhase.FREE_ROAM_DAY -> "التجول النهاري"
        GamePhase.NIGHT_PHASE -> "مرحلة الليل"
        GamePhase.MEETING_DISCUSSION -> "النقاش والاجتماع"
        GamePhase.VOTING -> "التصويت"
        GamePhase.RESOLUTION_WIN_CHECK -> "حسم النتائج والتحقق من الفوز"
        GamePhase.FINISHED -> "انتهت اللعبة"
    }
}

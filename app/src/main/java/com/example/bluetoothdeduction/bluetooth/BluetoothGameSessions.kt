package com.example.bluetoothdeduction.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.example.bluetoothdeduction.core.GameEngine
import com.example.bluetoothdeduction.core.GameEvent
import com.example.bluetoothdeduction.core.GamePhase
import com.example.bluetoothdeduction.core.PrivateRoleInfo
import com.example.bluetoothdeduction.core.PublicGameSnapshot
import com.example.bluetoothdeduction.core.Role
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface HostGameSessionListener {
    fun onSnapshot(snapshot: PublicGameSnapshot)
    fun onPrivateRole(info: PrivateRoleInfo)
    fun onDetectiveResult(targetId: String, isMafia: Boolean)
    fun onNotice(message: String)
    fun onError(message: String)
}

/** Host-side coordinator: all game mutations are executed by this device. */
class BluetoothGameHost(
    context: Context,
    hostName: String,
    private val listener: HostGameSessionListener
) : BluetoothTransportListener {
    companion object {
        const val RECONNECT_GRACE_SECONDS = 30L
    }

    val hostPlayerId: String = UUID.randomUUID().toString()
    private val engine = GameEngine()
    private val transport = BluetoothClassicTransport(context, this)
    private val graceExecutor = Executors.newSingleThreadScheduledExecutor()
    private val peerToPlayer = mutableMapOf<String, String>()
    private val playerToPeer = mutableMapOf<String, String>()
    private val tokenToPlayer = mutableMapOf<String, String>()
    private val disconnectGenerations = mutableMapOf<String, Long>()
    private val pendingDetectiveResults = mutableMapOf<String, LinkedHashMap<String, JSONObject>>()

    init {
        engine.addPlayer(hostPlayerId, hostName)
    }

    fun startHosting() = transport.startServer()
    fun snapshot(): PublicGameSnapshot = engine.publicSnapshot()

    @Synchronized
    fun startGame() = publish(engine.startGame())

    @Synchronized
    fun beginVoting() = publish(engine.beginVoting())

    @Synchronized
    fun resolveVoting() = publish(engine.resolveVoting())

    @Synchronized
    fun resolveNight() = publish(engine.resolveNight())

    @Synchronized
    fun reportBodyAsHost() = publish(engine.reportBody(hostPlayerId))

    @Synchronized
    fun submitHostVote(targetPlayerId: String?) = publish(engine.castVote(hostPlayerId, targetPlayerId))

    @Synchronized
    fun submitHostNightAction(targetPlayerId: String) = publish(engine.submitNightAction(hostPlayerId, targetPlayerId))

    fun stop() {
        transport.stop()
        graceExecutor.shutdownNow()
    }

    override fun onPeerConnected(peerId: String, deviceName: String) {
        listener.onNotice("اتصل جهاز: $deviceName")
    }

    @Synchronized
    override fun onPeerDisconnected(peerId: String) {
        val playerId = peerToPlayer.remove(peerId) ?: return
        playerToPeer.remove(playerId)
        publish(engine.markDisconnected(playerId))
        val generation = (disconnectGenerations[playerId] ?: 0L) + 1L
        disconnectGenerations[playerId] = generation
        graceExecutor.schedule({
            synchronized(this) {
                val player = engine.getPlayer(playerId)
                val stillSameDisconnect = disconnectGenerations[playerId] == generation
                if (stillSameDisconnect && player != null && !player.connected) {
                    if (player.alive) publish(engine.forfeitDisconnectedPlayer(playerId))
                    tokenToPlayer.entries.removeAll { it.value == playerId }
                    disconnectGenerations.remove(playerId)
                }
            }
        }, RECONNECT_GRACE_SECONDS, TimeUnit.SECONDS)
        listener.onNotice("انقطع اتصال لاعب. مهلة إعادة الاتصال: ${RECONNECT_GRACE_SECONDS} ثانية")
    }

    @Synchronized
    override fun onMessage(peerId: String, type: String, payload: JSONObject) {
        try {
            when (type) {
                "JOIN" -> handleJoin(peerId, payload)
                "REPORT_BODY" -> {
                    val playerId = authenticatedPlayer(peerId)
                    publish(engine.reportBody(playerId))
                }
                "CAST_VOTE" -> {
                    val playerId = authenticatedPlayer(peerId)
                    publish(engine.castVote(playerId, payload.optString("targetId").takeIf { it.isNotBlank() }))
                }
                "NIGHT_ACTION" -> {
                    val playerId = authenticatedPlayer(peerId)
                    val target = payload.getString("targetId")
                    publish(engine.submitNightAction(playerId, target))
                }
                "PRIVATE_ACK" -> {
                    val playerId = authenticatedPlayer(peerId)
                    val messageId = payload.optString("privateMessageId")
                    pendingDetectiveResults[playerId]?.remove(messageId)
                }
                "POSITION" -> {
                    authenticatedPlayer(peerId)
                    // Movement is deliberately relayed as a low-priority, non-ACK event.
                    transport.broadcast("PLAYER_POSITION", payload, reliable = false)
                }
                else -> sendError(peerId, "نوع رسالة غير مدعوم: $type")
            }
        } catch (t: Throwable) {
            sendError(peerId, t.message ?: "تعذر تنفيذ الطلب")
        }
    }

    override fun onTransportError(message: String, cause: Throwable?) {
        listener.onError(message + (cause?.message?.let { ": $it" } ?: ""))
    }

    private fun handleJoin(peerId: String, payload: JSONObject) {
        val name = payload.optString("name", "لاعب").trim().ifBlank { "لاعب" }
        val suppliedToken = payload.optString("reconnectToken", "")
        val existingPlayerId = tokenToPlayer[suppliedToken]

        val playerId: String
        val token: String
        if (!existingPlayerId.isNullOrBlank()) {
            val existing = engine.getPlayer(existingPlayerId)
                ?: throw IllegalStateException("تعذر العثور على جلسة اللاعب")
            check(existing.alive) { "انتهت مهلة إعادة الاتصال أو تم استبعاد اللاعب" }
            check(!existing.connected) { "هذا اللاعب متصل بالفعل" }
            disconnectGenerations[existingPlayerId] = (disconnectGenerations[existingPlayerId] ?: 0L) + 1L
            engine.reconnectPlayer(existingPlayerId)
            playerId = existingPlayerId
            token = suppliedToken
        } else {
            check(engine.currentPhase() == GamePhase.LOBBY) { "بدأت الجولة؛ لا يمكن إضافة لاعب جديد" }
            playerId = UUID.randomUUID().toString()
            engine.addPlayer(playerId, name)
            token = newReconnectToken()
            tokenToPlayer[token] = playerId
        }

        peerToPlayer[peerId] = playerId
        playerToPeer[playerId] = peerId
        transport.sendTo(peerId, "WELCOME", JSONObject()
            .put("playerId", playerId)
            .put("reconnectToken", token)
            .put("snapshot", snapshotToJson(engine.publicSnapshot())))

        engine.privateRoleInfo(playerId)?.let { sendPrivateRole(playerId, it) }
        replayPendingDetectiveResults(playerId)
        broadcastSnapshot()
        listener.onNotice("${engine.getPlayer(playerId)?.name ?: name} انضم إلى اللعبة")
    }

    private fun authenticatedPlayer(peerId: String): String =
        peerToPlayer[peerId] ?: throw IllegalStateException("يجب الانضمام إلى الجلسة أولاً")

    private fun newReconnectToken(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun publish(events: List<GameEvent>) {
        events.forEach { event ->
            when (event) {
                is GameEvent.PrivateRoleAssigned -> sendPrivateRole(event.playerId, event.info)
                is GameEvent.PrivateDetectiveResult -> {
                    if (event.playerId == hostPlayerId) {
                        listener.onDetectiveResult(event.targetId, event.isMafia)
                    } else {
                        val messageId = UUID.randomUUID().toString()
                        val payload = JSONObject()
                            .put("targetId", event.targetId)
                            .put("isMafia", event.isMafia)
                            .put("privateMessageId", messageId)
                        pendingDetectiveResults.getOrPut(event.playerId) { linkedMapOf() }[messageId] = JSONObject(payload.toString())
                        sendPrivate(event.playerId, "DETECTIVE_RESULT", payload)
                    }
                }
                is GameEvent.Error -> listener.onError(event.message)
                else -> transport.broadcast("GAME_EVENT", eventToJson(event), reliable = true)
            }
        }
        broadcastSnapshot()
    }

    private fun sendPrivateRole(playerId: String, info: PrivateRoleInfo) {
        if (playerId == hostPlayerId) {
            listener.onPrivateRole(info)
            return
        }
        val team = JSONArray()
        info.mafiaTeamIds.forEach(team::put)
        sendPrivate(playerId, "PRIVATE_ROLE", JSONObject()
            .put("role", info.role.name)
            .put("mafiaTeamIds", team))
    }

    private fun replayPendingDetectiveResults(playerId: String) {
        val peerId = playerToPeer[playerId] ?: return
        pendingDetectiveResults[playerId]?.values?.forEach { payload ->
            runCatching { transport.sendTo(peerId, "DETECTIVE_RESULT", JSONObject(payload.toString()), reliable = true) }
                .onFailure { listener.onError("تعذر استعادة نتيجة تحقيق خاصة") }
        }
    }

    private fun sendPrivate(playerId: String, type: String, payload: JSONObject) {
        val peerId = playerToPeer[playerId] ?: return
        runCatching { transport.sendTo(peerId, type, payload, reliable = true) }
            .onFailure { listener.onError("تعذر إرسال معلومة خاصة إلى لاعب") }
    }

    private fun sendError(peerId: String, message: String) {
        runCatching { transport.sendTo(peerId, "ERROR", JSONObject().put("message", message)) }
        listener.onError(message)
    }

    private fun broadcastSnapshot() {
        val snapshot = engine.publicSnapshot()
        listener.onSnapshot(snapshot)
        transport.broadcast("PUBLIC_SNAPSHOT", snapshotToJson(snapshot), reliable = true)
    }

    private fun snapshotToJson(snapshot: PublicGameSnapshot): JSONObject {
        val players = JSONArray()
        snapshot.players.forEach { player ->
            players.put(JSONObject()
                .put("id", player.id)
                .put("name", player.name)
                .put("connected", player.connected)
                .put("alive", player.alive))
        }
        return JSONObject()
            .put("phase", snapshot.phase.name)
            .put("players", players)
            .put("winner", snapshot.winner?.name ?: JSONObject.NULL)
    }

    private fun eventToJson(event: GameEvent): JSONObject = when (event) {
        is GameEvent.PhaseChanged -> JSONObject().put("event", "PHASE_CHANGED").put("phase", event.phase.name)
        is GameEvent.PlayerJoined -> JSONObject().put("event", "PLAYER_JOINED").put("playerId", event.player.id)
        is GameEvent.PlayerLeft -> JSONObject().put("event", "PLAYER_LEFT").put("playerId", event.playerId)
        is GameEvent.PlayerConnectionChanged -> JSONObject()
            .put("event", "PLAYER_CONNECTION")
            .put("playerId", event.playerId)
            .put("connected", event.connected)
        is GameEvent.MeetingStarted -> JSONObject().put("event", "MEETING_STARTED").put("reporterId", event.reporterId)
        is GameEvent.PlayerEliminated -> JSONObject()
            .put("event", "PLAYER_ELIMINATED")
            .put("playerId", event.playerId)
            .put("cause", event.cause)
        is GameEvent.NightResolved -> {
            val ids = JSONArray()
            event.eliminatedPlayerIds.forEach { ids.put(it) }
            JSONObject().put("event", "NIGHT_RESOLVED").put("eliminatedPlayerIds", ids)
        }
        is GameEvent.VotingResolved -> JSONObject()
            .put("event", "VOTING_RESOLVED")
            .put("eliminatedPlayerId", event.eliminatedPlayerId ?: JSONObject.NULL)
        is GameEvent.GameFinished -> JSONObject().put("event", "GAME_FINISHED").put("winner", event.winner.name)
        is GameEvent.PrivateRoleAssigned, is GameEvent.PrivateDetectiveResult, is GameEvent.Error -> JSONObject().put("event", "NO_PUBLIC_DATA")
    }
}

interface ClientGameSessionListener {
    fun onConnected(playerId: String, reconnectToken: String)
    fun onSnapshot(snapshot: JSONObject)
    fun onPrivateRole(role: Role, mafiaTeamIds: List<String>)
    fun onDetectiveResult(targetId: String, isMafia: Boolean)
    fun onEvent(event: JSONObject)
    fun onDisconnected()
    fun onError(message: String)
}

/** Client facade. It receives role and investigation details only via private messages. */
class BluetoothGameClient(
    context: Context,
    private val listener: ClientGameSessionListener
) : BluetoothTransportListener {
    private val transport = BluetoothClassicTransport(context, this)
    private var playerName: String = "Player"
    private var reconnectToken: String = ""
    private var playerId: String = ""

    fun connect(device: BluetoothDevice, name: String, token: String = "") {
        playerName = name.trim().ifBlank { "Player" }
        reconnectToken = token
        transport.connect(device)
    }

    fun currentPlayerId(): String = playerId
    fun currentReconnectToken(): String = reconnectToken

    fun reportBody() = send("REPORT_BODY", JSONObject())
    fun castVote(targetId: String?) = send("CAST_VOTE", JSONObject().put("targetId", targetId ?: ""))
    fun nightAction(targetId: String) = send("NIGHT_ACTION", JSONObject().put("targetId", targetId))
    fun movement(x: Float, y: Float) {
        transport.sendMovement(BluetoothClassicTransport.SERVER_PEER_ID, x, y)
    }

    fun disconnect() = transport.stop()

    override fun onPeerConnected(peerId: String, deviceName: String) {
        runCatching {
            transport.sendTo(peerId, "JOIN", JSONObject()
                .put("name", playerName)
                .put("reconnectToken", reconnectToken), reliable = true)
        }.onFailure { listener.onError("تعذر إرسال طلب الانضمام: ${it.message}") }
    }

    override fun onPeerDisconnected(peerId: String) = listener.onDisconnected()

    override fun onMessage(peerId: String, type: String, payload: JSONObject) {
        when (type) {
            "WELCOME" -> {
                playerId = payload.optString("playerId", "")
                reconnectToken = payload.optString("reconnectToken", reconnectToken)
                payload.optJSONObject("snapshot")?.let(listener::onSnapshot)
                listener.onConnected(playerId, reconnectToken)
            }
            "PUBLIC_SNAPSHOT" -> listener.onSnapshot(payload)
            "PRIVATE_ROLE" -> {
                val ids = payload.optJSONArray("mafiaTeamIds")
                val mafiaIds = buildList {
                    if (ids != null) for (i in 0 until ids.length()) add(ids.optString(i))
                }
                runCatching { Role.valueOf(payload.getString("role")) }
                    .onSuccess { listener.onPrivateRole(it, mafiaIds) }
                    .onFailure { listener.onError("وصل دور غير معروف") }
            }
            "DETECTIVE_RESULT" -> {
                listener.onDetectiveResult(payload.optString("targetId"), payload.optBoolean("isMafia"))
                val messageId = payload.optString("privateMessageId")
                if (messageId.isNotBlank()) send("PRIVATE_ACK", JSONObject().put("privateMessageId", messageId))
            }
            "GAME_EVENT" -> listener.onEvent(payload)
            "ERROR" -> listener.onError(payload.optString("message", "خطأ من المضيف"))
            "PLAYER_POSITION" -> listener.onEvent(JSONObject().put("event", "PLAYER_POSITION").put("data", payload))
        }
    }

    override fun onTransportError(message: String, cause: Throwable?) {
        listener.onError(message + (cause?.message?.let { ": $it" } ?: ""))
    }

    private fun send(type: String, payload: JSONObject) {
        runCatching { transport.sendTo(BluetoothClassicTransport.SERVER_PEER_ID, type, payload, reliable = true) }
            .onFailure { listener.onError("تعذر إرسال الطلب: ${it.message}") }
    }
}

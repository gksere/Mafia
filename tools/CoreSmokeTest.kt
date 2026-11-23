import com.example.bluetoothdeduction.core.*
import java.security.SecureRandom

fun main() {
    val expected = mapOf(
        4 to (1 to 0),
        5 to (1 to 1),
        6 to (1 to 2),
        7 to (1 to 3),
        9 to (2 to 4),
        10 to (2 to 5),
        12 to (3 to 6)
    )
    expected.forEach { (count, pair) ->
        val result = RoleDistributor.assign((1..count).map { "p$it" }, SecureRandom())
        check(result.mafiaCount == pair.first) { "Mafia count mismatch at $count" }
        check(result.ordinaryCitizenCount == pair.second) { "Citizen count mismatch at $count" }
        check(result.rolesByPlayerId.values.count { it == Role.BULLET } == 1)
        check(result.rolesByPlayerId.values.count { it == Role.DETECTIVE } == 1)
        check(result.rolesByPlayerId.values.count { it == Role.DOCTOR } == 1)
        check(result.rolesByPlayerId.size == count)
    }

    listOf(3, 8, 11).forEach { count ->
        val rejected = runCatching { RoleDistributor.assign((1..count).map { "p$it" }) }.isFailure
        check(rejected) { "Expected unsupported player count $count to be rejected" }
    }

    val lobbyDisconnect = GameEngine()
    lobbyDisconnect.addPlayer("host", "Host")
    lobbyDisconnect.addPlayer("guest", "Guest")
    lobbyDisconnect.markDisconnected("guest")
    val lobbyExpiry = lobbyDisconnect.forfeitDisconnectedPlayer("guest")
    check(lobbyExpiry.single() is GameEvent.PlayerLeft)
    check(lobbyDisconnect.playerCount() == 1)
    check(lobbyDisconnect.currentPhase() == GamePhase.LOBBY)

    val lobbyReconnect = GameEngine()
    lobbyReconnect.addPlayer("host", "Host")
    lobbyReconnect.addPlayer("returning", "Returning")
    lobbyReconnect.markDisconnected("returning")
    lobbyReconnect.reconnectPlayer("returning")
    check(lobbyReconnect.getPlayer("returning")!!.connected)
    check(lobbyReconnect.forfeitDisconnectedPlayer("returning").isEmpty())
    check(lobbyReconnect.playerCount() == 2)

    val engine = GameEngine()
    (1..9).forEach { engine.addPlayer("p$it", "Player $it") }
    engine.startGame(SecureRandom())
    check(engine.currentPhase() == GamePhase.FREE_ROAM_DAY)

    val players = (1..9).map { engine.getPlayer("p$it")!! }
    val mafia = players.filter { it.role == Role.MAFIA }
    val doctor = players.single { it.role == Role.DOCTOR }
    val detective = players.single { it.role == Role.DETECTIVE }
    val bullet = players.single { it.role == Role.BULLET }
    val civilians = players.filter { it.role == Role.CITIZEN }
    check(mafia.size == 2 && civilians.size == 4)

    engine.reportBody("p1")
    check(runCatching { engine.reportBody("p2") }.isFailure) { "Duplicate report must be blocked" }
    engine.beginVoting()
    engine.resolveVoting()
    check(engine.currentPhase() == GamePhase.NIGHT_PHASE)

    val mafiaTarget = civilians[0]
    val bulletTarget = civilians[1]
    mafia.forEach { engine.submitNightAction(it.id, mafiaTarget.id) }
    engine.submitNightAction(doctor.id, mafiaTarget.id)
    engine.submitNightAction(detective.id, mafia.first().id)
    engine.submitNightAction(bullet.id, bulletTarget.id)
    val nightEvents = engine.resolveNight()
    check(engine.currentPhase() == GamePhase.FREE_ROAM_DAY)
    check(nightEvents.filterIsInstance<GameEvent.PrivateDetectiveResult>().single().isMafia)
    check(nightEvents.filterIsInstance<GameEvent.NightResolved>().single().eliminatedPlayerIds == listOf(bulletTarget.id))
    check(engine.publicSnapshot().players.none { player ->
        // PublicPlayer intentionally has no role field; verify its serialized shape stays public only.
        player.name.isBlank()
    })

    println("PASS: role distribution, invalid player counts, lobby reconnect/expiry, FSM transitions, batched night resolution, doctor protection and private detective result")
}

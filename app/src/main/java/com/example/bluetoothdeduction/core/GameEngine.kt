package com.example.bluetoothdeduction.core

/** Commands are checked by the active State before any mutation is applied. */
private enum class GameCommand {
    ADD_PLAYER, START_GAME, REPORT, BEGIN_VOTING, CAST_VOTE, RESOLVE_VOTING,
    NIGHT_ACTION, RESOLVE_NIGHT
}

private sealed interface PhaseState {
    val phase: GamePhase
    fun allows(command: GameCommand): Boolean
}

private data object LobbyState : PhaseState {
    override val phase = GamePhase.LOBBY
    override fun allows(command: GameCommand) = command == GameCommand.ADD_PLAYER || command == GameCommand.START_GAME
}

private data object RoleAssignmentState : PhaseState {
    override val phase = GamePhase.ROLE_ASSIGNMENT
    override fun allows(command: GameCommand) = false
}

private data object FreeRoamDayState : PhaseState {
    override val phase = GamePhase.FREE_ROAM_DAY
    override fun allows(command: GameCommand) = command == GameCommand.REPORT
}

private data object NightPhaseState : PhaseState {
    override val phase = GamePhase.NIGHT_PHASE
    override fun allows(command: GameCommand) = command == GameCommand.NIGHT_ACTION || command == GameCommand.RESOLVE_NIGHT
}

private data object MeetingDiscussionState : PhaseState {
    override val phase = GamePhase.MEETING_DISCUSSION
    override fun allows(command: GameCommand) = command == GameCommand.BEGIN_VOTING
}

private data object VotingState : PhaseState {
    override val phase = GamePhase.VOTING
    override fun allows(command: GameCommand) = command == GameCommand.CAST_VOTE || command == GameCommand.RESOLVE_VOTING
}

private data object ResolutionWinCheckState : PhaseState {
    override val phase = GamePhase.RESOLUTION_WIN_CHECK
    override fun allows(command: GameCommand) = false
}

private data object FinishedState : PhaseState {
    override val phase = GamePhase.FINISHED
    override fun allows(command: GameCommand) = false
}

/**
 * Server-authoritative game rules. Public snapshots never contain roles.
 * All public methods are synchronized because Bluetooth reader threads may
 * submit commands concurrently.
 */
class GameEngine {
    private val players = linkedMapOf<String, Player>()
    private var state: PhaseState = LobbyState
    private val nightActions = linkedMapOf<String, NightAction>()
    private val votes = linkedMapOf<String, String?>()
    private var bulletUsed = false
    private var winner: Winner? = null
    private var meetingReported = false

    @Synchronized
    fun addPlayer(id: String, name: String): List<GameEvent> {
        requireAllowed(GameCommand.ADD_PLAYER)
        require(id.isNotBlank()) { "معرّف اللاعب مطلوب" }
        require(name.isNotBlank()) { "اسم اللاعب مطلوب" }
        require(id !in players) { "اللاعب موجود مسبقاً" }
        val player = Player(id = id, name = name.trim())
        players[id] = player
        return listOf(GameEvent.PlayerJoined(player.toPublic()))
    }

    @Synchronized
    fun startGame(random: java.security.SecureRandom = java.security.SecureRandom()): List<GameEvent> {
        requireAllowed(GameCommand.START_GAME)
        val distribution = RoleDistributor.assign(players.keys.toList(), random)
        val events = mutableListOf<GameEvent>()
        moveTo(RoleAssignmentState, events)

        distribution.rolesByPlayerId.forEach { (id, role) ->
            players[id] = players.getValue(id).copy(role = role)
        }

        val mafiaIds = players.values.filter { it.role == Role.MAFIA }.map { it.id }
        players.values.forEach { player ->
            val role = requireNotNull(player.role)
            val privateInfo = PrivateRoleInfo(
                role = role,
                mafiaTeamIds = if (role == Role.MAFIA) mafiaIds else emptyList()
            )
            events += GameEvent.PrivateRoleAssigned(player.id, privateInfo)
        }

        moveTo(FreeRoamDayState, events)
        return events
    }

    /** A body report is accepted only in FreeRoam; the first report wins atomically. */
    @Synchronized
    fun reportBody(reporterId: String): List<GameEvent> {
        requireAllowed(GameCommand.REPORT)
        val reporter = requireLivingConnected(reporterId)
        check(!meetingReported) { "تم الإبلاغ عن الجثة بالفعل" }
        meetingReported = true
        val events = mutableListOf<GameEvent>()
        moveTo(MeetingDiscussionState, events)
        events += GameEvent.MeetingStarted(reporter.id)
        return events
    }

    @Synchronized
    fun beginVoting(): List<GameEvent> {
        requireAllowed(GameCommand.BEGIN_VOTING)
        votes.clear()
        val events = mutableListOf<GameEvent>()
        moveTo(VotingState, events)
        return events
    }

    /** targetId=null means abstain. A player can cast only one vote per meeting. */
    @Synchronized
    fun castVote(voterId: String, targetId: String?): List<GameEvent> {
        requireAllowed(GameCommand.CAST_VOTE)
        requireLivingConnected(voterId)
        check(voterId !in votes) { "تم تسجيل تصويت هذا اللاعب مسبقاً" }
        if (targetId != null) {
            val target = requireLiving(targetId)
            require(target.id != voterId) { "لا يمكن التصويت للنفس" }
        }
        votes[voterId] = targetId
        return emptyList()
    }

    /** Resolves votes as one batch; ties or a non-majority result cause no elimination. */
    @Synchronized
    fun resolveVoting(): List<GameEvent> {
        requireAllowed(GameCommand.RESOLVE_VOTING)
        val events = mutableListOf<GameEvent>()
        moveTo(ResolutionWinCheckState, events)

        val validVotes = votes.values.filterNotNull()
        val counts = validVotes.groupingBy { it }.eachCount()
        val maximum = counts.values.maxOrNull() ?: 0
        val leaders = counts.filterValues { it == maximum }.keys
        val eliminatedId = if (leaders.size == 1 && maximum > validVotes.size / 2) leaders.first() else null

        if (eliminatedId != null) {
            eliminate(eliminatedId, "التصويت", events)
        }
        events += GameEvent.VotingResolved(eliminatedId)
        votes.clear()
        checkWinner(events)
        if (winner == null) {
            nightActions.clear()
            moveTo(NightPhaseState, events)
        }
        return events
    }

    /**
     * One accepted action per player per night. Mafia vote for a non-mafia target;
     * Doctor protects; Detective inspects; Bullet may shoot once in the whole game.
     */
    @Synchronized
    fun submitNightAction(actorId: String, targetId: String): List<GameEvent> {
        requireAllowed(GameCommand.NIGHT_ACTION)
        val actor = requireLivingConnected(actorId)
        val target = requireLiving(targetId)
        check(actor.id !in nightActions) { "تم تسجيل إجراء هذا اللاعب لهذه الليلة مسبقاً" }

        val action = when (actor.role) {
            Role.MAFIA -> {
                require(target.role != Role.MAFIA) { "لا يمكن للمافيا استهداف أحد أفراد فريقها" }
                NightAction.MafiaVote(actor.id, target.id)
            }
            Role.DOCTOR -> NightAction.DoctorProtect(actor.id, target.id)
            Role.DETECTIVE -> NightAction.DetectiveInspect(actor.id, target.id)
            Role.BULLET -> {
                check(!bulletUsed) { "تم استخدام الطلقة الوحيدة بالفعل" }
                require(target.id != actor.id) { "لا يمكن استهداف النفس" }
                bulletUsed = true
                NightAction.BulletShoot(actor.id, target.id)
            }
            Role.CITIZEN, null -> throw IllegalStateException("لا يملك المواطن إجراءً ليلياً")
        }
        nightActions[actor.id] = action
        return emptyList()
    }

    /**
     * Applies all night choices together, preventing kill/report race conditions.
     * Doctor protection blocks both Mafia and Bullet attacks for this prototype.
     */
    @Synchronized
    fun resolveNight(): List<GameEvent> {
        requireAllowed(GameCommand.RESOLVE_NIGHT)
        val events = mutableListOf<GameEvent>()
        moveTo(ResolutionWinCheckState, events)

        val mafiaTarget = resolveMafiaTarget()
        val protectedTarget = nightActions.values
            .filterIsInstance<NightAction.DoctorProtect>()
            .singleOrNull()?.targetId

        // Inspector output is private and is never included in a public snapshot.
        nightActions.values.filterIsInstance<NightAction.DetectiveInspect>().forEach { action ->
            val target = players[action.targetId]
            events += GameEvent.PrivateDetectiveResult(
                playerId = action.actorId,
                targetId = action.targetId,
                isMafia = target?.role == Role.MAFIA
            )
        }

        val attacks = linkedSetOf<String>()
        mafiaTarget?.let(attacks::add)
        nightActions.values.filterIsInstance<NightAction.BulletShoot>()
            .singleOrNull()?.targetId?.let(attacks::add)

        val eliminated = attacks.filter { it != protectedTarget && players[it]?.alive == true }
        eliminated.forEach { eliminate(it, "مرحلة الليل", events) }
        events += GameEvent.NightResolved(eliminated)

        nightActions.clear()
        checkWinner(events)
        if (winner == null) {
            meetingReported = false
            moveTo(FreeRoamDayState, events)
        }
        return events
    }

    /** Mark a temporary disconnect; the host starts the grace-window timer. */
    @Synchronized
    fun markDisconnected(playerId: String): List<GameEvent> {
        val player = players[playerId] ?: return listOf(GameEvent.Error("لاعب غير معروف"))
        if (!player.connected) return emptyList()
        players[playerId] = player.copy(connected = false)
        return listOf(GameEvent.PlayerConnectionChanged(playerId, false))
    }

    /** Call only after the reconnect grace period expires and only if still offline. */
    @Synchronized
    fun forfeitDisconnectedPlayer(playerId: String): List<GameEvent> {
        val player = players[playerId] ?: return emptyList()
        if (player.connected || !player.alive || state == FinishedState) return emptyList()
        if (state == LobbyState) {
            players.remove(playerId)
            return listOf(GameEvent.PlayerLeft(playerId))
        }
        val events = mutableListOf<GameEvent>()
        eliminate(playerId, "انتهاء مهلة إعادة الاتصال", events)
        checkWinner(events)
        return events
    }

    @Synchronized
    fun reconnectPlayer(playerId: String): List<GameEvent> {
        val player = players[playerId] ?: return listOf(GameEvent.Error("لاعب غير معروف"))
        if (player.connected) return emptyList()
        players[playerId] = player.copy(connected = true)
        return listOf(GameEvent.PlayerConnectionChanged(playerId, true))
    }

    @Synchronized
    fun privateRoleInfo(playerId: String): PrivateRoleInfo? {
        val player = players[playerId] ?: return null
        val role = player.role ?: return null
        val mafiaIds = if (role == Role.MAFIA) {
            players.values.filter { it.role == Role.MAFIA }.map { it.id }
        } else emptyList()
        return PrivateRoleInfo(role, mafiaIds)
    }

    @Synchronized
    fun publicSnapshot(): PublicGameSnapshot = PublicGameSnapshot(
        phase = state.phase,
        players = players.values.map { it.toPublic() },
        winner = winner
    )

    @Synchronized
    fun currentPhase(): GamePhase = state.phase

    @Synchronized
    fun playerCount(): Int = players.size

    @Synchronized
    fun getPlayer(playerId: String): Player? = players[playerId]

    private fun resolveMafiaTarget(): String? {
        val mafiaVotes = nightActions.values.filterIsInstance<NightAction.MafiaVote>()
        if (mafiaVotes.isEmpty()) return null
        val counts = mafiaVotes.groupingBy { it.targetId }.eachCount()
        val max = counts.values.maxOrNull() ?: return null
        val winners = counts.filterValues { it == max }.keys
        return winners.singleOrNull()
    }

    private fun eliminate(playerId: String, cause: String, events: MutableList<GameEvent>) {
        val player = players[playerId] ?: return
        if (!player.alive) return
        players[playerId] = player.copy(alive = false)
        events += GameEvent.PlayerEliminated(playerId, cause)
    }

    private fun checkWinner(events: MutableList<GameEvent>) {
        if (winner != null) return
        val living = players.values.filter { it.alive }
        val mafia = living.count { it.role == Role.MAFIA }
        val citizens = living.size - mafia
        val result = when {
            mafia == 0 -> Winner.CITIZENS
            mafia >= citizens -> Winner.MAFIA
            else -> null
        }
        if (result != null) {
            winner = result
            moveTo(FinishedState, events)
            events += GameEvent.GameFinished(result)
        }
    }

    private fun requireLiving(playerId: String): Player {
        val player = players[playerId] ?: throw IllegalArgumentException("لاعب غير معروف")
        check(player.alive) { "اللاعب مستبعد من الجولة" }
        return player
    }

    private fun requireLivingConnected(playerId: String): Player {
        val player = requireLiving(playerId)
        check(player.connected) { "اللاعب غير متصل" }
        return player
    }

    private fun requireAllowed(command: GameCommand) {
        check(state.allows(command)) {
            "الأمر $command غير مسموح في الحالة ${state.phase}"
        }
    }

    private fun moveTo(next: PhaseState, events: MutableList<GameEvent>) {
        state = next
        events += GameEvent.PhaseChanged(next.phase)
    }

    private fun Player.toPublic() = PublicPlayer(id, name, connected, alive)
}

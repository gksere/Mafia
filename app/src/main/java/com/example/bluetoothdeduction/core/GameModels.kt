package com.example.bluetoothdeduction.core

enum class Role {
    MAFIA,
    CITIZEN,
    BULLET,
    DETECTIVE,
    DOCTOR
}

enum class GamePhase {
    LOBBY,
    ROLE_ASSIGNMENT,
    FREE_ROAM_DAY,
    NIGHT_PHASE,
    MEETING_DISCUSSION,
    VOTING,
    RESOLUTION_WIN_CHECK,
    FINISHED
}

enum class Winner {
    MAFIA,
    CITIZENS
}

data class Player(
    val id: String,
    val name: String,
    val connected: Boolean = true,
    val alive: Boolean = true,
    val role: Role? = null
)

data class RoleDistribution(
    val mafiaCount: Int,
    val ordinaryCitizenCount: Int,
    val rolesByPlayerId: Map<String, Role>
)

data class PrivateRoleInfo(
    val role: Role,
    val mafiaTeamIds: List<String>
)

data class PublicPlayer(
    val id: String,
    val name: String,
    val connected: Boolean,
    val alive: Boolean
)

data class PublicGameSnapshot(
    val phase: GamePhase,
    val players: List<PublicPlayer>,
    val winner: Winner? = null
)

sealed interface GameEvent {
    data class PhaseChanged(val phase: GamePhase) : GameEvent
    data class PlayerJoined(val player: PublicPlayer) : GameEvent
    data class PlayerLeft(val playerId: String) : GameEvent
    data class PlayerConnectionChanged(val playerId: String, val connected: Boolean) : GameEvent
    data class PrivateRoleAssigned(val playerId: String, val info: PrivateRoleInfo) : GameEvent
    data class MeetingStarted(val reporterId: String) : GameEvent
    data class PlayerEliminated(val playerId: String, val cause: String) : GameEvent
    data class PrivateDetectiveResult(val playerId: String, val targetId: String, val isMafia: Boolean) : GameEvent
    data class NightResolved(val eliminatedPlayerIds: List<String>) : GameEvent
    data class VotingResolved(val eliminatedPlayerId: String?) : GameEvent
    data class GameFinished(val winner: Winner) : GameEvent
    data class Error(val message: String) : GameEvent
}

sealed interface NightAction {
    val actorId: String
    val targetId: String

    data class MafiaVote(override val actorId: String, override val targetId: String) : NightAction
    data class DoctorProtect(override val actorId: String, override val targetId: String) : NightAction
    data class DetectiveInspect(override val actorId: String, override val targetId: String) : NightAction
    data class BulletShoot(override val actorId: String, override val targetId: String) : NightAction
}

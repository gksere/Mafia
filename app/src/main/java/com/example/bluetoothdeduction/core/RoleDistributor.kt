package com.example.bluetoothdeduction.core

import java.security.SecureRandom
import java.util.Collections

/**
 * Implements the requested rule literally, where "citizens" means players with
 * the ordinary CITIZEN role (the three special roles are counted separately).
 * Some lobby sizes have no mathematically consistent composition; those sizes
 * are rejected instead of silently violating the rule.
 */
object RoleDistributor {
    private const val SPECIAL_ROLE_COUNT = 3

    fun mafiaCountForOrdinaryCitizens(citizens: Int): Int = when {
        citizens < 0 -> throw IllegalArgumentException("عدد المواطنين لا يمكن أن يكون سالباً")
        citizens <= 3 -> 1
        citizens <= 5 -> 2
        else -> 3
    }

    fun assign(playerIds: List<String>, secureRandom: SecureRandom = SecureRandom()): RoleDistribution {
        require(playerIds.distinct().size == playerIds.size) { "معرّفات اللاعبين يجب أن تكون فريدة" }
        val total = playerIds.size
        require(total >= SPECIAL_ROLE_COUNT + 1) {
            "يلزم 4 لاعبين على الأقل: مافيا واحدة وثلاثة أدوار خاصة"
        }

        // Solve N = mafia + 3 special roles + ordinary citizens, while
        // satisfying the mafia threshold based on ordinary citizens.
        val validMafiaCount = (1..3).firstOrNull { mafia ->
            val citizens = total - mafia - SPECIAL_ROLE_COUNT
            citizens >= 0 && mafiaCountForOrdinaryCitizens(citizens) == mafia
        } ?: throw IllegalArgumentException(
            "لا يوجد توزيع يطابق القاعدة عند $total لاعبين. " +
                "الأعداد المتوافقة هي 4–7، و9–10، و12 لاعباً فأكثر."
        )

        val ordinaryCitizens = total - validMafiaCount - SPECIAL_ROLE_COUNT
        val roles = buildList {
            repeat(validMafiaCount) { add(Role.MAFIA) }
            add(Role.BULLET)
            add(Role.DETECTIVE)
            add(Role.DOCTOR)
            repeat(ordinaryCitizens) { add(Role.CITIZEN) }
        }.toMutableList()

        Collections.shuffle(roles, secureRandom)
        return RoleDistribution(
            mafiaCount = validMafiaCount,
            ordinaryCitizenCount = ordinaryCitizens,
            rolesByPlayerId = playerIds.zip(roles).toMap()
        )
    }
}

package com.conquerer.server.domain.building.command;

/**
 * Master → BuildingActor: start an upgrade for the given building.
 *
 * masterPlayerId (String) replaces ActorRef<KingdomCommand> replyToMaster.
 * Using a String ensures:
 *   - Serializable across JVM restarts (crash recovery)
 *   - No dead-letter risk: BuildingActor resolves the EntityRef at send-time
 *     via sharding, which wakes the Master if it was passivated.
 */
public record StartConstructionCmd(
        String buildingId,
        String buildingType,
        int    targetLevel,
        String masterPlayerId   // playerId used to look up KingdomPlayerActor via sharding
) implements BuildingCommand {}

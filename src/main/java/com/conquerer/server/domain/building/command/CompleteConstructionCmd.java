package com.conquerer.server.domain.building;

/**
 * Internal timer command: upgrade duration elapsed, finalize the construction.
 *
 * masterPlayerId (String) replaces ActorRef<KingdomCommand> replyToMaster.
 * At completion time, BuildingActor resolves the EntityRef via sharding —
 * this survives passivation and is fully serializable for crash recovery.
 */
public record CompleteConstructionCmd(
        String buildingId,
        int    targetLevel,
        String masterPlayerId   // may be null for legacy recovery paths (safe: EntityRef is used)
) implements BuildingCommand {}

package com.conquerer.server.domain.building;

import com.conquerer.server.domain.player.KingdomCommand;
import org.apache.pekko.actor.typed.ActorRef;

/**
 * Internal command scheduled by the BuildingActor itself via a timer.
 * Signals that the construction/upgrade duration has elapsed and
 * the building should transition to COMPLETED state.
 */
public record CompleteConstructionCmd(
        String buildingId,
        int targetLevel,
        ActorRef<KingdomCommand> replyToMaster
) implements BuildingCommand {}

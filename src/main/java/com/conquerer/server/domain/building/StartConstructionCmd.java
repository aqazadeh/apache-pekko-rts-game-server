package com.conquerer.server.domain.building;

import com.conquerer.server.domain.player.KingdomCommand;

import org.apache.pekko.actor.typed.ActorRef;

public record StartConstructionCmd(
        String buildingId,
        String buildingType,
        int targetLevel,
        ActorRef<KingdomCommand> replyToMaster
) implements BuildingCommand {}


package com.conquerer.server.domain.player.command;

public record BuildingUpdateNotificationCmd(
        String buildingId,
        int newLevel,
        String status) implements KingdomCommand {
}

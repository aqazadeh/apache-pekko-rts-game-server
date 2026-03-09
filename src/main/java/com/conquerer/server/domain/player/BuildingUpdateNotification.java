package com.conquerer.server.domain.player;

public record BuildingUpdateNotification(
        String buildingId,
        int newLevel,
        String status) implements KingdomCommand {
}

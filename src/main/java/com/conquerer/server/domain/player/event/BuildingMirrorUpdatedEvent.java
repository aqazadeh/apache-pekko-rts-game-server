package com.conquerer.server.domain.player.event;

public record BuildingMirrorUpdatedEvent(
        String buildingId,
        int newLevel,
        String status) implements KingdomEvent {
}

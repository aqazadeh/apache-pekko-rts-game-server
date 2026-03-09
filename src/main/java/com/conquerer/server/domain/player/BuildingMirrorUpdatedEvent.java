package com.conquerer.server.domain.player;

public record BuildingMirrorUpdatedEvent(
        String buildingId,
        int newLevel,
        String status) implements KingdomEvent {
}

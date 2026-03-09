package com.conquerer.server.domain.building;

public record ConstructionStartedEvent(
        String buildingId,
        int targetLevel,
        long startTime) implements BuildingEvent {
}

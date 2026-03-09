package com.conquerer.server.domain.building.event;

/**
 * Persisted event when a building upgrade is fully completed.
 * Transitions the building status from CONSTRUCTING → COMPLETED.
 */
public record ConstructionCompletedEvent(
        String buildingId,
        int completedLevel,
        long completedAt
) implements BuildingEvent {}

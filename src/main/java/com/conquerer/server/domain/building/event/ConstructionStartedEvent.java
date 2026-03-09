package com.conquerer.server.domain.building.event;

public record ConstructionStartedEvent(
        String buildingId,
        String buildingType,
        int targetLevel,
        long startTime) implements BuildingEvent {}


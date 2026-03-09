package com.conquerer.server.domain.player.state;

import com.conquerer.server.domain.common.JsonSerializable;

import java.util.HashMap;
import java.util.Map;

public record PlayerProfileAggregate(
    String playerId,
    Progression progression,
    Resources resources,
    Map<String, BuildingMirror> buildings
) implements JsonSerializable {

    public PlayerProfileAggregate updateBuildingMirror(String buildingId, int newLevel, String status) {
        var updatedBuildings = new HashMap<>(buildings);
        var existing = updatedBuildings.getOrDefault(buildingId, new BuildingMirror(buildingId, "UNKNOWN", 0, "IDLE"));
        updatedBuildings.put(buildingId, new BuildingMirror(buildingId, existing.type(), newLevel, status));

        return new PlayerProfileAggregate(playerId, progression, resources, Map.copyOf(updatedBuildings));
    }
}

package com.conquerer.server.domain.player;

public record UpgradeBuildingCmd(String buildingId, String buildingType) implements KingdomCommand {
}

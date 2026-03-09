package com.conquerer.server.domain.player.command;

public record UpgradeBuildingCmd(String buildingId, String buildingType) implements KingdomCommand {
}

package com.conquerer.server.domain.player;

import com.conquerer.server.domain.common.JsonSerializable;

public record Progression(int level, long experience) implements JsonSerializable {
}

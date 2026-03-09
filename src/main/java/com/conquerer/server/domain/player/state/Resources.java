package com.conquerer.server.domain.player.state;

import com.conquerer.server.domain.common.JsonSerializable;

public record Resources(long gold, long diamonds) implements JsonSerializable {}

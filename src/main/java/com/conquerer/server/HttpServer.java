package com.conquerer.server;

import com.conquerer.server.actor.KingdomPlayerActor;
import com.conquerer.server.domain.player.command.KingdomCommand;
import com.conquerer.server.domain.player.command.UpgradeBuildingCmd;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;
import org.apache.pekko.http.javadsl.marshallers.jackson.Jackson;
import org.apache.pekko.http.javadsl.model.StatusCodes;
import org.apache.pekko.http.javadsl.server.AllDirectives;
import org.apache.pekko.http.javadsl.server.Route;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;

import static org.apache.pekko.http.javadsl.server.PathMatchers.segment;

public class HttpServer extends AllDirectives {

    private final ClusterSharding sharding;

    public HttpServer(ClusterSharding sharding) {
        this.sharding = sharding;
    }

    public Route createRoute() {
        return concat(
                pathPrefix("api", () ->
                        pathPrefix("player", () ->
                                path(segment(), (String playerId) ->
                                        post(() ->
                                                entity(Jackson.unmarshaller(UpgradeRequest.class), request -> {
                                                    // Sharding üzerinden aktöre erişim
                                                    EntityRef<KingdomCommand> playerRef =
                                                            sharding.entityRefFor(KingdomPlayerActor.ENTITY_KEY, playerId);

                                                    // Komutu gönder (Asenkron - Fire and Forget)
                                                    playerRef.tell(new UpgradeBuildingCmd(request.buildingId(), request.type()));

                                                    return complete(StatusCodes.ACCEPTED, "Command accepted for player: " + playerId);
                                                })
                                        )
                                )
                        )
                ),
                path("health", () -> get(() -> complete(StatusCodes.OK, "UP")))
        );
    }
}
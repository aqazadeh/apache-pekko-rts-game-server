package com.conquerer.server;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;

import com.conquerer.server.actor.BuildingActor;
import com.conquerer.server.actor.KingdomPlayerActor;
import com.conquerer.server.domain.player.command.KingdomCommand;
import com.conquerer.server.domain.player.command.UpgradeBuildingCmd;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.model.StatusCodes;
import org.apache.pekko.http.javadsl.server.AllDirectives;
import org.apache.pekko.http.javadsl.server.Route;

import static org.apache.pekko.http.javadsl.server.PathMatchers.segment;

public class Main {
    public static void main(String[] args) {
        Config config = ConfigFactory.load();

        ActorSystem<Void> system = ActorSystem.create(Behaviors.empty(), "ConquererSystem", config);
        ClusterSharding sharding = ClusterSharding.get(system);

        sharding.init(Entity.of(KingdomPlayerActor.ENTITY_KEY,
                ctx -> KingdomPlayerActor.create(ctx.getEntityId(), sharding)));
        sharding.init(Entity.of(BuildingActor.ENTITY_KEY,
                ctx -> BuildingActor.create(ctx.getEntityId())));

        int port = config.hasPath("http.port") ? config.getInt("http.port") : 8080;

        HttpServer server = new HttpServer(sharding);

        Http.get(system)
                .newServerAt("0.0.0.0", port)
                .bind(server.createRoute());

        System.out.println(">>> Node UP | HTTP: " + port + " | Cluster: " + config.getString("pekko.remote.artery.canonical.port"));
    }
}
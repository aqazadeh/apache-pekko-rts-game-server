package com.conquerer.server.actor;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.javadsl.*;

import com.conquerer.server.domain.player.BuildingMirror;
import com.conquerer.server.domain.player.PlayerProfileAggregate;
import com.conquerer.server.domain.player.Progression;
import com.conquerer.server.domain.player.Resources;
import com.conquerer.server.domain.player.*;
import com.conquerer.server.domain.building.*;
import com.conquerer.server.domain.building.BuildingDomainService;

import java.util.Map;

public class KingdomPlayerActor extends EventSourcedBehavior<KingdomCommand, KingdomEvent, PlayerProfileAggregate> {

        public static final EntityTypeKey<KingdomCommand> ENTITY_KEY = EntityTypeKey.create(KingdomCommand.class,
                        "KingdomPlayer");

        private final String playerId;
        private final ClusterSharding sharding;
        private final ActorContext<KingdomCommand> context;

        private KingdomPlayerActor(ActorContext<KingdomCommand> context, PersistenceId persistenceId, String playerId,
                        ClusterSharding sharding) {
                super(persistenceId);
                this.context = context;
                this.playerId = playerId;
                this.sharding = sharding;
        }

        public static Behavior<KingdomCommand> create(String playerId, ClusterSharding sharding) {
                return Behaviors.setup(
                                ctx -> new KingdomPlayerActor(ctx, PersistenceId.of(ENTITY_KEY.name(), playerId),
                                                playerId, sharding));
        }

        @Override
        public PlayerProfileAggregate emptyState() {
                return new PlayerProfileAggregate(playerId, new Progression(1, 0), new Resources(1000, 50), Map.of());
        }

        @Override
        public CommandHandler<KingdomCommand, KingdomEvent, PlayerProfileAggregate> commandHandler() {
                return newCommandHandlerBuilder()
                        .forAnyState()
                        .onCommand(UpgradeBuildingCmd.class, this::onUpgradeBuilding)
                        .onCommand(BuildingUpdateNotification.class, this::onBuildingUpdateNotification)
                        .build();
        }

        @Override
        public RetentionCriteria retentionCriteria() {
                return RetentionCriteria.snapshotEvery(5, 2);
        }

        // 1- Client's generic request comes here
        private Effect<KingdomEvent, PlayerProfileAggregate> onUpgradeBuilding(PlayerProfileAggregate state,
                        UpgradeBuildingCmd cmd) {
                if (!BuildingDomainService.canUpgrade(state, cmd.buildingId())) {
                        context.getLog().warn("Not enough resources to upgrade building: {}", cmd.buildingId());
                        return Effect().none();
                }

                BuildingMirror mirror = state.buildings().get(cmd.buildingId());
                int targetLevel = (mirror != null ? mirror.level() : 0) + 1;

                // Find the slave actor
                EntityRef<BuildingCommand> buildingSlave = sharding.entityRefFor(BuildingActor.ENTITY_KEY, playerId);

                // Tell slave, pass a reference to self for push-based response
                // NO context.ask is used. It's a completely asynchronous fire-and-forget push.
                context.getLog().info("Sending StartConstructionCmd to BuildingActor {}", cmd.buildingId());
                buildingSlave.tell(new StartConstructionCmd(cmd.buildingId(), cmd.buildingType(), targetLevel, context.getSelf()));

                return Effect().none();
        }

        // 2a- Slave persisted CONSTRUCTING → mirror reflects "in progress" immediately
        // 2b- Slave's timer elapsed, COMPLETED → mirror reflects final level and goes IDLE
        private Effect<KingdomEvent, PlayerProfileAggregate> onBuildingUpdateNotification(PlayerProfileAggregate state,
                        BuildingUpdateNotification notification) {

                String status = notification.status();

                if ("CONSTRUCTING".equals(status)) {
                        context.getLog().info(
                                        "[Master] Building {} is now CONSTRUCTING (current level={})",
                                        notification.buildingId(), notification.newLevel());
                } else if ("COMPLETED".equals(status)) {
                        context.getLog().info(
                                        "[Master] Building {} upgrade DONE → new level={}. Resetting to IDLE.",
                                        notification.buildingId(), notification.newLevel());
                        // Once completed, normalize status to IDLE so the building
                        // is ready for the next upgrade cycle.
                        status = "IDLE";
                }

                BuildingMirrorUpdatedEvent event = new BuildingMirrorUpdatedEvent(
                                notification.buildingId(),
                                notification.newLevel(),
                                status);
                return Effect().persist(event);
        }

        @Override
        public EventHandler<PlayerProfileAggregate, KingdomEvent> eventHandler() {
                return newEventHandlerBuilder()
                                .forAnyState()
                                .onEvent(BuildingMirrorUpdatedEvent.class,
                                                (state, event) -> state.updateBuildingMirror(event.buildingId(),
                                                                event.newLevel(),
                                                                event.status()))
                                .build();
        }
}

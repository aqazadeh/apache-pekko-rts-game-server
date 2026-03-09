package com.conquerer.server.actor;

import com.conquerer.server.domain.building.command.BuildingCommand;
import com.conquerer.server.domain.building.command.StartConstructionCmd;
import com.conquerer.server.domain.player.command.BuildingUpdateNotificationCmd;
import com.conquerer.server.domain.player.command.KingdomCommand;
import com.conquerer.server.domain.player.command.UpgradeBuildingCmd;
import com.conquerer.server.domain.player.event.BuildingMirrorUpdatedEvent;
import com.conquerer.server.domain.player.event.KingdomEvent;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.javadsl.*;

import com.conquerer.server.domain.player.state.BuildingMirror;
import com.conquerer.server.domain.player.state.PlayerProfileAggregate;
import com.conquerer.server.domain.player.state.Progression;
import com.conquerer.server.domain.player.state.Resources;
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
//        static MessageExtractor<ShardEnvelope, Object> messageExtractor(int numberOfShards) {
//                return new HashCodeMessageExtractor<ShardEnvelope, Object>(numberOfShards) {
//
//                        @Override
//                        public String entityId(ShardEnvelope message) {
//                                // Aktörün kendisi hala PlayerId ile tekil olmalı
//                                return message.getPlayerId();
//                        }
//
//                        @Override
//                        public String shardId(ShardEnvelope message) {
//                                // KRİTİK NOKTA: Aynı krallıktaki herkes aynı shardId'yi alır
//                                // Böylece Pekko bu shard'ı (ve içindeki tüm oyuncuları) aynı Node'a koyar
//                                return String.valueOf(Math.abs(message.getKingdomId().hashCode() % numberOfShards));
//                        }
//
//                        @Override
//                        public Object entityMessage(ShardEnvelope message) {
//                                return message.getPayload();
//                        }
//                };
//        }

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
                        .onCommand(BuildingUpdateNotificationCmd.class, this::onBuildingUpdateNotification)
                        .build();
        }

        @Override
        public RetentionCriteria retentionCriteria() {
                return RetentionCriteria.snapshotEvery(50, 2);
        }

        private Effect<KingdomEvent, PlayerProfileAggregate> onUpgradeBuilding(PlayerProfileAggregate state,
                        UpgradeBuildingCmd cmd) {
                if (!BuildingDomainService.canUpgrade(state, cmd.buildingId())) {
                        context.getLog().warn("Not enough resources to upgrade building: {}", cmd.buildingId());
                        return Effect().none();
                }

                BuildingMirror mirror = state.buildings().get(cmd.buildingId());
                int targetLevel = (mirror != null ? mirror.level() : 0) + 1;

                EntityRef<BuildingCommand> buildingSlave = sharding.entityRefFor(BuildingActor.ENTITY_KEY, "Buildings-" + playerId);

                context.getLog().info("Sending StartConstructionCmd to BuildingActor {}", cmd.buildingId());
                buildingSlave.tell(new StartConstructionCmd(cmd.buildingId(), cmd.buildingType(), targetLevel, playerId));

                return Effect().none();
        }

        private Effect<KingdomEvent, PlayerProfileAggregate> onBuildingUpdateNotification(PlayerProfileAggregate state,
                        BuildingUpdateNotificationCmd notification) {

                String status = notification.status();

                if ("CONSTRUCTING".equals(status)) {
                        context.getLog().info(
                                        "[Master] Building {} is now CONSTRUCTING (current level={})",
                                        notification.buildingId(), notification.newLevel());
                } else if ("COMPLETED".equals(status)) {
                        context.getLog().info(
                                        "[Master] Building {} upgrade DONE → new level={}. Resetting to IDLE.",
                                        notification.buildingId(), notification.newLevel());
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

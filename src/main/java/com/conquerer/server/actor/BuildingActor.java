package com.conquerer.server.actor;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.RecoveryCompleted;
import org.apache.pekko.persistence.typed.javadsl.*;

import com.conquerer.server.domain.building.*;
import com.conquerer.server.domain.player.*;

import java.time.Duration;

public class BuildingActor extends EventSourcedBehavior<BuildingCommand, BuildingEvent, BuildingsState> {

    public static final EntityTypeKey<BuildingCommand> ENTITY_KEY =
            EntityTypeKey.create(BuildingCommand.class, "BuildingActor");

    private final String playerId; // Artık entityId aslında PlayerId'dir.
    private final ActorContext<BuildingCommand> context;

    private BuildingActor(ActorContext<BuildingCommand> context, PersistenceId persistenceId, String playerId) {
        super(persistenceId);
        this.context = context;
        this.playerId = playerId;
    }

    public static Behavior<BuildingCommand> create(String playerId) {
        // ID artık "Buildings-player-1" formatında gelecek
        return Behaviors.setup(ctx -> new BuildingActor(ctx, PersistenceId.of(ENTITY_KEY.name(), playerId), playerId));
    }

    @Override
    public BuildingsState emptyState() {
        // Tekil bir bina değil, binaların haritasını (Map) tutan bir State
        return new BuildingsState(Map.of());
    }

    // -------------------------------------------------------------------------
    // Command Handler
    // -------------------------------------------------------------------------
    @Override
    public CommandHandler<BuildingCommand, BuildingEvent, BuildingsState> commandHandler() {
        return newCommandHandlerBuilder()
                .forAnyState()
                .onCommand(StartConstructionCmd.class, this::onStartConstruction)
                .onCommand(CompleteConstructionCmd.class, this::onCompleteConstruction)
                .build();
    }

    private Effect<BuildingEvent, BuildingsState> onStartConstruction(BuildingsState state, StartConstructionCmd cmd) {
        // 1. ÖNCE KONTROL: Bu spesifik bina zaten inşaat halinde mi?
        // state artık içinde bir Map<String, BuildingDetail> tutuyor.
        if (state.isConstructing(cmd.buildingId())) {
            context.getLog().warn("[BuildingActor] {} zaten inşa ediliyor, istek reddedildi.", cmd.buildingId());
            return Effect().none();
        }

        long startTime = System.currentTimeMillis();
        ConstructionStartedEvent event = new ConstructionStartedEvent(
                cmd.buildingId(), cmd.buildingType(), cmd.targetLevel(), startTime);

        return Effect().persist(event)
                .thenRun(newState -> {
                    // Timer başlatma (Duration hesabı Domain'den gelmeli)
                    Duration upgradeDuration = BuildingUpgradeConfig.getDuration(cmd.buildingType(), cmd.targetLevel());

                    context.getLog().info("[BuildingActor] {} inşaatı başladı. Süre: {}", cmd.buildingId(), upgradeDuration);

                    context.scheduleOnce(
                            upgradeDuration,
                            context.getSelf(),
                            // Önemli: replyToMaster'ı saklıyoruz
                            new CompleteConstructionCmd(cmd.buildingId(), cmd.targetLevel(), cmd.replyToMaster()));

                    // Master'a (KingdomPlayerActor) "Başladım" haberi gönder (Push-based)
                    cmd.replyToMaster().tell(new BuildingUpdateNotification(
                            cmd.buildingId(), cmd.targetLevel(), "CONSTRUCTING"));
                });
    }

    private Effect<BuildingEvent, BuildingsState> onCompleteConstruction(BuildingsState state, CompleteConstructionCmd cmd) {
        // İnşaat bitişini persist et
        ConstructionCompletedEvent event = new ConstructionCompletedEvent(cmd.buildingId(), cmd.targetLevel());

        return Effect().persist(event)
                .thenRun(newState -> {
                    context.getLog().info("[BuildingActor] {} inşaatı tamamlandı! Seviye: {}", cmd.buildingId(), cmd.targetLevel());

                    // Master'a "Tamamlandı" haberi gönder
                    if (cmd.replyToMaster() != null) {
                        cmd.replyToMaster().tell(new BuildingUpdateNotification(
                                cmd.buildingId(), cmd.targetLevel(), "COMPLETED"));
                    }
                });
    }

    // -------------------------------------------------------------------------
    // Recovery & Event Handler
    // -------------------------------------------------------------------------
    @Override
    public EventHandler<BuildingsState, BuildingEvent> eventHandler() {
        return newEventHandlerBuilder()
                .forAnyState()
                .onEvent(ConstructionStartedEvent.class, (state, event) -> state.withStartedBuilding(event))
                .onEvent(ConstructionCompletedEvent.class, (state, event) -> state.withCompletedBuilding(event))
                .build();
    }

    @Override
    public SignalHandler<BuildingsState> signalHandler() {
        return newSignalHandlerBuilder()
                .onSignal(RecoveryCompleted.instance(), state -> {
                    // Recovery anında hala CONSTRUCTING olan tüm binalar için timer'ları yeniden kur
                    state.getConstructingBuildings().forEach(building -> {
                        // Timer yeniden hesaplama mantığı buraya...
                    });
                })
                .build();
    }
}
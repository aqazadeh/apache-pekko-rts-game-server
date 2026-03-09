package com.conquerer.server.actor;

import com.conquerer.server.domain.building.command.BuildingCommand;
import com.conquerer.server.domain.building.command.CompleteConstructionCmd;
import com.conquerer.server.domain.building.command.StartConstructionCmd;
import com.conquerer.server.domain.building.event.BuildingEvent;
import com.conquerer.server.domain.building.event.ConstructionCompletedEvent;
import com.conquerer.server.domain.building.event.ConstructionStartedEvent;
import com.conquerer.server.domain.building.state.BuildingDetail;
import com.conquerer.server.domain.building.state.BuildingUpgradeConfig;
import com.conquerer.server.domain.building.state.BuildingsState;
import com.conquerer.server.domain.player.command.BuildingUpdateNotificationCmd;
import com.conquerer.server.domain.player.command.KingdomCommand;
import org.apache.pekko.actor.Cancellable;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.RecoveryCompleted;
import org.apache.pekko.persistence.typed.javadsl.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class BuildingActor
        extends EventSourcedBehavior<BuildingCommand, BuildingEvent, BuildingsState> {

    public static final EntityTypeKey<BuildingCommand> ENTITY_KEY =
            EntityTypeKey.create(BuildingCommand.class, "BuildingActor");

    private final String entityId;
    private final ActorContext<BuildingCommand> context;
    private final ClusterSharding sharding;

    private final Map<String, Cancellable> activeTimers = new HashMap<>();

    private BuildingActor(ActorContext<BuildingCommand> context,
                          PersistenceId persistenceId,
                          String entityId) {
        super(persistenceId);
        this.context  = context;
        this.entityId = entityId;
        this.sharding = ClusterSharding.get(context.getSystem());
    }

    public static Behavior<BuildingCommand> create(String entityId) {
        return Behaviors.setup(ctx ->
                new BuildingActor(ctx,
                        PersistenceId.of(ENTITY_KEY.name(), entityId),
                        entityId));
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    @Override
    public BuildingsState emptyState() {
        return BuildingsState.EMPTY;
    }

    @Override
    public RetentionCriteria retentionCriteria() {
        return RetentionCriteria.snapshotEvery(50, 2);
    }

    // -----------------------------------------------------------------------
    // Signals
    // -----------------------------------------------------------------------

    @Override
    public SignalHandler<BuildingsState> signalHandler() {
        return newSignalHandlerBuilder()
                .onSignal(RecoveryCompleted.instance(), this::onRecoveryCompleted)
                .onSignal(PostStop.instance(), this::onPostStop)
                .build();
    }

    private void onRecoveryCompleted(BuildingsState state) {
        state.buildings().forEach((buildingId, detail) -> {
            if (!"CONSTRUCTING".equals(detail.status())) return;

            BuildingUpgradeConfig config = BuildingUpgradeConfig.fromType(detail.type());
            Duration fullDuration        = config.getDurationForLevel(detail.level());
            long elapsed                 = System.currentTimeMillis() - detail.startTime();
            long remainingMs             = fullDuration.toMillis() - elapsed;

            // null masterPlayerId — no push notification after recovery (by design)
            CompleteConstructionCmd cmd = new CompleteConstructionCmd(
                    buildingId, detail.level() + 1, null);

            if (remainingMs <= 0) {
                context.getLog().warn(
                        "[BuildingActor:{}] {} overdue by {}ms on recovery — completing immediately.",
                        entityId, buildingId, -remainingMs);
                context.getSelf().tell(cmd);        // self-message, current incarnation
            } else {
                context.getLog().info(
                        "[BuildingActor:{}] {} rescheduling timer via EntityRef in {}ms",
                        entityId, buildingId, remainingMs);
                scheduleViaEntityRef(buildingId, Duration.ofMillis(remainingMs), cmd);
            }
        });
    }

    private void onPostStop(BuildingsState state) {
        context.getLog().info(
                "[BuildingActor:{}] PostStop — cancelling {} timer(s).",
                entityId, activeTimers.size());
        activeTimers.values().forEach(Cancellable::cancel);
        activeTimers.clear();
    }

    // -----------------------------------------------------------------------
    // Command Handler
    // -----------------------------------------------------------------------

    @Override
    public CommandHandler<BuildingCommand, BuildingEvent, BuildingsState> commandHandler() {
        return newCommandHandlerBuilder()
                .forAnyState()
                .onCommand(StartConstructionCmd.class,    this::onStartConstruction)
                .onCommand(CompleteConstructionCmd.class, this::onCompleteConstruction)
                .build();
    }

    private Effect<BuildingEvent, BuildingsState> onStartConstruction(
            BuildingsState state, StartConstructionCmd cmd) {

        if (state.isConstructing(cmd.buildingId())) {
            context.getLog().warn(
                    "[BuildingActor:{}] {} already CONSTRUCTING — ignoring duplicate.",
                    entityId, cmd.buildingId());
            return Effect().none();
        }

        long startTime = System.currentTimeMillis();
        ConstructionStartedEvent event = new ConstructionStartedEvent(
                cmd.buildingId(), cmd.buildingType(), cmd.targetLevel(), startTime);

        return Effect().persist(event)
                .thenRun(newState -> {
                    BuildingDetail detail = newState.buildings().get(cmd.buildingId());
                    int currentLevel      = detail != null ? detail.level() : 0;

                    BuildingUpgradeConfig config = BuildingUpgradeConfig.fromType(cmd.buildingType());
                    Duration upgradeDuration      = config.getDurationForLevel(currentLevel);

                    context.getLog().info(
                            "[BuildingActor:{}] {} (type={}) level {} → {} | duration: {}",
                            entityId, cmd.buildingId(), cmd.buildingType(),
                            currentLevel, cmd.targetLevel(), upgradeDuration);

                    // ── Timer: via EntityRef → actor wakes up if passivated ──
                    scheduleViaEntityRef(cmd.buildingId(), upgradeDuration,
                            new CompleteConstructionCmd(
                                    cmd.buildingId(), cmd.targetLevel(), cmd.masterPlayerId()));

                    // ── Notify Master: CONSTRUCTING (via EntityRef) ──────────
                    notifyMaster(cmd.masterPlayerId(),
                            new BuildingUpdateNotificationCmd(cmd.buildingId(), currentLevel, "CONSTRUCTING"));
                });
    }

    private Effect<BuildingEvent, BuildingsState> onCompleteConstruction(
            BuildingsState state, CompleteConstructionCmd cmd) {

        Cancellable handle = activeTimers.remove(cmd.buildingId());
        if (handle != null) handle.cancel();    // no-op on natural fire, defensive on self-msg

        long completedAt = System.currentTimeMillis();
        context.getLog().info(
                "[BuildingActor:{}] {} upgrade complete → level {}",
                entityId, cmd.buildingId(), cmd.targetLevel());

        ConstructionCompletedEvent event = new ConstructionCompletedEvent(
                cmd.buildingId(), cmd.targetLevel(), completedAt);

        return Effect().persist(event)
                .thenRun(newState -> {
                    BuildingDetail detail = newState.buildings().get(cmd.buildingId());
                    int finalLevel        = detail != null ? detail.level() : cmd.targetLevel();

                    // ── Notify Master: COMPLETED (via EntityRef) ─────────────
                    if (cmd.masterPlayerId() != null) {
                        notifyMaster(cmd.masterPlayerId(),
                                new BuildingUpdateNotificationCmd(cmd.buildingId(), finalLevel, "COMPLETED"));
                    } else {
                        // Post-recovery path: masterPlayerId not available (not persisted by design)
                        context.getLog().info(
                                "[BuildingActor:{}] {} completed (post-recovery) — no masterPlayerId, skipping notification.",
                                entityId, cmd.buildingId());
                    }
                });
    }

    // -----------------------------------------------------------------------
    // Event Handler
    // -----------------------------------------------------------------------

    @Override
    public EventHandler<BuildingsState, BuildingEvent> eventHandler() {
        return newEventHandlerBuilder()
                .forAnyState()
                .onEvent(ConstructionStartedEvent.class,    BuildingsState::withConstructionStarted)
                .onEvent(ConstructionCompletedEvent.class,  BuildingsState::withConstructionCompleted)
                .build();
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private void scheduleViaEntityRef(String buildingId, Duration delay, BuildingCommand message) {
        Cancellable existing = activeTimers.remove(buildingId);
        if (existing != null) {
            existing.cancel();
            context.getLog().warn("[BuildingActor:{}] Replaced timer for {} (safety).", entityId, buildingId);
        }

        EntityRef<BuildingCommand> selfRef = sharding.entityRefFor(ENTITY_KEY, entityId);
        Cancellable handle = context.getSystem().scheduler().scheduleOnce(
                delay,
                () -> selfRef.tell(message),
                context.getSystem().executionContext()
        );
        activeTimers.put(buildingId, handle);
    }


    private void notifyMaster(String masterPlayerId, KingdomCommand notification) {
        if (masterPlayerId == null) return;
        EntityRef<KingdomCommand> masterRef =
                sharding.entityRefFor(KingdomPlayerActor.ENTITY_KEY, masterPlayerId);
        masterRef.tell(notification);
    }
}

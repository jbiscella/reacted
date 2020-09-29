/*
 * Copyright (c) 2020 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.core.reactorsystem;

import io.reacted.core.config.reactors.ReActorConfig;
import io.reacted.core.config.reactors.SubscriptionPolicy;
import io.reacted.core.mailboxes.MailBox;
import io.reacted.core.messages.Message;
import io.reacted.core.messages.reactors.DeliveryStatus;
import io.reacted.core.reactors.ReActions;
import io.reacted.core.reactors.ReActiveEntity;
import io.reacted.core.reactors.ReActor;
import io.reacted.core.runtime.Dispatcher;
import io.reacted.patterns.NonNullByDefault;
import io.reacted.patterns.Try;

import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;

@NonNullByDefault
public final class ReActorContext {
    @Nullable
    public static final ReActorContext NO_REACTOR_CTX = null;
    private final MailBox actorMbox;
    private final ReActorRef reactorRef;
    private final ReActorSystem reActorSystem;
    private final List<ReActorRef> children;
    private final ReActorRef parent;
    private final Dispatcher dispatcher;
    private final AtomicBoolean isScheduled;
    private final ReadWriteLock structuralLock;
    private final CompletionStage<Void> hierarchyTermination;
    private final AtomicLong msgExecutionId;
    private final ReActions reActions;

    private SubscriptionPolicy.SniffSubscription[] interceptRules;

    private volatile boolean stop = false;
    private volatile boolean isAcquired = false;

    private ReActorRef lastMsgSender = ReActorRef.NO_REACTOR_REF;

    private ReActorContext(Builder reActorCtxBuilder) {
        this.actorMbox = Objects.requireNonNull(reActorCtxBuilder.mbox);
        this.reactorRef = Objects.requireNonNull(reActorCtxBuilder.reactorRef);
        this.reActorSystem = Objects.requireNonNull(reActorCtxBuilder.reActorSystem);
        this.children = new CopyOnWriteArrayList<>();
        this.parent = Objects.requireNonNull(reActorCtxBuilder.parent);
        this.dispatcher = Objects.requireNonNull(reActorCtxBuilder.dispatcher);
        this.isScheduled = new AtomicBoolean(false);
        this.structuralLock = new ReentrantReadWriteLock();
        this.interceptRules = Objects.requireNonNull(reActorCtxBuilder.interceptRules).length == 0
                              ? SubscriptionPolicy.SniffSubscription.NO_SUBSCRIPTIONS
                              : Arrays.copyOf(reActorCtxBuilder.interceptRules,
                                              reActorCtxBuilder.interceptRules.length);
        this.hierarchyTermination = new CompletableFuture<>();
        this.msgExecutionId = new AtomicLong();
        this.reActions = Objects.requireNonNull(reActorCtxBuilder.reActions);
    }

    public static Builder newBuilder() { return new Builder(); }

    public ReActorRef getSelf() { return reactorRef; }

    public ReActorSystem getReActorSystem() { return reActorSystem; }

    public List<ReActorRef> getChildren() { return children; }

    public ReActorRef getParent() { return parent; }

    public Dispatcher getDispatcher() { return dispatcher; }

    public ReadWriteLock getStructuralLock() { return structuralLock; }

    public MailBox getMbox() { return actorMbox; }

    public CompletionStage<Void> getHierarchyTermination() { return hierarchyTermination; }

    public long getNextMsgExecutionId() { return msgExecutionId.getAndIncrement(); }

    public boolean acquireScheduling() {
        return isScheduled.compareAndSet(false, true);
    }

    public void releaseScheduling() { isScheduled.compareAndSet(true, false); }

    @SuppressWarnings("UnusedReturnValue")
    public boolean acquireCoherence() { return !isAcquired; }

    public void releaseCoherence() { isAcquired = false; }

    @SuppressWarnings("UnusedReturnValue")
    public boolean registerChild(ReActorRef childActor) {
        return children.add(childActor);
    }

    @SuppressWarnings("UnusedReturnValue")
    public boolean unregisterChild(ReActorRef childActor) {
        return children.remove(childActor);
    }

    public void refreshInterceptors(SubscriptionPolicy.SniffSubscription... newInterceptedClasses) {

        getStructuralLock().writeLock().lock();
        try {
            getReActorSystem().updateMessageInterceptors(this, interceptRules, newInterceptedClasses);
            interceptRules = newInterceptedClasses;
        } finally {
            getStructuralLock().writeLock().unlock();
        }
    }

    public SubscriptionPolicy.SniffSubscription[] getInterceptRules() {
        SubscriptionPolicy.SniffSubscription[] interceptedMsgTypes;

        getStructuralLock().readLock().lock();
        interceptedMsgTypes = Arrays.copyOf(this.interceptRules, this.interceptRules.length);
        getStructuralLock().readLock().unlock();

        return interceptedMsgTypes;
    }

    public final void reschedule() {
        getDispatcher().dispatch(this);
    }

    public CompletionStage<Try<DeliveryStatus>> reply(Serializable anyPayload) {
        return reply(getSelf(), anyPayload);
    }

    public CompletionStage<Try<DeliveryStatus>> aReply(Serializable anyPayload) {
        return aReply(getSelf(), anyPayload);
    }

    public CompletionStage<Try<DeliveryStatus>> reply(ReActorRef sender, Serializable anyPayload) {
        return getSender().tell(sender, anyPayload);
    }

    public CompletionStage<Try<DeliveryStatus>> aReply(ReActorRef sender, Serializable anyPayload) {
        return getSender().aTell(sender, anyPayload);
    }

    public CompletionStage<Try<DeliveryStatus>> selfTell(Serializable anyPayload) {
        return getSelf().tell(this.getSelf(), anyPayload);
    }

    public Try<ReActorRef> spawnChild(ReActor reActor) {
        return getReActorSystem().spawnChild(reActor.getReActions(), getSelf(), reActor.getConfig());
    }

    public Try<ReActorRef> spawnChild(ReActiveEntity reActiveEntity, ReActorConfig reActorConfig) {
        return getReActorSystem().spawnChild(reActiveEntity.getReActions(), getSelf(), reActorConfig);
    }

    public Try<ReActorRef> spawnChild(ReActions reActions, ReActorConfig reActorConfig) {
        return getReActorSystem().spawnChild(reActions, getSelf(), reActorConfig);
    }

    public final void setInterceptRules(SubscriptionPolicy.SniffSubscription... interceptRules) {
        refreshInterceptors(Objects.requireNonNull(interceptRules).length == 0
                            ? SubscriptionPolicy.SniffSubscription.NO_SUBSCRIPTIONS
                            : Arrays.copyOf(interceptRules, interceptRules.length));
    }

    public CompletionStage<Void> stop() {
        this.stop = true;
        reschedule();
        return getHierarchyTermination();
    }

    public boolean isStop() {
        return this.stop;
    }

    public void logInfo(String descriptionFormat, Serializable ...args) {
        getReActorSystem().logInfo(descriptionFormat, args);
    }

    public void logError(String descriptionFormat, Serializable ...args) {
        getReActorSystem().logError(descriptionFormat, args);
    }

    public void logDebug(String descriptionFormat, Serializable ...args) {
        getReActorSystem().logDebug(descriptionFormat, args);
    }

    public void reAct(Message msg) {
        this.lastMsgSender = msg.getSender();
        BiConsumer<ReActorContext, Serializable> reAction = reActions.getReAction(msg.getPayload());
        reAction.accept(this, msg.getPayload());
    }

    public ReActorRef getSender() {
        return this.lastMsgSender;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof ReActorContext)) return false;
        ReActorContext that = (ReActorContext) o;
        return getSelf().equals(that.getSelf());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getSelf());
    }

    @SuppressWarnings("NotNullFieldNotInitialized")
    public static class Builder {
        private MailBox mbox;
        private ReActorRef reactorRef;
        private ReActorSystem reActorSystem;
        private ReActorRef parent;
        private SubscriptionPolicy.SniffSubscription[] interceptRules;
        private Dispatcher dispatcher;
        private ReActions reActions;

        public Builder setMbox(MailBox actorMbox) {
            this.mbox = actorMbox;
            return this;
        }

        public Builder setReactorRef(ReActorRef reactorRef) {
            this.reactorRef = reactorRef;
            return this;
        }

        public Builder setReActorSystem(ReActorSystem reActorSystem) {
            this.reActorSystem = reActorSystem;
            return this;
        }

        public Builder setParentActor(ReActorRef parentActor) {
            this.parent = parentActor;
            return this;
        }

        public Builder setInterceptRules(SubscriptionPolicy.SniffSubscription... interceptRules) {
            this.interceptRules = interceptRules;
            return this;
        }

        public Builder setDispatcher(Dispatcher dispatcher) {
            this.dispatcher = dispatcher;
            return this;
        }

        public Builder setReActions(ReActions reActions) {
            this.reActions = reActions;
            return this;
        }

        public ReActorContext build() {
            return new ReActorContext(this);
        }
    }
}

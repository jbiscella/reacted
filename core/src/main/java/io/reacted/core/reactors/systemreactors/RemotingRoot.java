/*
 * Copyright (c) 2020 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.core.reactors.systemreactors;

import io.reacted.core.drivers.system.RemotingDriver;
import io.reacted.core.messages.reactors.DeliveryStatus;
import io.reacted.core.messages.reactors.ReActorInit;
import io.reacted.core.messages.reactors.ReActorStop;
import io.reacted.core.messages.serviceregistry.RegistryDriverInitComplete;
import io.reacted.core.messages.serviceregistry.RegistryGateRemoved;
import io.reacted.core.messages.serviceregistry.RegistryGateUpserted;
import io.reacted.core.messages.serviceregistry.ReActorSystemChannelIdPublicationRequest;
import io.reacted.core.messages.serviceregistry.ServiceCancellationRequest;
import io.reacted.core.messages.serviceregistry.RegistryServicePublicationFailed;
import io.reacted.core.messages.serviceregistry.ServiceServicePublicationRequest;
import io.reacted.core.messages.serviceregistry.RegistrySubscriptionComplete;
import io.reacted.core.messages.serviceregistry.SynchronizationWithServiceRegistryRequest;
import io.reacted.core.reactors.ReActions;
import io.reacted.core.reactorsystem.ReActorContext;
import io.reacted.core.reactorsystem.ReActorRef;
import io.reacted.core.reactorsystem.ReActorSystemId;

import javax.annotation.concurrent.Immutable;
import java.io.Serializable;
import java.util.Collection;

@Immutable
public class RemotingRoot {
    private final Collection<RemotingDriver> remotingDrivers;
    private final ReActorSystemId localReActorSystem;

    public RemotingRoot(ReActorSystemId localReActorSystem,
                        Collection<RemotingDriver> remotingDrivers) {
        this.remotingDrivers = remotingDrivers;
        this.localReActorSystem = localReActorSystem;
    }

    public ReActions getReActions() {
        return ReActions.newBuilder()
                        .reAct(ReActorInit.class, (raCtx, init) -> {})
                        .reAct(RegistryDriverInitComplete.class, RemotingRoot::onInitComplete)
                        .reAct(RegistrySubscriptionComplete.class, this::onSubscriptionComplete)
                        .reAct(RegistryGateUpserted.class, this::onRegistryGateUpsert)
                        .reAct(RegistryGateRemoved.class, this::onRegistryGateRemoval)
                        .reAct(ServiceServicePublicationRequest.class, RemotingRoot::onPublishService)
                        .reAct(RegistryServicePublicationFailed.class, RemotingRoot::onRegistryServicePublicationFailure)
                        .reAct(ServiceCancellationRequest.class, RemotingRoot::onCancelService)
                        .reAct(ReActorStop.class, RemotingRoot::onStop)
                        .reAct(RemotingRoot::onSpuriousMessage)
                        .build();
    }

    @SuppressWarnings("EmptyMethod")
    private static void onStop(ReActorContext raCtx, ReActorStop stop) { /* Nothing to do */ }

    private static void onCancelService(ReActorContext raCtx,
                                        ServiceCancellationRequest serviceCancellationRequest) {
        raCtx.getChildren().forEach(serviceRegistryDriver -> serviceRegistryDriver.tell(raCtx.getSelf(),
                                                                                        serviceCancellationRequest));
    }

    private static void onPublishService(ReActorContext raCtx, ServiceServicePublicationRequest publishService) {
        for(ReActorRef serviceRegistryDriver : raCtx.getChildren()) {
            var deliveryAttempt = serviceRegistryDriver.tell(raCtx.getSelf(), publishService);
            deliveryAttempt.thenAccept(attempt -> attempt.filter(DeliveryStatus::isDelivered)
                                                         .ifError(error -> raCtx.logError("Unable to deliver service publish request",
                                                                                          error)));
        }
    }

    private static void onInitComplete(ReActorContext raCtx,
                                       RegistryDriverInitComplete initComplete) {
        raCtx.reply(new SynchronizationWithServiceRegistryRequest());
    }

    private static void onSpuriousMessage(ReActorContext raCtx, Serializable payload) {
        raCtx.logError("Spurious message received", new IllegalStateException(payload.toString()));
    }

    private void onSubscriptionComplete(ReActorContext raCtx,
                                        RegistrySubscriptionComplete subCompleted) {
        remotingDrivers.stream()
                       .map(remotingDriver -> new ReActorSystemChannelIdPublicationRequest(localReActorSystem,
                                                                                           remotingDriver.getChannelId(),
                                                                                           remotingDriver.getChannelProperties()))
                       .map(raCtx::reply)
                       .forEach(pubRequest -> pubRequest.thenAccept(result -> result.filter(DeliveryStatus::isDelivered)
                                                                                    .ifError(error -> raCtx.getReActorSystem()
                                                                                                           .logError("Unable to publish channel:",
                                                                                                                     error))));
    }

    private static void onRegistryServicePublicationFailure(ReActorContext raCtx,
                                                            RegistryServicePublicationFailed failure) {
        raCtx.logError("Error publishing service {}", failure.getServiceName(), failure.getPublicationError());
    }

    private void onRegistryGateUpsert(ReActorContext raCtx, RegistryGateUpserted upsert) {
        //skip self notifications
        if (!raCtx.getReActorSystem().getLocalReActorSystemId().equals(upsert.getReActorSystemId())) {

            raCtx.getReActorSystem().unregisterRoute(upsert.getReActorSystemId(), upsert.getChannelId());
            raCtx.getReActorSystem().registerNewRoute(upsert.getReActorSystemId(), upsert.getChannelId(),
                                                      upsert.getChannelData());

            raCtx.logDebug("I am {} received config for {} Channel {} Data: {}",
                           raCtx.getReActorSystem().getLocalReActorSystemId().getReActorSystemName(),
                           upsert.getReActorSystemId().getReActorSystemName(), upsert.getChannelId(),
                           upsert.getChannelData().toString());
        }
    }

    private void onRegistryGateRemoval(ReActorContext raCtx, RegistryGateRemoved removed) {
        if (raCtx.getReActorSystem().getLocalReActorSystemId().equals(removed.getReActorSystem())) {
            //if for any reason we got removed from remove service registry, refresh subscription
            raCtx.getSelf().tell(raCtx.getSender(), new RegistrySubscriptionComplete());
            return;
        }
        raCtx.getReActorSystem().unregisterRoute(removed.getReActorSystem(),
                                                 removed.getChannelId());
        raCtx.logDebug("I am {} received removal request for {} channel {}",
                       raCtx.getReActorSystem().getLocalReActorSystemId().getReActorSystemName(),
                       removed.getReActorSystem().getReActorSystemName(), removed.getChannelId());
    }
}

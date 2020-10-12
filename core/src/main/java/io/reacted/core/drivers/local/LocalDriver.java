/*
 * Copyright (c) 2020 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.core.drivers.local;

import io.reacted.core.config.drivers.ReActedDriverCfg;
import io.reacted.core.drivers.system.ReActorSystemDriver;
import io.reacted.core.messages.AckingPolicy;
import io.reacted.core.messages.Message;
import io.reacted.core.messages.reactors.DeadMessage;
import io.reacted.core.messages.reactors.DeliveryStatus;
import io.reacted.core.reactorsystem.ReActorContext;
import io.reacted.core.reactorsystem.ReActorRef;
import io.reacted.patterns.NonNullByDefault;
import io.reacted.patterns.Try;

import java.io.Serializable;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@NonNullByDefault
public abstract class LocalDriver<CfgT extends ReActedDriverCfg<?, CfgT>>
        extends ReActorSystemDriver<CfgT> {
     private static final Try<DeliveryStatus> TARGET_MISSING = Try.ofFailure(new NoSuchElementException());

     protected LocalDriver(CfgT driverCfg) {
          super(driverCfg);
     }

     @Override
     public boolean channelRequiresDeliveryAck() { return false; }

     @Override
     public final <PayloadT extends Serializable>
     CompletionStage<Try<DeliveryStatus>> tell(ReActorRef src, ReActorRef dst, AckingPolicy ackingPolicy,
                                               PayloadT message) {
          return CompletableFuture.completedFuture(Try.ofFailure(new UnsupportedOperationException()));
     }

     protected void offerMessage(Message message) {
          var deliveryAttempt = getLocalReActorSystem().getReActor(Objects.requireNonNull(message)
                                                                          .getDestination()
                                                                          .getReActorId())
                                                       .map(raCtx -> forwardMessageToLocalActor(raCtx, message));
          var ackTrigger = removePendingAckTrigger(message.getSequenceNumber());

          if (deliveryAttempt.isPresent()) {
               var deliveryAttemptStatus = deliveryAttempt.get();
               deliveryAttemptStatus.thenAccept(deliveryStatus -> ackTrigger.ifPresent(trigger -> trigger.complete(deliveryStatus)));

          } else {
               ackTrigger.ifPresent(trigger -> trigger.complete(TARGET_MISSING));
               propagateToDeadLetters(getLocalReActorSystem().getSystemDeadLetters(), message);
          }
     }

     protected static CompletionStage<Try<DeliveryStatus>> forwardMessageToLocalActor(ReActorContext destination,
                                                                                      Message message) {
          return SystemLocalDrivers.DIRECT_COMMUNICATION.sendAsyncMessage(destination, Objects.requireNonNull(message));
     }

     protected static Try<DeliveryStatus> localDeliver(ReActorContext destination, Message message) {
          Try<DeliveryStatus> deliverOperation = Try.of(() -> destination.getMbox()
                                                                         .deliver(message));
          rescheduleIfSuccess(deliverOperation, destination);
          return deliverOperation;
     }

     protected static CompletionStage<Try<DeliveryStatus>> asyncLocalDeliver(ReActorContext destination,
                                                                             Message message) {
          var asyncDeliverResult = destination.getMbox()
                                              .asyncDeliver(message);
          asyncDeliverResult.thenAccept(result -> rescheduleIfSuccess(result, destination));
          return asyncDeliverResult;
     }

     protected static void rescheduleIfSuccess(Try<DeliveryStatus> deliveryResult, ReActorContext destination) {
          deliveryResult.peekFailure(error -> LOGGER.error("Unable to deliver: ", error))
                        .filter(DeliveryStatus::isDelivered)
                        .ifSuccess(deliveryStatus -> destination.reschedule());
     }

     private static void propagateToDeadLetters(ReActorRef systemDeadLetters, Message originalMessage) {
          systemDeadLetters.tell(originalMessage.getSender(), new DeadMessage(originalMessage.getPayload()));
     }
}

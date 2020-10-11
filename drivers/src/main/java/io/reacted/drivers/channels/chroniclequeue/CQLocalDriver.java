/*
 * Copyright (c) 2020 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.drivers.channels.chroniclequeue;

import io.reacted.core.config.ChannelId;
import io.reacted.core.drivers.local.LocalDriver;
import io.reacted.core.messages.Message;
import io.reacted.core.messages.reactors.DeliveryStatus;
import io.reacted.core.reactors.ReActorId;
import io.reacted.core.reactorsystem.ReActorContext;
import io.reacted.core.reactorsystem.ReActorSystem;
import io.reacted.patterns.NonNullByDefault;
import io.reacted.patterns.Try;
import io.reacted.patterns.UnChecked;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.threads.Pauser;
import net.openhft.chronicle.wire.ValueOut;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;


@NonNullByDefault
public class CQLocalDriver extends LocalDriver {
    private final CQDriverConfig driverConfig;
    @Nullable
    private ChronicleQueue chronicle;
    @Nullable
    private ExcerptAppender cqAppender;
    @Nullable
    private ExcerptTailer cqTailer;

    public CQLocalDriver(CQDriverConfig driverConfig) {
        this.driverConfig = Objects.requireNonNull(driverConfig);
    }

    @Override
    public void initDriverLoop(ReActorSystem localReActorSystem) {
        this.chronicle = ChronicleQueue.singleBuilder(driverConfig.getChronicleFilesDir()).build();
        this.cqAppender = chronicle.acquireAppender();
        this.cqTailer = chronicle.createTailer().toEnd();
    }

    @Override
    public UnChecked.CheckedRunnable getDriverLoop() {
        return () -> chronicleMainLoop(Objects.requireNonNull(cqTailer));
    }

    @Override
    public ChannelId getChannelId() {
        return new ChannelId(ChannelId.ChannelType.LOCAL_CHRONICLE_QUEUE, driverConfig.getChannelName());
    }

    @Override
    public Properties getChannelProperties() { return driverConfig.getProperties(); }

    @Override
    public CompletionStage<Try<DeliveryStatus>> sendAsyncMessage(ReActorContext destination, Message message) {
        return CompletableFuture.completedFuture(sendMessage(destination, message));
    }

    @Override
    public Try<DeliveryStatus> sendMessage(ReActorContext destination, Message message) {
        return sendMessage(message);
    }

    @Override
    public boolean channelRequiresDeliveryAck() { return driverConfig.isDeliveryAckRequiredByChannel(); }

    @Override
    public CompletionStage<Try<Void>> cleanDriverLoop() {
        return CompletableFuture.completedFuture(Try.ofRunnable(() -> Objects.requireNonNull(chronicle).close()));
    }

    private void chronicleMainLoop(ExcerptTailer tailer) {
        Pauser waitForNextMsg = Pauser.millis(100, 500);

        while(!Thread.currentThread().isInterrupted()) {

            @SuppressWarnings("ConstantConditions") var newMessage = Try.withResources(tailer::readingDocument,
                                               dCtx -> dCtx.isPresent()
                                                        ? dCtx.wire().read().object(Message.class)
                                                        : null)
                                .peekFailure(error -> logErrorDecodingMessage(getLocalReActorSystem()::logError, error))
                                .toOptional()
                                .orElse(null);
            if (newMessage == null) {
                waitForNextMsg.pause();
                waitForNextMsg.reset();
                continue;
            }
            offerMessage(newMessage);
        }
        Thread.currentThread().interrupt();
    }

    private Try<DeliveryStatus> sendMessage(Message message) {
        BiConsumer<ValueOut, Message> msgWriter;
        msgWriter = (vOut, payload) -> vOut.object(Message.class, payload);
        return Try.ofRunnable(() -> Objects.requireNonNull(cqAppender).writeDocument(message, msgWriter))
                  .map(dummy -> DeliveryStatus.DELIVERED)
                  .orElseTry(Try::ofFailure);
    }

    private static void logErrorDecodingMessage(BiConsumer<String, Throwable> logger, Throwable error) {
        logger.accept("Unable to properly decode message", error);
    }
}

/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.mailbox.cassandra.mail;

import static org.apache.james.util.ReactorUtils.publishIfPresent;

import java.time.Duration;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.mail.Flags;
import javax.mail.Flags.Flag;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.james.backends.cassandra.init.configuration.CassandraConfiguration;
import org.apache.james.mailbox.ApplicableFlagBuilder;
import org.apache.james.mailbox.FlagsBuilder;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.ModSeq;
import org.apache.james.mailbox.cassandra.ids.CassandraId;
import org.apache.james.mailbox.cassandra.ids.CassandraMessageId;
import org.apache.james.mailbox.cassandra.mail.utils.FlagsUpdateStageResult;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.model.ComposedMessageId;
import org.apache.james.mailbox.model.ComposedMessageIdWithMetaData;
import org.apache.james.mailbox.model.Mailbox;
import org.apache.james.mailbox.model.MailboxCounters;
import org.apache.james.mailbox.model.MessageMetaData;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.model.UpdatedFlags;
import org.apache.james.mailbox.store.FlagsUpdateCalculator;
import org.apache.james.mailbox.store.mail.MessageMapper;
import org.apache.james.mailbox.store.mail.model.MailboxMessage;
import org.apache.james.mailbox.store.mail.model.impl.SimpleMailboxMessage;
import org.apache.james.util.ReactorUtils;
import org.apache.james.util.streams.Limit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.github.steveash.guavate.Guavate;
import com.google.common.collect.ImmutableList;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

public class CassandraMessageMapper implements MessageMapper {
    public static final Logger LOGGER = LoggerFactory.getLogger(CassandraMessageMapper.class);

    private static final int MAX_RETRY = 5;
    private static final Duration MIN_RETRY_BACKOFF = Duration.ofMillis(10);
    private static final Duration MAX_RETRY_BACKOFF = Duration.ofMillis(1000);

    private final CassandraModSeqProvider modSeqProvider;
    private final CassandraUidProvider uidProvider;
    private final CassandraMessageDAO messageDAO;
    private final CassandraMessageIdDAO messageIdDAO;
    private final CassandraMessageIdToImapUidDAO imapUidDAO;
    private final CassandraMailboxCounterDAO mailboxCounterDAO;
    private final CassandraMailboxRecentsDAO mailboxRecentDAO;
    private final CassandraApplicableFlagDAO applicableFlagDAO;
    private final CassandraIndexTableHandler indexTableHandler;
    private final CassandraFirstUnseenDAO firstUnseenDAO;
    private final AttachmentLoader attachmentLoader;
    private final CassandraDeletedMessageDAO deletedMessageDAO;
    private final CassandraConfiguration cassandraConfiguration;

    public CassandraMessageMapper(CassandraUidProvider uidProvider, CassandraModSeqProvider modSeqProvider,
                                  CassandraAttachmentMapper attachmentMapper,
                                  CassandraMessageDAO messageDAO, CassandraMessageIdDAO messageIdDAO,
                                  CassandraMessageIdToImapUidDAO imapUidDAO, CassandraMailboxCounterDAO mailboxCounterDAO,
                                  CassandraMailboxRecentsDAO mailboxRecentDAO, CassandraApplicableFlagDAO applicableFlagDAO,
                                  CassandraIndexTableHandler indexTableHandler, CassandraFirstUnseenDAO firstUnseenDAO,
                                  CassandraDeletedMessageDAO deletedMessageDAO, CassandraConfiguration cassandraConfiguration) {
        this.uidProvider = uidProvider;
        this.modSeqProvider = modSeqProvider;
        this.messageDAO = messageDAO;
        this.messageIdDAO = messageIdDAO;
        this.imapUidDAO = imapUidDAO;
        this.mailboxCounterDAO = mailboxCounterDAO;
        this.mailboxRecentDAO = mailboxRecentDAO;
        this.indexTableHandler = indexTableHandler;
        this.firstUnseenDAO = firstUnseenDAO;
        this.attachmentLoader = new AttachmentLoader(attachmentMapper);
        this.applicableFlagDAO = applicableFlagDAO;
        this.deletedMessageDAO = deletedMessageDAO;
        this.cassandraConfiguration = cassandraConfiguration;
    }

    @Override
    public Flux<MessageUid> listAllMessageUids(Mailbox mailbox) {
        CassandraId cassandraId = (CassandraId) mailbox.getMailboxId();
        return messageIdDAO.retrieveMessages(cassandraId, MessageRange.all())
            .map(metaData -> metaData.getComposedMessageId().getUid());
    }

    @Override
    public long countMessagesInMailbox(Mailbox mailbox) {
        return getMailboxCounters(mailbox).getCount();
    }

    @Override
    public MailboxCounters getMailboxCounters(Mailbox mailbox) {
        return getMailboxCountersReactive(mailbox).block();
    }

    @Override
    public Mono<MailboxCounters> getMailboxCountersReactive(Mailbox mailbox) {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();
        return mailboxCounterDAO.retrieveMailboxCounters(mailboxId)
            .defaultIfEmpty(MailboxCounters.builder()
                .mailboxId(mailboxId)
                .count(0)
                .unseen(0)
                .build());
    }

    @Override
    public void delete(Mailbox mailbox, MailboxMessage message) {
        deleteAsFuture(message)
            .block();
    }

    private Mono<Void> deleteAsFuture(MailboxMessage message) {
        ComposedMessageIdWithMetaData composedMessageIdWithMetaData = message.getComposedMessageIdWithMetaData();

        return deleteUsingMailboxId(composedMessageIdWithMetaData);
    }

    private Mono<Void> deleteUsingMailboxId(ComposedMessageIdWithMetaData composedMessageIdWithMetaData) {
        ComposedMessageId composedMessageId = composedMessageIdWithMetaData.getComposedMessageId();
        CassandraMessageId messageId = (CassandraMessageId) composedMessageId.getMessageId();
        CassandraId mailboxId = (CassandraId) composedMessageId.getMailboxId();
        MessageUid uid = composedMessageId.getUid();
        return Flux.merge(
                imapUidDAO.delete(messageId, mailboxId),
                messageIdDAO.delete(mailboxId, uid))
            .then(indexTableHandler.updateIndexOnDelete(composedMessageIdWithMetaData, mailboxId));
    }

    @Override
    public Iterator<MailboxMessage> findInMailbox(Mailbox mailbox, MessageRange messageRange, FetchType ftype, int max) {
        return findInMailboxReactive(mailbox, messageRange, ftype, max)
            .toIterable()
            .iterator();
    }

    @Override
    public Flux<MailboxMessage> findInMailboxReactive(Mailbox mailbox, MessageRange messageRange, FetchType ftype, int limit) {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();

        return Limit.from(limit).applyOnFlux(
            messageIdDAO.retrieveMessages(mailboxId, messageRange)
                .flatMap(id -> retrieveMessage(id, ftype), cassandraConfiguration.getMessageReadChunkSize()))
            .map(MailboxMessage.class::cast)
            .sort(Comparator.comparing(MailboxMessage::getUid));
    }

    private Mono<MailboxMessage> retrieveMessage(ComposedMessageIdWithMetaData messageId, FetchType fetchType) {
        return messageDAO.retrieveMessage(messageId, fetchType)
            .flatMap(messageRepresentation -> attachmentLoader.addAttachmentToMessage(Pair.of(messageId, messageRepresentation), fetchType));
    }

    @Override
    public List<MessageUid> findRecentMessageUidsInMailbox(Mailbox mailbox) {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();
        return mailboxRecentDAO.getRecentMessageUidsInMailbox(mailboxId)
            .collectList()
            .block();
    }

    @Override
    public MessageUid findFirstUnseenMessageUid(Mailbox mailbox) {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();
        return firstUnseenDAO.retrieveFirstUnread(mailboxId)
                .blockOptional()
                .orElse(null);
    }

    @Override
    public List<MessageUid> retrieveMessagesMarkedForDeletion(Mailbox mailbox, MessageRange messageRange) {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();

        return deletedMessageDAO.retrieveDeletedMessage(mailboxId, messageRange)
            .collect(Guavate.toImmutableList())
            .block();
    }

    @Override
    public Map<MessageUid, MessageMetaData> deleteMessages(Mailbox mailbox, List<MessageUid> uids) {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();

        return Flux.fromStream(uids.stream())
            .flatMap(messageUid -> expungeOne(mailboxId, messageUid), cassandraConfiguration.getExpungeChunkSize())
            .collect(Guavate.<SimpleMailboxMessage, MessageUid, MessageMetaData>toImmutableMap(MailboxMessage::getUid, MailboxMessage::metaData))
            .block();
    }

    private Mono<SimpleMailboxMessage> expungeOne(CassandraId mailboxId, MessageUid messageUid) {
        return retrieveComposedId(mailboxId, messageUid)
            .flatMap(idWithMetadata -> deleteUsingMailboxId(idWithMetadata).thenReturn(idWithMetadata))
            .flatMap(idWithMetadata -> messageDAO.retrieveMessage(idWithMetadata, FetchType.Metadata)
                .map(pair -> pair.toMailboxMessage(idWithMetadata, ImmutableList.of())));
    }

    private Mono<ComposedMessageIdWithMetaData> retrieveComposedId(CassandraId mailboxId, MessageUid uid) {
        return messageIdDAO.retrieve(mailboxId, uid)
            .handle((t, sink) ->
                t.ifPresentOrElse(
                    sink::next,
                    () -> LOGGER.warn("Could not retrieve message {} {}", mailboxId, uid)));
    }

    @Override
    public MessageMetaData move(Mailbox destinationMailbox, MailboxMessage original) throws MailboxException {
        ComposedMessageIdWithMetaData composedMessageIdWithMetaData = original.getComposedMessageIdWithMetaData();

        MessageMetaData messageMetaData = copy(destinationMailbox, original);
        deleteUsingMailboxId(composedMessageIdWithMetaData).block();

        return messageMetaData;
    }

    @Override
    public void endRequest() {
        // Do nothing
    }

    @Override
    public ModSeq getHighestModSeq(Mailbox mailbox) throws MailboxException {
        return modSeqProvider.highestModSeq(mailbox);
    }

    @Override
    public MessageMetaData add(Mailbox mailbox, MailboxMessage message) throws MailboxException {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();

        return block(addUidAndModseq(message, mailboxId)
            .flatMap(Throwing.function(messageWithUidAndModSeq -> save(mailbox, messageWithUidAndModSeq)
                .thenReturn(messageWithUidAndModSeq)))
            .map(MailboxMessage::metaData));
    }

    private Mono<MailboxMessage> addUidAndModseq(MailboxMessage message, CassandraId mailboxId) {
        Mono<MessageUid> messageUidMono = uidProvider
            .nextUid(mailboxId)
            .switchIfEmpty(Mono.error(() -> new MailboxException("Can not find a UID to save " + message.getMessageId() + " in " + mailboxId)));

        Mono<ModSeq> nextModSeqMono = modSeqProvider.nextModSeq(mailboxId)
            .switchIfEmpty(Mono.error(() -> new MailboxException("Can not find a MODSEQ to save " + message.getMessageId() + " in " + mailboxId)));

        return Mono.zip(messageUidMono, nextModSeqMono)
                .doOnNext(tuple -> {
                    message.setUid(tuple.getT1());
                    message.setModSeq(tuple.getT2());
                })
                .thenReturn(message);
    }

    private <T> T block(Mono<T> mono) throws MailboxException {
        try {
            return mono.block();
        } catch (RuntimeException e) {
            if (e.getCause() instanceof MailboxException) {
                throw (MailboxException) e.getCause();
            }
            throw e;
        }
    }

    @Override
    public Iterator<UpdatedFlags> updateFlags(Mailbox mailbox, FlagsUpdateCalculator flagUpdateCalculator, MessageRange range) {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();

        Flux<ComposedMessageIdWithMetaData> toBeUpdated = messageIdDAO.retrieveMessages(mailboxId, range);

        FlagsUpdateStageResult firstResult = runUpdateStage(mailboxId, toBeUpdated, flagUpdateCalculator).block();
        FlagsUpdateStageResult finalResult = handleUpdatesStagedRetry(mailboxId, flagUpdateCalculator, firstResult);
        if (finalResult.containsFailedResults()) {
            LOGGER.error("Can not update following UIDs {} for mailbox {}", finalResult.getFailed(), mailboxId.asUuid());
        }
        return finalResult.getSucceeded().iterator();
    }

    private FlagsUpdateStageResult handleUpdatesStagedRetry(CassandraId mailboxId, FlagsUpdateCalculator flagUpdateCalculator, FlagsUpdateStageResult firstResult) {
        FlagsUpdateStageResult globalResult = firstResult;
        int retryCount = 0;
        while (retryCount < cassandraConfiguration.getFlagsUpdateMessageMaxRetry() && globalResult.containsFailedResults()) {
            retryCount++;
            FlagsUpdateStageResult stageResult = retryUpdatesStage(mailboxId, flagUpdateCalculator, globalResult.getFailed()).block();
            globalResult = globalResult.keepSucceded().merge(stageResult);
        }
        return globalResult;
    }

    private Mono<FlagsUpdateStageResult> retryUpdatesStage(CassandraId mailboxId, FlagsUpdateCalculator flagsUpdateCalculator, List<MessageUid> failed) {
        if (!failed.isEmpty()) {
            Flux<ComposedMessageIdWithMetaData> toUpdate = Flux.fromIterable(failed)
                .flatMap(uid -> messageIdDAO.retrieve(mailboxId, uid))
                .handle(publishIfPresent());
            return runUpdateStage(mailboxId, toUpdate, flagsUpdateCalculator);
        } else {
            return Mono.empty();
        }
    }

    private Mono<FlagsUpdateStageResult> runUpdateStage(CassandraId mailboxId, Flux<ComposedMessageIdWithMetaData> toBeUpdated, FlagsUpdateCalculator flagsUpdateCalculator) {
        Mono<ModSeq> newModSeq = computeNewModSeq(mailboxId);
        return toBeUpdated
            .concatMap(metadata -> newModSeq.flatMap(modSeq -> tryFlagsUpdate(flagsUpdateCalculator, modSeq, metadata)))
            .reduce(FlagsUpdateStageResult.none(), FlagsUpdateStageResult::merge)
            .flatMap(result -> updateIndexesForUpdatesResult(mailboxId, result));
    }

    private Mono<ModSeq> computeNewModSeq(CassandraId mailboxId) {
        return modSeqProvider.nextModSeq(mailboxId)
            .switchIfEmpty(ReactorUtils.executeAndEmpty(() -> new RuntimeException("ModSeq generation failed for mailbox " + mailboxId.asUuid())));
    }

    private Mono<FlagsUpdateStageResult> updateIndexesForUpdatesResult(CassandraId mailboxId, FlagsUpdateStageResult result) {
        return Flux.fromIterable(result.getSucceeded())
            .flatMap(Throwing
                .function((UpdatedFlags updatedFlags) -> indexTableHandler.updateIndexOnFlagsUpdate(mailboxId, updatedFlags))
                .fallbackTo(failedIndex -> {
                    LOGGER.error("Could not update flag indexes for mailboxId {} UID {}. This will lead to inconsistencies across Cassandra tables", mailboxId, failedIndex.getUid());
                    return Mono.empty();
                }))
            .then(Mono.just(result));
    }

    @Override
    public <T> T execute(Transaction<T> transaction) throws MailboxException {
        return transaction.run();
    }

    @Override
    public MessageMetaData copy(Mailbox mailbox, MailboxMessage original) throws MailboxException {
        original.setFlags(new FlagsBuilder().add(original.createFlags()).add(Flag.RECENT).build());
        return setInMailbox(mailbox, original);
    }

    @Override
    public Optional<MessageUid> getLastUid(Mailbox mailbox) throws MailboxException {
        return uidProvider.lastUid(mailbox);
    }

    @Override
    public Flags getApplicableFlag(Mailbox mailbox) {
        return ApplicableFlagBuilder.builder()
            .add(applicableFlagDAO.retrieveApplicableFlag((CassandraId) mailbox.getMailboxId())
                .defaultIfEmpty(new Flags())
                .block())
            .build();
    }

    private MessageMetaData setInMailbox(Mailbox mailbox, MailboxMessage message) throws MailboxException {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();
        return block(addUidAndModseq(message, mailboxId)
            .flatMap(messageWithUidAndModseq -> insertIds(messageWithUidAndModseq, mailboxId)
                .thenReturn(messageWithUidAndModseq))
            .map(MailboxMessage::metaData));
    }

    private Mono<Void> save(Mailbox mailbox, MailboxMessage message) throws MailboxException {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();
        return messageDAO.save(message)
            .thenEmpty(insertIds(message, mailboxId));
    }

    private Mono<Void> insertIds(MailboxMessage message, CassandraId mailboxId) {
        ComposedMessageIdWithMetaData composedMessageIdWithMetaData = ComposedMessageIdWithMetaData.builder()
                .composedMessageId(new ComposedMessageId(mailboxId, message.getMessageId(), message.getUid()))
                .flags(message.createFlags())
                .modSeq(message.getModSeq())
                .build();
        return imapUidDAO.insert(composedMessageIdWithMetaData)
            .then(Flux.merge(
                messageIdDAO.insert(composedMessageIdWithMetaData)
                    .retryWhen(Retry.backoff(MAX_RETRY, MIN_RETRY_BACKOFF).maxBackoff(MAX_RETRY_BACKOFF)),
                indexTableHandler.updateIndexOnAdd(message, mailboxId))
            .then());
    }


    private Mono<FlagsUpdateStageResult> tryFlagsUpdate(FlagsUpdateCalculator flagUpdateCalculator, ModSeq newModSeq, ComposedMessageIdWithMetaData oldMetaData) {
        Flags oldFlags = oldMetaData.getFlags();
        Flags newFlags = flagUpdateCalculator.buildNewFlags(oldFlags);

        if (identicalFlags(oldFlags, newFlags)) {
            return Mono.just(FlagsUpdateStageResult.success(UpdatedFlags.builder()
                .uid(oldMetaData.getComposedMessageId().getUid())
                .modSeq(oldMetaData.getModSeq())
                .oldFlags(oldFlags)
                .newFlags(newFlags)
                .build()));
        }

        return updateFlags(oldMetaData, newFlags, newModSeq)
            .map(success -> {
                if (success) {
                    return FlagsUpdateStageResult.success(UpdatedFlags.builder()
                        .uid(oldMetaData.getComposedMessageId().getUid())
                        .modSeq(newModSeq)
                        .oldFlags(oldFlags)
                        .newFlags(newFlags)
                        .build());
                } else {
                    return FlagsUpdateStageResult.fail(oldMetaData.getComposedMessageId().getUid());
                }
            });
    }

    private boolean identicalFlags(Flags oldFlags, Flags newFlags) {
        return oldFlags.equals(newFlags);
    }

    private Mono<Boolean> updateFlags(ComposedMessageIdWithMetaData oldMetadata, Flags newFlags, ModSeq newModSeq) {
        ComposedMessageIdWithMetaData newMetadata = ComposedMessageIdWithMetaData.builder()
                .composedMessageId(oldMetadata.getComposedMessageId())
                .modSeq(newModSeq)
                .flags(newFlags)
                .build();
        return imapUidDAO.updateMetadata(newMetadata, oldMetadata.getModSeq())
            .flatMap(success -> {
                if (success) {
                    return messageIdDAO.updateMetadata(newMetadata).thenReturn(true);
                } else {
                    return Mono.just(false);
                }
            });
    }
}

/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.copycat.server.state;

import io.atomix.copycat.client.response.Response;
import io.atomix.copycat.server.CopycatServer;
import io.atomix.copycat.server.request.AppendRequest;
import io.atomix.copycat.server.response.AppendResponse;
import io.atomix.copycat.server.storage.entry.ConfigurationEntry;
import io.atomix.copycat.server.storage.entry.ConnectEntry;
import io.atomix.copycat.server.storage.entry.Entry;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The passive state receives {@link AppendRequest}s from followers and maintains a state machine.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
class PassiveState extends ReserveState {
  private final Queue<AtomicInteger> counterPool = new ArrayDeque<>();

  public PassiveState(ServerState context) {
    super(context);
  }

  @Override
  public CopycatServer.State type() {
    return CopycatServer.State.PASSIVE;
  }

  @Override
  protected CompletableFuture<AppendResponse> append(AppendRequest request) {
    context.checkThread();

    // If the request indicates a term that is greater than the current term then
    // assign that term and leader to the current context and step down as leader.
    if (request.term() > context.getTerm() || (request.term() == context.getTerm() && context.getLeader() == null)) {
      context.setTerm(request.term());
      context.setLeader(request.leader());
      context.heartbeat();
    }

    return CompletableFuture.completedFuture(logResponse(handleAppend(logRequest(request))));
  }

  /**
   * Handles an AppendRequest.
   */
  protected AppendResponse handleAppend(AppendRequest request) {
    // If the request term is less than the current term then immediately
    // reply false and return our current term. The leader will receive
    // the updated term and step down.
    if (request.term() < context.getTerm()) {
      LOGGER.warn("{} - Rejected {}: request term is less than the current term ({})", context.getMember().serverAddress(), request, context.getTerm());
      return AppendResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withSucceeded(false)
        .withLogIndex(context.getLog().lastIndex())
        .build();
    } else if (request.logIndex() != 0 && request.logTerm() != 0) {
      return doCheckPreviousEntry(request);
    } else {
      return doAppendEntries(request);
    }
  }

  /**
   * Checks the previous log entry for consistency.
   */
  protected AppendResponse doCheckPreviousEntry(AppendRequest request) {
    if (request.logIndex() != 0 && context.getLog().isEmpty()) {
      LOGGER.warn("{} - Rejected {}: Previous index ({}) is greater than the local log's last index ({})", context.getMember().serverAddress(), request, request.logIndex(), context.getLog().lastIndex());
      return AppendResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withSucceeded(false)
        .withLogIndex(context.getLog().lastIndex())
        .build();
    } else if (request.logIndex() != 0 && context.getLog().lastIndex() != 0 && request.logIndex() > context.getLog().lastIndex()) {
      LOGGER.warn("{} - Rejected {}: Previous index ({}) is greater than the local log's last index ({})", context.getMember().serverAddress(), request, request.logIndex(), context.getLog().lastIndex());
      return AppendResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withSucceeded(false)
        .withLogIndex(context.getLog().lastIndex())
        .build();
    }

    // If the previous entry term doesn't match the local previous term then reject the request.
    try (Entry entry = context.getLog().get(request.logIndex())) {
      if (entry == null || entry.getTerm() != request.logTerm()) {
        LOGGER.warn("{} - Rejected {}: Request log term does not match local log term {} for the same entry", context.getMember().serverAddress(), request, entry != null ? entry.getTerm() : "unknown");
        return AppendResponse.builder()
          .withStatus(Response.Status.OK)
          .withTerm(context.getTerm())
          .withSucceeded(false)
          .withLogIndex(request.logIndex() <= context.getLog().lastIndex() ? request.logIndex() - 1 : context.getLog().lastIndex())
          .build();
      } else {
        return doAppendEntries(request);
      }
    }
  }

  /**
   * Appends entries to the local log.
   */
  protected AppendResponse doAppendEntries(AppendRequest request) {
    // If the log contains entries after the request's previous log index
    // then remove those entries to be replaced by the request entries.
    if (!request.entries().isEmpty()) {

      // Iterate through request entries and append them to the log.
      for (Entry entry : request.entries()) {
        // If the entry index is greater than the last log index, skip missing entries.
        if (context.getLog().lastIndex() < entry.getIndex()) {
          context.getLog().skip(entry.getIndex() - context.getLog().lastIndex() - 1).append(entry);
          LOGGER.debug("{} - Appended {} to log at index {}", context.getMember().serverAddress(), entry, entry.getIndex());
        } else {
          // Compare the term of the received entry with the matching entry in the log.
          try (Entry match = context.getLog().get(entry.getIndex())) {
            if (match != null) {
              if (entry.getTerm() != match.getTerm()) {
                // We found an invalid entry in the log. Remove the invalid entry and append the new entry.
                // If appending to the log fails, apply commits and reply false to the append request.
                LOGGER.warn("{} - Appended entry term does not match local log, removing incorrect entries", context.getMember().serverAddress());
                context.getLog().truncate(entry.getIndex() - 1).append(entry);
                LOGGER.debug("{} - Appended {} to log at index {}", context.getMember().serverAddress(), entry, entry.getIndex());
              }
            } else {
              context.getLog().truncate(entry.getIndex() - 1).append(entry);
              LOGGER.debug("{} - Appended {} to log at index {}", context.getMember().serverAddress(), entry, entry.getIndex());
            }
          }
        }

        // If the entry is a configuration entry then immediately configure the cluster.
        if (entry instanceof ConfigurationEntry) {
          ConfigurationEntry configurationEntry = (ConfigurationEntry) entry;

          // Store the previous member type for comparison to determine whether this node should transition.
          Member.Type previousType = context.getMemberState().getMemberType();

          // Configure the cluster membership.
          context.configure(entry.getIndex(), configurationEntry.getMembers());

          // If the local member type changed, transition the state as appropriate.
          // ACTIVE servers are initialized to the FOLLOWER state but may transition to CANDIDATE or LEADER.
          // PASSIVE servers are transitioned to the PASSIVE state.
          // RESERVE servers are transitioned to the RESERVE state.
          if (previousType != context.getMemberState().getMemberType()) {
            Member.Type type = context.getMemberState().getMemberType();
            if (type != null) {
              switch (context.getMemberState().getMemberType()) {
                case ACTIVE:
                  transition(CopycatServer.State.FOLLOWER);
                  break;
                case PASSIVE:
                  transition(CopycatServer.State.PASSIVE);
                  break;
                case RESERVE:
                  transition(CopycatServer.State.RESERVE);
                  break;
              }
            } else {
              transition(CopycatServer.State.INACTIVE);
            }
          }
        }
        // If the entry is a ConnectEntry then immediately register the connection. This is necessary to ensure
        // that connection information is updated even before the commitment of an earlier entry for cases where
        // the connection information is needed to complete the application of the entry to the state machine,
        // such as in cases where a command triggers linearizable events that must be completed synchronously.
        else if (entry instanceof ConnectEntry) {
          ConnectEntry connectEntry = (ConnectEntry) entry;
          context.getStateMachine().executor().context().sessions().registerAddress(connectEntry.getSession(), connectEntry.getAddress());
        }
      }
    }

    // If we've made it this far, apply commits and send a successful response.
    long commitIndex = request.commitIndex();
    context.getThreadContext().execute(() -> applyCommits(commitIndex)).thenRun(() -> {
      context.getLog().compactor().minorIndex(context.getLastCompleted());
    });

    return AppendResponse.builder()
      .withStatus(Response.Status.OK)
      .withTerm(context.getTerm())
      .withSucceeded(true)
      .withLogIndex(context.getLog().lastIndex())
      .build();
  }

  /**
   * Applies commits to the local state machine.
   */
  protected CompletableFuture<Void> applyCommits(long commitIndex) {
    // Set the commit index, ensuring that the index cannot be decreased.
    context.setCommitIndex(Math.max(context.getCommitIndex(), commitIndex));

    // The entries to be applied to the state machine are the difference between min(lastIndex, commitIndex) and lastApplied.
    long lastIndex = context.getLog().lastIndex();
    long lastApplied = context.getLastApplied();

    long effectiveIndex = Math.min(lastIndex, context.getCommitIndex());

    // If the effective commit index is greater than the last index applied to the state machine then apply remaining entries.
    if (effectiveIndex > lastApplied) {
      long entriesToApply = effectiveIndex - lastApplied;
      LOGGER.debug("{} - Applying {} commits", context.getMember().serverAddress(), entriesToApply);

      CompletableFuture<Void> future = new CompletableFuture<>();

      // Rather than composing all futures into a single future, use a counter to count completions in order to preserve memory.
      AtomicInteger counter = getCounter();

      for (long i = lastApplied + 1; i <= effectiveIndex; i++) {
        Entry entry = context.getLog().get(i);
        if (entry != null) {
          applyEntry(entry).whenComplete((result, error) -> {
            if (isOpen() && error != null) {
              LOGGER.info("{} - An application error occurred: {}", context.getMember().serverAddress(), error.getMessage());
            }

            if (counter.incrementAndGet() == entriesToApply) {
              future.complete(null);
              recycleCounter(counter);
            }
            entry.release();
          });
        }
      }
      return future;
    }
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Gets a counter from the counter pool.
   */
  private AtomicInteger getCounter() {
    AtomicInteger counter = counterPool.poll();
    if (counter == null)
      counter = new AtomicInteger();
    counter.set(0);
    return counter;
  }

  /**
   * Adds a used counter to the counter pool.
   */
  private void recycleCounter(AtomicInteger counter) {
    counterPool.add(counter);
  }

  /**
   * Applies an entry to the state machine.
   */
  protected CompletableFuture<?> applyEntry(Entry entry) {
    LOGGER.debug("{} - Applying {}", context.getMember().serverAddress(), entry);
    return context.getStateMachine().apply(entry);
  }

}

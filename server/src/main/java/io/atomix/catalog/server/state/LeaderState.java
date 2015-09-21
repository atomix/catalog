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
package io.atomix.catalog.server.state;

import io.atomix.catalog.client.Command;
import io.atomix.catalog.client.Query;
import io.atomix.catalog.client.error.RaftError;
import io.atomix.catalog.client.error.RaftException;
import io.atomix.catalog.client.request.CommandRequest;
import io.atomix.catalog.client.request.KeepAliveRequest;
import io.atomix.catalog.client.request.QueryRequest;
import io.atomix.catalog.client.request.RegisterRequest;
import io.atomix.catalog.client.response.*;
import io.atomix.catalog.server.RaftServer;
import io.atomix.catalog.server.request.*;
import io.atomix.catalog.server.response.*;
import io.atomix.catalog.server.storage.*;
import io.atomix.catalyst.transport.Address;
import io.atomix.catalyst.util.concurrent.Scheduled;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Leader state.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
final class LeaderState extends ActiveState {
  private static final int MAX_BATCH_SIZE = 1024 * 28;
  private Scheduled currentTimer;
  private final Replicator replicator = new Replicator();

  public LeaderState(ServerContext context) {
    super(context);
  }

  @Override
  public RaftServer.State type() {
    return RaftServer.State.LEADER;
  }

  @Override
  public synchronized CompletableFuture<AbstractState> open() {
    // Schedule the initial entries commit to occur after the state is opened. Attempting any communication
    // within the open() method will result in a deadlock since RaftProtocol calls this method synchronously.
    // What is critical about this logic is that the heartbeat timer not be started until a no-op entry has been committed.
    context.getContext().execute(this::commitEntries).whenComplete((result, error) -> {
      if (isOpen() && error == null) {
        startHeartbeatTimer();
      }
    });

    return super.open()
      .thenRun(this::takeLeadership)
      .thenApply(v -> this);
  }

  /**
   * Sets the current node as the cluster leader.
   */
  private void takeLeadership() {
    context.setLeader(context.getAddress().hashCode());
  }

  /**
   * Commits a no-op entry to the log, ensuring any entries from a previous term are committed.
   */
  private CompletableFuture<Void> commitEntries() {
    final long term = context.getTerm();
    final long index;
    try (NoOpEntry entry = context.getLog().create(NoOpEntry.class)) {
      entry.setTerm(term).setTimestamp(System.currentTimeMillis());
      index = context.getLog().append(entry);
    }

    CompletableFuture<Void> future = new CompletableFuture<>();
    replicator.commit(index).whenComplete((resultIndex, error) -> {
      context.checkThread();
      if (isOpen()) {
        if (error == null) {
          applyEntries(resultIndex);
          future.complete(null);
        } else {
          transition(RaftServer.State.FOLLOWER);
        }
      }
    });
    return future;
  }

  /**
   * Applies all unapplied entries to the log.
   */
  private void applyEntries(long index) {
    if (!context.getLog().isEmpty()) {
      int count = 0;
      for (long lastApplied = Math.max(context.getLastApplied(), context.getLog().firstIndex()); lastApplied <= index; lastApplied++) {
        Entry entry = context.getLog().get(lastApplied);
        if (entry != null) {
          context.getStateMachine().apply(entry).whenComplete((result, error) -> {
            if (isOpen() && error != null) {
              LOGGER.info("{} - An application error occurred: {}", context.getAddress(), error.getMessage());
            }
            entry.release();
          });
        }
        count++;
      }

      LOGGER.debug("{} - Applied {} entries to log", context.getAddress(), count);
    }
  }

  /**
   * Starts heartbeating all cluster members.
   */
  private void startHeartbeatTimer() {
    // Set a timer that will be used to periodically synchronize with other nodes
    // in the cluster. This timer acts as a heartbeat to ensure this node remains
    // the leader.
    LOGGER.debug("{} - Starting heartbeat timer", context.getAddress());
    currentTimer = context.getContext().schedule(this::heartbeatMembers, Duration.ZERO, context.getHeartbeatInterval());
  }

  /**
   * Sends a heartbeat to all members of the cluster.
   */
  private void heartbeatMembers() {
    context.checkThread();
    if (isOpen()) {
      replicator.commit();
    }
  }

  @Override
  public CompletableFuture<JoinResponse> join(final JoinRequest request) {
    try {
      context.checkThread();
      logRequest(request);

      if (context.getCluster().getMember(request.member().hashCode()) != null) {
        return CompletableFuture.completedFuture(logResponse(JoinResponse.builder()
          .withStatus(Response.Status.OK)
          .withVersion(context.getCluster().getVersion())
          .withActiveMembers(context.getCluster().buildActiveMembers())
          .withPassiveMembers(context.getCluster().buildPassiveMembers())
          .build()));
      }

      final long term = context.getTerm();
      final long index;

      Collection<Address> activeMembers = context.getCluster().buildActiveMembers();
      Collection<Address> passiveMembers = context.getCluster().buildPassiveMembers();
      passiveMembers.add(request.member());

      try (ConfigurationEntry entry = context.getLog().create(ConfigurationEntry.class)) {
        entry.setTerm(term)
          .setActive(activeMembers)
          .setPassive(passiveMembers);
        index = context.getLog().append(entry);
        LOGGER.debug("{} - Appended {} to log at index {}", context.getAddress(), entry, index);

        // Immediately apply the configuration change. No need to validate whether this node was changed
        // to PASSIVE since it's the leader and would have already transitioned to the LEAVE state if
        // it were leaving the cluster.
        context.getCluster().configure(entry.getIndex(), entry.getActive(), entry.getPassive());
      }

      CompletableFuture<JoinResponse> future = new CompletableFuture<>();
      replicator.commit(index).whenComplete((commitIndex, commitError) -> {
        context.checkThread();
        if (isOpen()) {
          if (commitError == null) {
            future.complete(logResponse(JoinResponse.builder()
              .withStatus(Response.Status.OK)
              .withVersion(index)
              .withActiveMembers(activeMembers)
              .withPassiveMembers(passiveMembers)
              .build()));
          } else {
            future.complete(logResponse(JoinResponse.builder()
              .withStatus(Response.Status.ERROR)
              .withError(RaftError.Type.INTERNAL_ERROR)
              .build()));
          }
        }
      });
      return future;
    } finally {
      request.release();
    }
  }

  @Override
  public CompletableFuture<LeaveResponse> leave(final LeaveRequest request) {
    try {
      context.checkThread();
      logRequest(request);

      if (context.getCluster().getMember(request.member().hashCode()) == null) {
        return CompletableFuture.completedFuture(logResponse(LeaveResponse.builder()
          .withStatus(Response.Status.OK)
          .build()));
      }

      final long term = context.getTerm();
      final long index;

      Collection<Address> activeMembers = context.getCluster().buildActiveMembers();
      activeMembers.remove(request.member());

      Collection<Address> passiveMembers = context.getCluster().buildPassiveMembers();
      passiveMembers.remove(request.member());

      try (ConfigurationEntry entry = context.getLog().create(ConfigurationEntry.class)) {
        entry.setTerm(term)
          .setActive(activeMembers)
          .setPassive(passiveMembers);
        index = context.getLog().append(entry);
        LOGGER.debug("{} - Appended {} to log at index {}", context.getAddress(), entry, index);

        // Immediately apply the configuration change. No need to validate whether this node was changed
        // to PASSIVE since it's the leader and would have already transitioned to the LEAVE state if
        // it were leaving the cluster.
        context.getCluster().configure(entry.getIndex(), entry.getActive(), entry.getPassive());
      }

      CompletableFuture<LeaveResponse> future = new CompletableFuture<>();
      replicator.commit(index).whenComplete((commitIndex, commitError) -> {
        context.checkThread();
        if (isOpen()) {
          if (commitError == null) {
            future.complete(logResponse(LeaveResponse.builder()
              .withStatus(Response.Status.OK)
              .build()));
          } else {
            future.complete(logResponse(LeaveResponse.builder()
              .withStatus(Response.Status.ERROR)
              .withError(RaftError.Type.INTERNAL_ERROR)
              .build()));
          }
        }
      });
      return future;
    } finally {
      request.release();
    }
  }

  @Override
  public CompletableFuture<PollResponse> poll(final PollRequest request) {
    try {
      return CompletableFuture.completedFuture(logResponse(PollResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withAccepted(false)
        .build()));
    } finally {
      request.release();
    }
  }

  @Override
  public CompletableFuture<VoteResponse> vote(final VoteRequest request) {
    try {
      if (request.term() > context.getTerm()) {
        LOGGER.debug("{} - Received greater term", context.getAddress());
        context.setLeader(0);
        transition(RaftServer.State.FOLLOWER);
        request.acquire();
        return super.vote(request);
      } else {
        return CompletableFuture.completedFuture(logResponse(VoteResponse.builder()
          .withStatus(Response.Status.OK)
          .withTerm(context.getTerm())
          .withVoted(false)
          .build()));
      }
    } finally {
      request.release();
    }
  }

  @Override
  public CompletableFuture<AppendResponse> append(final AppendRequest request) {
    try {
      context.checkThread();
      if (request.term() > context.getTerm()) {
        request.acquire();
        return super.append(request);
      } else if (request.term() < context.getTerm()) {
        return CompletableFuture.completedFuture(logResponse(AppendResponse.builder()
          .withStatus(Response.Status.OK)
          .withTerm(context.getTerm())
          .withSucceeded(false)
          .withLogIndex(context.getLog().lastIndex())
          .build()));
      } else {
        context.setLeader(request.leader());
        transition(RaftServer.State.FOLLOWER);
        request.acquire();
        return super.append(request);
      }
    } finally {
      request.release();
    }
  }

  @Override
  protected CompletableFuture<CommandResponse> command(final CommandRequest request) {
    CompletableFuture<CommandResponse> future = new CompletableFuture<>();

    Command command = request.command();

    final long term = context.getTerm();
    final long timestamp = System.currentTimeMillis();
    final long index;

    try {
      context.checkThread();
      logRequest(request);

      // Create a CommandEntry and append it to the log.
      try (CommandEntry entry = context.getLog().create(CommandEntry.class)) {
        entry.setTerm(term)
          .setTimestamp(timestamp)
          .setSession(request.session())
          .setSequence(request.sequence())
          .setCommand(command);
        index = context.getLog().append(entry);
        LOGGER.debug("{} - Appended entry to log at index {}", context.getAddress(), index);
      }
    } finally {
      request.release();
    }

    replicator.commit(index).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        if (commitError == null) {
          CommandEntry entry = context.getLog().get(index);
          applyEntry(entry).whenComplete((result, error) -> {
            if (isOpen()) {
              if (error == null) {
                future.complete(logResponse(CommandResponse.builder()
                  .withStatus(Response.Status.OK)
                  .withVersion(entry.getIndex())
                  .withResult(result)
                  .build()));
              } else if (error instanceof RaftException) {
                future.complete(logResponse(CommandResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withVersion(entry.getIndex())
                  .withError(((RaftException) error).getType())
                  .build()));
              } else {
                future.complete(logResponse(CommandResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withVersion(entry.getIndex())
                  .withError(RaftError.Type.INTERNAL_ERROR)
                  .build()));
              }
            }
            entry.release();
          });
        } else {
          future.complete(logResponse(CommandResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(RaftError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });

    return future;
  }

  @Override
  protected CompletableFuture<QueryResponse> query(final QueryRequest request) {

    Query query = request.query();

    final long timestamp = System.currentTimeMillis();
    final long index = context.getCommitIndex();

    try {
      context.checkThread();
      logRequest(request);

      QueryEntry entry = context.getLog().create(QueryEntry.class)
        .setIndex(index)
        .setTerm(context.getTerm())
        .setTimestamp(timestamp)
        .setSession(request.session())
        .setSequence(request.sequence())
        .setVersion(request.version())
        .setQuery(query);

      Query.ConsistencyLevel consistency = query.consistency();
      if (consistency == null)
        return submitQueryLinearizable(entry);

      switch (consistency) {
        case CAUSAL:
        case SEQUENTIAL:
          return submitQueryLocal(entry);
        case BOUNDED_LINEARIZABLE:
          return submitQueryBoundedLinearizable(entry);
        case LINEARIZABLE:
          return submitQueryLinearizable(entry);
        default:
          throw new IllegalStateException("unknown consistency level");
      }
    } finally {
      request.release();
    }
  }

  /**
   * Submits a query with serializable consistency.
   */
  private CompletableFuture<QueryResponse> submitQueryLocal(QueryEntry entry) {
    return applyQuery(entry, new CompletableFuture<>());
  }

  /**
   * Submits a query with lease bounded linearizable consistency.
   */
  private CompletableFuture<QueryResponse> submitQueryBoundedLinearizable(QueryEntry entry) {
    long commitTime = replicator.commitTime();
    if (System.currentTimeMillis() - commitTime < context.getElectionTimeout().toMillis()) {
      return submitQueryLocal(entry);
    } else {
      return submitQueryLinearizable(entry);
    }
  }

  /**
   * Submits a query with strict linearizable consistency.
   */
  private CompletableFuture<QueryResponse> submitQueryLinearizable(QueryEntry entry) {
    CompletableFuture<QueryResponse> future = new CompletableFuture<>();
    replicator.commit().whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        if (commitError == null) {
          entry.acquire();
          applyQuery(entry, future);
        } else {
          future.complete(logResponse(QueryResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(RaftError.Type.COMMAND_ERROR)
            .build()));
        }
      }
      entry.release();
    });
    return future;
  }

  /**
   * Applies a query to the state machine.
   */
  private CompletableFuture<QueryResponse> applyQuery(QueryEntry entry, CompletableFuture<QueryResponse> future) {
    // In the case of the leader, the state machine is always up to date, so no queries will be queued and all query
    // versions will be the last applied index.
    final long version = context.getStateMachine().getLastApplied();
    applyEntry(entry).whenComplete((result, error) -> {
      if (isOpen()) {
        if (error == null) {
          future.complete(logResponse(QueryResponse.builder()
            .withStatus(Response.Status.OK)
            .withVersion(version)
            .withResult(result)
            .build()));
        } else if (error instanceof RaftException) {
          future.complete(logResponse(QueryResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(((RaftException) error).getType())
            .build()));
        } else {
          future.complete(logResponse(QueryResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(RaftError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
      entry.release();
    });
    return future;
  }

  @Override
  protected CompletableFuture<RegisterResponse> register(RegisterRequest request) {
    final long timestamp = System.currentTimeMillis();
    final long index;
    final long timeout = context.getSessionTimeout().toMillis();

    try {
      context.checkThread();
      logRequest(request);

      try (RegisterEntry entry = context.getLog().create(RegisterEntry.class)) {
        entry.setTerm(context.getTerm());
        entry.setTimestamp(timestamp);
        entry.setConnection(request.connection());
        entry.setTimeout(timeout);
        index = context.getLog().append(entry);
        LOGGER.debug("{} - Appended {}", context.getAddress(), entry);
      }
    } finally {
      request.release();
    }

    CompletableFuture<RegisterResponse> future = new CompletableFuture<>();
    replicator.commit(index).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        if (commitError == null) {
          RegisterEntry entry = context.getLog().get(index);
          applyEntry(entry).whenComplete((sessionId, sessionError) -> {
            if (isOpen()) {
              if (sessionError == null) {
                future.complete(logResponse(RegisterResponse.builder()
                  .withStatus(Response.Status.OK)
                  .withSession((Long) sessionId)
                  .withTimeout(timeout)
                  .withMembers(context.getCluster().buildActiveMembers())
                  .build()));
              } else if (sessionError instanceof RaftException) {
                future.complete(logResponse(RegisterResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(((RaftException) sessionError).getType())
                  .build()));
              } else {
                future.complete(logResponse(RegisterResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(RaftError.Type.INTERNAL_ERROR)
                  .build()));
              }
            }
            entry.release();
          });
        } else {
          future.complete(logResponse(RegisterResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(RaftError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });
    return future;
  }

  @Override
  protected CompletableFuture<KeepAliveResponse> keepAlive(KeepAliveRequest request) {
    final long timestamp = System.currentTimeMillis();
    final long index;

    try {
      context.checkThread();
      logRequest(request);

      try (KeepAliveEntry entry = context.getLog().create(KeepAliveEntry.class)) {
        entry.setTerm(context.getTerm())
          .setSession(request.session())
          .setCommandSequence(request.commandSequence())
          .setEventVersion(request.eventVersion())
          .setEventSequence(request.eventSequence())
          .setTimestamp(timestamp);
        index = context.getLog().append(entry);
        LOGGER.debug("{} - Appended {}", context.getAddress(), entry);
      }
    } finally {
      request.release();
    }

    CompletableFuture<KeepAliveResponse> future = new CompletableFuture<>();
    replicator.commit(index).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        if (commitError == null) {
          KeepAliveEntry entry = context.getLog().get(index);
          applyEntry(entry).whenCompleteAsync((sessionResult, sessionError) -> {
            if (isOpen()) {
              if (sessionError == null) {
                future.complete(logResponse(KeepAliveResponse.builder()
                  .withStatus(Response.Status.OK)
                  .withMembers(context.getCluster().buildActiveMembers())
                  .build()));
              } else if (sessionError instanceof RaftException) {
                future.complete(logResponse(KeepAliveResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(((RaftException) sessionError).getType())
                  .build()));
              } else {
                future.complete(logResponse(KeepAliveResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(RaftError.Type.INTERNAL_ERROR)
                  .build()));
              }
            }
            entry.release();
          }, context.getContext().executor());
        } else {
          future.complete(logResponse(KeepAliveResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(RaftError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });
    return future;
  }

  /**
   * Cancels the ping timer.
   */
  private void cancelPingTimer() {
    if (currentTimer != null) {
      LOGGER.debug("{} - Cancelling heartbeat timer", context.getAddress());
      currentTimer.cancel();
    }
  }

  @Override
  public synchronized CompletableFuture<Void> close() {
    return super.close().thenRun(this::cancelPingTimer);
  }

  /**
   * Log replicator.
   */
  private class Replicator {
    private final Set<MemberState> committing = new HashSet<>();
    private long commitTime;
    private CompletableFuture<Long> commitFuture;
    private CompletableFuture<Long> nextCommitFuture;
    private final TreeMap<Long, CompletableFuture<Long>> commitFutures = new TreeMap<>();

    /**
     * Returns the current quorum index.
     *
     * @return The current quorum index.
     */
    private int quorumIndex() {
      return context.getCluster().getQuorum() - 1;
    }

    /**
     * Triggers a commit.
     *
     * @return A completable future to be completed the next time entries are committed to a majority of the cluster.
     */
    private CompletableFuture<Long> commit() {
      if (context.getCluster().getMembers().size() == 1)
        return CompletableFuture.completedFuture(null);

      if (commitFuture == null) {
        commitFuture = new CompletableFuture<>();
        commitTime = System.currentTimeMillis();
        for (MemberState member : context.getCluster().getMembers()) {
          commit(member);
        }
        return commitFuture;
      } else if (nextCommitFuture == null) {
        nextCommitFuture = new CompletableFuture<>();
        return nextCommitFuture;
      } else {
        return nextCommitFuture;
      }
    }

    /**
     * Registers a commit handler for the given commit index.
     *
     * @param index The index for which to register the handler.
     * @return A completable future to be completed once the given log index has been committed.
     */
    private CompletableFuture<Long> commit(long index) {
      if (index == 0)
        return commit();

      if (context.getCluster().getActiveMembers().isEmpty()) {
        context.setCommitIndex(index);
        return CompletableFuture.completedFuture(index);
      }

      return commitFutures.computeIfAbsent(index, i -> {
        for (MemberState member : context.getCluster().getMembers()) {
          commit(member);
        }
        return new CompletableFuture<>();
      });
    }

    /**
     * Returns the last time a majority of the cluster was contacted.
     */
    private long commitTime() {
      return context.getCluster()
        .getActiveMembers((m1, m2) -> (int) (m2.getTime() - m1.getTime()))
        .get(quorumIndex())
        .getTime();
    }

    /**
     * Sets a commit time.
     */
    private void commitTime(MemberState member) {
      member.setTime(System.currentTimeMillis());

      // Sort the list of commit times. Use the quorum index to get the last time the majority of the cluster
      // was contacted. If the current commitFuture's time is less than the commit time then trigger the
      // commit future and reset it to the next commit future.
      long commitTime = commitTime();
      if (commitFuture != null && this.commitTime <= commitTime) {
        commitFuture.complete(null);
        commitFuture = nextCommitFuture;
        nextCommitFuture = null;
        if (commitFuture != null) {
          this.commitTime = System.currentTimeMillis();
          for (MemberState replica : context.getCluster().getMembers()) {
            commit(replica);
          }
        }
      }
    }

    /**
     * Checks whether any futures can be completed.
     */
    private void commitEntries() {
      context.checkThread();

      // Sort the list of replicas, order by the last index that was replicated
      // to the replica. This will allow us to determine the median index
      // for all known replicated entries across all cluster members.
      List<MemberState> members = context.getCluster().getActiveMembers((m1, m2) ->
        Long.compare(m2.getMatchIndex() != 0 ? m2.getMatchIndex() : 0l, m1.getMatchIndex() != 0 ? m1.getMatchIndex() : 0l));

      // Set the current commit index as the median replicated index.
      // Since replicas is a list with zero based indexes, use the negation of
      // the required quorum count to get the index of the replica with the least
      // possible quorum replication. That replica's match index is the commit index.
      // Set the commit index. Once the commit index has been set we can run
      // all tasks up to the given commit.
      long commitIndex = members.get(quorumIndex()).getMatchIndex();
      long globalIndex = members.get(members.size() - 1).getMatchIndex();
      if (commitIndex > 0) {
        context.setCommitIndex(commitIndex);
        context.setGlobalIndex(globalIndex);
        SortedMap<Long, CompletableFuture<Long>> futures = commitFutures.headMap(commitIndex, true);
        for (Map.Entry<Long, CompletableFuture<Long>> entry : futures.entrySet()) {
          entry.getValue().complete(entry.getKey());
        }
        futures.clear();
      }
    }

    /**
     * Triggers a commit for the replica.
     */
    private void commit(MemberState member) {
      if (!committing.contains(member) && isOpen()) {
        // If the log is empty then send an empty commit.
        // If the next index hasn't yet been set then we send an empty commit first.
        // If the next index is greater than the last index then send an empty commit.
        if (context.getLog().isEmpty() || member.getNextIndex() > context.getLog().lastIndex()) {
          emptyCommit(member);
        } else {
          entriesCommit(member);
        }
      }
    }

    /**
     * Gets the previous index.
     */
    private long getPrevIndex(MemberState member) {
      return member.getNextIndex() - 1;
    }

    /**
     * Gets the previous entry.
     */
    private RaftEntry getPrevEntry(MemberState member, long prevIndex) {
      if (prevIndex > 0) {
        return context.getLog().get(prevIndex);
      }
      return null;
    }

    /**
     * Performs an empty commit.
     */
    private void emptyCommit(MemberState member) {
      long prevIndex = getPrevIndex(member);
      RaftEntry prevEntry = getPrevEntry(member, prevIndex);

      AppendRequest.Builder builder = AppendRequest.builder()
        .withTerm(context.getTerm())
        .withLeader(context.getAddress().hashCode())
        .withLogIndex(prevIndex)
        .withLogTerm(prevEntry != null ? prevEntry.getTerm() : 0)
        .withCommitIndex(context.getCommitIndex())
        .withGlobalIndex(context.getGlobalIndex());

      commit(member, builder.build(), false);
    }

    /**
     * Performs a commit with entries.
     */
    private void entriesCommit(MemberState member) {
      long prevIndex = getPrevIndex(member);
      RaftEntry prevEntry = getPrevEntry(member, prevIndex);

      AppendRequest.Builder builder = AppendRequest.builder()
        .withTerm(context.getTerm())
        .withLeader(context.getAddress().hashCode())
        .withLogIndex(prevIndex)
        .withLogTerm(prevEntry != null ? prevEntry.getTerm() : 0)
        .withCommitIndex(context.getCommitIndex())
        .withGlobalIndex(context.getGlobalIndex());

      if (!context.getLog().isEmpty()) {
        long index = prevIndex != 0 ? prevIndex + 1 : context.getLog().firstIndex();

        int size = 0;
        while (size < MAX_BATCH_SIZE && index <= context.getLog().lastIndex()) {
          RaftEntry entry = context.getLog().get(index);
          if (entry != null && size + entry.size() <= MAX_BATCH_SIZE) {
            size += entry.size();
            builder.addEntry(entry);
          }
          index++;
        }
      }

      if (prevEntry != null) {
        prevEntry.release();
      }

      commit(member, builder.build(), true);
    }

    /**
     * Sends a commit message.
     */
    private void commit(MemberState member, AppendRequest request, boolean recursive) {
      committing.add(member);

      LOGGER.debug("{} - Sent {} to {}", context.getAddress(), request, member.getAddress());
      context.getConnections().getConnection(member.getAddress()).thenAccept(connection -> {
        connection.<AppendRequest, AppendResponse>send(request).whenComplete((response, error) -> {
          committing.remove(member);
          context.checkThread();

          if (isOpen()) {
            if (error == null) {
              LOGGER.debug("{} - Received {} from {}", context.getAddress(), response, member.getAddress());
              if (response.status() == Response.Status.OK) {
                // Update the commit time for the replica. This will cause heartbeat futures to be triggered.
                commitTime(member);

                // If replication succeeded then trigger commit futures.
                if (response.succeeded()) {
                  updateMatchIndex(member, response);
                  updateNextIndex(member);
                  updateConfiguration(member);

                  // If entries were committed to the replica then check commit indexes.
                  if (recursive) {
                    commitEntries();
                  }

                  // If there are more entries to send then attempt to send another commit.
                  if (hasMoreEntries(member)) {
                    commit();
                  }
                } else if (response.term() > context.getTerm()) {
                  context.setLeader(0);
                  transition(RaftServer.State.FOLLOWER);
                } else {
                  resetMatchIndex(member, response);
                  resetNextIndex(member);

                  // If there are more entries to send then attempt to send another commit.
                  if (hasMoreEntries(member)) {
                    commit();
                  }
                }
              } else if (response.term() > context.getTerm()) {
                LOGGER.debug("{} - Received higher term from {}", context.getAddress(), member.getAddress());
                context.setLeader(0);
                transition(RaftServer.State.FOLLOWER);
              } else {
                LOGGER.warn("{} - {}", context.getAddress(), response.error() != null ? response.error() : "");
              }
              response.release();
            } else {
              LOGGER.warn("{} - {}", context.getAddress(), error.getMessage());

              // Verify that the leader has contacted a majority of the cluster within the last two election timeouts.
              // If the leader is not able to contact a majority of the cluster within two election timeouts, assume
              // that a partition occurred and transition back to the FOLLOWER state.
              if (System.currentTimeMillis() - commitTime() > context.getElectionTimeout().toMillis() * 2) {
                LOGGER.warn("{} - Suspected network partition. Stepping down", context.getAddress());
                context.setLeader(0);
                transition(RaftServer.State.FOLLOWER);
              }
            }
          } else if (response != null) {
            response.release();
          }
        });
      });
    }

    /**
     * Returns a boolean value indicating whether there are more entries to send.
     */
    private boolean hasMoreEntries(MemberState member) {
      return member.getNextIndex() < context.getLog().lastIndex();
    }

    /**
     * Updates the match index when a response is received.
     */
    private void updateMatchIndex(MemberState member, AppendResponse response) {
      // If the replica returned a valid match index then update the existing match index. Because the
      // replicator pipelines replication, we perform a MAX(matchIndex, logIndex) to get the true match index.
      member.setMatchIndex(Math.max(member.getMatchIndex(), response.logIndex()));
    }

    /**
     * Updates the next index when the match index is updated.
     */
    private void updateNextIndex(MemberState member) {
      // If the match index was set, update the next index to be greater than the match index if necessary.
      // Note that because of pipelining append requests, the next index can potentially be much larger than
      // the match index. We rely on the algorithm to reject invalid append requests.
      member.setNextIndex(Math.max(member.getNextIndex(), Math.max(member.getMatchIndex() + 1, 1)));
    }

    /**
     * Updates the cluster configuration for the given member.
     */
    private void updateConfiguration(MemberState member) {
      if (context.getCluster().isPassiveMember(member) && member.getMatchIndex() >= context.getCommitIndex()) {
        Collection<Address> activeMembers = context.getCluster().buildActiveMembers();
        activeMembers.add(member.getAddress());

        Collection<Address> passiveMembers = context.getCluster().buildPassiveMembers();
        passiveMembers.remove(member.getAddress());

        try (ConfigurationEntry entry = context.getLog().create(ConfigurationEntry.class)) {
          entry.setTerm(context.getTerm())
            .setActive(activeMembers)
            .setPassive(passiveMembers);
          long index = context.getLog().append(entry);
          LOGGER.debug("{} - Appended {} to log at index {}", context.getAddress(), entry, index);

          // Immediately apply the configuration upon appending the configuration entry.
          context.getCluster().configure(entry.getIndex(), entry.getActive(), entry.getPassive());
        }
      }
    }

    /**
     * Resets the match index when a response fails.
     */
    private void resetMatchIndex(MemberState member, AppendResponse response) {
      member.setMatchIndex(response.logIndex());
      LOGGER.debug("{} - Reset match index for {} to {}", context.getAddress(), member, member.getMatchIndex());
    }

    /**
     * Resets the next index when a response fails.
     */
    private void resetNextIndex(MemberState member) {
      if (member.getMatchIndex() != 0) {
        member.setNextIndex(member.getMatchIndex() + 1);
      } else {
        member.setNextIndex(context.getLog().firstIndex());
      }
      LOGGER.debug("{} - Reset next index for {} to {}", context.getAddress(), member, member.getNextIndex());
    }
  }

}

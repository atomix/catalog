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

import io.atomix.catalyst.transport.Address;
import io.atomix.catalyst.transport.Connection;
import io.atomix.catalyst.util.concurrent.ComposableFuture;
import io.atomix.catalyst.util.concurrent.Scheduled;
import io.atomix.copycat.client.Command;
import io.atomix.copycat.client.Query;
import io.atomix.copycat.client.error.InternalException;
import io.atomix.copycat.client.error.RaftError;
import io.atomix.copycat.client.error.RaftException;
import io.atomix.copycat.client.request.*;
import io.atomix.copycat.client.response.*;
import io.atomix.copycat.server.CopycatServer;
import io.atomix.copycat.server.request.*;
import io.atomix.copycat.server.response.*;
import io.atomix.copycat.server.storage.entry.*;

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
  private long leaderTime = System.currentTimeMillis();
  private long leaderIndex;
  private long configuring;

  public LeaderState(ServerState context) {
    super(context);
  }

  @Override
  public CopycatServer.State type() {
    return CopycatServer.State.LEADER;
  }

  @Override
  public synchronized CompletableFuture<AbstractState> open() {
    // Schedule the initial entries commit to occur after the state is opened. Attempting any communication
    // within the open() method will result in a deadlock since RaftProtocol calls this method synchronously.
    // What is critical about this logic is that the heartbeat timer not be started until a no-op entry has been committed.
    context.getThreadContext().execute(this::commitEntries).whenComplete((result, error) -> {
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
    context.getLog().setLeader(context.getAddress().hashCode());
    context.getCluster().getMembers().forEach(m -> m.resetState(context.getLog()));
  }

  /**
   * Commits a no-op entry to the log, ensuring any entries from a previous term are committed.
   */
  private CompletableFuture<Void> commitEntries() {
    final long term = context.getLog().getTerm();
    final long index;
    try (NoOpEntry entry = context.getLog().createEntry(NoOpEntry.class)) {
      entry.setTerm(term)
        .setTimestamp(leaderTime);
      index = context.getLog().appendEntry(entry);
    }

    // Store the index at which the leader took command.
    leaderIndex = index;

    CompletableFuture<Void> future = new CompletableFuture<>();
    replicator.commit(index).whenComplete((resultIndex, error) -> {
      context.checkThread();
      if (isOpen()) {
        if (error == null) {
          applyEntries(resultIndex);
          future.complete(null);
        } else {
          transition(CopycatServer.State.FOLLOWER);
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
      for (long lastApplied = Math.max(context.getLog().getLastApplied(), context.getLog().getFirstIndex()); lastApplied <= index; lastApplied++) {
        Entry entry = context.getLog().getEntry(lastApplied);
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
    currentTimer = context.getThreadContext().schedule(Duration.ZERO, context.getHeartbeatInterval(), this::heartbeatMembers);
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

  /**
   * Checks for expired sessions.
   */
  private void checkSessions() {
    long term = context.getLog().getTerm();
    for (ServerSession session : context.getStateMachine().executor().context().sessions().sessions.values()) {
      if (!session.isUnregistering() && session.isSuspect()) {
        LOGGER.debug("{} - Detected expired session: {}", context.getAddress(), session.id());

        final long index;
        try (UnregisterEntry entry = context.getLog().createEntry(UnregisterEntry.class)) {
          entry.setTerm(term)
            .setSession(session.id())
            .setExpired(true)
            .setTimestamp(System.currentTimeMillis());
          index = context.getLog().appendEntry(entry);
          LOGGER.debug("{} - Appended {} to log at index {}", context.getAddress(), entry, index);
        }

        replicator.commit(index).whenComplete((result, error) -> {
          if (isOpen()) {
            UnregisterEntry entry = context.getLog().getEntry(index);
            LOGGER.debug("{} - Applying {}", context.getAddress(), entry);
            context.getStateMachine().apply(entry, true).whenComplete((unregisterResult, unregisterError) -> entry.release());
          }
        });
        session.unregister();
      }
    }
  }

  @Override
  public CompletableFuture<JoinResponse> join(final JoinRequest request) {
    context.checkThread();
    logRequest(request);

    // If another configuration change is already under way, reject the configuration.
    if (configuring > 0) {
      return CompletableFuture.completedFuture(logResponse(JoinResponse.builder()
        .withStatus(Response.Status.ERROR)
        .build()));
    }

    // If the leader index is 0 or is greater than the commitIndex, reject the join requests.
    // Configuration changes should not be allowed until the leader has committed a no-op entry.
    // See https://groups.google.com/forum/#!topic/raft-dev/t4xj6dJTP6E
    if (leaderIndex == 0 || context.getLog().getCommitIndex() < leaderIndex) {
      return CompletableFuture.completedFuture(logResponse(JoinResponse.builder()
        .withStatus(Response.Status.ERROR)
        .build()));
    }

    // If the member is already a known member of the cluster, complete the join successfully.
    if (context.getCluster().getMember(request.member().hashCode()) != null) {
      return CompletableFuture.completedFuture(logResponse(JoinResponse.builder()
        .withStatus(Response.Status.OK)
        .withVersion(context.getCluster().getVersion())
        .withActiveMembers(context.getCluster().buildActiveMembers())
        .withPassiveMembers(context.getCluster().buildPassiveMembers())
        .build()));
    }

    final long term = context.getLog().getTerm();
    final long index;

    Collection<Address> activeMembers = context.getCluster().buildActiveMembers();
    Collection<Address> passiveMembers = context.getCluster().buildPassiveMembers();
    passiveMembers.add(request.member());

    try (ConfigurationEntry entry = context.getLog().createEntry(ConfigurationEntry.class)) {
      entry.setTerm(term)
        .setActive(activeMembers)
        .setPassive(passiveMembers);
      index = context.getLog().appendEntry(entry);
      LOGGER.debug("{} - Appended {} to log at index {}", context.getAddress(), entry, index);

      // Store the index of the configuration entry in order to prevent other configurations from
      // being logged and committed concurrently. This is an important safety property of Raft.
      configuring = index;
      context.getCluster().configure(entry.getIndex(), entry.getActive(), entry.getPassive());
    }

    CompletableFuture<JoinResponse> future = new CompletableFuture<>();
    replicator.commit(index).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        // Reset the configuration index to allow new configuration changes to be committed.
        configuring = 0;
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
  }

  @Override
  public CompletableFuture<LeaveResponse> leave(final LeaveRequest request) {
    context.checkThread();
    logRequest(request);

    // If another configuration change is already under way, reject the configuration.
    if (configuring > 0) {
      return CompletableFuture.completedFuture(logResponse(LeaveResponse.builder()
        .withStatus(Response.Status.ERROR)
        .build()));
    }

    // If the leader index is 0 or is greater than the commitIndex, reject the join requests.
    // Configuration changes should not be allowed until the leader has committed a no-op entry.
    // See https://groups.google.com/forum/#!topic/raft-dev/t4xj6dJTP6E
    if (leaderIndex == 0 || context.getLog().getCommitIndex() < leaderIndex) {
      return CompletableFuture.completedFuture(logResponse(LeaveResponse.builder()
        .withStatus(Response.Status.ERROR)
        .build()));
    }

    // If the leaving member is not a known member of the cluster, complete the leave successfully.
    if (context.getMember(request.member().hashCode()) == null) {
      return CompletableFuture.completedFuture(logResponse(LeaveResponse.builder()
        .withStatus(Response.Status.OK)
        .withActiveMembers(context.getCluster().buildActiveMembers())
        .withPassiveMembers(context.getCluster().buildPassiveMembers())
        .build()));
    }

    final long term = context.getLog().getTerm();
    final long index;

    Collection<Address> activeMembers = context.getCluster().buildActiveMembers();
    activeMembers.remove(request.member());

    Collection<Address> passiveMembers = context.getCluster().buildPassiveMembers();
    passiveMembers.remove(request.member());

    try (ConfigurationEntry entry = context.getLog().createEntry(ConfigurationEntry.class)) {
      entry.setTerm(term)
        .setActive(activeMembers)
        .setPassive(passiveMembers);
      index = context.getLog().appendEntry(entry);
      LOGGER.debug("{} - Appended {} to log at index {}", context.getAddress(), entry, index);

      // Store the index of the configuration entry in order to prevent other configurations from
      // being logged and committed concurrently. This is an important safety property of Raft.
      configuring = index;
      context.getCluster().configure(entry.getIndex(), entry.getActive(), entry.getPassive());
    }

    CompletableFuture<LeaveResponse> future = new CompletableFuture<>();
    replicator.commit(index).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        // Reset the configuration index to allow new configuration changes to be committed.
        configuring = 0;
        if (commitError == null) {
          future.complete(logResponse(LeaveResponse.builder()
            .withStatus(Response.Status.OK)
            .withVersion(index)
            .withActiveMembers(activeMembers)
            .withPassiveMembers(passiveMembers)
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
  }

  @Override
  public CompletableFuture<PollResponse> poll(final PollRequest request) {
    return CompletableFuture.completedFuture(logResponse(PollResponse.builder()
      .withStatus(Response.Status.OK)
      .withTerm(context.getLog().getTerm())
      .withAccepted(false)
      .build()));
  }

  @Override
  public CompletableFuture<VoteResponse> vote(final VoteRequest request) {
    if (request.term() > context.getLog().getTerm()) {
      LOGGER.debug("{} - Received greater term", context.getAddress());
      context.getLog().setLeader(0);
      transition(CopycatServer.State.FOLLOWER);
      return super.vote(request);
    } else {
      return CompletableFuture.completedFuture(logResponse(VoteResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getLog().getTerm())
        .withVoted(false)
        .build()));
    }
  }

  @Override
  public CompletableFuture<AppendResponse> append(final AppendRequest request) {
    context.checkThread();
    if (request.term() > context.getLog().getTerm()) {
      return super.append(request);
    } else if (request.term() < context.getLog().getTerm()) {
      return CompletableFuture.completedFuture(logResponse(AppendResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getLog().getTerm())
        .withSucceeded(false)
        .withLogIndex(context.getLog().getLastIndex())
        .build()));
    } else {
      context.getLog().setLeader(request.leader());
      transition(CopycatServer.State.FOLLOWER);
      return super.append(request);
    }
  }

  @Override
  protected CompletableFuture<CommandResponse> command(final CommandRequest request) {
    context.checkThread();
    logRequest(request);

    // Get the client's server session. If the session doesn't exist, return an unknown session error.
    ServerSession session = context.getStateMachine().executor().context().sessions().getSession(request.session());
    if (session == null) {
      return CompletableFuture.completedFuture(logResponse(CommandResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(RaftError.Type.UNKNOWN_SESSION_ERROR)
        .build()));
    }

    ComposableFuture<CommandResponse> future = new ComposableFuture<>();

    Command command = request.command();

    // If the command is LINEARIZABLE and the session's current sequence number is less then one prior to the request
    // sequence number, queue this request for handling later. We want to handle command requests in the order in which
    // they were sent by the client. Note that it's possible for the session sequence number to be greater than the request
    // sequence number. In that case, it's likely that the command was submitted more than once to the
    // cluster, and the command will be deduplicated once applied to the state machine.
    if (request.sequence() > session.nextRequest()) {
      session.registerRequest(request.sequence(), () -> command(request).whenComplete(future));
      return future;
    }

    final long term = context.getLog().getTerm();
    final long timestamp = System.currentTimeMillis();
    final long index;

    // Create a CommandEntry and append it to the log.
    try (CommandEntry entry = context.getLog().createEntry(CommandEntry.class)) {
      entry.setTerm(term)
        .setSession(request.session())
        .setTimestamp(timestamp)
        .setSequence(request.sequence())
        .setCommand(command);
      index = context.getLog().appendEntry(entry);
      LOGGER.debug("{} - Appended {} to log at index {}", context.getAddress(), entry, index);
    }

    replicator.commit(index).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        if (commitError == null) {
          CommandEntry entry = context.getLog().getEntry(index);

          LOGGER.debug("{} - Applying {}", context.getAddress(), entry);
          context.getStateMachine().apply(entry, true).whenComplete((result, error) -> {
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
              checkSessions();
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

    // Set the last processed request for the session. This will cause sequential command callbacks to be executed.
    session.setRequest(request.sequence());

    return future;
  }

  @Override
  protected CompletableFuture<QueryResponse> query(final QueryRequest request) {

    Query query = request.query();

    final long timestamp = System.currentTimeMillis();
    final long index = context.getLog().getCommitIndex();

    context.checkThread();
    logRequest(request);

    QueryEntry entry = context.getLog().createEntry(QueryEntry.class)
      .setIndex(index)
      .setTerm(context.getLog().getTerm())
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
    final long version = context.getLog().getLastApplied();
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
        checkSessions();
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

    context.checkThread();
    logRequest(request);

    try (RegisterEntry entry = context.getLog().createEntry(RegisterEntry.class)) {
      entry.setTerm(context.getLog().getTerm())
        .setTimestamp(timestamp)
        .setClient(request.client())
        .setTimeout(timeout);
      index = context.getLog().appendEntry(entry);
      LOGGER.debug("{} - Appended {}", context.getAddress(), entry);
    }

    CompletableFuture<RegisterResponse> future = new CompletableFuture<>();
    replicator.commit(index).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        if (commitError == null) {
          RegisterEntry entry = context.getLog().getEntry(index);

          LOGGER.debug("{} - Applying {}", context.getAddress(), entry);
          context.getStateMachine().apply(entry, true).whenComplete((sessionId, sessionError) -> {
            if (isOpen()) {
              if (sessionError == null) {
                future.complete(logResponse(RegisterResponse.builder()
                  .withStatus(Response.Status.OK)
                  .withSession((Long) sessionId)
                  .withTimeout(timeout)
                  .withLeader(context.getAddress())
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
              checkSessions();
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
  protected CompletableFuture<ConnectResponse> connect(ConnectRequest request, Connection connection) {
    context.checkThread();
    logRequest(request);

    context.getStateMachine().executor().context().sessions().registerConnection(request.session(), connection);

    AcceptRequest acceptRequest = AcceptRequest.builder()
      .withSession(request.session())
      .withAddress(context.getAddress())
      .build();
    return accept(acceptRequest)
      .thenApply(acceptResponse -> ConnectResponse.builder().withStatus(Response.Status.OK).build())
      .thenApply(this::logResponse);
  }

  @Override
  protected CompletableFuture<AcceptResponse> accept(AcceptRequest request) {
    final long timestamp = System.currentTimeMillis();
    final long index;

    context.checkThread();
    logRequest(request);

    try (ConnectEntry entry = context.getLog().createEntry(ConnectEntry.class)) {
      entry.setTerm(context.getLog().getTerm())
        .setSession(request.session())
        .setTimestamp(timestamp)
        .setAddress(request.address());
      index = context.getLog().appendEntry(entry);
      LOGGER.debug("{} - Appended {}", context.getAddress(), entry);
    }

    context.getStateMachine().executor().context().sessions().registerAddress(request.session(), request.address());

    CompletableFuture<AcceptResponse> future = new CompletableFuture<>();
    replicator.commit(index).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        if (commitError == null) {
          ConnectEntry entry = context.getLog().getEntry(index);
          applyEntry(entry).whenComplete((connectResult, connectError) -> {
            if (isOpen()) {
              if (connectError == null) {
                future.complete(logResponse(AcceptResponse.builder()
                  .withStatus(Response.Status.OK)
                  .build()));
              } else if (connectError instanceof RaftException) {
                future.complete(logResponse(AcceptResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(((RaftException) connectError).getType())
                  .build()));
              } else {
                future.complete(logResponse(AcceptResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(RaftError.Type.INTERNAL_ERROR)
                  .build()));
              }
              checkSessions();
            }
            entry.release();
          });
        } else {
          future.complete(logResponse(AcceptResponse.builder()
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

    context.checkThread();
    logRequest(request);

    try (KeepAliveEntry entry = context.getLog().createEntry(KeepAliveEntry.class)) {
      entry.setTerm(context.getLog().getTerm())
        .setSession(request.session())
        .setCommandSequence(request.commandSequence())
        .setEventVersion(request.eventVersion())
        .setTimestamp(timestamp);
      index = context.getLog().appendEntry(entry);
      LOGGER.debug("{} - Appended {}", context.getAddress(), entry);
    }

    CompletableFuture<KeepAliveResponse> future = new CompletableFuture<>();
    replicator.commit(index).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        if (commitError == null) {
          KeepAliveEntry entry = context.getLog().getEntry(index);
          applyEntry(entry).whenCompleteAsync((sessionResult, sessionError) -> {
            if (isOpen()) {
              if (sessionError == null) {
                future.complete(logResponse(KeepAliveResponse.builder()
                  .withStatus(Response.Status.OK)
                  .withLeader(context.getAddress())
                  .withMembers(context.getCluster().buildActiveMembers())
                  .build()));
              } else if (sessionError instanceof RaftException) {
                future.complete(logResponse(KeepAliveResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withLeader(context.getAddress())
                  .withError(((RaftException) sessionError).getType())
                  .build()));
              } else {
                future.complete(logResponse(KeepAliveResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withLeader(context.getAddress())
                  .withError(RaftError.Type.INTERNAL_ERROR)
                  .build()));
              }
              checkSessions();
            }
            entry.release();
          }, context.getThreadContext().executor());
        } else {
          future.complete(logResponse(KeepAliveResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withLeader(context.getAddress())
            .withError(RaftError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });
    return future;
  }

  @Override
  protected CompletableFuture<UnregisterResponse> unregister(UnregisterRequest request) {
    final long timestamp = System.currentTimeMillis();
    final long index;

    context.checkThread();
    logRequest(request);

    try (UnregisterEntry entry = context.getLog().createEntry(UnregisterEntry.class)) {
      entry.setTerm(context.getLog().getTerm())
        .setSession(request.session())
        .setExpired(false)
        .setTimestamp(timestamp);
      index = context.getLog().appendEntry(entry);
      LOGGER.debug("{} - Appended {}", context.getAddress(), entry);
    }

    CompletableFuture<UnregisterResponse> future = new CompletableFuture<>();
    replicator.commit(index).whenComplete((commitIndex, commitError) -> {
      context.checkThread();
      if (isOpen()) {
        if (commitError == null) {
          UnregisterEntry entry = context.getLog().getEntry(index);

          LOGGER.debug("{} - Applying {}", context.getAddress(), entry);
          context.getStateMachine().apply(entry, true).whenComplete((unregisterResult, unregisterError) -> {
            if (isOpen()) {
              if (unregisterError == null) {
                future.complete(logResponse(UnregisterResponse.builder()
                  .withStatus(Response.Status.OK)
                  .build()));
              } else if (unregisterError instanceof RaftException) {
                future.complete(logResponse(UnregisterResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(((RaftException) unregisterError).getType())
                  .build()));
              } else {
                future.complete(logResponse(UnregisterResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(RaftError.Type.INTERNAL_ERROR)
                  .build()));
              }
              checkSessions();
            }
            entry.release();
          });
        } else {
          future.complete(logResponse(UnregisterResponse.builder()
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
    private int commitFailures;
    private CompletableFuture<Long> commitFuture;
    private CompletableFuture<Long> nextCommitFuture;
    private final TreeMap<Long, CompletableFuture<Long>> commitFutures = new TreeMap<>();

    /**
     * Returns the current quorum index.
     *
     * @return The current quorum index.
     */
    private int quorumIndex() {
      return context.getCluster().getQuorum() - 2;
    }

    /**
     * Triggers a commit.
     *
     * @return A completable future to be completed the next time entries are committed to a majority of the cluster.
     */
    private CompletableFuture<Long> commit() {
      if (context.getCluster().getMembers().size() == 0)
        return CompletableFuture.completedFuture(null);

      // If no commit future already exists, that indicates there's no heartbeat currently under way.
      // Create a new commit future and commit to all members in the cluster.
      if (commitFuture == null) {
        commitFuture = new CompletableFuture<>();
        commitTime = System.currentTimeMillis();
        for (MemberState member : context.getCluster().getMembers()) {
          commit(member);
        }
        return commitFuture;
      }
      // If a commit future already exists, that indicates there is a heartbeat currently underway.
      // We don't want to allow callers to be completed by a heartbeat that may already almost be done.
      // So, we create the next commit future if necessary and return that. Once the current heartbeat
      // completes the next future will be used to do another heartbeat. This ensures that only one
      // heartbeat can be outstanding at any given point in time.
      else if (nextCommitFuture == null) {
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

      // If there are no other servers in the cluster, immediately commit the index.
      if (context.getCluster().getMembers().isEmpty()) {
        context.getLog().setCommitIndex(index);
        context.getLog().setGlobalIndex(index);
        return CompletableFuture.completedFuture(index);
      }
      // If there are no other active members in the cluster, update the commit index and complete
      // the commit but ensure append entries requests are sent to passive members.
      else if (context.getCluster().getActiveMembers().isEmpty()) {
        context.getLog().setCommitIndex(index);
        for (MemberState member : context.getCluster().getMembers()) {
          commit(member);
        }
        return CompletableFuture.completedFuture(index);
      }

      // Ensure append requests are being sent to all members, including passive members.
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
      int quorumIndex = quorumIndex();
      if (quorumIndex >= 0) {
        return context.getCluster().getActiveMembers((m1, m2) -> Long.compare(m2.getCommitTime(), m1.getCommitTime())).get(quorumIndex).getCommitTime();
      }
      return System.currentTimeMillis();
    }

    /**
     * Sets a commit time or fails the commit if a quorum of successful responses cannot be achieved.
     */
    private void commitTime(MemberState member, Throwable error) {
      if (commitFuture == null) {
        return;
      }

      boolean completed = false;
      if (error != null && member.getCommitStartTime() == this.commitTime) {
        int activeMemberSize = context.getCluster().getActiveMembers().size() + (context.getCluster().isActive() ? 1 : 0);
        int quorumSize = context.getCluster().getQuorum();
        // If a quorum of successful responses cannot be achieved, fail this commit.
        if (activeMemberSize - quorumSize + 1 <= ++commitFailures) {
          commitFuture.completeExceptionally(new InternalException("Failed to reach consensus"));
          completed = true;
        }
      } else {
        member.setCommitTime(System.currentTimeMillis());

        // Sort the list of commit times. Use the quorum index to get the last time the majority of the cluster
        // was contacted. If the current commitFuture's time is less than the commit time then trigger the
        // commit future and reset it to the next commit future.
        if (this.commitTime <= commitTime()) {
          commitFuture.complete(null);
          completed = true;
        }
      }

      if (completed) {
        commitFailures = 0;
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

      // The global index may have increased even if the commit index didn't. Update the global index.
      // The global index is calculated by the minimum matchIndex for *all* servers in the cluster, including
      // passive members. This is critical since passive members still have state machines and thus it's still
      // important to ensure that tombstones are applied to their state machines.
      // If the members list is empty, use the local server's last log index as the global index.
      context.getLog().setGlobalIndex(context.getCluster().getMembers().stream().mapToLong(MemberState::getMatchIndex).min().orElse(context.getLog().getLastIndex()));

      // Sort the list of replicas, order by the last index that was replicated to the replica. This will allow
      // us to determine the median index for all known replicated entries across all cluster members.
      // Note that this sort should be fast in most cases since the list should already be sorted, but there
      // may still be a more efficient way to go about this.
      List<MemberState> members = context.getCluster().getActiveMembers((m1, m2) ->
        Long.compare(m2.getMatchIndex() != 0 ? m2.getMatchIndex() : 0l, m1.getMatchIndex() != 0 ? m1.getMatchIndex() : 0l));

      // If the active members list is empty (a configuration change occurred between an append request/response)
      // ensure all commit futures are completed and cleared.
      if (members.isEmpty()) {
        context.getLog().setCommitIndex(context.getLog().getLastIndex());
        for (Map.Entry<Long, CompletableFuture<Long>> entry : commitFutures.entrySet()) {
          entry.getValue().complete(entry.getKey());
        }
        commitFutures.clear();
        return;
      }

      // Calculate the current commit index as the median matchIndex.
      long commitIndex = members.get(quorumIndex()).getMatchIndex();

      // If the commit index has increased then update the commit index. Note that in order to ensure
      // the leader completeness property holds, verify that the commit index is greater than or equal to
      // the index of the leader's no-op entry. Update the commit index and trigger commit futures.
      if (commitIndex > 0 && commitIndex > context.getLog().getCommitIndex() && (leaderIndex > 0 && commitIndex >= leaderIndex)) {
        context.getLog().setCommitIndex(commitIndex);

        // TODO: This seems like an annoyingly expensive operation to perform on every response.
        // Futures could simply be stored in a hash map and we could use a sequential index starting
        // from the previous commit index to get the appropriate futures. But for now, at least this
        // ensures that no memory leaks can occur.
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
        // If the member failed to respond to recent communication send an empty commit. This
        // helps avoid doing expensive work until we can ascertain the member is back up.
        if (context.getLog().isEmpty() || member.getNextIndex() > context.getLog().getLastIndex() || member.getFailureCount() > 0) {
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
    private Entry getPrevEntry(MemberState member, long prevIndex) {
      if (prevIndex > 0) {
        return context.getLog().getEntry(prevIndex);
      }
      return null;
    }

    /**
     * Performs an empty commit.
     */
    private void emptyCommit(MemberState member) {
      long prevIndex = getPrevIndex(member);
      Entry prevEntry = getPrevEntry(member, prevIndex);

      AppendRequest.Builder builder = AppendRequest.builder()
        .withTerm(context.getLog().getTerm())
        .withLeader(context.getAddress().hashCode())
        .withLogIndex(prevIndex)
        .withLogTerm(prevEntry != null ? prevEntry.getTerm() : 0)
        .withCommitIndex(context.getLog().getCommitIndex())
        .withGlobalIndex(context.getLog().getGlobalIndex());

      commit(member, builder.build(), false);
    }

    /**
     * Performs a commit with entries.
     */
    private void entriesCommit(MemberState member) {
      long prevIndex = getPrevIndex(member);
      Entry prevEntry = getPrevEntry(member, prevIndex);

      AppendRequest.Builder builder = AppendRequest.builder()
        .withTerm(context.getLog().getTerm())
        .withLeader(context.getAddress().hashCode())
        .withLogIndex(prevIndex)
        .withLogTerm(prevEntry != null ? prevEntry.getTerm() : 0)
        .withCommitIndex(context.getLog().getCommitIndex())
        .withGlobalIndex(context.getLog().getGlobalIndex());

      // Build a list of entries to send to the member.
      if (!context.getLog().isEmpty()) {
        long index = prevIndex != 0 ? prevIndex + 1 : context.getLog().getFirstIndex();

        // We build a list of entries up to the MAX_BATCH_SIZE. Note that entries in the log may
        // be null if they've been compacted and the member to which we're sending entries is just
        // joining the cluster or is otherwise far behind. Null entries are simply skipped and not
        // counted towards the size of the batch.
        int size = 0;
        while (index <= context.getLog().getLastIndex()) {
          Entry entry = context.getLog().getEntry(index);
          if (entry != null) {
            if (size + entry.size() > MAX_BATCH_SIZE) {
              break;
            }
            size += entry.size();
            builder.addEntry(entry);
          }
          index++;
        }
      }

      // Release the previous entry back to the entry pool.
      if (prevEntry != null) {
        prevEntry.release();
      }

      commit(member, builder.build(), true);
    }

    /**
     * Connects to the member and sends a commit message.
     */
    private void commit(MemberState member, AppendRequest request, boolean recursive) {
      committing.add(member);
      member.setCommitStartTime(commitTime);

      LOGGER.debug("{} - Sent {} to {}", context.getAddress(), request, member.getAddress());
      context.getConnections().getConnection(member.getAddress()).whenComplete((connection, error) -> {
        context.checkThread();

        if (isOpen()) {
          if (error == null) {
            commit(connection, member, request, recursive);
          } else {
            committing.remove(member);
            commitTime(member, error);
            failAttempt(member, error);
          }
        }
      });
    }

    /**
     * Sends a commit message.
     */
    private void commit(Connection connection, MemberState member, AppendRequest request, boolean recursive) {
      connection.<AppendRequest, AppendResponse>send(request).whenComplete((response, error) -> {
        committing.remove(member);
        context.checkThread();

        if (isOpen()) {
          if (error == null) {
            LOGGER.debug("{} - Received {} from {}", context.getAddress(), response, member.getAddress());
            if (response.status() == Response.Status.OK) {
              // Reset the member failure count.
              member.resetFailureCount();

              // Update the commit time for the replica. This will cause heartbeat futures to be triggered.
              commitTime(member, null);

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
              } else if (response.term() > context.getLog().getTerm()) {
                context.getLog().setLeader(0);
                transition(CopycatServer.State.FOLLOWER);
              } else {
                resetMatchIndex(member, response);
                resetNextIndex(member);

                // If there are more entries to send then attempt to send another commit.
                if (hasMoreEntries(member)) {
                  commit();
                }
              }
            } else if (response.term() > context.getLog().getTerm()) {
              LOGGER.debug("{} - Received higher term from {}", context.getAddress(), member.getAddress());
              context.getLog().setLeader(0);
              transition(CopycatServer.State.FOLLOWER);
            } else {
              int failures = member.incrementFailureCount();
              if (failures <= 3 || failures % 100 == 0) {
                LOGGER.warn("{} - {}", context.getAddress(), response.error() != null ? response.error() : "");
              }
            }
          } else {
            commitTime(member, error);
            failAttempt(member, error);
          }
        }
      });
    }

    /**
     * Fails an attempt to contact a member.
     */
    private void failAttempt(MemberState member, Throwable error) {
      int failures = member.incrementFailureCount();
      if (failures <= 3 || failures % 100 == 0) {
        LOGGER.warn("{} - {}", context.getAddress(), error.getMessage());
      }

      // Verify that the leader has contacted a majority of the cluster within the last two election timeouts.
      // If the leader is not able to contact a majority of the cluster within two election timeouts, assume
      // that a partition occurred and transition back to the FOLLOWER state.
      if (System.currentTimeMillis() - Math.max(commitTime(), leaderTime) > context.getElectionTimeout().toMillis() * 2) {
        LOGGER.warn("{} - Suspected network partition. Stepping down", context.getAddress());
        context.getLog().setLeader(0);
        transition(CopycatServer.State.FOLLOWER);
      }
    }

    /**
     * Returns a boolean value indicating whether there are more entries to send.
     */
    private boolean hasMoreEntries(MemberState member) {
      return member.getNextIndex() < context.getLog().getLastIndex();
    }

    /**
     * Updates the match index when a response is received.
     */
    private void updateMatchIndex(MemberState member, AppendResponse response) {
      // If the replica returned a valid match index then update the existing match index.
      member.setMatchIndex(Math.max(member.getMatchIndex(), response.logIndex()));
    }

    /**
     * Updates the next index when the match index is updated.
     */
    private void updateNextIndex(MemberState member) {
      // If the match index was set, update the next index to be greater than the match index if necessary.
      member.setNextIndex(Math.max(member.getNextIndex(), Math.max(member.getMatchIndex() + 1, 1)));
    }

    /**
     * Updates the cluster configuration for the given member.
     */
    private void updateConfiguration(MemberState member) {
      if (context.getCluster().isPassiveMember(member) && member.getMatchIndex() >= context.getLog().getCommitIndex()) {
        if (configuring > 0) {
          commit(configuring).whenComplete((result, error) -> {
            promoteConfiguration(member);
          });
        } else {
          promoteConfiguration(member);
        }
      }
    }

    /**
     * Promotes the given member.
     */
    private void promoteConfiguration(MemberState member) {
      LOGGER.info("{} - Promoting {}", context.getAddress(), member);

      Collection<Address> activeMembers = context.getCluster().buildActiveMembers();
      activeMembers.add(member.getAddress());

      Collection<Address> passiveMembers = context.getCluster().buildPassiveMembers();
      passiveMembers.remove(member.getAddress());

      try (ConfigurationEntry entry = context.getLog().createEntry(ConfigurationEntry.class)) {
        entry.setTerm(context.getLog().getTerm())
          .setActive(activeMembers)
          .setPassive(passiveMembers);
        long index = context.getLog().appendEntry(entry);
        LOGGER.debug("{} - Appended {} to log at index {}", context.getAddress(), entry, index);

        // Immediately apply the configuration upon appending the configuration entry.
        // Store the index of the configuration in order to block other configurations from taking
        // place at the same time.
        configuring = index;
        context.getCluster().configure(entry.getIndex(), entry.getActive(), entry.getPassive());
      }

      commit(configuring).whenComplete((result, error) -> {
        context.checkThread();
        configuring = 0;
      });
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
        member.setNextIndex(context.getLog().getFirstIndex());
      }
      LOGGER.debug("{} - Reset next index for {} to {}", context.getAddress(), member, member.getNextIndex());
    }
  }

}

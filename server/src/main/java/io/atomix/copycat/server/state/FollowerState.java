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

import io.atomix.catalyst.transport.Connection;
import io.atomix.catalyst.util.concurrent.Scheduled;
import io.atomix.copycat.client.error.RaftError;
import io.atomix.copycat.client.request.*;
import io.atomix.copycat.client.response.*;
import io.atomix.copycat.server.CopycatServer;
import io.atomix.copycat.server.request.*;
import io.atomix.copycat.server.response.*;
import io.atomix.copycat.server.storage.entry.Entry;
import io.atomix.copycat.server.util.Quorum;

import java.time.Duration;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Follower state.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
final class FollowerState extends ActiveState {
  private final Random random = new Random();
  private Scheduled heartbeatTimer;

  public FollowerState(ServerState context) {
    super(context);
  }

  @Override
  public CopycatServer.State type() {
    return CopycatServer.State.FOLLOWER;
  }

  @Override
  public synchronized CompletableFuture<AbstractState> open() {
    return super.open().thenRun(this::startHeartbeatTimeout).thenApply(v -> this);
  }

  @Override
  protected CompletableFuture<RegisterResponse> register(RegisterRequest request) {
    context.checkThread();
    logRequest(request);

    if (context.getLeader() == null) {
      return CompletableFuture.completedFuture(logResponse(RegisterResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(RaftError.Type.NO_LEADER_ERROR)
        .build()));
    } else {
      return this.<RegisterRequest, RegisterResponse>forward(request).thenApply(this::logResponse);
    }
  }

  @Override
  protected CompletableFuture<ConnectResponse> connect(ConnectRequest request, Connection connection) {
    context.checkThread();
    logRequest(request);

    if (context.getLeader() == null) {
      return CompletableFuture.completedFuture(logResponse(ConnectResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(RaftError.Type.NO_LEADER_ERROR)
        .build()));
    } else {
      // Immediately register the session connection and send an accept request to the leader.
      context.getStateMachine().executor().context().sessions().registerConnection(request.session(), connection);

      AcceptRequest acceptRequest = AcceptRequest.builder()
        .withSession(request.session())
        .withAddress(context.getCluster().getMember().serverAddress())
        .build();
      return this.<AcceptRequest, AcceptResponse>forward(acceptRequest)
        .thenApply(acceptResponse -> ConnectResponse.builder().withStatus(Response.Status.OK).build())
        .thenApply(this::logResponse);
    }
  }

  @Override
  protected CompletableFuture<KeepAliveResponse> keepAlive(KeepAliveRequest request) {
    context.checkThread();
    logRequest(request);

    if (context.getLeader() == null) {
      return CompletableFuture.completedFuture(logResponse(KeepAliveResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(RaftError.Type.NO_LEADER_ERROR)
        .build()));
    } else {
      return this.<KeepAliveRequest, KeepAliveResponse>forward(request).thenApply(this::logResponse);
    }
  }

  @Override
  protected CompletableFuture<PublishResponse> publish(PublishRequest request) {
    context.checkThread();
    logRequest(request);

    ServerSession session = context.getStateMachine().executor().context().sessions().getSession(request.session());
    if (session == null || session.getConnection() == null) {
      return CompletableFuture.completedFuture(logResponse(PublishResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(RaftError.Type.ILLEGAL_MEMBER_STATE_ERROR)
        .build()));
    } else {
      return session.getConnection().<PublishRequest, PublishResponse>send(request);
    }
  }

  @Override
  protected CompletableFuture<UnregisterResponse> unregister(UnregisterRequest request) {
    context.checkThread();
    logRequest(request);

    if (context.getLeader() == null) {
      return CompletableFuture.completedFuture(logResponse(UnregisterResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(RaftError.Type.NO_LEADER_ERROR)
        .build()));
    } else {
      return this.<UnregisterRequest, UnregisterResponse>forward(request).thenApply(this::logResponse);
    }
  }

  /**
   * Starts the heartbeat timer.
   */
  private void startHeartbeatTimeout() {
    LOGGER.debug("{} - Starting heartbeat timer", context.getCluster().getMember().serverAddress());
    resetHeartbeatTimeout();
  }

  /**
   * Resets the heartbeat timer.
   */
  private void resetHeartbeatTimeout() {
    context.checkThread();
    if (isClosed())
      return;

    // If a timer is already set, cancel the timer.
    if (heartbeatTimer != null) {
      LOGGER.debug("{} - Reset heartbeat timeout", context.getCluster().getMember().serverAddress());
      heartbeatTimer.cancel();
    }

    // Set the election timeout in a semi-random fashion with the random range
    // being election timeout and 2 * election timeout.
    Duration delay = context.getElectionTimeout().plus(Duration.ofMillis(random.nextInt((int) context.getElectionTimeout().toMillis())));
    heartbeatTimer = context.getThreadContext().schedule(delay, () -> {
      heartbeatTimer = null;
      if (isOpen()) {
        context.setLeader(0);
        if (context.getLastVotedFor() == 0) {
          LOGGER.debug("{} - Heartbeat timed out in {}", context.getCluster().getMember().serverAddress(), delay);
          sendPollRequests();
        } else {
          // If the node voted for a candidate then reset the election timer.
          resetHeartbeatTimeout();
        }
      }
    });
  }

  /**
   * Polls all members of the cluster to determine whether this member should transition to the CANDIDATE state.
   */
  private void sendPollRequests() {
    // Set a new timer within which other nodes must respond in order for this node to transition to candidate.
    heartbeatTimer = context.getThreadContext().schedule(context.getElectionTimeout(), () -> {
      LOGGER.debug("{} - Failed to poll a majority of the cluster in {}", context.getCluster().getMember().serverAddress(), context.getElectionTimeout());
      resetHeartbeatTimeout();
    });

    // Create a quorum that will track the number of nodes that have responded to the poll request.
    final AtomicBoolean complete = new AtomicBoolean();
    final Set<Member> votingMembers = new HashSet<>(context.getCluster().getRemoteMembers(CopycatServer.Type.ACTIVE));

    // If there are no other members in the cluster, immediately transition to leader.
    if (votingMembers.isEmpty()) {
      LOGGER.debug("{} - Single member cluster. Transitioning directly to leader.", context.getCluster().getMember().serverAddress());
      transition(CopycatServer.State.LEADER);
      return;
    }

    final Quorum quorum = new Quorum(context.getCluster().getQuorum(), (elected) -> {
      // If a majority of the cluster indicated they would vote for us then transition to candidate.
      complete.set(true);
      if (elected) {
        transition(CopycatServer.State.CANDIDATE);
      } else {
        resetHeartbeatTimeout();
      }
    });

    // First, load the last log entry to get its term. We load the entry
    // by its index since the index is required by the protocol.
    long lastIndex = context.getLog().lastIndex();
    Entry lastEntry = lastIndex > 0 ? context.getLog().get(lastIndex) : null;

    final long lastTerm;
    if (lastEntry != null) {
      lastTerm = lastEntry.getTerm();
      lastEntry.close();
    } else {
      lastTerm = 0;
    }

    LOGGER.info("{} - Polling members {}", context.getCluster().getMember().serverAddress(), votingMembers);

    // Once we got the last log term, iterate through each current member
    // of the cluster and vote each member for a vote.
    for (Member member : votingMembers) {
      LOGGER.debug("{} - Polling {} for next term {}", context.getCluster().getMember().serverAddress(), member, context.getTerm() + 1);
      PollRequest request = PollRequest.builder()
        .withTerm(context.getTerm())
        .withCandidate(context.getCluster().getMember().serverAddress().hashCode())
        .withLogIndex(lastIndex)
        .withLogTerm(lastTerm)
        .build();
      context.getConnections().getConnection(member.serverAddress()).thenAccept(connection -> {
        connection.<PollRequest, PollResponse>send(request).whenCompleteAsync((response, error) -> {
          context.checkThread();
          if (isOpen() && !complete.get()) {
            if (error != null) {
              LOGGER.warn("{} - {}", context.getCluster().getMember().serverAddress(), error.getMessage());
              quorum.fail();
            } else {
              if (response.term() > context.getTerm()) {
                context.setTerm(response.term());
              }

              if (!response.accepted()) {
                LOGGER.debug("{} - Received rejected poll from {}", context.getCluster().getMember().serverAddress(), member);
                quorum.fail();
              } else if (response.term() != context.getTerm()) {
                LOGGER.debug("{} - Received accepted poll for a different term from {}", context.getCluster().getMember().serverAddress(), member);
                quorum.fail();
              } else {
                LOGGER.debug("{} - Received accepted poll from {}", context.getCluster().getMember().serverAddress(), member);
                quorum.succeed();
              }
            }
          }
        }, context.getThreadContext().executor());
      });
    }
  }

  @Override
  protected CompletableFuture<InstallResponse> install(InstallRequest request) {
    resetHeartbeatTimeout();
    return super.install(request);
  }

  @Override
  protected CompletableFuture<ConfigureResponse> configure(ConfigureRequest request) {
    resetHeartbeatTimeout();
    return super.configure(request);
  }

  @Override
  public CompletableFuture<AppendResponse> append(AppendRequest request) {
    resetHeartbeatTimeout();
    return super.append(request);
  }

  @Override
  protected VoteResponse handleVote(VoteRequest request) {
    // Reset the heartbeat timeout if we voted for another candidate.
    VoteResponse response = super.handleVote(request);
    if (response.voted()) {
      resetHeartbeatTimeout();
    }
    return response;
  }

  /**
   * Cancels the heartbeat timeout.
   */
  private void cancelHeartbeatTimeout() {
    if (heartbeatTimer != null) {
      LOGGER.debug("{} - Cancelling heartbeat timer", context.getCluster().getMember().serverAddress());
      heartbeatTimer.cancel();
    }
  }

  @Override
  public synchronized CompletableFuture<Void> close() {
    return super.close().thenRun(this::cancelHeartbeatTimeout);
  }

}

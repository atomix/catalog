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
import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.util.Listener;
import io.atomix.catalyst.util.Listeners;
import io.atomix.catalyst.util.concurrent.Scheduled;
import io.atomix.catalyst.util.concurrent.SingleThreadContext;
import io.atomix.catalyst.util.concurrent.ThreadContext;
import io.atomix.copycat.client.request.*;
import io.atomix.copycat.client.response.Response;
import io.atomix.copycat.server.CopycatServer;
import io.atomix.copycat.server.StateMachine;
import io.atomix.copycat.server.request.*;
import io.atomix.copycat.server.response.JoinResponse;
import io.atomix.copycat.server.storage.Log;
import io.atomix.copycat.server.storage.MetaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Raft server state.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class ServerState {
  private static final Logger LOGGER = LoggerFactory.getLogger(ServerState.class);
  private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
  private static final int MAX_JOIN_ATTEMPTS = 3;
  private final Listeners<CopycatServer.State> stateChangeListeners = new Listeners<>();
  private final Listeners<Address> electionListeners = new Listeners<>();
  private ThreadContext threadContext;
  private final StateMachine userStateMachine;
  private final int quorumHint;
  private final int backupCount;
  private long version = -1;
  private final MemberState member;
  private Map<Integer, MemberState> membersMap = new HashMap<>();
  private List<MemberState> members = new ArrayList<>();
  private List<MemberState> activeMembers = new ArrayList<>();
  private List<MemberState> passiveMembers = new ArrayList<>();
  private List<MemberState> reserveMembers = new ArrayList<>();
  private List<MemberState> assignedPassiveMembers = new ArrayList<>();
  private List<MemberState> assignedReserveMembers = new ArrayList<>();
  private final MetaStore meta;
  private final Log log;
  private final ServerStateMachine stateMachine;
  private final ConnectionManager connections;
  private AbstractState state = new InactiveState(this);
  private Duration electionTimeout = Duration.ofMillis(500);
  private Duration sessionTimeout = Duration.ofMillis(5000);
  private Duration heartbeatInterval = Duration.ofMillis(150);
  private Scheduled joinTimer;
  private Scheduled leaveTimer;
  private Scheduled heartbeatTimer;
  private int leader;
  private long term;
  private int lastVotedFor;
  private long lastApplied;
  private long lastCompleted;
  private long commitIndex;
  private long globalIndex;

  @SuppressWarnings("unchecked")
  ServerState(Member member, Collection<Address> members, int quorumHint, int backupCount, MetaStore meta, Log log, StateMachine stateMachine, ConnectionManager connections, ThreadContext threadContext) {
    this.quorumHint = quorumHint;
    this.backupCount = backupCount;
    this.member = new MemberState(member.serverAddress()).setClientAddress(member.clientAddress());
    this.meta = Assert.notNull(meta, "meta");
    this.log = Assert.notNull(log, "log");
    this.threadContext = Assert.notNull(threadContext, "threadContext");
    this.connections = Assert.notNull(connections, "connections");
    this.userStateMachine = Assert.notNull(stateMachine, "stateMachine");

    // Create a state machine executor and configure the state machine.
    ThreadContext stateContext = new SingleThreadContext("copycat-server-" + member.serverAddress() + "-state-%d", threadContext.serializer().clone());
    this.stateMachine = new ServerStateMachine(userStateMachine, this, new ServerStateMachineContext(connections, new ServerSessionManager()), stateContext);

    // Load the current term and last vote from disk.
    this.term = meta.loadTerm();
    this.lastVotedFor = meta.loadVote();

    // If a configuration is stored, use the stored configuration, otherwise configure the server with the user provided configuration.
    MetaStore.Configuration configuration = meta.loadConfiguration();
    if (configuration != null) {
      configure(configuration.version(), configuration.members());
    } else if (members.contains(member.serverAddress())) {
      Set<Member> activeMembers = members.stream().filter(m -> !m.equals(member.serverAddress())).map(m -> new Member(Member.Type.ACTIVE, m, null)).collect(Collectors.toSet());
      activeMembers.add(new Member(Member.Type.ACTIVE, member.serverAddress(), member.clientAddress()));
      configure(0, activeMembers);
    } else {
      Set<Member> activeMembers = members.stream().map(m -> new Member(Member.Type.ACTIVE, m, null)).collect(Collectors.toSet());
      configure(0, activeMembers);
    }
  }

  /**
   * Registers a state change listener.
   *
   * @param listener The state change listener.
   * @return The listener context.
   */
  public Listener<CopycatServer.State> onStateChange(Consumer<CopycatServer.State> listener) {
    return stateChangeListeners.add(listener);
  }

  /**
   * Registers a leader election listener.
   *
   * @param listener The leader election listener.
   * @return The listener context.
   */
  public Listener<Address> onLeaderElection(Consumer<Address> listener) {
    return electionListeners.add(listener);
  }

  /**
   * Returns the cluster quorum hint.
   *
   * @return The cluster quorum hint.
   */
  int getQuorumHint() {
    return quorumHint;
  }

  /**
   * Returns the cluster backup count.
   *
   * @return The cluster backup count.
   */
  int getBackupCount() {
    return backupCount;
  }

  /**
   * Returns the remote quorum count.
   *
   * @return The remote quorum count.
   */
  int getQuorum() {
    return (int) Math.floor((activeMembers.size() + 1) / 2.0) + 1;
  }

  /**
   * Returns the cluster state version.
   *
   * @return The cluster state version.
   */
  long getVersion() {
    return version;
  }

  /**
   * Returns the execution context.
   *
   * @return The execution context.
   */
  public ThreadContext getThreadContext() {
    return threadContext;
  }

  /**
   * Returns the context connection manager.
   *
   * @return The context connection manager.
   */
  ConnectionManager getConnections() {
    return connections;
  }

  /**
   * Sets the election timeout.
   *
   * @param electionTimeout The election timeout.
   * @return The Raft context.
   */
  public ServerState setElectionTimeout(Duration electionTimeout) {
    this.electionTimeout = electionTimeout;
    return this;
  }

  /**
   * Returns the election timeout.
   *
   * @return The election timeout.
   */
  public Duration getElectionTimeout() {
    return electionTimeout;
  }

  /**
   * Sets the heartbeat interval.
   *
   * @param heartbeatInterval The Raft heartbeat interval.
   * @return The Raft context.
   */
  public ServerState setHeartbeatInterval(Duration heartbeatInterval) {
    this.heartbeatInterval = heartbeatInterval;
    return this;
  }

  /**
   * Returns the heartbeat interval.
   *
   * @return The heartbeat interval.
   */
  public Duration getHeartbeatInterval() {
    return heartbeatInterval;
  }

  /**
   * Returns the session timeout.
   *
   * @return The session timeout.
   */
  public Duration getSessionTimeout() {
    return sessionTimeout;
  }

  /**
   * Sets the session timeout.
   *
   * @param sessionTimeout The session timeout.
   * @return The Raft state machine.
   */
  public ServerState setSessionTimeout(Duration sessionTimeout) {
    this.sessionTimeout = sessionTimeout;
    return this;
  }

  /**
   * Sets the state leader.
   *
   * @param leader The state leader.
   * @return The Raft context.
   */
  ServerState setLeader(int leader) {
    if (this.leader != leader) {
      // 0 indicates no leader.
      if (leader == 0) {
        this.leader = 0;
      } else {
        // If a valid leader ID was specified, it must be a member that's currently a member of the
        // ACTIVE members configuration. Note that we don't throw exceptions for unknown members. It's
        // possible that a failure following a configuration change could result in an unknown leader
        // sending AppendRequest to this server. Simply configure the leader if it's known.
        MemberState member = leader == getMember().id() ? getMemberState() : getMemberState(leader);
        if (member != null) {
          this.leader = member.getMember().id();
          LOGGER.info("{} - Found leader {}", this.member.getMember().serverAddress(), member.getMember().serverAddress());
          electionListeners.forEach(l -> l.accept(member.getMember().serverAddress()));
        }
      }

      this.lastVotedFor = 0;
      meta.storeVote(0);
      reassign();
    }
    return this;
  }

  /**
   * Returns the state leader.
   *
   * @return The state leader.
   */
  public Member getLeader() {
    if (leader == 0) {
      return null;
    } else if (leader == member.getMember().id()) {
      return member.getMember();
    }

    MemberState member = membersMap.get(leader);
    return member != null ? member.getMember() : null;
  }

  /**
   * Sets the state term.
   *
   * @param term The state term.
   * @return The Raft context.
   */
  ServerState setTerm(long term) {
    if (term > this.term) {
      this.term = term;
      this.leader = 0;
      this.lastVotedFor = 0;
      meta.storeTerm(this.term);
      meta.storeVote(this.lastVotedFor);
      LOGGER.debug("{} - Set term {}", member.getMember().serverAddress(), term);
    }
    return this;
  }

  /**
   * Returns the state term.
   *
   * @return The state term.
   */
  public long getTerm() {
    return term;
  }

  /**
   * Sets the state last voted for candidate.
   *
   * @param candidate The candidate that was voted for.
   * @return The Raft context.
   */
  ServerState setLastVotedFor(int candidate) {
    // If we've already voted for another candidate in this term then the last voted for candidate cannot be overridden.
    Assert.stateNot(lastVotedFor != 0 && candidate != 0l, "Already voted for another candidate");
    Assert.stateNot(leader != 0 && candidate != 0, "Cannot cast vote - leader already exists");
    MemberState member = candidate == getMember().id() ? getMemberState() : getMemberState(candidate);
    Assert.state(member != null, "unknown candidate: %d", candidate);
    Assert.state(member.getMember().isActive(), "invalid candidate: ", member.getMember().serverAddress());
    this.lastVotedFor = candidate;
    meta.storeVote(this.lastVotedFor);

    if (candidate != 0) {
      LOGGER.debug("{} - Voted for {}", this.member.getMember().serverAddress(), member.getMember().serverAddress());
    } else {
      LOGGER.debug("{} - Reset last voted for", this.member.getMember().serverAddress());
    }
    return this;
  }

  /**
   * Returns the state last voted for candidate.
   *
   * @return The state last voted for candidate.
   */
  public int getLastVotedFor() {
    return lastVotedFor;
  }

  /**
   * Returns the server member.
   *
   * @return The server member.
   */
  public Member getMember() {
    return member.getMember();
  }

  /**
   * Returns the server member state.
   *
   * @return The server member state.
   */
  MemberState getMemberState() {
    return member;
  }

  /**
   * Returns a member by ID.
   *
   * @param id The member ID.
   * @return The member.
   */
  public Member getMember(int id) {
    MemberState member = membersMap.get(id);
    return member != null ? member.getMember() : null;
  }

  /**
   * Returns a member state by ID.
   *
   * @param id The member state ID.
   * @return The member state.
   */
  MemberState getMemberState(int id) {
    return membersMap.get(id);
  }

  /**
   * Returns a member state by member object.
   *
   * @param member The member object.
   * @return The member state.
   */
  MemberState getMemberState(Member member) {
    return membersMap.get(member.id());
  }

  /**
   * Returns a list of all members.
   *
   * @return A list of all members.
   */
  public Collection<Member> getMembers() {
    return members.stream().map(MemberState::getMember).collect(Collectors.toList());
  }

  /**
   * Returns a list of all member states.
   *
   * @return A list of all member states.
   */
  List<MemberState> getMemberStates() {
    return members;
  }

  /**
   * Returns a list of active members.
   *
   * @return A list of active members.
   */
  List<MemberState> getActiveMemberStates() {
    return activeMembers;
  }

  /**
   * Returns a list of active members.
   *
   * @param comparator A comparator with which to sort the members list.
   * @return The sorted members list.
   */
  List<MemberState> getActiveMemberStates(Comparator<MemberState> comparator) {
    Collections.sort(activeMembers, comparator);
    return activeMembers;
  }

  /**
   * Returns a list of passive members.
   *
   * @return A list of passive members.
   */
  List<MemberState> getPassiveMemberStates() {
    return passiveMembers;
  }

  /**
   * Returns a list of passive members.
   *
   * @param comparator A comparator with which to sort the members list.
   * @return The sorted members list.
   */
  List<MemberState> getPassiveMemberStates(Comparator<MemberState> comparator) {
    Collections.sort(passiveMembers, comparator);
    return passiveMembers;
  }

  /**
   * Returns a list of reserve members.
   *
   * @return A list of reserve members.
   */
  List<MemberState> getReserveMemberStates() {
    return reserveMembers;
  }

  /**
   * Returns a list of reserve members.
   *
   * @param comparator A comparator with which to sort the members list.
   * @return The sorted members list.
   */
  List<MemberState> getReserveMemberStates(Comparator<MemberState> comparator) {
    Collections.sort(reserveMembers, comparator);
    return reserveMembers;
  }

  /**
   * Returns a list of assigned passive member states.
   *
   * @return A list of assigned passive member states.
   */
  List<MemberState> getAssignedPassiveMemberStates() {
    return assignedPassiveMembers;
  }

  /**
   * Returns a list of assigned reserve member states.
   *
   * @return A list of assigned reserve member states.
   */
  List<MemberState> getAssignedReserveMemberStates() {
    return assignedReserveMembers;
  }

  /**
   * Rebuilds assigned member states.
   */
  private void reassign() {
    if (member.getMember().isActive() && member.getMember().id() != leader) {
      // Calculate this server's index within the collection of active members, excluding the leader.
      // This is done in a deterministic way by sorting the list of active members by ID.
      int index = 1;
      for (MemberState member : getActiveMemberStates((m1, m2) -> m1.getMember().id() - m2.getMember().id())) {
        if (member.getMember().id() != leader) {
          if (this.member.getMember().id() < member.getMember().id()) {
            index++;
          } else {
            break;
          }
        }
      }

      // Intersect the active members list with a sorted list of passive members to get assignments.
      List<MemberState> sortedPassiveMembers = getPassiveMemberStates((m1, m2) -> m1.getMember().id() - m2.getMember().id());
      assignedPassiveMembers = assignMembers(index, sortedPassiveMembers);

      // Intersect the active members list with a sorted list of reserve members to get assignments.
      List<MemberState> sortedReserveMembers = getReserveMemberStates((m1, m2) -> m1.getMember().id() - m2.getMember().id());
      assignedReserveMembers = assignMembers(index, sortedReserveMembers);
    } else {
      assignedPassiveMembers = new ArrayList<>(0);
      assignedReserveMembers = new ArrayList<>(0);
    }
  }

  /**
   * Assigns members using consistent hashing.
   */
  private List<MemberState> assignMembers(int index, List<MemberState> sortedMembers) {
    List<MemberState> members = new ArrayList<>(sortedMembers.size());
    for (int i = 0; i < sortedMembers.size(); i++) {
      if ((i + 1) % index == 0) {
        members.add(sortedMembers.get(i));
      }
    }
    return members;
  }

  /**
   * Configures the cluster state.
   *
   * @param version The cluster state version.
   * @param members The configured members.
   * @return The cluster state.
   */
  @SuppressWarnings("unchecked")
  ServerState configure(long version, Collection<Member> members) {
    // If the configuration version is less than the currently configured version, ignore it.
    // Configurations can be persisted and applying old configurations can revert newer configurations.
    if (version <= this.version)
      return this;

    // Iterate through members in the new configuration, add any missing members, and update existing members.
    for (Member member : members) {
      if (member.equals(this.member.getMember())) {
        this.member.setMemberType(member.type());
      } else {
        // If the member state doesn't already exist, create it.
        MemberState state = membersMap.get(member.id());
        if (state == null) {
          state = new MemberState(member.serverAddress());
          state.resetState(log);
          this.members.add(state);
          membersMap.put(member.id(), state);
        }

        // If the member type has changed, update the member type and reset its state.
        if (state.getMemberType() != member.type()) {
          state.setMemberType(member.type()).resetState(log);
        }

        // Update the optimized member collections according to the member type.
        if (member.type() == null) {
          activeMembers.remove(state);
          passiveMembers.remove(state);
          reserveMembers.remove(state);
        } else {
          switch (member.type()) {
            case ACTIVE:
              if (!activeMembers.contains(state))
                activeMembers.add(state);
              passiveMembers.remove(state);
              reserveMembers.remove(state);
              break;
            case PASSIVE:
              activeMembers.remove(state);
              if (!passiveMembers.contains(state))
                passiveMembers.add(state);
              reserveMembers.remove(state);
              break;
            case RESERVE:
              activeMembers.remove(state);
              passiveMembers.remove(state);
              if (!reserveMembers.contains(state))
                reserveMembers.add(state);
              break;
          }
        }
      }
    }

    // If the local member is not part of the configuration, set its type to null.
    if (!members.contains(this.member.getMember())) {
      this.member.setMemberType(null);
    }

    // Iterate through configured members and remove any that no longer exist in the configuration.
    Iterator<MemberState> iterator = this.members.iterator();
    while (iterator.hasNext()) {
      MemberState member = iterator.next();
      if (!members.contains(member.getMember())) {
        iterator.remove();
        activeMembers.remove(member);
        passiveMembers.remove(member);
        reserveMembers.remove(member);
        membersMap.remove(member.getMember().id());
      }
    }

    this.version = version;

    // Store the configuration to ensure it can be easily loaded on server restart.
    meta.storeConfiguration(new MetaStore.Configuration(version, members));

    reassign();

    return this;
  }

  /**
   * Builds a list of active members.
   */
  List<Member> buildMembers() {
    // Add all members to a list. The "members" field is only remote members, so we must separately
    // add the local member to the list if necessary.
    List<Member> members = new ArrayList<>(this.members.size() + 1);
    for (MemberState member : this.members) {
      members.add(member.getMember());
    }

    // If the local member type is null, that indicates it's not a member of the current configuration.
    if (member.getMemberType() != null) {
      members.add(member.getMember());
    }
    return members;
  }

  /**
   * Returns the last applied index.
   *
   * @return The last applied index.
   */
  long getLastApplied() {
    return lastApplied;
  }

  /**
   * Sets the last applied index.
   * <p>
   * The last applied index is updated *after* each time a non-query entry is applied to the state machine.
   *
   * @param lastApplied The last applied index.
   * @return The server state.
   */
  ServerState setLastApplied(long lastApplied) {
    // If the last applied index decreased then that's very concerning.
    this.lastApplied = Assert.argNot(lastApplied, lastApplied < this.lastApplied, "lastApplied index must be monotonically increasing");
    return this;
  }

  /**
   * Returns the highest index completed for all sessions.
   * <p>
   * The lastCompleted index is representative of the highest index for which related events have been
   * received by *all* clients. In other words, no events lower than the given index should remain in
   * memory.
   *
   * @return The highest index completed for all sessions.
   */
  long getLastCompleted() {
    return lastCompleted > 0 ? lastCompleted : lastApplied;
  }

  /**
   * Sets the last completed index.
   * <p>
   * The lastCompleted index is representative of the highest index for which related events have been
   * received by *all* clients. In other words, no events lower than the given index should remain in
   * memory.
   *
   * @param lastCompleted The last completed index.
   * @return The server state.
   */
  ServerState setLastCompleted(long lastCompleted) {
    // The last completed index must not have decreased. New sessions will always start their lastCompleted
    // index at the index of their session registration to ensure the creation of new sessions does not
    // result in the decrease of the lastCompleted index.
    this.lastCompleted = Assert.argNot(lastCompleted, lastCompleted < this.lastCompleted, "lastCompleted index must be monotonically increasing");
    return this;
  }

  /**
   * Sets the commit index.
   *
   * @param commitIndex The commit index.
   * @return The Raft context.
   */
  ServerState setCommitIndex(long commitIndex) {
    Assert.argNot(commitIndex < 0, "commit index must be positive");
    Assert.argNot(commitIndex < this.commitIndex, "cannot decrease commit index");
    this.commitIndex = commitIndex;
    log.commit(commitIndex);
    return this;
  }

  /**
   * Returns the commit index.
   *
   * @return The commit index.
   */
  public long getCommitIndex() {
    return commitIndex;
  }

  /**
   * Sets the global index.
   *
   * @param globalIndex The global index.
   * @return The Raft context.
   */
  ServerState setGlobalIndex(long globalIndex) {
    Assert.argNot(globalIndex < 0, "global index must be positive");
    this.globalIndex = Math.max(this.globalIndex, globalIndex);
    log.compactor().majorIndex(globalIndex);
    return this;
  }

  /**
   * Returns the global index.
   *
   * @return The global index.
   */
  public long getGlobalIndex() {
    return globalIndex;
  }

  /**
   * Returns the server state machine.
   *
   * @return The server state machine.
   */
  ServerStateMachine getStateMachine() {
    return stateMachine;
  }

  /**
   * Returns the current state.
   *
   * @return The current state.
   */
  public CopycatServer.State getState() {
    return state.type();
  }

  /**
   * Returns the state log.
   *
   * @return The state log.
   */
  public Log getLog() {
    return log;
  }

  /**
   * Checks that the current thread is the state context thread.
   */
  void checkThread() {
    threadContext.checkThread();
  }

  /**
   * Handles a connection from a client.
   */
  void connectClient(Connection connection) {
    threadContext.checkThread();

    // Note we do not use method references here because the "state" variable changes over time.
    // We have to use lambdas to ensure the request handler points to the current state.
    connection.handler(RegisterRequest.class, request -> state.register(request));
    connection.handler(ConnectRequest.class, request -> state.connect(request, connection));
    connection.handler(KeepAliveRequest.class, request -> state.keepAlive(request));
    connection.handler(UnregisterRequest.class, request -> state.unregister(request));
    connection.handler(CommandRequest.class, request -> state.command(request));
    connection.handler(QueryRequest.class, request -> state.query(request));

    connection.closeListener(stateMachine.executor().context().sessions()::unregisterConnection);
  }

  /**
   * Handles a connection from another server.
   */
  void connectServer(Connection connection) {
    threadContext.checkThread();

    // Handlers for all request types are registered since requests can be proxied between servers.
    // Note we do not use method references here because the "state" variable changes over time.
    // We have to use lambdas to ensure the request handler points to the current state.
    connection.handler(RegisterRequest.class, request -> state.register(request));
    connection.handler(ConnectRequest.class, request -> state.connect(request, connection));
    connection.handler(AcceptRequest.class, request -> state.accept(request));
    connection.handler(KeepAliveRequest.class, request -> state.keepAlive(request));
    connection.handler(UnregisterRequest.class, request -> state.unregister(request));
    connection.handler(PublishRequest.class, request -> state.publish(request));
    connection.handler(JoinRequest.class, request -> state.join(request));
    connection.handler(LeaveRequest.class, request -> state.leave(request));
    connection.handler(HeartbeatRequest.class, request -> state.heartbeat(request));
    connection.handler(ConfigureRequest.class, request -> state.configure(request));
    connection.handler(AppendRequest.class, request -> state.append(request));
    connection.handler(PollRequest.class, request -> state.poll(request));
    connection.handler(VoteRequest.class, request -> state.vote(request));
    connection.handler(CommandRequest.class, request -> state.command(request));
    connection.handler(QueryRequest.class, request -> state.query(request));
  }

  /**
   * Transition handler.
   */
  public CompletableFuture<CopycatServer.State> transition(CopycatServer.State state) {
    checkThread();

    // If the state has not changed, simply complete the transition.
    if (state == this.state.type()) {
      return CompletableFuture.completedFuture(this.state.type());
    }

    LOGGER.info("{} - Transitioning to {}", member.getMember().serverAddress(), state);

    // If a valid state exists, close the current state synchronously.
    try {
      this.state.close().get();
    } catch (InterruptedException | ExecutionException e) {
      throw new IllegalStateException("failed to close Raft state", e);
    }

    // If we're transitioning out of the RESERVE state, truncate the log.
    if (this.state.type() == CopycatServer.State.RESERVE) {
      log.truncate();
    }

    // Force state transitions to occur synchronously in order to prevent race conditions.
    try {
      this.state = createState(state);
      this.state.open().get();
    } catch (InterruptedException | ExecutionException e) {
      throw new IllegalStateException("failed to initialize Raft state", e);
    }

    stateChangeListeners.forEach(l -> l.accept(this.state.type()));
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Creates an internal state for the given state type.
   */
  private AbstractState createState(CopycatServer.State state) {
    switch (state) {
      case INACTIVE:
        return new InactiveState(this);
      case RESERVE:
        return new ReserveState(this);
      case PASSIVE:
        return new PassiveState(this);
      case FOLLOWER:
        return new FollowerState(this);
      case CANDIDATE:
        return new CandidateState(this);
      case LEADER:
        return new LeaderState(this);
      default:
        throw new AssertionError();
    }
  }

  /**
   * Joins the cluster.
   */
  public CompletableFuture<Void> join() {
    CompletableFuture<Void> future = new CompletableFuture<>();

    // If the server type is defined, that indicates it's a member of the current configuration and
    // doesn't need to be added to the configuration. Immediately transition to the appropriate state.
    if (member.getMember().type() != null) {
      if (member.getMember().isActive()) {
        transition(CopycatServer.State.FOLLOWER);
      } else if (member.getMember().isPassive()) {
        transition(CopycatServer.State.PASSIVE);
      } else if (member.getMember().isReserve()) {
        transition(CopycatServer.State.RESERVE);
      }
      future.complete(null);
    } else {
      join(getActiveMemberStates().iterator(), 1, future);
    }

    return future;
  }

  /**
   * Recursively attempts to join the cluster.
   */
  private void join(Iterator<MemberState> iterator, int attempts, CompletableFuture<Void> future) {
    if (iterator.hasNext()) {
      MemberState member = iterator.next();
      LOGGER.debug("{} - Attempting to join via {}", this.member.getMember().serverAddress(), member.getMember().serverAddress());

      connections.getConnection(member.getMember().serverAddress()).thenCompose(connection -> {
        JoinRequest request = JoinRequest.builder()
          .withMember(new Member(this.member.getMember().serverAddress(), this.member.getMember().clientAddress()))
          .build();
        return connection.<JoinRequest, JoinResponse>send(request);
      }).whenComplete((response, error) -> {
        if (error == null) {
          if (response.status() == Response.Status.OK) {
            LOGGER.info("{} - Successfully joined via {}", this.member.getMember().serverAddress(), member.getMember().serverAddress());

            // Configure the cluster with the join response.
            configure(response.version(), response.members());

            // Cancel the join timer.
            cancelJoinTimer();

            // If the local member type is null, that indicates it's not a part of the configuration.
            Member.Type type = this.member.getMemberType();
            if (type == null) {
              future.completeExceptionally(new IllegalStateException("not a member of the cluster"));
            } else {
              // Transition the state context according to the local member type.
              switch (type) {
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

              // Start sending heartbeats to the leader to ensure this server can be considered for promotion.
              startHeartbeatTimer();
              future.complete(null);
            }
          } else if (response.error() == null) {
            // If the response error is null, that indicates that no error occurred but the leader was
            // in a state that was incapable of handling the join request. Attempt to join the leader
            // again after an election timeout.
            LOGGER.debug("{} - Failed to join {}", this.member.getMember().serverAddress(), member.getMember().serverAddress());
            cancelJoinTimer();
            joinTimer = threadContext.schedule(electionTimeout, () -> {
              join(getActiveMemberStates().iterator(), attempts, future);
            });
          } else {
            // If the response error was non-null, attempt to join via the next server in the members list.
            LOGGER.debug("{} - Failed to join {}", this.member.getMember().serverAddress(), member.getMember().serverAddress());
            join(iterator, attempts, future);
          }
        } else {
          LOGGER.debug("{} - Failed to join {}", this.member.getMember().serverAddress(), member.getMember().serverAddress());
          join(iterator, attempts, future);
        }
      });
    }
    // If the maximum number of join attempts has been reached, fail the join.
    else if (attempts >= MAX_JOIN_ATTEMPTS) {
      future.completeExceptionally(new IllegalStateException("failed to join the cluster"));
    }
    // If join attempts remain, schedule another attempt after two election timeouts. This allows enough time
    // for servers to potentially timeout and elect a leader.
    else {
      cancelJoinTimer();
      joinTimer = threadContext.schedule(electionTimeout.multipliedBy(2), () -> {
        join(getActiveMemberStates().iterator(), attempts + 1, future);
      });
    }
  }

  /**
   * Cancels the join timeout.
   */
  private void cancelJoinTimer() {
    if (joinTimer != null) {
      LOGGER.debug("{} - Cancelling join timeout", member.getMember().serverAddress());
      joinTimer.cancel();
      joinTimer = null;
    }
  }

  /**
   * Leaves the cluster.
   */
  public CompletableFuture<Void> leave() {
    CompletableFuture<Void> future = new CompletableFuture<>();
    threadContext.execute(() -> {
      cancelHeartbeatTimer();
      if (getActiveMemberStates().isEmpty()) {
        LOGGER.debug("{} - Single member cluster. Transitioning directly to inactive.", member.getMember().serverAddress());
        transition(CopycatServer.State.INACTIVE);
        future.complete(null);
      } else {
        leave(future);
      }
    });
    return future;
  }

  /**
   * Attempts to leave the cluster.
   */
  private void leave(CompletableFuture<Void> future) {
    // Set a timer to retry the attempt to leave the cluster.
    leaveTimer = threadContext.schedule(electionTimeout, () -> {
      leave(future);
    });

    // Attempt to leave the cluster by submitting a LeaveRequest directly to the server state.
    // Non-leader states should forward the request to the leader if there is one. Leader states
    // will log, replicate, and commit the reconfiguration.
    state.leave(LeaveRequest.builder()
      .withMember(new Member(member.getMember().serverAddress(), member.getMember().clientAddress()))
      .build()).whenComplete((response, error) -> {
      if (error == null && response.status() == Response.Status.OK) {
        cancelLeaveTimer();
        configure(response.version(), response.members());
        transition(CopycatServer.State.INACTIVE);
        future.complete(null);
      }
    });
  }

  /**
   * Cancels the leave timeout.
   */
  private void cancelLeaveTimer() {
    if (leaveTimer != null) {
      LOGGER.debug("{} - Cancelling leave timeout", member.getMember().serverAddress());
      leaveTimer.cancel();
      leaveTimer = null;
    }
  }

  /**
   * Starts the heartbeat timer.
   */
  private void startHeartbeatTimer() {
    heartbeatTimer = threadContext.schedule(Duration.ZERO, HEARTBEAT_INTERVAL, this::heartbeat);
  }

  /**
   * Sends a heartbeat to the leader.
   */
  void heartbeat() {
    HeartbeatRequest request = HeartbeatRequest.builder()
      .withMember(member.getMember())
      .withCommitIndex(commitIndex)
      .build();

    state.heartbeat(request);
  }

  /**
   * Cancels the heartbeat timer.
   */
  private void cancelHeartbeatTimer() {
    if (heartbeatTimer != null) {
      LOGGER.debug("{} - Cancelling heartbeat timer", member.getMember().serverAddress());
      heartbeatTimer.cancel();
      heartbeatTimer = null;
    }
  }

  @Override
  public String toString() {
    return getClass().getCanonicalName();
  }

}

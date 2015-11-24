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

import io.atomix.catalyst.util.concurrent.ComposableFuture;
import io.atomix.catalyst.util.concurrent.Futures;
import io.atomix.catalyst.util.concurrent.ThreadContext;
import io.atomix.copycat.client.Command;
import io.atomix.copycat.client.error.InternalException;
import io.atomix.copycat.client.error.UnknownSessionException;
import io.atomix.copycat.server.StateMachine;
import io.atomix.copycat.server.storage.entry.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Raft server state machine.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
class ServerStateMachine implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(ServerStateMachine.class);
  private final StateMachine stateMachine;
  private final ServerState state;
  private final ServerStateMachineExecutor executor;
  private final ServerCommitPool commits;
  private long configuration;

  ServerStateMachine(StateMachine stateMachine, ServerState state, ServerStateMachineContext context, ThreadContext executor) {
    this.stateMachine = stateMachine;
    this.state = state;
    this.executor = new ServerStateMachineExecutor(context, executor);
    this.commits = new ServerCommitPool(state.getLog(), this.executor.context().sessions());
    init();
  }

  /**
   * Initializes the state machine.
   */
  private void init() {
    stateMachine.init(executor);
  }

  /**
   * Returns the server state machine executor.
   *
   * @return The server state machine executor.
   */
  ServerStateMachineExecutor executor() {
    return executor;
  }

  /**
   * Applies an entry to the state machine.
   *
   * @param entry The entry to apply.
   * @return The result.
   */
  CompletableFuture<?> apply(Entry entry) {
    return apply(entry, false);
  }

  /**
   * Applies an entry to the state machine.
   *
   * @param entry The entry to apply.
   * @param expectResult Indicates whether this call expects a result.
   * @return The result.
   */
  CompletableFuture<?> apply(Entry entry, boolean expectResult) {
    boolean apply = !(entry instanceof QueryEntry);
    try {
      if (!apply) {
        return apply((QueryEntry) entry);
      } else if (entry instanceof CommandEntry) {
        return apply((CommandEntry) entry, expectResult);
      } else if (entry instanceof RegisterEntry) {
        return apply((RegisterEntry) entry, expectResult);
      } else if (entry instanceof KeepAliveEntry) {
        return apply((KeepAliveEntry) entry);
      } else if (entry instanceof UnregisterEntry) {
        return apply((UnregisterEntry) entry, expectResult);
      } else if (entry instanceof HeartbeatEntry) {
        return apply((HeartbeatEntry) entry);
      } else if (entry instanceof NoOpEntry) {
        return apply((NoOpEntry) entry);
      } else if (entry instanceof ConnectEntry) {
        return apply((ConnectEntry) entry);
      } else if (entry instanceof ConfigurationEntry) {
        return apply((ConfigurationEntry) entry);
      }
      return Futures.exceptionalFuture(new InternalException("unknown state machine operation"));
    } finally {
      // After the entry has been applied, update the lastApplied index.
      if (apply) {
        state.setLastApplied(entry.getIndex());

        // Update the index for each session. This will be used to trigger queries that are awaiting the
        // application of specific indexes to the state machine. Setting the session index may cause query
        // callbacks to be called and queries to be evaluated.
        for (ServerSession session : executor.context().sessions().sessions.values()) {
          session.setVersion(entry.getIndex());
        }
      }
    }
  }

  /**
   * Applies a configuration entry to the internal state machine.
   * <p>
   * Configuration entries are applied to internal server state when written to the log. Thus, no significant
   * logic needs to take place in the handling of configuration entries. We simply clean the previous configuration
   * entry since it was overwritten by a more recent committed configuration entry.
   */
  private CompletableFuture<Void> apply(ConfigurationEntry entry) {
    long previousConfiguration = configuration;
    configuration = entry.getIndex();
    // Immediately clean the commit for the previous configuration since configuration entries
    // completely override the previous configuration.
    if (previousConfiguration > 0) {
      state.getLog().clean(previousConfiguration);
    }
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Applies connect entry to the state machine.
   * <p>
   * Connect entries are applied to internal server state when written to the log. Thus, no significant logic needs
   * to take place in the handling of connect entries. We simply clean the previous connect entry for the session
   * from the log. This ensures that the most recent connection is always retained in the log and replicated. Note
   * that connection indexes are not stored when applied to the internal state since a ConnectEntry may be applied
   * but never committed. Storing indexes in the internal state machine ensures that the stored index is committed
   * and will therefore be retained in the log.
   */
  private CompletableFuture<Void> apply(ConnectEntry entry) {
    // Connections are stored in the state machine when they're *written* to the log, so we need only
    // clean them once they're committed.
    ServerSession session = executor().context().sessions().getSession(entry.getSession());
    if (session != null) {
      long previousIndex = session.getConnect();
      session.setConnect(entry.getIndex());
      if (previousIndex > 0) {
        state.getLog().clean(previousIndex);
      }
    }
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Applies register session entry to the state machine.
   * <p>
   * Register entries are applied to the state machine to create a new session. The resulting session ID is the
   * index of the RegisterEntry. Once a new session is registered, we call register() on the state machine.
   * In the event that the {@code synchronous} flag is set, that indicates that the registration command expects a
   * response, i.e. it was applied by a leader. In that case, any events published during the execution of the
   * state machine's register() method must be completed synchronously prior to the completion of the returned future.
   */
  private CompletableFuture<Long> apply(RegisterEntry entry, boolean synchronous) {
    ServerSession session = new ServerSession(entry.getIndex(), executor.context(), entry.getTimeout());
    executor.context().sessions().registerSession(session);

    // Allow the executor to execute any scheduled events.
    long timestamp = executor.tick(entry.getTimestamp());

    // Update the session timestamp *after* executing any scheduled operations. The executor's timestamp
    // is guaranteed to be monotonically increasing, whereas the RegisterEntry may have an earlier timestamp
    // if, e.g., it was written shortly after a leader change.
    session.setTimestamp(timestamp);

    // Determine whether any sessions appear to be expired. This won't immediately expire the session(s),
    // but it will make them available to be unregistered by the leader.
    suspectSessions(timestamp);

    ThreadContext context = ThreadContext.currentContextOrThrow();
    long index = entry.getIndex();

    // Call the register() method on the user-provided state machine to allow the state machine to react to
    // a new session being registered. User state machine methods are always called in the state machine thread.
    CompletableFuture<Long> future = new ComposableFuture<>();
    executor.executor().execute(() -> {
      // Update the state machine context with the register entry's index. This ensures that events published
      // within the register method will be properly associated with the unregister entry's index. All events
      // published during registration of a session are linearizable to ensure that clients receive related events
      // before the registration is completed.
      executor.context().update(index, Instant.ofEpochMilli(timestamp), synchronous, Command.ConsistencyLevel.LINEARIZABLE);

      // Register the session and then open it. This ensures that state machines cannot publish events to this
      // session before the client has learned of the session ID.
      stateMachine.register(session);
      session.open();

      // Once register callbacks have been completed, ensure that events published during the callbacks are
      // received by clients. The state machine context will generate an event future for all published events
      // to all sessions.
      CompletableFuture<Void> sessionFuture = executor.context().commit();
      if (sessionFuture != null) {
        sessionFuture.whenComplete((result, error) -> {
          context.executor().execute(() -> future.complete(index));
        });
      } else {
        context.executor().execute(() -> future.complete(index));
      }
    });

    // Update the highest index completed for all sessions to allow log compaction to progress.
    updateLastCompleted(index);

    return future;
  }

  /**
   * Applies a session keep alive entry to the state machine.
   * <p>
   * Keep alive entries are applied to the internal state machine to reset the timeout for a specific session.
   * If the session indicated by the KeepAliveEntry is still held in memory, we mark the session as trusted,
   * indicating that the client has committed a keep alive within the required timeout. Additionally, we check
   * all other sessions for expiration based on the timestamp provided by this KeepAliveEntry. Note that sessions
   * are never completely expired via this method. Leaders must explicitly commit an UnregisterEntry to expire
   * a session.
   * <p>
   * When a KeepAliveEntry is committed to the internal state machine, two specific fields provided in the entry
   * are used to update server-side session state. The {@code commandSequence} indicates the highest command for
   * which the session has received a successful response in the proper sequence. By applying the {@code commandSequence}
   * to the server session, we clear command output held in memory up to that point. The {@code eventVersion} indicates
   * the index up to which the client has received event messages in sequence for the session. Applying the
   * {@code eventVersion} to the server-side session results in events up to that index being removed from memory
   * as they were acknowledged by the client. It's essential that both of these fields be applied via entries committed
   * to the Raft log to ensure they're applied on all servers in sequential order.
   * <p>
   * Keep alive entries are retained in the log until the next time the client sends a keep alive entry or until the
   * client's session is expired. This ensures for sessions that have long timeouts, keep alive entries cannot be cleaned
   * from the log before they're replicated to some servers.
   */
  private CompletableFuture<Void> apply(KeepAliveEntry entry) {
    ServerSession session = executor.context().sessions().getSession(entry.getSession());

    // Update the deterministic executor time and allow the executor to execute any scheduled events.
    long timestamp = executor.tick(entry.getTimestamp());

    // Determine whether any sessions appear to be expired. This won't immediately expire the session(s),
    // but it will make them available to be unregistered by the leader. Note that it's safe to trigger
    // scheduled executor callbacks even if the keep-alive entry is for an unknown session since the
    // leader still committed the entry with its time and so time will still progress deterministically.
    suspectSessions(timestamp);

    CompletableFuture<Void> future;

    // If the server session is null, the session either never existed or already expired.
    if (session == null) {
      LOGGER.warn("Unknown session: " + entry.getSession());
      future = Futures.exceptionalFuture(new UnknownSessionException("unknown session: " + entry.getSession()));
    }
    // If the session exists, don't allow it to expire even if its expiration has passed since we still
    // managed to receive a keep alive request from the client before it was removed. This allows the
    // client some arbitrary leeway in keeping its session alive. It's up to the leader to explicitly
    // expire a session by committing an UnregisterEntry in order to ensure sessions can't be expired
    // during leadership changes.
    else {
      ThreadContext context = ThreadContext.currentContextOrThrow();

      // Store the index of the previous keep alive request.
      long previousIndex = session.getKeepAlive();

      // Set the session as trusted. This will prevent the leader from explicitly unregistering the
      // session if it hasn't done so already.
      session.trust();

      // Update the session's timestamp with the current state machine time.
      session.setTimestamp(timestamp);

      // Store the command/event sequence and event version instead of acquiring a reference to the entry.
      long commandSequence = entry.getCommandSequence();
      long eventVersion = entry.getEventVersion();

      // The keep-alive entry also serves to clear cached command responses and events from memory.
      // Remove responses and clear/resend events in the state machine thread to prevent thread safety issues.
      executor.executor().execute(() -> session.clearResponses(commandSequence).resendEvents(eventVersion));

      // Since the index of acked events in the session changed, update the highest index completed for all
      // sessions to allow log compaction to progress. This is only done during session operations and not
      // within sessions themselves.
      updateLastCompleted(entry.getIndex());

      // Update the session keep alive index for log cleaning.
      session.setKeepAlive(entry.getIndex());

      // If a prior keep alive entry was already committed, clean it as it no longer contributes to the session's state.
      if (previousIndex > 0) {
        state.getLog().clean(previousIndex);
      }

      future = new CompletableFuture<>();
      context.executor().execute(() -> future.complete(null));
    }

    // Immediately clean the keep alive entry from the log.
    state.getLog().clean(entry.getIndex());

    return future;
  }

  /**
   * Applies an unregister session entry to the state machine.
   * <p>
   * Unregister entries may either be committed by clients or by the cluster's leader. Clients will commit
   * an unregister entry when closing their session normally. Leaders will commit an unregister entry when
   * an expired session is detected. This ensures that sessions are never expired purely on gaps in the log
   * which may result from normal log cleaning or lengthy leadership changes.
   * <p>
   * If the session was unregistered by the client, the isExpired flag will be false. Sessions expired by
   * the client are only close()ed on the state machine but not expire()d. Alternatively, entries where
   * isExpired is true were committed by a leader. For expired sessions, the state machine's expire() method
   * is called before close().
   * <p>
   * State machines may publish events during the handling of session expired or closed events. If the
   * {@code synchronous} flag passed to this method is true, events published during the commitment of the
   * UnregisterEntry must be synchronously completed prior to the completion of the returned future. This
   * ensures that state changes resulting from the expiration or closing of a session are completed before
   * the session close itself is completed.
   */
  private CompletableFuture<Void> apply(UnregisterEntry entry, boolean synchronous) {
    ServerSession session = executor.context().sessions().unregisterSession(entry.getSession());

    // Update the deterministic executor time and allow the executor to execute any scheduled events.
    long timestamp = executor.tick(entry.getTimestamp());

    // Determine whether any sessions appear to be expired. This won't immediately expire the session(s),
    // but it will make them available to be unregistered by the leader. Note that it's safe to trigger
    // scheduled executor callbacks even if the keep-alive entry is for an unknown session since the
    // leader still committed the entry with its time and so time will still progress deterministically.
    suspectSessions(timestamp);

    CompletableFuture<Void> future;

    // If the server session is null, the session either never existed or already expired.
    if (session == null) {
      LOGGER.warn("Unknown session: " + entry.getSession());
      future = Futures.exceptionalFuture(new UnknownSessionException("unknown session: " + entry.getSession()));
    }
    // If the session exists, don't allow it to expire even if its expiration has passed since we still
    // managed to receive a keep alive request from the client before it was removed.
    else {
      ThreadContext context = ThreadContext.currentContextOrThrow();
      future = new CompletableFuture<>();

      long index = entry.getIndex();

      // If the entry was marked expired, that indicates that the leader explicitly expired the session due to
      // the session not being kept alive by the client. In all other cases, we close the session normally.
      if (entry.isExpired()) {
        executor.executor().execute(() -> {
          // Update the state machine context with the unregister entry's index. This ensures that events published
          // within the expire or close methods will be properly associated with the unregister entry's index.
          // All events published during expiration or closing of a session are linearizable to ensure that clients
          // receive related events before the expiration is completed.
          executor.context().update(index, Instant.ofEpochMilli(timestamp), synchronous, Command.ConsistencyLevel.LINEARIZABLE);

          // Expire the session and call state machine callbacks.
          session.expire();
          stateMachine.expire(session);
          stateMachine.close(session);

          // Once expiration callbacks have been completed, ensure that events published during the callbacks
          // are published in batch. The state machine context will generate an event future for all published events
          // to all sessions. If the event future is non-null, that indicates events are pending which were published
          // during the call to expire(). Wait for the events to be received by the client before completing the future.
          CompletableFuture<Void> sessionFuture = executor.context().commit();
          if (sessionFuture != null) {
            sessionFuture.whenComplete((result, error) -> {
              context.executor().execute(() -> future.complete(null));
            });
          } else {
            context.executor().execute(() -> future.complete(null));
          }
        });
      }
      // If the unregister entry is not indicated as expired, a client must have submitted a request to unregister
      // the session. In that case, we simply close the session without expiring it.
      else {
        executor.executor().execute(() -> {
          // Update the state machine context with the unregister entry's index. This ensures that events published
          // within the close method will be properly associated with the unregister entry's index. All events published
          // during expiration or closing of a session are linearizable to ensure that clients receive related events
          // before the expiration is completed.
          executor.context().update(index, Instant.ofEpochMilli(timestamp), synchronous, Command.ConsistencyLevel.LINEARIZABLE);

          // Close the session and call state machine callbacks.
          session.close();
          stateMachine.close(session);

          // Once close callbacks have been completed, ensure that events published during the callbacks
          // are published in batch. The state machine context will generate an event future for all published events
          // to all sessions. If the event future is non-null, that indicates events are pending which were published
          // during the call to expire(). Wait for the events to be received by the client before completing the future.
          CompletableFuture<Void> sessionFuture = executor.context().commit();
          if (sessionFuture != null) {
            sessionFuture.whenComplete((result, error) -> {
              context.executor().execute(() -> future.complete(null));
            });
          } else {
            context.executor().execute(() -> future.complete(null));
          }
        });
      }

      // Clean the unregister entry from the log immediately after it's applied.
      state.getLog().clean(session.id());

      // Clean the session's last keep alive entry from the log if one exists.
      long keepAlive = session.getKeepAlive();
      if (keepAlive > 0) {
        state.getLog().clean(keepAlive);
      }

      // Update the highest index completed for all sessions. This will be used to indicate the highest
      // index for which logs can be compacted.
      updateLastCompleted(entry.getIndex());
    }

    // Immediately clean the unregister entry from the log.
    state.getLog().clean(entry.getIndex());

    return future;
  }

  /**
   * Applies a command entry to the state machine.
   * <p>
   * Command entries result in commands being executed on the user provided {@link StateMachine} and a
   * response being sent back to the client by completing the returned future. All command responses are
   * cached in the command's {@link ServerSession} for fault tolerance. In the event that the same command
   * is applied to the state machine more than once, the original response will be returned.
   * <p>
   * Command entries are written with a sequence number. The sequence number is used to ensure that
   * commands are applied to the state machine in sequential order. If a command entry has a sequence
   * number that is less than the next sequence number for the session, that indicates that it is a
   * duplicate of a command that was already applied. Otherwise, commands are assumed to have been
   * received in sequential order. The reason for this assumption is because leaders always sequence
   * commands as they're written to the log, so no sequence number will be skipped.
   * <p>
   * During the execution of a command, state machines may publish zero or many session events.
   * The command's {@link io.atomix.copycat.client.Command.ConsistencyLevel} and the {@code synchronous}
   * flag dictate how commands that publish session events should be handled. If {@code synchronous}
   * is {@code true}, that indicates a response is expected (this is the leader's state machine). For
   * linearizable commands, we wait for events to be received and acknowledged by their respective
   * clients.
   */
  private CompletableFuture<Object> apply(CommandEntry entry, boolean synchronous) {
    final CompletableFuture<Object> future = new CompletableFuture<>();

    // First check to ensure that the session exists.
    ServerSession session = executor.context().sessions().getSession(entry.getSession());

    // If the session is null then that indicates that the session already timed out or it never existed.
    // Return with an UnknownSessionException.
    if (session == null) {
      LOGGER.warn("Unknown session: " + entry.getSession());
      future.completeExceptionally(new UnknownSessionException("unknown session: " + entry.getSession()));
    }
    // If the command's sequence number is less than the next session sequence number then that indicates that
    // we've received a command that was previously applied to the state machine. Ensure linearizability by
    // returning the cached response instead of applying it to the user defined state machine.
    else if (entry.getSequence() > 0 && entry.getSequence() < session.nextSequence()) {
      // Ensure the response check is executed in the state machine thread in order to ensure the
      // command was applied, otherwise there will be a race condition and concurrent modification issues.
      ThreadContext context = ThreadContext.currentContextOrThrow();
      long sequence = entry.getSequence();

      // Get the consistency level of the command. This should match the consistency level of the original command.
      Command.ConsistencyLevel consistency = entry.getCommand().consistency();

      // Switch to the state machine thread and get the existing response.
      executor.executor().execute(() -> {

        // If the command's consistency level is not LINEARIZABLE or null (which are equivalent), return the
        // cached response immediately in the server thread.
        if (consistency == Command.ConsistencyLevel.NONE || consistency == Command.ConsistencyLevel.SEQUENTIAL) {
          Object response = session.getResponse(sequence);
          if (response == null) {
            context.executor().execute(() -> future.complete(null));
          } else if (response instanceof Throwable) {
            context.executor().execute(() -> future.completeExceptionally((Throwable) response));
          } else {
            context.executor().execute(() -> future.complete(response));
          }
        } else {
          // For linearizable commands, check whether a future is registered for the command. A future will be
          // registered if the original command resulted in publishing events to any session. For linearizable
          // commands, we wait until the event future is completed, indicating that all sessions to which event
          // messages were sent have received/acked the messages.
          CompletableFuture<Void> sessionFuture = session.getResponseFuture(sequence);
          if (sessionFuture != null) {
            sessionFuture.whenComplete((result, error) -> {
              Object response = session.getResponse(sequence);
              if (response == null) {
                context.executor().execute(() -> future.complete(null));
              } else if (response instanceof Throwable) {
                context.executor().execute(() -> future.completeExceptionally((Throwable) response));
              } else {
                context.executor().execute(() -> future.complete(response));
              }
            });
          } else {
            // If no event future was registered for the original command, return the cached response in the
            // server thread.
            Object response = session.getResponse(sequence);
            if (response == null) {
              context.executor().execute(() -> future.complete(null));
            } else if (response instanceof Throwable) {
              context.executor().execute(() -> future.completeExceptionally((Throwable) response));
            } else {
              context.executor().execute(() -> future.complete(response));
            }
          }
        }
      });
    }
    // If we've made it this far, the command must have been applied in the proper order as sequenced by the
    // session. This should be the case for most commands applied to the state machine.
    else {
      executeCommand(entry, session, synchronous, future, ThreadContext.currentContextOrThrow());
    }

    return future;
  }

  /**
   * Executes a state machine command.
   */
  private CompletableFuture<Object> executeCommand(CommandEntry entry, ServerSession session, boolean synchronous, CompletableFuture<Object> future, ThreadContext context) {
    context.checkThread();

    // Allow the executor to execute any scheduled events.
    long timestamp = executor.tick(entry.getTimestamp());
    long sequence = entry.getSequence();

    Command.ConsistencyLevel consistency = entry.getCommand().consistency();

    // Execute the command in the state machine thread. Once complete, the CompletableFuture callback will be completed
    // in the state machine thread. Register the result in that thread and then complete the future in the caller's thread.
    ServerCommit commit = commits.acquire(entry, timestamp);
    executor.executor().execute(() -> {

      // Update the state machine context with the commit index and local server context. The synchronous flag
      // indicates whether the server expects linearizable completion of published events. Events will be published
      // based on the configured consistency level for the context.
      executor.context().update(commit.index(), commit.time(), synchronous, consistency != null ? consistency : Command.ConsistencyLevel.LINEARIZABLE);

      try {
        // Execute the state machine operation and get the result.
        Object result = executor.executeOperation(commit);

        // Once the operation has been applied to the state machine, commit events published by the command.
        // The state machine context will build a composite future for events published to all sessions.
        CompletableFuture<Void> sessionFuture = executor.context().commit();

        // If the command consistency level is not LINEARIZABLE or null, register the response and complete the
        // command immediately in the state machine thread. Note that we don't store the event future since
        // the sequential nature of the command means we shouldn't need to block even on retries.
        if (consistency == Command.ConsistencyLevel.NONE || consistency == Command.ConsistencyLevel.SEQUENTIAL) {
          session.registerResponse(sequence, result, null);
          context.executor().execute(() -> future.complete(result));
        } else {
          // If the command consistency level is LINEARIZABLE, store the response with the event future. The stored
          // response will be used to provide linearizable semantics for commands resubmitted to the cluster.
          // If an event future was provided by the state machine context indicating that events were published by
          // the command, wait for the events to be received and acknowledged by the respective sessions before returning.
          session.registerResponse(sequence, result, sessionFuture);
          if (sessionFuture != null) {
            sessionFuture.whenComplete((sessionResult, sessionError) -> {
              context.executor().execute(() -> future.complete(result));
            });
          } else {
            context.executor().execute(() -> future.complete(result));
          }
        }
      } catch (Exception e) {
        // If an exception occurs during execution of the command, store the exception.
        session.registerResponse(sequence, e, null);
        context.executor().execute(() -> future.completeExceptionally(e));
      }
    });

    // Update the session timestamp and command sequence number. This is done in the caller's thread since all
    // timestamp/version/sequence checks are done in this thread prior to executing operations on the state machine thread.
    session.setTimestamp(timestamp).setSequence(sequence);

    return future;
  }

  /**
   * Applies a query entry to the state machine.
   * <p>
   * Query entries are applied to the user {@link StateMachine} for read-only operations.
   * Because queries are read-only, they may only be applied on a single server in the cluster,
   * and query entries do not go through the Raft log. Thus, it is critical that measures be taken
   * to ensure clients see a consistent view of the cluster event when switching servers. To do so,
   * clients provide a sequence and version number for each query. The sequence number is the order
   * in which the query was sent by the client. Sequence numbers are shared across both commands and
   * queries. The version number indicates the last index for which the client saw a command or query
   * response. In the event that the lastApplied index of this state machine does not meet the provided
   * version number, we wait for the state machine to catch up before applying the query. This ensures
   * clients see state progress monotonically even when switching servers.
   * <p>
   * Because queries may only be applied on a single server in the cluster they cannot result in the
   * publishing of session events. Events require commands to be written to the Raft log to ensure
   * fault-tolerance and consistency across the cluster.
   */
  private CompletableFuture<Object> apply(QueryEntry entry) {
    ServerSession session = executor.context().sessions().getSession(entry.getSession());

    // If the session is null then that indicates that the session already timed out or it never existed.
    // Return with an UnknownSessionException.
    if (session == null) {
      LOGGER.warn("Unknown session: " + entry.getSession());
      return Futures.exceptionalFuture(new UnknownSessionException("unknown session " + entry.getSession()));
    }
    // Query execution is determined by the sequence and version supplied for the query. All queries are queued until the state
    // machine advances at least until the provided sequence and version.
    // If the query sequence number is greater than the current sequence number for the session, queue the query.
    else if (entry.getSequence() > session.getSequence()) {
      CompletableFuture<Object> future = new CompletableFuture<>();

      // Get the caller's context.
      ThreadContext context = ThreadContext.currentContextOrThrow();

      // Store the entry version and sequence instead of acquiring a reference to the entry.
      long version = entry.getVersion();
      long sequence = entry.getSequence();

      // Once the query has met its sequence requirement, check whether it has also met its version requirement. If the version
      // requirement is not yet met, queue the query for the state machine to catch up to the required version.
      ServerCommit commit = commits.acquire(entry, executor.timestamp());
      session.registerSequenceQuery(sequence, () -> {
        context.checkThread();
        if (version > session.getVersion()) {
          session.registerVersionQuery(version, () -> {
            context.checkThread();
            executeQuery(commit, future, context);
          });
        } else {
          executeQuery(commit, future, context);
        }
      });
      return future;
    }
    // If the query version number is greater than the current version number for the session, queue the query.
    else if (entry.getVersion() > session.getVersion()) {
      CompletableFuture<Object> future = new CompletableFuture<>();

      ThreadContext context = ThreadContext.currentContextOrThrow();

      // Register the query to be executed once the version number reaches the request version number.
      ServerCommit commit = commits.acquire(entry, executor.timestamp());
      session.registerVersionQuery(entry.getVersion(), () -> {
        context.checkThread();
        executeQuery(commit, future, context);
      });
      return future;
    } else {
      return executeQuery(commits.acquire(entry, executor.timestamp()), new CompletableFuture<>(), ThreadContext.currentContextOrThrow());
    }
  }

  /**
   * Executes a state machine query.
   */
  private CompletableFuture<Object> executeQuery(ServerCommit commit, CompletableFuture<Object> future, ThreadContext context) {
    // Execute the query in the state machine thread.
    executor.executor().execute(() -> {
      // Once in the state machine thread, update the current state machine index and time. The consistency level
      // is set to null to indicate that session events cannot be published in the current context. Session events
      // may not be published in response to queries since they may only be applied on a single server.
      executor.context().update(commit.index(), commit.time(), true, null);
      try {
        Object result = executor.executeOperation(commit);
        context.executor().execute(() -> future.complete(result));
      } catch (Exception e) {
        context.executor().execute(() -> future.completeExceptionally(e));
      }
    });
    return future;
  }

  /**
   * Applies a heartbeat entry to the state machine.
   * <p>
   * Heartbeat entries are sent by servers within the current configuration to indicate availability.
   * In the event that a server does not send a heartbeat for more than the configured heartbeat timeout,
   * the member will be considered UNAVAILABLE and may be demoted by the leader if it can be replaced.
   * <p>
   * Heartbeat entries also inform the calculation of the cluster's {@code globalIndex}. The global index
   * is the lowest index for which entries have been stored on all servers. This is critical to determining
   * when it's safe to remove tombstones from the log during compaction. The usage of heartbeats to determine
   * server availability allows servers that are unavailable for long periods of time to be demoted to allow
   * the global index and thus log compaction to progress.
   */
  private CompletableFuture<Boolean> apply(HeartbeatEntry entry) {
    long timestamp = executor.tick(entry.getTimestamp());

    boolean changed = false;

    // Set the member status to AVAILABLE and update the member heartbeat time.
    MemberState member = state.getMemberState(entry.getMember());
    if (member != null) {
      // Store the previous heartbeat index. This can be used to clean the previous heartbeat entry.
      long previousIndex = member.getHeartbeatIndex();

      // Update the member state.
      changed = member.getStatus() == MemberState.Status.UNAVAILABLE;
      member.setHeartbeatTime(timestamp)
        .setHeartbeatIndex(entry.getIndex())
        .setStatus(MemberState.Status.AVAILABLE)
        .setCommitIndex(entry.getCommitIndex());

      // If the previous heartbeat index is non-zero then clean the entry. This ensures that the last
      // heartbeat from each member is always retained in the log so that commitIndex is always consistent
      // across servers.
      if (previousIndex > 0) {
        state.getLog().clean(previousIndex);
      }
    }

    // Iterate through all members and update statuses based on the heartbeat time.
    for (MemberState memberState : state.getMemberStates()) {
      if (timestamp - memberState.getHeartbeatTime() > memberState.getHeartbeatTimeout()) {
        memberState.setStatus(MemberState.Status.UNAVAILABLE);
        changed = true;
      }
    }

    // The global index is calculated by the minimum commitIndex for *all* stateful members in the cluster, including
    // passive members. This is critical since passive members still have state machines and thus it's still
    // important to ensure that tombstones are applied to their state machines.
    // If the members list is empty, use the local server's last log index as the global index.
    long activeIndex = state.getActiveMemberStates().stream().mapToLong(MemberState::getCommitIndex).min().orElse(state.getLog().lastIndex());
    long passiveIndex = state.getPassiveMemberStates().stream().mapToLong(MemberState::getCommitIndex).min().orElse(state.getLog().lastIndex());
    state.setGlobalIndex(Math.max(Math.min(activeIndex, passiveIndex), 0));

    return Futures.completedFutureAsync(changed, ThreadContext.currentContextOrThrow().executor());
  }

  /**
   * Applies a no-op entry to the state machine.
   * <p>
   * No-op entries are committed by leaders at the start of their term. Typically, no-op entries
   * serve as a mechanism to allow leaders to commit entries from prior terms. However, we extend
   * the functionality of the no-op entry to use it as an indicator that a leadership change occurred.
   * In order to ensure timeouts do not expire during lengthy leadership changes, we use no-op entries
   * to reset timeouts for client sessions and server heartbeats.
   */
  private CompletableFuture<Long> apply(NoOpEntry entry) {
    // Iterate through all the server sessions and reset timestamps. This ensures that sessions do not
    // timeout during leadership changes or shortly thereafter.
    long timestamp = executor.tick(entry.getTimestamp());

    // Reset the timestamp for AVAILABLE member states in order to ensure members aren't marked UNAVAILABLE
    // due to lengthy leadership changes.
    for (MemberState memberState : state.getMemberStates()) {
      if (memberState.getStatus() == MemberState.Status.AVAILABLE) {
        memberState.setHeartbeatTime(timestamp);
      }
    }

    // Reset the timestamp for all sessions in order to ensure sessions aren't marked suspicious or
    // expired due to lengthy leadership changes.
    for (ServerSession session : executor.context().sessions().sessions.values()) {
      session.setTimestamp(timestamp);
    }

    // Immediately clean the no-op entry from the log. Note that no-op entries are marked as tombstones
    // and as such will be retained in the log until applied on all active servers, so there's no risk
    // of a server failing to reset session timeouts due to a cleaned no-op entry.
    state.getLog().clean(entry.getIndex());

    return Futures.completedFutureAsync(entry.getIndex(), ThreadContext.currentContextOrThrow().executor());
  }

  /**
   * Updates the last completed event version based on a commit at the given index.
   */
  private void updateLastCompleted(long index) {
    long lastCompleted = index;

    // Iterate through sessions and find the lowest lastCompleted index.
    for (ServerSession session : executor.context().sessions().sessions.values()) {
      lastCompleted = Math.min(lastCompleted, session.getLastCompleted());
    }

    // Update the last completed index.
    state.setLastCompleted(lastCompleted);
  }

  /**
   * Suspects any sessions that have timed out.
   */
  private void suspectSessions(long timestamp) {
    for (ServerSession session : executor.context().sessions().sessions.values()) {
      if (timestamp - session.timeout() > session.getTimestamp()) {
        session.suspect();
      }
    }
  }

  @Override
  public void close() {
    executor.close();
  }

}

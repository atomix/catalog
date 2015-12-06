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

import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.transport.Address;
import io.atomix.catalyst.transport.LocalServerRegistry;
import io.atomix.catalyst.transport.LocalTransport;
import io.atomix.catalyst.transport.Transport;
import io.atomix.catalyst.util.concurrent.SingleThreadContext;
import io.atomix.catalyst.util.concurrent.ThreadContext;
import io.atomix.copycat.client.Command;
import io.atomix.copycat.client.Query;
import io.atomix.copycat.client.session.Session;
import io.atomix.copycat.server.CopycatServer;
import io.atomix.copycat.server.StateMachine;
import io.atomix.copycat.server.storage.Log;
import io.atomix.copycat.server.storage.Storage;
import io.atomix.copycat.server.storage.StorageLevel;
import io.atomix.copycat.server.storage.entry.*;
import io.atomix.copycat.server.storage.snapshot.SnapshotStore;
import io.atomix.copycat.server.storage.system.MetaStore;
import net.jodah.concurrentunit.ConcurrentTestCase;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

/**
 * State machine test case.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public abstract class StateMachineTestCase extends ConcurrentTestCase {
  private ThreadContext callerContext;
  private ThreadContext stateContext;
  private ServerState state;
  private Map<Long, Long> sequence;
  private MetaStore meta;
  private Log log;
  private SnapshotStore snapshot;

  @BeforeMethod
  public void setupStateMachine() {
    sequence = new HashMap<>();
    callerContext = new SingleThreadContext("caller", new Serializer());
    stateContext = new SingleThreadContext("state", new Serializer());
    Transport transport = new LocalTransport(new LocalServerRegistry());
    Storage storage = new Storage(StorageLevel.MEMORY);
    meta = storage.openMetaStore("test");
    log = storage.openLog("test");
    snapshot = storage.openSnapshotStore("test");
    Member member = new Member(CopycatServer.Type.ACTIVE, new Address("localhost", 5000), new Address("localhost", 6000));
    Collection<Address> members = Arrays.asList(
      new Address("localhost", 5000),
      new Address("localhost", 5000),
      new Address("localhost", 5000)
    );
    state = new ServerState(member, members, meta, log, snapshot, createStateMachine(), new ConnectionManager(transport.client()), callerContext);
  }

  /**
   * Creates a new state machine.
   */
  protected abstract StateMachine createStateMachine();

  /**
   * Registers a new session.
   */
  protected Session register(long index, long timestamp) throws Throwable {
    callerContext.execute(() -> {

      RegisterEntry entry = new RegisterEntry()
        .setIndex(index)
        .setTerm(1)
        .setTimestamp(timestamp)
        .setTimeout(500)
        .setClient(UUID.randomUUID());

      state.getStateMachine().apply(entry).whenComplete((result, error) -> {
        threadAssertNull(error);
        resume();
      });
    });

    await();

    ServerSession session = state.getStateMachine().executor().context().sessions().getSession(index);
    assertNotNull(session);
    assertEquals(session.id(), index);
    assertEquals(session.getTimestamp(), timestamp);

    return session;
  }

  /**
   * Keeps a session alive.
   */
  protected Session keepAlive(long index, long timestamp, Session session) throws Throwable {
    callerContext.execute(() -> {

      KeepAliveEntry entry = new KeepAliveEntry()
        .setIndex(index)
        .setTerm(1)
        .setSession(session.id())
        .setTimestamp(timestamp)
        .setCommandSequence(0)
        .setEventVersion(0);

      state.getStateMachine().apply(entry).whenComplete((result, error) -> {
        threadAssertNull(error);
        resume();
      });
    });

    await();

    assertEquals(((ServerSession) session).getTimestamp(), timestamp);
    return session;
  }

  /**
   * Applies the given command to the state machine.
   *
   * @param session The session for which to apply the command.
   * @param command The command to apply.
   * @return The command output.
   */
  protected <T> T apply(long index, long timestamp, Session session, Command<T> command) throws Throwable {
    Long sequence = this.sequence.get(session.id());
    sequence = sequence != null ? sequence + 1 : 1;
    this.sequence.put(session.id(), sequence);

    CommandEntry entry = new CommandEntry()
      .setIndex(index)
      .setTerm(1)
      .setSession(session.id())
      .setTimestamp(timestamp)
      .setSequence(1)
      .setCommand(command);

    return apply(entry);
  }

  /**
   * Applies the given query to the state machine.
   *
   * @param session The session for which to apply the query.
   * @param query The query to apply.
   * @return The query output.
   */
  protected <T> T apply(long timestamp, Session session, Query<T> query) throws Throwable {
    QueryEntry entry = new QueryEntry()
      .setIndex(state.getStateMachine().getLastApplied())
      .setTerm(1)
      .setSession(session.id())
      .setTimestamp(timestamp)
      .setSequence(0)
      .setVersion(0)
      .setQuery(query);

    return apply(entry);
  }

  /**
   * Applies the given entry to the state machine.
   *
   * @param entry The entry to apply.
   * @return The entry output.
   */
  @SuppressWarnings("unchecked")
  private <T> T apply(Entry entry) throws Throwable {
    AtomicReference<Object> reference = new AtomicReference<>();
    callerContext.execute(() -> state.getStateMachine().apply(entry).whenComplete((result, error) -> {
      if (error == null) {
        reference.set(result);
      } else {
        reference.set(error);
      }
      resume();
    }));

    await();

    Object result = reference.get();
    if (result instanceof Throwable) {
      throw (Throwable) result;
    }
    return (T) result;
  }

  @AfterMethod
  public void teardownStateMachine() {
    state.getStateMachine().close();
    meta.close();
    log.close();
    stateContext.close();
    callerContext.close();
  }

}

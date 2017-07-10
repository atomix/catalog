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
 * limitations under the License
 */
package io.atomix.copycat.examples;

import io.atomix.catalyst.transport.Address;
import io.atomix.catalyst.transport.netty.NettyTransport;
import io.atomix.copycat.client.CommunicationStrategies;
import io.atomix.copycat.client.ConnectionStrategies;
import io.atomix.copycat.client.CopycatClient;
import io.atomix.copycat.client.session.CopycatSession;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Value client example. Expects at least 1 argument:
 * <p>
 * <ul>
 * <li>host:port pairs - the host address and port of cluster members</li>
 * </ul>
 * <p>Example cluster arguments: <pre>10.0.1.10:5000 10.0.1.11:5001 10.0.1.12:5002</pre>
 * <p>Example single node arguments: <pre>localhost:5000</pre>
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public class ValueClientExample {

  /**
   * Starts the client.
   */
  public static void main(String[] args) throws Exception {
    if (args.length < 1)
      throw new IllegalArgumentException("must supply a set of host:port tuples");

    // Build a list of all member addresses to which to connect.
    List<Address> members = new ArrayList<>();
    for (String arg : args) {
      String[] parts = arg.split(":");
      members.add(new Address(parts[0], Integer.valueOf(parts[1])));
    }

    CopycatClient client = CopycatClient.builder()
      .withTransport(new NettyTransport())
      .withConnectionStrategy(ConnectionStrategies.FIBONACCI_BACKOFF)
      .withServerSelectionStrategy(CommunicationStrategies.LEADER)
      .withSessionTimeout(Duration.ofSeconds(15))
      .build();

    client.serializer().register(SetCommand.class, 1);
    client.serializer().register(GetQuery.class, 2);
    client.serializer().register(DeleteCommand.class, 3);

    client.connect(members).join();

    CopycatSession session = client.sessionBuilder()
      .withType("value")
      .withName("test")
      .build();

    recursiveSet(session);

    while (client.state() != CopycatClient.State.CLOSED) {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        break;
      }
    }
  }

  /**
   * Recursively sets state machine values.
   */
  private static void recursiveSet(CopycatSession session) {
    session.submit(new SetCommand(UUID.randomUUID().toString())).whenComplete((result, error) -> {
      session.context().schedule(Duration.ofSeconds(5), () -> recursiveSet(session));
    });
  }

}

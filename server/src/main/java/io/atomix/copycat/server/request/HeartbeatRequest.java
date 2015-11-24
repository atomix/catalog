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
package io.atomix.copycat.server.request;

import io.atomix.catalyst.buffer.BufferInput;
import io.atomix.catalyst.buffer.BufferOutput;
import io.atomix.catalyst.serializer.SerializeWith;
import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.util.Assert;
import io.atomix.copycat.client.request.AbstractRequest;
import io.atomix.copycat.server.state.Member;

import java.util.Objects;

/**
 * Server heartbeat request.
 * <p>
 * Heartbeat requests are sent by all servers to the current leader to aid in determining availability
 * of servers throughout the cluster. Copycat dynamically resizes clusters based on server availability.
 * To do so, each server is responsible for sending a periodic heartbeat at a fixed interval. If a server
 * becomes disconnected from the leader, it will eventually be considered unavailable.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@SerializeWith(id=217)
public class HeartbeatRequest extends AbstractRequest<HeartbeatRequest> {

  /**
   * Returns a new heartbeat request builder.
   *
   * @return A new heartbeat request builder.
   */
  public static Builder builder() {
    return new Builder(new HeartbeatRequest());
  }

  /**
   * Returns an heartbeat request builder for an existing request.
   *
   * @param request The request to build.
   * @return The heartbeat request builder.
   */
  public static Builder builder(HeartbeatRequest request) {
    return new Builder(request);
  }

  protected Member member;
  protected long commitIndex;

  /**
   * Returns the heartbeat member.
   *
   * @return The heartbeat member.
   */
  public Member member() {
    return member;
  }

  /**
   * Returns the member commit index.
   *
   * @return The member commit index.
   */
  public long commitIndex() {
    return commitIndex;
  }

  @Override
  public void writeObject(BufferOutput<?> buffer, Serializer serializer) {
    serializer.writeObject(member, buffer);
    buffer.writeLong(commitIndex);
  }

  @Override
  public void readObject(BufferInput<?> buffer, Serializer serializer) {
    member = serializer.readObject(buffer);
    commitIndex = buffer.readLong();
  }

  @Override
  public int hashCode() {
    return Objects.hash(getClass(), member, commitIndex);
  }

  @Override
  public boolean equals(Object object) {
    if (object instanceof HeartbeatRequest) {
      HeartbeatRequest request = (HeartbeatRequest) object;
      return request.member == member && request.commitIndex == commitIndex;
    }
    return false;
  }

  @Override
  public String toString() {
    return String.format("%s[member=%s, commitIndex=%d]", getClass().getSimpleName(), member, commitIndex);
  }

  /**
   * Heartbeat request builder.
   */
  public static class Builder extends AbstractRequest.Builder<Builder, HeartbeatRequest> {
    protected Builder(HeartbeatRequest request) {
      super(request);
    }

    /**
     * Sets the request member.
     *
     * @param member The request member.
     * @return The request builder.
     * @throws NullPointerException if {@code member} is null
     */
    public Builder withMember(Member member) {
      request.member = Assert.notNull(member, "member");
      return this;
    }

    /**
     * Sets the member commit index.
     *
     * @param commitIndex The member commit index.
     * @return The request builder.
     */
    public Builder withCommitIndex(long commitIndex) {
      request.commitIndex = Assert.argNot(commitIndex, commitIndex < 0, "commitIndex must be positive");
      return this;
    }

    /**
     * @throws IllegalStateException if member is null
     */
    @Override
    public HeartbeatRequest build() {
      super.build();
      Assert.notNull(request.member, "member");
      return request;
    }
  }

}

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
package io.atomix.catalog.client.response;

import io.atomix.catalog.client.error.RaftError;
import io.atomix.catalyst.buffer.BufferInput;
import io.atomix.catalyst.buffer.BufferOutput;
import io.atomix.catalyst.serializer.SerializeWith;
import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.transport.Address;
import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.util.BuilderPool;
import io.atomix.catalyst.util.ReferenceManager;

import java.util.Collection;
import java.util.Objects;

/**
 * Protocol keep alive response.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@SerializeWith(id=263)
public class KeepAliveResponse extends SessionResponse<KeepAliveResponse> {

  /**
   * The unique identifier for the keep alive response type.
   */
  public static final byte TYPE = 0x0A;

  private static final BuilderPool<Builder, KeepAliveResponse> POOL = new BuilderPool<>(Builder::new);

  /**
   * Returns a new keep alive response builder.
   *
   * @return A new keep alive response builder.
   */
  public static Builder builder() {
    return POOL.acquire();
  }

  /**
   * Returns a keep alive response builder for an existing response.
   *
   * @param response The response to build.
   * @return The keep alive response builder.
   * @throws NullPointerException if {@code response} is null
   */
  public static Builder builder(KeepAliveResponse response) {
    return POOL.acquire(Assert.notNull(response, "response"));
  }

  private Collection<Address> members;

  /**
   * @throws NullPointerException if {@code referenceManager} is null
   */
  public KeepAliveResponse(ReferenceManager<KeepAliveResponse> referenceManager) {
    super(referenceManager);
  }

  @Override
  public byte type() {
    return TYPE;
  }

  /**
   * Returns the cluster members.
   *
   * @return The cluster members.
   */
  public Collection<Address> members() {
    return members;
  }

  @Override
  public void readObject(BufferInput buffer, Serializer serializer) {
    status = Status.forId(buffer.readByte());
    if (status == Status.OK) {
      error = null;
      members = serializer.readObject(buffer);
    } else {
      error = RaftError.forId(buffer.readByte());
    }
  }

  @Override
  public void writeObject(BufferOutput buffer, Serializer serializer) {
    buffer.writeByte(status.id());
    if (status == Status.OK) {
      serializer.writeObject(members, buffer);
    } else {
      buffer.writeByte(error.id());
    }
  }

  @Override
  public int hashCode() {
    return Objects.hash(getClass(), status);
  }

  @Override
  public boolean equals(Object object) {
    if (object instanceof KeepAliveResponse) {
      KeepAliveResponse response = (KeepAliveResponse) object;
      return response.status == status
        && ((response.members == null && members == null)
        || (response.members != null && members != null && response.members.equals(members)));
    }
    return false;
  }

  @Override
  public String toString() {
    return String.format("%s[status=%s, members=%s]", getClass().getSimpleName(), status, members);
  }

  /**
   * Status response builder.
   */
  public static class Builder extends SessionResponse.Builder<Builder, KeepAliveResponse> {

    protected Builder(BuilderPool<Builder, KeepAliveResponse> pool) {
      super(pool, KeepAliveResponse::new);
    }

    @Override
    protected void reset() {
      super.reset();
      response.members = null;
    }

    /**
     * Sets the response members.
     *
     * @param members The response members.
     * @return The response builder.
     * @throws NullPointerException if {@code members} is null
     */
    public Builder withMembers(Collection<Address> members) {
      response.members = Assert.notNull(members, "members");
      return this;
    }

    /**
     * @throws IllegalStateException if status is OK and members is null
     */
    @Override
    public KeepAliveResponse build() {
      super.build();
      Assert.stateNot(response.status == Status.OK && response.members == null, "members cannot be null");
      return response;
    }
  }

}

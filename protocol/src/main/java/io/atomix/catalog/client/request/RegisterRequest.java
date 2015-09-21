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
package io.atomix.catalog.client.request;

import io.atomix.catalyst.buffer.BufferInput;
import io.atomix.catalyst.buffer.BufferOutput;
import io.atomix.catalyst.serializer.SerializeWith;
import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.util.BuilderPool;
import io.atomix.catalyst.util.ReferenceManager;

import java.util.Objects;
import java.util.UUID;

/**
 * Protocol register client request.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@SerializeWith(id=272)
public class RegisterRequest extends AbstractRequest<RegisterRequest> {

  /**
   * The unique identifier for the register request type.
   */
  public static final byte TYPE = 0x07;

  private static final BuilderPool<Builder, RegisterRequest> POOL = new BuilderPool<>(Builder::new);

  /**
   * Returns a new register client request builder.
   *
   * @return A new register client request builder.
   */
  public static Builder builder() {
    return POOL.acquire();
  }

  /**
   * Returns a register client request builder for an existing request.
   *
   * @param request The request to build.
   * @return The register client request builder.
   * @throws NullPointerException if {@code request} is null
   */
  public static Builder builder(RegisterRequest request) {
    return POOL.acquire(Assert.notNull(request, "request"));
  }

  private UUID connection;

  /**
   * @throws NullPointerException if {@code referenceManager} is null
   */
  public RegisterRequest(ReferenceManager<RegisterRequest> referenceManager) {
    super(referenceManager);
  }

  @Override
  public byte type() {
    return TYPE;
  }

  /**
   * Returns the connection ID.
   *
   * @return The connection ID.
   */
  public UUID connection() {
    return connection;
  }

  @Override
  public void writeObject(BufferOutput buffer, Serializer serializer) {
    serializer.writeObject(connection, buffer);
  }

  @Override
  public void readObject(BufferInput buffer, Serializer serializer) {
    connection = serializer.readObject(buffer);
  }

  @Override
  public int hashCode() {
    return Objects.hash(getClass(), connection);
  }

  @Override
  public boolean equals(Object object) {
    if (object instanceof RegisterRequest) {
      RegisterRequest request = (RegisterRequest) object;
      return request.connection.equals(connection);
    }
    return false;
  }

  @Override
  public String toString() {
    return String.format("%s", getClass().getSimpleName());
  }

  /**
   * Register client request builder.
   */
  public static class Builder extends AbstractRequest.Builder<Builder, RegisterRequest> {

    /**
     * @throws NullPointerException if {@code pool} is null
     */
    protected Builder(BuilderPool<Builder, RegisterRequest> pool) {
      super(pool, RegisterRequest::new);
    }

    @Override
    protected void reset() {
      super.reset();
      request.connection = null;
    }

    /**
     * Sets the connection ID.
     *
     * @param connection The connection ID.
     * @return The request builder.
     * @throws NullPointerException if {@code connection} is null
     */
    public Builder withConnection(UUID connection) {
      request.connection = Assert.notNull(connection, "connection");
      return this;
    }

    /**
     * @throws IllegalStateException if connection is null
     */
    @Override
    public RegisterRequest build() {
      super.build();
      Assert.stateNot(request.connection == null, "connection");
      return request;
    }
  }

}

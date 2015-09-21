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

import io.atomix.catalyst.serializer.SerializeWith;
import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.util.BuilderPool;
import io.atomix.catalyst.util.ReferenceManager;

/**
 * Protocol command response.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@SerializeWith(id=259)
public class CommandResponse extends OperationResponse<CommandResponse> {

  /**
   * The unique identifier for the command response type.
   */
  public static final byte TYPE = 0x02;

  private static final BuilderPool<Builder, CommandResponse> POOL = new BuilderPool<>(Builder::new);

  /**
   * Returns a new submit response builder.
   *
   * @return A new submit response builder.
   */
  public static Builder builder() {
    return POOL.acquire();
  }

  /**
   * Returns a submit response builder for an existing request.
   *
   * @param request The response to build.
   * @return The submit response builder.
   * @throws NullPointerException if {@code request} is null
   */
  public static Builder builder(CommandResponse request) {
    return POOL.acquire(Assert.notNull(request, "request"));
  }

  /**
   * @throws NullPointerException if {@code referenceManager} is null
   */
  public CommandResponse(ReferenceManager<CommandResponse> referenceManager) {
    super(referenceManager);
  }

  @Override
  public byte type() {
    return TYPE;
  }

  /**
   * Command response builder.
   */
  public static class Builder extends OperationResponse.Builder<Builder, CommandResponse> {
    protected Builder(BuilderPool<Builder, CommandResponse> pool) {
      super(pool, CommandResponse::new);
    }
  }

}

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

import io.atomix.catalyst.util.*;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstract request implementation.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public abstract class AbstractRequest<T extends Request<T>> implements Request<T> {
  private final AtomicInteger references = new AtomicInteger();
  private final ReferenceManager<T> referenceManager;

  /**
   * @throws NullPointerException if {@code referenceManager} is null
   */
  protected AbstractRequest(ReferenceManager<T> referenceManager) {
    this.referenceManager = Assert.notNull(referenceManager, "referenceManager");
  }

  @Override
  @SuppressWarnings("unchecked")
  public T acquire() {
    references.incrementAndGet();
    return (T) this;
  }

  @Override
  @SuppressWarnings("unchecked")
  public boolean release() {
    int refs = references.decrementAndGet();
    if (refs == 0) {
      referenceManager.release((T) this);
      return true;
    } else if (refs < 0) {
      references.set(0);
      throw new IllegalStateException("cannot dereference non-referenced object");
    }
    return false;
  }

  @Override
  public int references() {
    return references.get();
  }

  @Override
  @SuppressWarnings("unchecked")
  public void close() {
    if (references.get() == 0)
      throw new IllegalStateException("cannot close non-referenced object");
    references.set(0);
    referenceManager.release((T) this);
  }

  /**
   * Abstract request builder.
   *
   * @param <T> The builder type.
   * @param <U> The request type.
   */
  protected static abstract class Builder<T extends Builder<T, U>, U extends AbstractRequest<U>> extends Request.Builder<T, U> {
    protected final ReferencePool<U> pool;
    protected U request;

    /**
     * @throws NullPointerException if {@code pool} or {@code factory} are null
     */
    protected Builder(BuilderPool<T, U> pool, ReferenceFactory<U> factory) {
      super(pool);
      this.pool = new ReferencePool<>(factory);
    }

    @Override
    protected void reset() {
      request = pool.acquire();
    }

    @Override
    protected void reset(U request) {
      this.request = Assert.notNull(request, "request");
    }

    @Override
    public U build() {
      close();
      return request;
    }

    @Override
    public int hashCode() {
      return Objects.hash(request);
    }

    @Override
    public boolean equals(Object object) {
      return getClass().isAssignableFrom(object.getClass()) && ((Builder) object).request.equals(request);
    }

    @Override
    public String toString() {
      return String.format("%s[request=%s]", getClass().getCanonicalName(), request);
    }
  }

}

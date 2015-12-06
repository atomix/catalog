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
package io.atomix.copycat.server.storage.system;

import io.atomix.catalyst.util.Assert;
import io.atomix.copycat.server.state.Member;

import java.util.Collection;

/**
 * Stored server configuration.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public class Configuration {
  private final long version;
  private final Collection<Member> members;

  public Configuration(long version, Collection<Member> members) {
    this.version = version;
    this.members = Assert.notNull(members, "members");
  }

  /**
   * Returns the configuration version.
   *
   * @return The configuration version.
   */
  public long version() {
    return version;
  }

  /**
   * Returns the collection of active members.
   *
   * @return The collection of active members.
   */
  public Collection<Member> members() {
    return members;
  }

  @Override
  public String toString() {
    return String.format("%s[version=%d, members=%s]", getClass().getSimpleName(), version, members);
  }

}

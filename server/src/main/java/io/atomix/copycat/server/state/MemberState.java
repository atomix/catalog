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

import io.atomix.catalyst.util.Assert;
import io.atomix.copycat.server.storage.Log;

/**
 * Cluster member state.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
class MemberState {
  private Member member;
  private long term;
  private long version;
  private long snapshotIndex;
  private int snapshotOffset;
  private long matchIndex;
  private long nextIndex;
  private long commitTime;
  private long commitStartTime;
  private int failures;

  public MemberState(Member member) {
    this.member = Assert.notNull(member, "member");
  }

  /**
   * Resets the member state.
   */
  void resetState(Log log) {
    matchIndex = 0;
    nextIndex = log.lastIndex() + 1;
    commitTime = 0;
    commitStartTime = 0;
    failures = 0;
  }

  /**
   * Returs the member.
   *
   * @return The member.
   */
  public Member getMember() {
    return member;
  }

  /**
   * Sets the member.
   *
   * @param member The member.
   * @return The member state.
   */
  MemberState setMember(Member member) {
    this.member = Assert.notNull(member, "member");
    return this;
  }

  /**
   * Returns the member term.
   *
   * @return The member term.
   */
  long getTerm() {
    return term;
  }

  /**
   * Sets the member term.
   *
   * @param term The member term.
   * @return The member state.
   */
  MemberState setTerm(long term) {
    this.term = term;
    return this;
  }

  /**
   * Returns the member configuration version.
   *
   * @return The member configuration version.
   */
  long getVersion() {
    return version;
  }

  /**
   * Sets the member version.
   *
   * @param version The member version.
   * @return The member state.
   */
  MemberState setVersion(long version) {
    this.version = version;
    return this;
  }

  /**
   * Returns the member's snapshot index.
   *
   * @return The member's snapshot index.
   */
  long getSnapshotIndex() {
    return snapshotIndex;
  }

  /**
   * Sets the member's snapshot index.
   *
   * @param snapshotIndex The member's snapshot index.
   * @return The member state.
   */
  MemberState setSnapshotIndex(long snapshotIndex) {
    this.snapshotIndex = snapshotIndex;
    return this;
  }

  /**
   * Returns the member's snapshot offset.
   *
   * @return The member's snapshot offset.
   */
  int getSnapshotOffset() {
    return snapshotOffset;
  }

  /**
   * Sets the member's snapshot offset.
   *
   * @param snapshotOffset The member's snapshot offset.
   * @return The member state.
   */
  MemberState setSnapshotOffset(int snapshotOffset) {
    this.snapshotOffset = snapshotOffset;
    return this;
  }

  /**
   * Returns the member's match index.
   *
   * @return The member's match index.
   */
  long getMatchIndex() {
    return matchIndex;
  }

  /**
   * Sets the member's match index.
   *
   * @param matchIndex The member's match index.
   * @return The member state.
   */
  MemberState setMatchIndex(long matchIndex) {
    this.matchIndex = Assert.argNot(matchIndex, matchIndex < 0, "matchIndex cannot be less than 0");
    return this;
  }

  /**
   * Returns the member's next index.
   *
   * @return The member's next index.
   */
  long getNextIndex() {
    return nextIndex;
  }

  /**
   * Sets the member's next index.
   *
   * @param nextIndex The member's next index.
   * @return The member state.
   */
  MemberState setNextIndex(long nextIndex) {
    this.nextIndex = Assert.argNot(nextIndex, nextIndex <= 0, "nextIndex cannot be less than or equal to 0");
    return this;
  }

  /**
   * Returns the member commit time.
   *
   * @return The member commit time.
   */
  long getCommitTime() {
    return commitTime;
  }

  /**
   * Sets the member commit time.
   *
   * @param commitTime The member commit time.
   * @return The member state.
   */
  MemberState setCommitTime(long commitTime) {
    this.commitTime = commitTime;
    return this;
  }

  /**
   * Returns the member commit start time.
   *
   * @return The member commit start time.
   */
  long getCommitStartTime() {
    return commitStartTime;
  }

  /**
   * Sets the member commit start time.
   *
   * @param startTime The member commit attempt start time.
   * @return The member state.
   */
  MemberState setCommitStartTime(long startTime) {
    this.commitStartTime = startTime;
    return this;
  }

  /**
   * Returns the member failure count.
   *
   * @return The member failure count.
   */
  int getFailureCount() {
    return failures;
  }

  /**
   * Increments the member failure count.
   *
   * @return The member state.
   */
  int incrementFailureCount() {
    return ++failures;
  }

  /**
   * Resets the member failure count.
   *
   * @return The member state.
   */
  MemberState resetFailureCount() {
    failures = 0;
    return this;
  }

  @Override
  public String toString() {
    return member.serverAddress().toString();
  }

}

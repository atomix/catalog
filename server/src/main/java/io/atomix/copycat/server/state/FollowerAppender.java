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
package io.atomix.copycat.server.state;

import io.atomix.copycat.client.response.Response;
import io.atomix.copycat.server.request.AppendRequest;
import io.atomix.copycat.server.response.AppendResponse;
import io.atomix.copycat.server.storage.entry.Entry;

import java.util.List;

/**
 * The follower appender is responsible for sending {@link AppendRequest}s to passive/reserve
 * servers from a follower. Append requests are sent by followers to specific passive and reserve
 * servers based on consistent hashing. The appender sends {@link io.atomix.copycat.server.request.ConfigureRequest}s
 * to reserve servers, and {@link AppendRequest}s to passive servers.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
final class FollowerAppender extends AbstractAppender {
  private static final int MAX_BATCH_SIZE = 1024 * 32;

  public FollowerAppender(ServerState context) {
    super(context);
  }

  /**
   * Sends append entries requests to passive/reserve members.
   */
  public void appendEntries() {
    appendEntries(context.getAssignedPassiveMemberStates());
    appendEntries(context.getAssignedReserveMemberStates());
  }

  /**
   * Sends append entries requests for the given members.
   */
  private void appendEntries(List<MemberState> members) {
    for (MemberState member : members) {
      appendEntries(member);
    }
  }

  @Override
  protected AppendRequest buildAppendRequest(MemberState member) {
    // Only send AppendRequest to PASSIVE members. RESERVE members receive updates solely through configurations.
    if (member.getMember().isPassive() && !context.getLog().isEmpty() && member.getMatchIndex() < context.getCommitIndex()) {
      // Send as many entries as possible to the member. We use the basic mechanism of AppendEntries RPCs
      // as described in the Raft literature.
      // Note that we don't need to account for members that are unavailable with empty append requests since the
      // heartbeat mechanism handles availability for us. Thus, we can always safely attempt to send as many entries
      // as possible without incurring too much additional overhead.
      long prevIndex = getPrevIndex(member);
      Entry prevEntry = getPrevEntry(member, prevIndex);

      Member leader = context.getLeader();
      AppendRequest.Builder builder = AppendRequest.builder()
        .withTerm(context.getTerm())
        .withLeader(leader != null ? leader.id() : 0)
        .withLogIndex(prevIndex)
        .withLogTerm(prevEntry != null ? prevEntry.getTerm() : 0)
        .withCommitIndex(context.getCommitIndex());

      // Build a list of entries to send to the member.
      long index = prevIndex != 0 ? prevIndex + 1 : context.getLog().firstIndex();

      // We build a list of entries up to the MAX_BATCH_SIZE. Note that entries in the log may
      // be null if they've been compacted and the member to which we're sending entries is just
      // joining the cluster or is otherwise far behind. Null entries are simply skipped and not
      // counted towards the size of the batch.
      int size = 0;
      while (index <= context.getCommitIndex()) {
        Entry entry = context.getLog().get(index);
        if (entry != null) {
          if (size + entry.size() > MAX_BATCH_SIZE) {
            break;
          }
          size += entry.size();
          builder.addEntry(entry);
        }
        index++;
      }

      // Release the previous entry back to the entry pool.
      if (prevEntry != null) {
        prevEntry.release();
      }

      return builder.build();
    }
    return null;
  }

  @Override
  protected void handleAppendResponse(MemberState member, AppendRequest request, AppendResponse response) {
    if (response.status() == Response.Status.OK) {
      handleAppendResponseOk(member, request, response);
    } else {
      handleAppendResponseError(member, request, response);
    }
  }

  /**
   * Handles a {@link Response.Status#OK} response.
   */
  private void handleAppendResponseOk(MemberState member, AppendRequest request, AppendResponse response) {
    // Reset the member failure count.
    member.resetFailureCount();

    // If replication succeeded then trigger commit futures.
    if (response.succeeded()) {
      updateMatchIndex(member, response);
      updateNextIndex(member);

      // If there are more entries to send then attempt to send another commit.
      if (!request.entries().isEmpty() && hasMoreEntries(member)) {
        appendEntries(member);
      }
    } else {
      // If the response term is greater than the local term, increment it.
      if (response.term() > context.getTerm()) {
        context.setTerm(response.term());
      }

      // Reset the match and next indexes according to the response.
      resetMatchIndex(member, response);
      resetNextIndex(member);

      // If there are more entries to send then attempt to send another commit.
      if (!request.entries().isEmpty() && hasMoreEntries(member)) {
        appendEntries(member);
      }
    }
  }

  /**
   * Handles a {@link Response.Status#ERROR} response.
   */
  private void handleAppendResponseError(MemberState member, AppendRequest request, AppendResponse response) {
    // If the response term is greater than the local term, increment it.
    if (response.term() > context.getTerm()) {
      context.setTerm(response.term());
    }

    // Log 1% of append failures to reduce logging.
    if (member.incrementFailureCount() % 100 == 0) {
      LOGGER.warn("{} - AppendRequest to {} failed. Reason: [{}]", context.getMember().serverAddress(), member.getMember().serverAddress(), response.error() != null ? response.error() : "");
    }
  }

  @Override
  protected void handleAppendError(MemberState member, AppendRequest request, Throwable error) {
    // Ignore errors.
  }

}

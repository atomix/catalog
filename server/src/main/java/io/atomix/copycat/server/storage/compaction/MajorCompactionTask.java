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
package io.atomix.copycat.server.storage.compaction;

import io.atomix.catalyst.util.Assert;
import io.atomix.copycat.server.Commit;
import io.atomix.copycat.server.storage.Segment;
import io.atomix.copycat.server.storage.SegmentDescriptor;
import io.atomix.copycat.server.storage.SegmentManager;
import io.atomix.copycat.server.storage.entry.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Removes tombstones from the log and combines {@link Segment}s to reclaim disk space.
 * <p>
 * Major compaction is a more heavyweight compaction task which is responsible both for removing <em>tombstone</em>
 * {@link Entry entries} from the log and combining groups of neighboring log {@link Segment}s together.
 * <p>
 * <b>Combining segments</b>
 * <p>
 * As entries are written to the log and the log rolls over to new segments, entries are compacted out of individual
 * segments by {@link MinorCompactionTask}s. However, the minor compaction process only rewrites individual segments
 * and doesn't combine them. This will result in an ever growing number of open file pointers. During major compaction,
 * the major compaction task rewrites groups of segments provided by the {@link MajorCompactionManager}. For each group
 * of segments, a single compact segment will be created with the same {@code version} and starting {@code index} as
 * the first segment in the group. All entries from all segments in the group that haven't been
 * {@link io.atomix.copycat.server.storage.Log#clean(long) cleaned} will then be written to the new compact segment.
 * Once the rewrite is complete, the compact segment will be locked and the set of old segments deleted.
 * <p>
 * <b>Removing tombstones</b>
 * <p>
 * Tombstones are {@link Entry entries} in the log which amount to state changes that <em>remove</em> state. That is,
 * tombstones are an indicator that some set of prior entries no longer contribute to the state of the system. Thus,
 * it is critical that tombstones remain in the log as long as any prior related entries do. If a tombstone is removed
 * from the log before its prior related entries, rebuilding state from the log will result in inconsistencies.
 * <p>
 * A significant objective of the major compaction task is to remove tombstones from the log in a manor that ensures
 * failures before, during, or after the compaction task will not result in inconsistencies when state is rebuilt from
 * the log. In order to ensure tombstones are removed only <em>after</em> any prior related entries, the major compaction
 * task simply compacts segments in sequential order from the {@link Segment#firstIndex()} of the first segment to the
 * {@link Segment#lastIndex()} of the last segment. This ensures that if a failure occurs during the compaction process,
 * only entries earlier in the log will have been removed, and potential tombstones which erase the state of those entries
 * will remain.
 * <p>
 * Nevertheless, there are some significant potential race conditions that must be considered in the implementation of
 * major compaction. The major compaction task assumes that state machines will always clean <em>related</em> entries
 * in monotonically increasing order. That is, if a state machines receives a {@link io.atomix.copycat.server.Commit}
 * {@code remove 1} that deletes the state of a prior {@code Commit} {@code set 1}, the state machine will call
 * {@link Commit#clean()} on the {@code set 1} commit before cleaning the {@code remove 1} commit. But even if applications
 * clean entries from the log in monotonic order, and the major compaction task compacts segments in sequential order,
 * inconsistencies can still arise. Consider the following history:
 * <ul>
 *   <li>{@code set 1} is at index {@code 1} in segment {@code 1}</li>
 *   <li>{@code remove 1} is at index {@code 12345} in segment {@code 8}</li>
 *   <li>The major compaction task rewrites segment {@code 1}</li>
 *   <li>The application cleans {@code set 1} at index {@code 1} in the <em>rewritten</em> version of segment {@code 1}</li>
 *   <li>The application cleans {@code remove 1} at index {@code 12345} in segment {@code 8}, which the compaction task
 *   has yet to compact</li>
 *   <li>The compaction task compacts segments {@code 2} through {@code 8}, removing tombstone entry {@code 12345} during
 *   the process</li>
 * </ul>
 * <p>
 * In the scenario above, the resulting log contains {@code set 1} but not {@code remove 1}. If we replayed those entries
 * as {@link Commit}s to the log, it would result in an inconsistent state. Worse yet, not only is this server's state
 * incorrect, but it will be inconsistent with other servers which are likely to have correctly removed both entry
 * {@code 1} and entry {@code 12345} during major compaction.
 * <p>
 * In order to prevent such a scenario from occurring, the major compaction task takes an immutable snapshot of the
 * cleaned offsets underlying all the segments to be compacted prior to rewriting any entries. This ensures that any
 * entries cleaned after the start of rewriting segments will not be considered for compaction during the execution
 * of this task.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public final class MajorCompactionTask implements CompactionTask {
  private static final Logger LOGGER = LoggerFactory.getLogger(MajorCompactionTask.class);
  private final SegmentManager manager;
  private final List<List<Segment>> groups;
  private List<List<Predicate<Long>>> cleaners;
  private final long snapshotIndex;
  private final long compactIndex;

  MajorCompactionTask(SegmentManager manager, List<List<Segment>> groups, long snapshotIndex, long compactIndex) {
    this.manager = Assert.notNull(manager, "manager");
    this.groups = Assert.notNull(groups, "segments");
    this.snapshotIndex = snapshotIndex;
    this.compactIndex = compactIndex;
  }

  @Override
  public void run() {
    storeCleaners();
    compactGroups();
  }

  /**
   * Stores cleaned segment offsets.
   */
  private void storeCleaners() {
    cleaners = new ArrayList<>(groups.size());
    for (List<Segment> group : groups) {
      List<Predicate<Long>> groupCleaners = new ArrayList<>(group.size());
      for (Segment segment : group) {
        groupCleaners.add(segment.cleanPredicate());
      }
      cleaners.add(groupCleaners);
    }
  }

  /**
   * Compacts all compactable segments.
   */
  private void compactGroups() {
    for (int i = 0; i < groups.size(); i++) {
      List<Segment> group = groups.get(i);
      List<Predicate<Long>> groupCleaners = cleaners.get(i);
      Segment segment = compactGroup(group, groupCleaners);
      updateCleaned(group, groupCleaners, segment);
      deleteGroup(group);
    }
  }

  /**
   * Compacts a group.
   */
  private Segment compactGroup(List<Segment> segments, List<Predicate<Long>> cleaners) {
    // Get the first segment which contains the first index being cleaned. The clean segment will be written
    // as a newer version of the earliest segment being rewritten.
    Segment firstSegment = segments.iterator().next();

    // Create a clean segment with a newer version to which to rewrite the segment entries.
    Segment compactSegment = manager.createSegment(SegmentDescriptor.builder()
      .withId(firstSegment.descriptor().id())
      .withVersion(firstSegment.descriptor().version() + 1)
      .withIndex(firstSegment.descriptor().index())
      .withMaxSegmentSize(segments.stream().mapToLong(s -> s.descriptor().maxSegmentSize()).max().getAsLong())
      .withMaxEntries(segments.stream().mapToInt(s -> s.descriptor().maxEntries()).max().getAsInt())
      .build());

    compactGroup(segments, cleaners, compactSegment);

    // Replace the rewritten segments with the updated segment.
    manager.replaceSegments(segments, compactSegment);

    return compactSegment;
  }

  /**
   * Compacts segments in a group sequentially.
   *
   * @param segments The segments to compact.
   * @param compactSegment The compact segment.
   */
  private void compactGroup(List<Segment> segments, List<Predicate<Long>> cleaners, Segment compactSegment) {
    // Iterate through all segments being compacted and write entries to a single compact segment.
    for (int i = 0; i < segments.size(); i++) {
      compactSegment(segments.get(i), cleaners.get(i), compactSegment);
    }
  }

  /**
   * Compacts the given segment.
   *
   * @param segment The segment to compact.
   * @param compactSegment The segment to which to write the compacted segment.
   */
  private void compactSegment(Segment segment, Predicate<Long> cleaner, Segment compactSegment) {
    for (long i = segment.firstIndex(); i <= segment.lastIndex(); i++) {
      compactEntry(i, segment, cleaner, compactSegment);
    }
  }

  /**
   * Compacts the entry at the given index.
   *
   * @param index The index at which to compact the entry.
   * @param segment The segment to compact.
   * @param compactSegment The segment to which to write the cleaned segment.
   */
  private void compactEntry(long index, Segment segment, Predicate<Long> cleaner, Segment compactSegment) {
    try (Entry entry = segment.get(index)) {
      // If an entry was found, remove the entry from the segment.
      if (entry != null) {
        // If the entry is a snapshotted entry and its index is less than the snapshot index it can be safely
        // removed and doesn't have to be explicitly cleaned.
        if (entry.isSnapshotted() && index <= snapshotIndex) {
          compactSegment.skip(1);
          LOGGER.debug("Compacted entry {} from segment {}", index, segment.descriptor().id());
        }
        // If the entry's index is less than the major compact index then it can be safely removed if cleaned.
        // If the entry's index is greater than the major compact index, the entry must not be a tombstone.
        // Tombstones may only be removed from the log if their index is less than the major compact index.
        else if (!entry.isTombstone() || index <= compactIndex) {
          // If the entry has been cleaned, skip the entry in the compact segment.
          // Note that for major compaction this process includes normal and tombstone entries.
          long offset = segment.offset(index);
          if (offset == -1 || cleaner.test(offset)) {
            compactSegment.skip(1);
            LOGGER.debug("Compacted entry {} from segment {}", index, segment.descriptor().id());
          }
          // If the entry hasn't been cleaned, simply transfer it to the new segment.
          else {
            compactSegment.append(entry);
          }
        }
        // If the entry doesn't meet the criteria for compaction, transfer it to the new segment.
        else {
          compactSegment.append(entry);
        }
      }
      // If the entry has already been compacted, skip the index in the segment.
      else {
        compactSegment.skip(1);
      }
    }
  }

  /**
   * Updates the new compact segment with entries that were cleaned during compaction.
   */
  private void updateCleaned(List<Segment> segments, List<Predicate<Long>> cleaners, Segment compactSegment) {
    for (int i = 0; i < segments.size(); i++) {
      updateCleanedOffsets(segments.get(i), cleaners.get(i), compactSegment);
    }
  }

  /**
   * Updates the new compact segment with entries that were cleaned in the given segment during compaction.
   */
  private void updateCleanedOffsets(Segment segment, Predicate<Long> cleaner, Segment compactSegment) {
    for (long i = segment.firstIndex(); i <= segment.lastIndex(); i++) {
      long offset = segment.offset(i);
      if (offset != -1 && cleaner.test(offset)) {
        compactSegment.clean(offset);
      }
    }
  }

  /**
   * Completes compaction by deleting old segments.
   */
  private void deleteGroup(List<Segment> group) {
    // Delete the old segments.
    for (Segment oldSegment : group) {
      oldSegment.delete();
    }
  }

  @Override
  public String toString() {
    return String.format("%s[segments=%s]", getClass().getSimpleName(), groups);
  }

}

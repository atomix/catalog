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
package io.atomix.copycat.server.storage.snapshot;

import io.atomix.catalyst.buffer.Buffer;
import io.atomix.catalyst.buffer.BufferOutput;
import io.atomix.catalyst.buffer.Bytes;
import io.atomix.catalyst.util.Assert;

/**
 * Server snapshot writer.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public class SnapshotWriter implements BufferOutput<SnapshotWriter> {
  final Buffer buffer;
  private final Snapshot snapshot;

  SnapshotWriter(Buffer buffer, Snapshot snapshot) {
    this.buffer = Assert.notNull(buffer, "buffer");
    this.snapshot = Assert.notNull(snapshot, "snapshot");
  }

  @Override
  public SnapshotWriter write(Bytes bytes) {
    buffer.write(bytes);
    return this;
  }

  @Override
  public SnapshotWriter write(byte[] bytes) {
    buffer.write(bytes);
    return this;
  }

  @Override
  public SnapshotWriter write(Bytes bytes, long offset, long length) {
    buffer.write(bytes, offset, length);
    return this;
  }

  @Override
  public SnapshotWriter write(byte[] bytes, long offset, long length) {
    buffer.write(bytes, offset, length);
    return this;
  }

  @Override
  public SnapshotWriter write(Buffer buffer) {
    buffer.write(buffer);
    return this;
  }

  @Override
  public SnapshotWriter writeByte(int b) {
    buffer.writeByte(b);
    return this;
  }

  @Override
  public SnapshotWriter writeUnsignedByte(int b) {
    buffer.writeUnsignedByte(b);
    return this;
  }

  @Override
  public SnapshotWriter writeChar(char c) {
    buffer.writeChar(c);
    return this;
  }

  @Override
  public SnapshotWriter writeShort(short s) {
    buffer.writeShort(s);
    return this;
  }

  @Override
  public SnapshotWriter writeUnsignedShort(int s) {
    buffer.writeUnsignedShort(s);
    return this;
  }

  @Override
  public SnapshotWriter writeMedium(int m) {
    buffer.writeMedium(m);
    return this;
  }

  @Override
  public SnapshotWriter writeUnsignedMedium(int m) {
    buffer.writeUnsignedMedium(m);
    return this;
  }

  @Override
  public SnapshotWriter writeInt(int i) {
    buffer.writeInt(i);
    return this;
  }

  @Override
  public SnapshotWriter writeUnsignedInt(long i) {
    buffer.writeUnsignedInt(i);
    return this;
  }

  @Override
  public SnapshotWriter writeLong(long l) {
    buffer.writeLong(l);
    return this;
  }

  @Override
  public SnapshotWriter writeFloat(float f) {
    buffer.writeFloat(f);
    return this;
  }

  @Override
  public SnapshotWriter writeDouble(double d) {
    buffer.writeDouble(d);
    return this;
  }

  @Override
  public SnapshotWriter writeBoolean(boolean b) {
    buffer.writeBoolean(b);
    return this;
  }

  @Override
  public SnapshotWriter writeString(String s) {
    buffer.writeString(s);
    return this;
  }

  @Override
  public SnapshotWriter writeUTF8(String s) {
    buffer.writeUTF8(s);
    return this;
  }

  @Override
  public SnapshotWriter flush() {
    buffer.flush();
    return this;
  }

  @Override
  public void close() {
    snapshot.closeWriter(this);
    buffer.close();
  }

}

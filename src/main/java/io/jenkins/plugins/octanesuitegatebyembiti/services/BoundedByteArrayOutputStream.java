package io.jenkins.plugins.octanesuitegatebyembiti.services;

import java.io.ByteArrayOutputStream;

final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
  private final int maximumBytes;

  BoundedByteArrayOutputStream(int maximumBytes) {
    super(Math.min(maximumBytes, 8192));
    this.maximumBytes = maximumBytes;
  }

  @Override
  public synchronized void write(int value) {
    if (count < maximumBytes) {
      super.write(value);
    }
  }

  @Override
  public synchronized void write(byte[] buffer, int offset, int length) {
    int accepted = Math.min(length, maximumBytes - count);
    if (accepted > 0) {
      super.write(buffer, offset, accepted);
    }
  }
}

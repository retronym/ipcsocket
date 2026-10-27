package org.scalasbt.ipcsocket;

public class PendingIoTestJNI extends PendingIoTest {
  @Override
  boolean useJNI() {
    return true;
  }
}

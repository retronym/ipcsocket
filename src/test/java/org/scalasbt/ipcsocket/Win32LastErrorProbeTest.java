package org.scalasbt.ipcsocket;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinBase.OVERLAPPED;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Issues an overlapped ReadFile on a pipe with no data, which returns FALSE with ERROR_IO_PENDING,
 * then reads the error the way JNAWin32NamedPipeLibraryProvider does: a separate JNA call to
 * GetLastError. Compares that with Native.getLastError(), which JNA captures right after ReadFile.
 */
public class Win32LastErrorProbeTest extends BaseSocketSetup {
  @Test
  public void lastErrorAfterPendingRead() {
    if (!isWin) return;
    Win32NamedPipeLibrary lib = Win32NamedPipeLibrary.INSTANCE;
    String name = "\\\\.\\pipe\\ipcsocket-lasterror-probe-" + rand.nextInt();
    HANDLE pipe =
        lib.CreateNamedPipe(
            name,
            Win32NamedPipeLibrary.PIPE_ACCESS_DUPLEX | WinNT.FILE_FLAG_OVERLAPPED,
            0,
            1,
            65535,
            65535,
            0,
            null);
    assertNotEquals("CreateNamedPipe", WinBase_INVALID_HANDLE_VALUE(), pipe);
    HANDLE client =
        lib.CreateFile(
            name,
            WinNT.GENERIC_READ | WinNT.GENERIC_WRITE,
            0,
            null,
            WinNT.OPEN_EXISTING,
            WinNT.FILE_FLAG_OVERLAPPED,
            null);
    assertNotEquals("CreateFile", WinBase_INVALID_HANDLE_VALUE(), client);
    HANDLE event = lib.CreateEvent(null, true, false, null);
    Memory buffer = new Memory(16);
    int iterations = 200;
    int delegateMismatches = 0;
    int nativeMismatches = 0;
    String firstMismatch = null;
    try {
      for (int i = 0; i < iterations; i++) {
        OVERLAPPED olap = new OVERLAPPED();
        olap.hEvent = event;
        olap.write();
        boolean immediate = lib.ReadFile(client, buffer, 16, null, olap.getPointer());
        // as the provider does it: a separate JNA call
        int viaDelegate = lib.GetLastError();
        // what JNA captured right after ReadFile
        int viaNative = Native.getLastError();
        assertFalse("ReadFile completed with no data to read", immediate);
        if (viaDelegate != WinError.ERROR_IO_PENDING) {
          delegateMismatches++;
          if (firstMismatch == null)
            firstMismatch = "iteration " + i + ": delegate.GetLastError() = " + viaDelegate;
        }
        if (viaNative != WinError.ERROR_IO_PENDING) nativeMismatches++;
        lib.CancelIoEx(client, olap.getPointer());
        lib.GetOverlappedResult(client, olap.getPointer(), new IntByReference(), true);
      }
    } finally {
      lib.CloseHandle(event);
      lib.CloseHandle(client);
      lib.CloseHandle(pipe);
    }
    System.out.println(
        "Win32LastErrorProbeTest: of "
            + iterations
            + " pending reads, delegate.GetLastError() was not ERROR_IO_PENDING "
            + delegateMismatches
            + " times, Native.getLastError() "
            + nativeMismatches
            + " times"
            + (firstMismatch == null ? "" : " (first: " + firstMismatch + ")"));
    assertEquals("Native.getLastError() mismatches", 0, nativeMismatches);
    assertEquals(
        "delegate.GetLastError() mismatches" + (firstMismatch == null ? "" : ", " + firstMismatch),
        0,
        delegateMismatches);
  }

  private static HANDLE WinBase_INVALID_HANDLE_VALUE() {
    return Win32NamedPipeLibrary.INVALID_HANDLE_VALUE;
  }
}

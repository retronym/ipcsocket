package org.scalasbt.ipcsocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * A read issued before any data has arrived, and a write larger than the pipe's buffer, both have
 * to wait. On Windows that makes the overlapped ReadFile/WriteFile "fail" with ERROR_IO_PENDING,
 * which the provider must recognise.
 */
public class PendingIoTest extends BaseSocketSetup {
  @Test
  public void readAndWriteThatHaveToWait() throws IOException, InterruptedException {
    withSocket(
        sock -> {
          ServerSocket serverSocket = newServerSocket(sock);
          try {
            CompletableFuture<Socket> accepted =
                CompletableFuture.supplyAsync(
                    () -> {
                      try {
                        return serverSocket.accept();
                      } catch (IOException e) {
                        throw new UncheckedIOException(e);
                      }
                    });
            Socket client = newClientSocket(sock);
            Socket server = get(accepted);

            // a read with nothing to read yet
            CompletableFuture<Integer> read =
                CompletableFuture.supplyAsync(
                    () -> {
                      try {
                        return client.getInputStream().read(new byte[16], 0, 16);
                      } catch (IOException e) {
                        throw new UncheckedIOException(e);
                      }
                    });
            Thread.sleep(500);
            assertFalse("the read should be waiting for data", read.isDone());
            server.getOutputStream().write("hello".getBytes());
            server.getOutputStream().flush();
            assertEquals("bytes read", 5, (int) get(read));

            // a write larger than the pipe's buffer, with nobody reading yet
            byte[] big = new byte[1024 * 1024];
            CompletableFuture<Void> write =
                CompletableFuture.runAsync(
                    () -> {
                      try {
                        server.getOutputStream().write(big);
                        server.getOutputStream().flush();
                      } catch (IOException e) {
                        throw new UncheckedIOException(e);
                      }
                    });
            Thread.sleep(500);
            assertFalse("the write should be waiting for a reader", write.isDone());
            InputStream in = client.getInputStream();
            byte[] buf = new byte[64 * 1024];
            int total = 0;
            while (total < big.length) {
              int r = in.read(buf, 0, Math.min(buf.length, big.length - total));
              assertTrue("read returned " + r, r > 0);
              total += r;
            }
            get(write);
            client.close();
            server.close();
          } finally {
            serverSocket.close();
          }
        });
  }

  private static <T> T get(CompletableFuture<T> f) {
    try {
      return f.get(30, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }
}

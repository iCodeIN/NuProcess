/*
 * Copyright (C) 2013 Brett Wooldridge
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

package com.zaxxer.nuprocess;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.zaxxer.nuprocess.NuProcess.Stream;

/**
 * @author Brett Wooldridge
 */
public class DirectWriteTest
{
   private String command;

   @Before
   public void setup()
   {
      command = "/bin/cat";
      if (System.getProperty("os.name").toLowerCase().contains("win")) {
         command = "src\\test\\java\\com\\zaxxer\\nuprocess\\cat.exe";
      }
   }

   @Test
   public void testDirectWrite() throws InterruptedException
   {
      ProcessHandler1 processListener = new ProcessHandler1();
      NuProcessBuilder pb = new NuProcessBuilder(processListener, command);
      NuProcess nuProcess = pb.start();
      nuProcess.waitFor(5, TimeUnit.SECONDS);
      Assert.assertEquals("Results did not match", "This is a test", processListener.result);
   }

   // TODO: DirectWriteBig will explore a bug when using writeStdin at onStart()
   //       (has problem on Mac OS X and Linux, but works on Win32)
   // @Test
   public void testDirectWriteBig() throws InterruptedException
   {
      ProcessHandler2 processListener = new ProcessHandler2();
      NuProcessBuilder pb = new NuProcessBuilder(processListener, command);
      NuProcess nuProcess = pb.start();
      nuProcess.waitFor(0, TimeUnit.SECONDS);
      Assert.assertEquals("Checksums did not match", processListener.checksum, processListener.checksum2);
   }

   @Test
   public void testFewWrites() throws InterruptedException
   {
      final AtomicInteger count = new AtomicInteger();

      NuProcessHandler processListener = new NuAbstractProcessHandler() {
         @Override
         public void onStart(NuProcess nuProcess)
         {
            nuProcess.want(Stream.STDOUT);
         }

         @Override
         public boolean onStdout(ByteBuffer buffer, boolean closed)
         {
            count.addAndGet(buffer.remaining());
            buffer.position(buffer.limit());
            return !closed;
         }
      };

      NuProcessBuilder pb = new NuProcessBuilder(processListener, command);
      NuProcess nuProcess = pb.start();

      ByteBuffer buffer = ByteBuffer.allocate(64);
      buffer.put("This is a test".getBytes());
      buffer.flip();
      nuProcess.writeStdin(buffer);

      Thread.sleep(500);

      nuProcess.closeStdin(true);
      nuProcess.waitFor(5, TimeUnit.SECONDS);
      Assert.assertEquals("Count did not match", 14, count.get());
   }

   @Test
   public void testConsecutiveWrites() throws InterruptedException
   {
      final AtomicInteger count = new AtomicInteger();

      NuProcessHandler processListener = new NuAbstractProcessHandler() {
         @Override
         public boolean onStdout(ByteBuffer buffer, boolean closed)
         {
            count.addAndGet(buffer.remaining());
            buffer.position(buffer.limit());
            return !closed;
         }
      };

      NuProcessBuilder pb = new NuProcessBuilder(processListener, command);
      NuProcess nuProcess = pb.start();
      nuProcess.want(Stream.STDOUT);

      // TODO: given a large i (e.g. 1,000, 10,000), this unit test (testConsecutiveWrites) will
      //       produce a side-effect on InterruptTest (has problem on Mac OS X, but works on Linux and Win32).
      //       We do not reuse fork on surefire (reuseForks=false) to address this issue for now.
      for (int i = 0; i < 1000; i++) {
         ByteBuffer buffer = ByteBuffer.allocate(64);
         buffer.put("This is a test".getBytes());
         buffer.flip();
         nuProcess.writeStdin(buffer);
      }

      Thread.sleep(500);

      nuProcess.closeStdin(true);
      nuProcess.waitFor(5, TimeUnit.SECONDS);
      Assert.assertEquals("Count did not match", 14000, count.get());
   }

   private static class ProcessHandler1 extends NuAbstractProcessHandler
   {
      private NuProcess nuProcess;
      String result;

      @Override
      public void onStart(NuProcess nuProcess)
      {
         this.nuProcess = nuProcess;
         nuProcess.want(Stream.STDOUT);

         ByteBuffer buffer = ByteBuffer.allocate(256);
         buffer.put("This is a test".getBytes());
         buffer.flip();

         System.err.println("Writing: This is a test");
         nuProcess.writeStdin(buffer);
      }

      @Override
      public boolean onStdout(ByteBuffer buffer, boolean closed) {
         System.err.println("In ProcessHandler1.onStdout()...");
         if (buffer.hasRemaining()) {
            byte[] chars = new byte[buffer.remaining()];
            buffer.get(chars);
            result = new String(chars);
            System.err.println("  Read: " + result);
            nuProcess.closeStdin(false);
         }

         return !closed;
      }
   }

   private static class ProcessHandler2 extends NuAbstractProcessHandler
   {
      private NuProcess nuProcess;
      int checksum;
      int checksum2;

      @Override
      public void onStart(NuProcess nuProcess)
      {
         this.nuProcess = nuProcess;
         nuProcess.want(Stream.STDOUT);

         ByteBuffer buffer = ByteBuffer.allocate(1024 * 128);
         for (int i = 0; i < buffer.capacity(); i++) {
            byte b = (byte) (i % 256);
            buffer.put(b);
            checksum += b;
         }

         buffer.flip();

         System.err.println("Writing: 128K of data, waiting for checksum " + checksum);
         nuProcess.writeStdin(buffer);
      }

      @Override
      public boolean onStdout(ByteBuffer buffer, boolean closed)
      {
         while (buffer.hasRemaining()) {
            checksum2 += buffer.get();
         }

         System.out.println("Reading.  Current checksum " + checksum2);
         if (checksum2 == checksum) {
            System.out.println("Checksums matched, exiting.");
            nuProcess.closeStdin(true);
         }

         return !closed;
      }
   }
}

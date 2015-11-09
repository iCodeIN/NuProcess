package com.zaxxer.nuprocess;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.zaxxer.nuprocess.NuProcess.Stream;
import com.zaxxer.nuprocess.internal.BasePosixProcess;
import com.zaxxer.nuprocess.windows.WindowsProcess;

public class InterruptTest
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
   public void testDestroy() throws InterruptedException
   {
      testDestroy(false);
   }

   @Test
   public void testDestroyForcibly() throws InterruptedException
   {
      testDestroy(true);
   }

   private void testDestroy(boolean forceKill) throws InterruptedException
   {
      final Semaphore semaphore = new Semaphore(0);
      final AtomicInteger exitCode = new AtomicInteger();
      final AtomicInteger count = new AtomicInteger();

      NuProcessHandler processListener = new NuAbstractProcessHandler() {
         @Override
         public void onStart(NuProcess nuProcess)
         {
            nuProcess.want(Stream.STDIN);
            nuProcess.want(Stream.STDOUT);
         }

         @Override
         public void onExit(int statusCode)
         {
            exitCode.set(statusCode);
            semaphore.release();
         }

         @Override
         public boolean onStdout(ByteBuffer buffer, boolean closed)
         {
            count.addAndGet(buffer.remaining());
            buffer.position(buffer.limit());
            return true;
         }

         @Override
         public boolean onStdinReady(ByteBuffer buffer)
         {
            buffer.put("This is a test".getBytes());
            buffer.flip();
            return true;
         }
      };

      NuProcessBuilder pb = new NuProcessBuilder(processListener, command);
      NuProcess process = pb.start();
      while (true) {
         if (count.getAndIncrement() > 100) {
            process.destroy(forceKill);
            break;
         }
         Thread.sleep(20);
      }

      semaphore.acquireUninterruptibly();
      int exit = process.waitFor(2, TimeUnit.SECONDS);
      Assert.assertNotEquals("Process exit code did not match", 0, exit);
   }

   @Test
   public void chaosMonkey() throws InterruptedException
   {
      NuProcessHandler processListener = new NuAbstractProcessHandler() {

         @Override
         public void onStart(NuProcess nuProcess)
         {
            nuProcess.want(Stream.STDIN);
            nuProcess.want(Stream.STDOUT);
         }

         @Override
         public boolean onStdout(ByteBuffer buffer, boolean closed)
         {
            if (closed) {
               return false;
            }

            buffer.position(buffer.limit());
            return true;
         }

         @Override
         public boolean onStderr(ByteBuffer buffer, boolean closed)
         {
             return onStdout(buffer, closed);
         }

         @Override
         public boolean onStdinReady(ByteBuffer buffer)
         {
            buffer.put("This is a test".getBytes());
            buffer.flip();

            return true;
         }

      };

      NuProcessBuilder pb = new NuProcessBuilder(processListener, command);
      List<NuProcess> processes = new LinkedList<NuProcess>();
      for (int times = 0; times < 1; times++) {
         for (int i = 0; i < 50; i++) {
            NuProcess process = pb.start();
            processes.add(process);
         }

         TimeUnit.MILLISECONDS.sleep(2500);

         List<NuProcess> deadProcs = new ArrayList<NuProcess>();
         while (true) {
            TimeUnit.MILLISECONDS.sleep(100);

            int dead = (int) (Math.random() * processes.size());
            if (!System.getProperty("os.name").toLowerCase().contains("win")) {
               BasePosixProcess bpp = (BasePosixProcess) processes.remove(dead);
               if (bpp == null) {
                  continue;
               }
               deadProcs.add(bpp);
               bpp.destroy(false);
            }
            else {
               WindowsProcess wp = (WindowsProcess) processes.remove(dead);
               if (wp == null) {
                  continue;
               }
               deadProcs.add(wp);
               wp.destroy(false);
            }

            if (processes.isEmpty()) {
               for (int i = 0; i < 50; i++) {
                  int exit = deadProcs.get(i).waitFor(1, TimeUnit.SECONDS);
                  if (!System.getProperty("os.name").toLowerCase().contains("win")) {
                     Assert.assertEquals("Process exit code did not match", 15, exit);
                  }
                  else {
                     Assert.assertTrue("Process exit code did not match", (exit != 0 || exit == Integer.MAX_VALUE));
                  }
               }
               break;
            }
         }
      }
   }
}

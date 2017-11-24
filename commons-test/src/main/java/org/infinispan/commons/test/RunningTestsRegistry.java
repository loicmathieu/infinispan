package org.infinispan.commons.test;

import static java.util.Collections.singleton;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import org.infinispan.commons.test.skip.OS;

/**
 * Keep track of running tests and interrupt them if they take more than {@link #MAX_TEST_SECONDS} seconds.
 *
 * @author Dan Berindei
 * @since 9.2
 */
class RunningTestsRegistry {
   private static final long MAX_TEST_SECONDS = MINUTES.toSeconds(5);

   private static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
         r -> new Thread(r, "LongTestsWatcher"));
   private static final Map<Thread, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

   static void unregisterThreadWithTest() {
      ScheduledFuture<?> killTask = scheduledTasks.remove(Thread.currentThread());
      killTask.cancel(true);
   }

   static void registerThreadWithTest(String testName, String simpleName) {
      Thread testThread = Thread.currentThread();
      ScheduledFuture<?> future = executor.schedule(
         () -> killLongTest(testThread, testName, simpleName), MAX_TEST_SECONDS, SECONDS);
      scheduledTasks.put(testThread, future);
   }

   @SuppressWarnings("deprecation")
   private static void killLongTest(Thread testThread, String testName, String simpleName) {
      try {
         String safeTestName = testName.replaceAll("[^a-zA-Z0-9=]", "_");
         System.err.printf(
            "Test %s has been running for more than %d seconds. Interrupting the test thread and dumping " +
               "thread stacks of the test suite process and its children.\n",
            testName, MAX_TEST_SECONDS);

         String jvmName = ManagementFactory.getRuntimeMXBean().getName();
         String ppid = jvmName.split("@")[0];
         ArrayList<String> pids = new ArrayList<>(singleton(ppid));
         int index = 0;
         while (index < pids.size()) {
            String pid = pids.get(index);
            if (OS.getCurrentOs() != OS.WINDOWS) {
               Process ps = new ProcessBuilder()
                               .command("ps", "-o", "ppid=", pid)
                               .start();
               BufferedReader psOutput = new BufferedReader(new InputStreamReader(ps.getInputStream()));
               psOutput.lines().forEach(line -> pids.add(line.trim()));
               ps.waitFor(10, SECONDS);
            }

            File dumpFile =
               new File(String.format("threaddump-%1$s-%2$tY-%2$tm-%2$td-%3$s.txt", safeTestName, new Date(), pid));
            System.out.printf("Dumping thread stacks of process %s to %s\n", pid, dumpFile.getAbsolutePath());
            Process jstack = new ProcessBuilder()
               .command(System.getProperty("java.home") + "/../bin/jstack", pid)
               .redirectOutput(dumpFile)
               .start();
            jstack.waitFor(10, SECONDS);
         }

         // Interrupt the test thread
         testThread.interrupt();
         System.out.printf("Interrupted thread %s (%d).", testThread.getName(), testThread.getId());

         testThread.join(SECONDS.toMillis(MAX_TEST_SECONDS) / 10);
         if (testThread.isAlive()) {
            // Thread.interrupt() doesn't work if the thread is waiting to enter a synchronized block or in lock()
            // Thread.stop() works for lock(), but not if the thread is waiting to enter a synchronized block
            // So we just kill the fork and its children instead
            Process kill;
            if (OS.getCurrentOs() == OS.WINDOWS) {
               kill = new ProcessBuilder()
                         .command("taskkill /t /pid " + String.join("/pid ", pids))
                         .start();
            } else {
               kill = new ProcessBuilder()
                         .command("kill " + String.join(" ", pids))
                         .start();
            }
            kill.waitFor(10, SECONDS);
            System.out.printf("Killed processes %s", pids);
         }
      } catch (Exception e) {
         System.err.println("Error dumping thread stacks:" + e);
      }
   }
}

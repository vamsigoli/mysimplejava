package org.example;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Demonstrate potential for deadlock on a {@link ReentrantLock} when there is both a synchronized and
 * non-synchronized path to that lock, which can allow a virtual thread to hold the lock, but
 * other pinned waiters to consume all the available workers.
 */
public class VirtualThreadReentrantLockDeadlock {

    public static void main(String[] args) {
        final boolean shouldPin = args.length == 0 || Boolean.parseBoolean(args[0]);
        //who ever asks for a lock first will get it if fair is sent as true
        //in the below code unpinned thread asks for the lock first when it is stared
        final ReentrantLock lock = new ReentrantLock(true); // With fairness to ensure that the unpinned thread is next in line

        //lock the ReEntrant Lock
        lock.lock();

        //Get the lock and release immediately
        Runnable takeLock = () -> {
            try {
                System.out.println(Thread.currentThread() + " waiting for lock");
                lock.lock();
                System.out.println(Thread.currentThread() + " took lock");
            } finally {
                lock.unlock();
                System.out.println(Thread.currentThread() + " released lock");
            }
        };

        //unpinned thread is started. As the ReEntrant lock is held by main thread it waits.
        //since it is a virtual thread, it releases the control and cpu immediately
        //the thread is unpinned because there is no blocking operation.
        Thread unpinnedThread = Thread.ofVirtual().name("unpinned").start(takeLock);

        //kick off pinned threads.
        //a thread is pinned to a carrier thread  if there is a blocking operation or synchronized code
        // because each  thread is synchronized for object it is pinned to a CPU/carrier thread, and it cannot be reused
        //till the blocking thread is finished.
        //now all available processors are blocked by pinned threads

        List<Thread> pinnedThreads = IntStream.range(0, Runtime.getRuntime().availableProcessors())
                .mapToObj(i -> Thread.ofVirtual().name("pinning-" + i).start(() -> {
                    System.out.println(Thread.currentThread() + " started thread");
                    if (shouldPin) {
                        synchronized (new Object()) {
                            takeLock.run();
                        }
                    } else {
                        takeLock.run();
                    }
                })).toList();

        //main thread relinquishes the lock. normally the unpinned thread should get the lock and get its work done
        //but all the cpu/carrier threads are blocked by pinned threads and the unpinned thread is not able to become active
        //this results in a deadlock as lock cannot be acquired by any thread.
        lock.unlock();

        Stream.concat(Stream.of(unpinnedThread), pinnedThreads.stream()).forEach(thread -> {
            try {
                if (!thread.join(Duration.ofSeconds(3))) {
                    throw new RuntimeException("Deadlock detected");
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }

}

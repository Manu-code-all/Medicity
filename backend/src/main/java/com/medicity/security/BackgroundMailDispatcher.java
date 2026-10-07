package com.medicity.security;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Two threads and a bounded queue for sending email.
 *
 * <p>Deliberately not exposed as an {@code Executor} bean: Spring Boot only
 * creates its own application task executor when none is defined, so adding
 * one here would quietly replace it for everything else.
 *
 * <p>When the queue is full the message is dropped and logged, not run on the
 * caller's thread: that would make a flood of requests slow exactly the
 * requests it is meant to protect. The send limit per account already bounds
 * how much any one person can queue.
 */
@Component
@Slf4j
public class BackgroundMailDispatcher implements MailDispatcher {

    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(100),
            runnable -> {
                Thread thread = new Thread(runnable, "mail-dispatch");
                thread.setDaemon(true);
                return thread;
            });

    @Override
    public void dispatch(Runnable task) {
        try {
            pool.execute(task);
        } catch (RejectedExecutionException e) {
            log.error("Mail queue full or shut down; message dropped");
        }
    }

    /** Lets a message already queued go out before the process stops. */
    @PreDestroy
    void shutdown() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }
}

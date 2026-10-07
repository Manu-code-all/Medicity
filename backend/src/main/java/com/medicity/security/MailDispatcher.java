package com.medicity.security;

/**
 * Runs a task off the request thread. A seam so tests can run it inline and
 * assert on what was sent, where production queues it.
 */
@FunctionalInterface
public interface MailDispatcher {

    void dispatch(Runnable task);
}

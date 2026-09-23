package com.ticketlab.queue;

/**
 * One person pulled off the front of the queue.
 *
 * Redis hands back a TypedTuple whose score is the enqueue timestamp. Wrapping
 * it here keeps Redis types inside QueueService - the admitter never has to
 * know the queue happens to be a sorted set.
 */
public record QueueEntry(String userId, long enqueuedAtMs) {
}

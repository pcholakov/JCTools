/*
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
package org.jctools.queues;

/**
 * This is a direct Java port of the MPSC algorithm as presented
 * <a href="http://www.1024cores.net/home/lock-free-algorithms/queues/non-intrusive-mpsc-node-based-queue"> on
 * 1024 Cores</a> by D. Vyukov. The original has been adapted to Java and it's quirks with regards to memory
 * model and layout:
 * <ol>
 * <li>Use inheritance to ensure no false sharing occurs between producer/consumer node reference fields.
 * <li>Use XCHG functionality to the best of the JDK ability (see differences in JDK7/8 impls).
 * </ol>
 * The queue is initialized with a stub node which is set to both the producer and consumer node references.
 * From this point follow the notes on offer/poll.
 *
 * @param <E> the type of elements in this queue
 * @author nitsanw
 */
abstract class MpscLinkedQueue<E> extends BaseLinkedQueue<E> {
    protected MpscLinkedQueue() {
        consumerNode = new LinkedQueueNode<E>();
        xchgProducerNode(consumerNode);// this ensures correct construction: StoreLoad
    }

    protected abstract LinkedQueueNode<E> xchgProducerNode(LinkedQueueNode<E> nextNode);

    /**
     * {@inheritDoc} <br>
     * <p>
     * IMPLEMENTATION NOTES:<br>
     * Offer is allowed from multiple threads.<br>
     * Offer allocates a new node and:
     * <ol>
     * <li>Swaps it atomically with current producer node (only one producer 'wins')
     * <li>Sets the new node as the node following from the swapped producer node
     * </ol>
     * This works because each producer is guaranteed to 'plant' a new node and link the old node. No 2
     * producers can get the same producer node as part of XCHG guarantee.
     *
     * @see MessagePassingQueue#offer(Object)
     * @see java.util.Queue#offer(java.lang.Object)
     */
    @Override
    public final boolean offer(final E e) {
        if (null == e) {
            throw new NullPointerException();
        }
        final LinkedQueueNode<E> nextNode = new LinkedQueueNode<E>(e);
        final LinkedQueueNode<E> prevProducerNode = xchgProducerNode(nextNode);
        // Should a producer thread get interrupted here the chain WILL be broken until that thread is resumed
        // and completes the store in prev.next.
        prevProducerNode.soNext(nextNode); // StoreStore
        return true;
    }

    /**
     * {@inheritDoc} <br>
     * <p>
     * IMPLEMENTATION NOTES:<br>
     * Poll is allowed from a SINGLE thread.<br>
     * Poll reads the next node from the consumerNode and:
     * <ol>
     * <li>If it is null, the queue is assumed empty (though it might not be).
     * <li>If it is not null set it as the consumer node and return it's now evacuated value.
     * </ol>
     * This means the consumerNode.value is always null, which is also the starting point for the queue.
     * Because null values are not allowed to be offered this is the only node with it's value set to null at
     * any one time.
     *
     * @see MessagePassingQueue#poll()
     * @see java.util.Queue#poll()
     */
    @Override
    public final E poll() {
        LinkedQueueNode<E> currConsumerNode = lpConsumerNode(); // don't load twice, it's alright
        LinkedQueueNode<E> nextNode = currConsumerNode.lvNext();
        if (nextNode != null) {
            return getSingleConsumerNodeValue(currConsumerNode, nextNode);
        }
        else if (currConsumerNode != lvProducerNode()) {
            // spin, we are no longer wait free
            while ((nextNode = currConsumerNode.lvNext()) == null)
                ;
            // got the next node...

            return getSingleConsumerNodeValue(currConsumerNode, nextNode);
        }
        return null;
    }

    @Override
    public final E peek() {
        LinkedQueueNode<E> currConsumerNode = consumerNode; // don't load twice, it's alright
        LinkedQueueNode<E> nextNode = currConsumerNode.lvNext();
        if (nextNode != null) {
            return nextNode.lpValue();
        }
        else if (currConsumerNode != lvProducerNode()) {
            // spin, we are no longer wait free
            while ((nextNode = currConsumerNode.lvNext()) == null)
                ;
            // got the next node...
            return nextNode.lpValue();
        }
        return null;
    }

    @Override
    public int fill(Supplier<E> s) {
        long result = 0;// result is a long because we want to have a safepoint check at regular intervals
        do {
            fill(s, 4096);
            result += 4096;
        } while (result <= Integer.MAX_VALUE - 4096);
        return (int) result;
    }

    @Override
    public int fill(Supplier<E> s, int limit) {
        if (limit == 0) return 0;
        LinkedQueueNode<E> tail = new LinkedQueueNode<E>(s.get());
        final LinkedQueueNode<E> head = tail;
        for (int i = 1; i < limit; i++) {
            final LinkedQueueNode<E> temp = new LinkedQueueNode<E>(s.get());
            tail.soNext(temp);
            tail = temp;
        }
        final LinkedQueueNode<E> oldPNode = xchgProducerNode(tail);
        oldPNode.soNext(head);
        return limit;
    }

    @Override
    public void fill(Supplier<E> s, WaitStrategy wait, ExitCondition exit) {
        while (exit.keepRunning()) {
            fill(s, 4096);
        }
    }
}

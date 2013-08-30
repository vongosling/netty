/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
/*
 * Written by Josh Bloch of Google Inc. and released to the public domain,
 * as explained at http://creativecommons.org/publicdomain/zero/1.0/.
 */
package io.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/**
 * (Transport implementors only) an internal data structure used by {@link AbstractChannel} to store its pending
 * outbound write requests.
 */
public final class ChannelOutboundBuffer {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelOutboundBuffer.class);

    private static final int INITIAL_CAPACITY = 32;

    private static final Recycler<ChannelOutboundBuffer> RECYCLER = new Recycler<ChannelOutboundBuffer>() {
        @Override
        protected ChannelOutboundBuffer newObject(Handle handle) {
            return new ChannelOutboundBuffer(handle);
        }
    };

    static ChannelOutboundBuffer newInstance(AbstractChannel channel) {
        ChannelOutboundBuffer buffer = RECYCLER.get();
        buffer.channel = channel;
        buffer.totalPendingSize = 0;
        buffer.writable = 1;
        return buffer;
    }

    private final Handle handle;

    private AbstractChannel channel;

    // A circular buffer used to store messages.  The buffer is arranged such that:  flushed <= unflushed <= tail.  The
    // flushed messages are stored in the range [flushed, unflushed).  Unflushed messages are stored in the range
    // [unflushed, tail).
    private Entry[] buffer;
    private int flushed;
    private int unflushed;
    private int tail;

    private ByteBuffer[] nioBuffers;
    private int nioBufferCount;
    private long nioBufferSize;

    private boolean inFail;

    private static final AtomicLongFieldUpdater<ChannelOutboundBuffer> TOTAL_PENDING_SIZE_UPDATER =
            AtomicLongFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "totalPendingSize");

    private volatile long totalPendingSize;

    private static final AtomicIntegerFieldUpdater<ChannelOutboundBuffer> WRITABLE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "writable");

    private volatile int writable = 1;

    private ChannelOutboundBuffer(Handle handle) {
        this.handle = handle;

        buffer = new Entry[INITIAL_CAPACITY];
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = new Entry();
        }

        nioBuffers = new ByteBuffer[INITIAL_CAPACITY];
    }

    void addMessage(Object msg, ChannelPromise promise) {
        int size = channel.estimatorHandle().size(msg);
        if (size < 0) {
            size = 0;
        }

        Entry e = buffer[tail++];
        e.msg = msg;
        e.pendingSize = size;
        e.promise = promise;
        e.total = total(msg);

        tail &= buffer.length - 1;

        if (tail == flushed) {
            addCapacity();
        }

        // increment pending bytes after adding message to the unflushed arrays.
        // See https://github.com/netty/netty/issues/1619
        incrementPendingOutboundBytes(size, true);
    }

    private void addCapacity() {
        int p = flushed;
        int n = buffer.length;
        int r = n - p; // number of elements to the right of p
        int s = size();

        int newCapacity = n << 1;
        if (newCapacity < 0) {
            throw new IllegalStateException();
        }

        Entry[] e = new Entry[newCapacity];
        System.arraycopy(buffer, p, e, 0, r);
        System.arraycopy(buffer, 0, e, r, p);
        for (int i = n; i < e.length; i++) {
            e[i] = new Entry();
        }

        buffer = e;
        flushed = 0;
        unflushed = s;
        tail = n;
    }

    void addFlush() {
        unflushed = tail;
    }

    /**
     * Increment the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    void incrementPendingOutboundBytes(int size, boolean fireEvent) {
        // Cache the channel and check for null to make sure we not produce a NPE in case of the Channel gets
        // recycled while process this method.
        Channel channel = this.channel;
        if (size == 0 || channel == null) {
            return;
        }

        long oldValue = totalPendingSize;
        long newWriteBufferSize = oldValue + size;
        while (!TOTAL_PENDING_SIZE_UPDATER.compareAndSet(this, oldValue, newWriteBufferSize)) {
            oldValue = totalPendingSize;
            newWriteBufferSize = oldValue + size;
        }

        int highWaterMark = channel.config().getWriteBufferHighWaterMark();

        if (newWriteBufferSize > highWaterMark) {
            if (WRITABLE_UPDATER.compareAndSet(this, 1, 0)) {
                if (fireEvent) {
                    channel.pipeline().fireChannelWritabilityChanged();
                }
            }
        }
    }

    /**
     * Decrement the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    void decrementPendingOutboundBytes(int size, boolean fireEvent) {
        // Cache the channel and check for null to make sure we not produce a NPE in case of the Channel gets
        // recycled while process this method.
        Channel channel = this.channel;
        if (size == 0 || channel == null) {
            return;
        }

        long oldValue = totalPendingSize;
        long newWriteBufferSize = oldValue - size;
        while (!TOTAL_PENDING_SIZE_UPDATER.compareAndSet(this, oldValue, newWriteBufferSize)) {
            oldValue = totalPendingSize;
            newWriteBufferSize = oldValue - size;
        }

        int lowWaterMark = channel.config().getWriteBufferLowWaterMark();

        if (newWriteBufferSize == 0 || newWriteBufferSize < lowWaterMark) {
            if (WRITABLE_UPDATER.compareAndSet(this, 0, 1)) {
                if (fireEvent) {
                    channel.pipeline().fireChannelWritabilityChanged();
                }
            }
        }
    }

    private static long total(Object msg) {
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        }
        if (msg instanceof FileRegion) {
            return ((FileRegion) msg).count();
        }
        if (msg instanceof ByteBufHolder) {
            return ((ByteBufHolder) msg).content().readableBytes();
        }
        return -1;
    }

    public Object current() {
        if (isEmpty()) {
            return null;
        } else {
            return buffer[flushed].msg;
        }
    }

    public void progress(long amount) {
        Entry e = buffer[flushed];
        ChannelPromise p = e.promise;
        if (p instanceof ChannelProgressivePromise) {
            long progress = e.progress + amount;
            e.progress = progress;
            ((ChannelProgressivePromise) p).tryProgress(progress, e.total);
        }
    }

    public boolean remove() {
        if (isEmpty()) {
            return false;
        }

        Entry e = buffer[flushed];
        Object msg = e.msg;
        if (msg == null) {
            return false;
        }

        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        e.clear();

        flushed = flushed + 1 & buffer.length - 1;

        safeRelease(msg);

        promise.trySuccess();
        decrementPendingOutboundBytes(size, true);

        return true;
    }

    public boolean remove(Throwable cause) {
        if (isEmpty()) {
            return false;
        }

        Entry e = buffer[flushed];
        Object msg = e.msg;
        if (msg == null) {
            return false;
        }

        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        e.clear();

        flushed = flushed + 1 & buffer.length - 1;

        safeRelease(msg);

        safeFail(promise, cause);
        decrementPendingOutboundBytes(size, true);

        return true;
    }

    /**
     * Returns an array of direct NIO buffers if the currently pending messages are made of {@link ByteBuf} only.
     * {@code null} is returned otherwise.  If this method returns a non-null array, {@link #nioBufferCount()} and
     * {@link #nioBufferSize()} will return the number of NIO buffers in the returned array and the total number
     * of readable bytes of the NIO buffers respectively.
     * <p>
     * Note that the returned array is reused and thus should not escape
     * {@link AbstractChannel#doWrite(ChannelOutboundBuffer)}.
     * Refer to {@link NioSocketChannel#doWrite(ChannelOutboundBuffer)} for an example.
     * </p>
     */
    public ByteBuffer[] nioBuffers() {
        ByteBuffer[] nioBuffers = this.nioBuffers;
        long nioBufferSize = 0;
        int nioBufferCount = 0;

        final int mask = buffer.length - 1;

        Object m;
        int i = flushed;
        while (i != unflushed && (m = buffer[i].msg) != null) {
            if (!(m instanceof ByteBuf)) {
                this.nioBufferCount = 0;
                this.nioBufferSize = 0;
                return null;
            }

            ByteBuf buf = (ByteBuf) m;

            final int readerIndex = buf.readerIndex();
            final int readableBytes = buf.writerIndex() - readerIndex;

            if (readableBytes > 0) {
                nioBufferSize += readableBytes;

                if (buf.isDirect()) {
                    int count = buf.nioBufferCount();
                    if (count == 1) {
                        if (nioBufferCount == nioBuffers.length) {
                            this.nioBuffers = nioBuffers = doubleNioBufferArray(nioBuffers, nioBufferCount);
                        }
                        nioBuffers[nioBufferCount ++] = buf.internalNioBuffer(readerIndex, readableBytes);
                    } else {
                        ByteBuffer[] nioBufs = buf.nioBuffers();
                        if (nioBufferCount + nioBufs.length > nioBuffers.length) {
                            this.nioBuffers = nioBuffers = doubleNioBufferArray(nioBuffers, nioBufferCount);
                        }
                        for (ByteBuffer nioBuf: nioBufs) {
                            if (nioBuf == null) {
                                break;
                            }
                            nioBuffers[nioBufferCount ++] = nioBuf;
                        }
                    }
                } else {
                    ByteBuf directBuf = channel.alloc().directBuffer(readableBytes);
                    directBuf.writeBytes(buf, readerIndex, readableBytes);
                    buf.release();
                    buffer[i].msg = directBuf;
                    if (nioBufferCount == nioBuffers.length) {
                        nioBuffers = doubleNioBufferArray(nioBuffers, nioBufferCount);
                    }
                    nioBuffers[nioBufferCount ++] = directBuf.internalNioBuffer(0, readableBytes);
                }
            }

            i = i + 1 & mask;
        }

        this.nioBufferCount = nioBufferCount;
        this.nioBufferSize = nioBufferSize;

        return nioBuffers;
    }

    private static ByteBuffer[] doubleNioBufferArray(ByteBuffer[] array, int size) {
        int newCapacity = array.length << 1;
        if (newCapacity < 0) {
            throw new IllegalStateException();
        }

        ByteBuffer[] newArray = new ByteBuffer[newCapacity];
        System.arraycopy(array, 0, newArray, 0, size);

        return newArray;
    }

    public int nioBufferCount() {
        return nioBufferCount;
    }

    public long nioBufferSize() {
        return nioBufferSize;
    }

    boolean getWritable() {
        return writable != 0;
    }

    public int size() {
        return unflushed - flushed & buffer.length - 1;
    }

    public boolean isEmpty() {
        return unflushed == flushed;
    }

    void failFlushed(Throwable cause) {
        // Make sure that this method does not reenter.  A listener added to the current promise can be notified by the
        // current thread in the tryFailure() call of the loop below, and the listener can trigger another fail() call
        // indirectly (usually by closing the channel.)
        //
        // See https://github.com/netty/netty/issues/1501
        if (inFail) {
            return;
        }

        try {
            inFail = true;
            for (;;) {
                if (!remove(cause)) {
                    break;
                }
            }
        } finally {
            inFail = false;
        }
    }

    void close(final ClosedChannelException cause) {
        if (inFail) {
            channel.eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    close(cause);
                }
            });
            return;
        }

        inFail = true;

        if (channel.isOpen()) {
            throw new IllegalStateException("close() must be invoked after the channel is closed.");
        }

        if (!isEmpty()) {
            throw new IllegalStateException("close() must be invoked after all flushed writes are handled.");
        }

        // Release all unflushed messages.
        final int unflushedCount = tail - unflushed & buffer.length - 1;
        try {
            for (int i = 0; i < unflushedCount; i++) {
                Entry e = buffer[unflushed + i & buffer.length - 1];
                safeRelease(e.msg);
                e.msg = null;
                safeFail(e.promise, cause);
                e.promise = null;

                // Just decrease; do not trigger any events via decrementPendingOutboundBytes()
                int size = e.pendingSize;
                long oldValue = totalPendingSize;
                long newWriteBufferSize = oldValue - size;
                while (!TOTAL_PENDING_SIZE_UPDATER.compareAndSet(this, oldValue, newWriteBufferSize)) {
                    oldValue = totalPendingSize;
                    newWriteBufferSize = oldValue - size;
                }

                e.pendingSize = 0;
            }
        } finally {
            tail = unflushed;
            inFail = false;
        }

        recycle();
    }

    private static void safeRelease(Object message) {
        try {
            ReferenceCountUtil.release(message);
        } catch (Throwable t) {
            logger.warn("Failed to release a message.", t);
        }
    }

    private static void safeFail(ChannelPromise promise, Throwable cause) {
        if (!(promise instanceof VoidChannelPromise) && !promise.tryFailure(cause)) {
            logger.warn("Promise done already: {} - new exception is:", promise, cause);
        }
    }

    public void recycle() {
        if (buffer.length > INITIAL_CAPACITY) {
            Entry[] e = new Entry[INITIAL_CAPACITY];
            System.arraycopy(buffer, 0, e, 0, INITIAL_CAPACITY);
            buffer = e;
        }

        if (nioBuffers.length > INITIAL_CAPACITY) {
            nioBuffers = new ByteBuffer[INITIAL_CAPACITY];
        } else {
            // null out the nio buffers array so the can be GC'ed
            // https://github.com/netty/netty/issues/1763
            Arrays.fill(nioBuffers, null);
        }

        // reset flushed, unflushed and tail
        // See https://github.com/netty/netty/issues/1772
        flushed = 0;
        unflushed = 0;
        tail = 0;

        // Set the channel to null so it can be GC'ed ASAP
        channel = null;

        RECYCLER.recycle(this, handle);
    }

    private static final class Entry {
        Object msg;
        ChannelPromise promise;
        long progress;
        long total;
        int pendingSize;

        public void clear() {
            msg = null;
            promise = null;
            progress = 0;
            total = 0;
            pendingSize = 0;
        }
    }

}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.rocketmq.common.UtilAll;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageExtBatch;
import org.apache.rocketmq.store.config.FlushDiskType;
import org.apache.rocketmq.store.util.LibC;
import sun.nio.ch.DirectBuffer;

public class MappedFile extends ReferenceResource {
    public static final int OS_PAGE_SIZE = 1024 * 4;
    protected static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);

    //mmap文件占用内存大小
    private static final AtomicLong TOTAL_MAPPED_VIRTUAL_MEMORY = new AtomicLong(0);

    // 文件数
    private static final AtomicInteger TOTAL_MAPPED_FILES = new AtomicInteger(0);

    // 写到的位置，当值==fileSize时，说明文件写满了
    protected final AtomicInteger wrotePosition = new AtomicInteger(0);

    //ADD BY ChenYang
    /**
     * 当writeBuffer为空时，就不会有真正的commit行为 wrotePosition意义上就代表了committedPosition，
     * 真正的committedPosition值只会为初始值0,参考commit函数
     */
    protected final AtomicInteger committedPosition = new AtomicInteger(0);

    //刷盘刷到的位置
    private final AtomicInteger flushedPosition = new AtomicInteger(0);

    //mappedFile文件大小，参照MessageStoreConfig.mapedFileSizeCommitLog，默认1G
    protected int fileSize;
    protected FileChannel fileChannel;
    /**
     * Message will put to here first, and then reput to FileChannel if writeBuffer is not null.
     * 从transientStorePool的池中拿到ByteBuffer，可能为null
     */
    protected ByteBuffer writeBuffer = null;

    // 池对象，MappedByteBuffer内存缓存池，用来临时存储数据，数据先写入该内存映射中，然后又commit线程定时将数据从该内存复制到与目的物理文件对应的内存映射中
    // 目的：提供内存锁定，将堆外内存一直锁定在内存中，避免进程将内存交换到磁盘中
    // init函数中赋值
    protected TransientStorePool transientStorePool = null;

    private String fileName;

    //fileFromOffset就是文件名称，一般为20位数字,代表这个文件开始时的offset
    private long fileFromOffset;
    private File file;

    //内存映射文件
    private MappedByteBuffer mappedByteBuffer;

    // 文件最后一次内容写入时间
    private volatile long storeTimestamp = 0;

    // 是否是MappedFileQueue队列中第一个文件
    private boolean firstCreateInQueue = false;

    public MappedFile() {
    }

    public MappedFile(final String fileName, final int fileSize) throws IOException {
        init(fileName, fileSize);
    }

    public MappedFile(final String fileName, final int fileSize,
        final TransientStorePool transientStorePool) throws IOException {
        init(fileName, fileSize, transientStorePool);
    }

    public static void ensureDirOK(final String dirName) {
        if (dirName != null) {
            File f = new File(dirName);
            if (!f.exists()) {
                boolean result = f.mkdirs();
                log.info(dirName + " mkdir " + (result ? "OK" : "Failed"));
            }
        }
    }

    /**
     * 清理mappedByteBuffer 如果是directByteBuffer且容量不为0 则获取attachment转成directByteBuffer，调用其clearner.clean方法
     */
    public static void clean(final ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect() || buffer.capacity() == 0)
            return;
        //下面的逻辑一定是directByteBuffer的
        /**
         * 下面这段是
         * 嵌套拿到directByteBuffer的最内部的attachment，强制转换成ByteBuffer对象(实际运行应该会是directByteBuffer)
         * 然后获得directByteBuffer的Cleaner对象 cleaner
         * 然后调用cleaner.clean方法，进行深度的释放资源
         */
        invoke(invoke(viewed(buffer), "cleaner"), "clean");
    }

    //java反射,执行某个object的某方法,把所有访问级别都设置为Accessible再执行
    private static Object invoke(final Object target, final String methodName, final Class<?>... args) {
        return AccessController.doPrivileged(new PrivilegedAction<Object>() {
            public Object run() {
                try {
                    Method method = method(target, methodName, args);
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
    }

    //根据object，参数类型列表以及函数名得到Method对象
    private static Method method(Object target, String methodName, Class<?>[] args)
        throws NoSuchMethodException {
        try {
            return target.getClass().getMethod(methodName, args);
        } catch (NoSuchMethodException e) {
            return target.getClass().getDeclaredMethod(methodName, args);
        }
    }

    /**
     * 嵌套调用，获取最深层的attachment 或者 viewedBuffer方法 转化为ByteBuffer对象
     */
    private static ByteBuffer viewed(ByteBuffer buffer) {
        String methodName = "viewedBuffer";

        Method[] methods = buffer.getClass().getMethods();
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].getName().equals("attachment")) {
                methodName = "attachment";
                break;
            }
        }

        //执行DirectByteBuffer.attachment()方法
        ByteBuffer viewedBuffer = (ByteBuffer) invoke(buffer, methodName);
        if (viewedBuffer == null)
            return buffer;
        else
            return viewed(viewedBuffer);
    }

    public static int getTotalMappedFiles() {
        return TOTAL_MAPPED_FILES.get();
    }

    public static long getTotalMappedVirtualMemory() {
        return TOTAL_MAPPED_VIRTUAL_MEMORY.get();
    }

    /**
     * 初始化文件名，大小 设置transientStorePool 设置writeBuffer，从transientStorePool借一个出来，可能为null
     */
    public void init(final String fileName, final int fileSize,
        final TransientStorePool transientStorePool) throws IOException {
        init(fileName, fileSize);
        this.writeBuffer = transientStorePool.borrowBuffer();
        this.transientStorePool = transientStorePool;
    }

    private void init(final String fileName, final int fileSize) throws IOException {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.file = new File(fileName);
        this.fileFromOffset = Long.parseLong(this.file.getName());
        boolean ok = false;

        // 确保目录存在
        ensureDirOK(this.file.getParent());

        try {
            // 创建文件
            this.fileChannel = new RandomAccessFile(this.file, "rw").getChannel();
            // 设置映射，同时指定了文件大小
            this.mappedByteBuffer = this.fileChannel.map(MapMode.READ_WRITE, 0, fileSize);
            TOTAL_MAPPED_VIRTUAL_MEMORY.addAndGet(fileSize);
            TOTAL_MAPPED_FILES.incrementAndGet();
            ok = true;
        } catch (FileNotFoundException e) {
            log.error("create file channel " + this.fileName + " Failed. ", e);
            throw e;
        } catch (IOException e) {
            log.error("map file " + this.fileName + " Failed. ", e);
            throw e;
        } finally {
            if (!ok && this.fileChannel != null) {
                this.fileChannel.close();
            }
        }
    }

    // 获取文件最后一次修改时间
    public long getLastModifiedTimestamp() {
        return this.file.lastModified();
    }

    public int getFileSize() {
        return fileSize;
    }

    public FileChannel getFileChannel() {
        return fileChannel;
    }

    public AppendMessageResult appendMessage(final MessageExtBrokerInner msg, final AppendMessageCallback cb) {
        return appendMessagesInner(msg, cb);
    }

    public AppendMessageResult appendMessages(final MessageExtBatch messageExtBatch, final AppendMessageCallback cb) {
        return appendMessagesInner(messageExtBatch, cb);
    }

    /**
     * 将消息写入文件
     *
     * @param messageExt
     * @param cb 回调
     * @return
     */
    public AppendMessageResult appendMessagesInner(final MessageExt messageExt, final AppendMessageCallback cb) {
        assert messageExt != null;
        assert cb != null;

        // 获取当前文件写入的下标
        int currentPos = this.wrotePosition.get();

        // 如果下标小于文件大小
        if (currentPos < this.fileSize) {
            ByteBuffer byteBuffer = writeBuffer != null ? writeBuffer.slice() : this.mappedByteBuffer.slice();
            byteBuffer.position(currentPos);
            AppendMessageResult result = null;
            // 如果是单条消息
            if (messageExt instanceof MessageExtBrokerInner) {
                result = cb.doAppend(this.getFileFromOffset(), byteBuffer, this.fileSize - currentPos, (MessageExtBrokerInner) messageExt);
            } else if (messageExt instanceof MessageExtBatch) {
                result = cb.doAppend(this.getFileFromOffset(), byteBuffer, this.fileSize - currentPos, (MessageExtBatch) messageExt);
            } else {
                return new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR);
            }
            this.wrotePosition.addAndGet(result.getWroteBytes());
            this.storeTimestamp = result.getStoreTimestamp();
            return result;
        }
        log.error("MappedFile.appendMessage return null, wrotePosition: {} fileSize: {}", currentPos, this.fileSize);
        return new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR);
    }

    public long getFileFromOffset() {
        return this.fileFromOffset;
    }

    /**
     * 空间足够的话,将data[]直接写入fileChannel 更新wrotePosition
     */
    public boolean appendMessage(final byte[] data) {
        int currentPos = this.wrotePosition.get();

        if ((currentPos + data.length) <= this.fileSize) {
            try {
                this.fileChannel.position(currentPos);
                this.fileChannel.write(ByteBuffer.wrap(data));
            } catch (Throwable e) {
                log.error("Error occurred when append message to mappedFile.", e);
            }
            this.wrotePosition.addAndGet(data.length);
            return true;
        }

        return false;
    }

    /**
     * 空间足够的话,将data[]的一部分写入fileChannel Content of data from offset to offset + length will be wrote to file.
     *
     * @param offset The offset of the subarray to be used.
     * @param length The length of the subarray to be used.
     */
    public boolean appendMessage(final byte[] data, final int offset, final int length) {
        int currentPos = this.wrotePosition.get();

        if ((currentPos + length) <= this.fileSize) {
            try {
                this.fileChannel.position(currentPos);
                this.fileChannel.write(ByteBuffer.wrap(data, offset, length));
            } catch (Throwable e) {
                log.error("Error occurred when append message to mappedFile.", e);
            }
            this.wrotePosition.addAndGet(length);
            return true;
        }

        return false;
    }

    /**
     * @return The current flushed position
     */
    public int flush(final int flushLeastPages) {
        // 是否可以刷新
        if (this.isAbleToFlush(flushLeastPages)) {
            if (this.hold()) {  // 引用+1
                int value = getReadPosition();    // 获取可以读到的最大位置

                try {
                    //We only append data to fileChannel or mappedByteBuffer, never both.
                    // 从缓存刷到磁盘
                    if (writeBuffer != null || this.fileChannel.position() != 0) {
                        this.fileChannel.force(false);
                    } else {
                        this.mappedByteBuffer.force();
                    }
                } catch (Throwable e) {
                    log.error("Error occurred when force data to disk.", e);
                }

                //更新刷到的位置
                this.flushedPosition.set(value);
                this.release();      // 释放引用
            } else {
                log.warn("in flush, hold failed, flush offset = " + this.flushedPosition.get());
                this.flushedPosition.set(getReadPosition());
            }
        }
        return this.getFlushedPosition();
    }

    /**
     * 提交:writeBuffer写入FileChannel
     * 1.当writeBuffer为null，直接返回
     * 2.否则将writeBuffer中[lastCommittedPosition,writePos)的部分写入fileChannel
     * 3.然后对writeBuffer归还给transientStorePool，返回committedPosition
     *
     * @param commitLeastPages 本次提交最小的页数
     */
    public int commit(final int commitLeastPages) {
        if (writeBuffer == null) {
            //no need to commit data to file channel, so just regard wrotePosition as committedPosition.
            return this.wrotePosition.get();
        }
        if (this.isAbleToCommit(commitLeastPages)) {           //是否可以提交
            if (this.hold()) {
                commit0(commitLeastPages);
                this.release();
            } else {
                log.warn("in commit, hold failed, commit offset = " + this.committedPosition.get());
            }
        }

        // All dirty data has been committed to FileChannel.
        if (writeBuffer != null && this.transientStorePool != null && this.fileSize == this.committedPosition.get()) {
            this.transientStorePool.returnBuffer(writeBuffer);          //还给复用池
            this.writeBuffer = null;
        }

        return this.committedPosition.get();
    }

    /**
     * commit实现,持久化到日志 将writeBuffer中[lastCommittedPosition,writePos)的部分 写入fileChannel 更新committedPosition 参数
     * commitLeastPages 没用
     */
    protected void commit0(final int commitLeastPages) {
        int writePos = this.wrotePosition.get();
        int lastCommittedPosition = this.committedPosition.get();

        // 有可以提交的内容
        if (writePos - this.committedPosition.get() > 0) {
            try {
                // 创建writeBuffer共享缓存区
                ByteBuffer byteBuffer = writeBuffer.slice();
                // 设置position和limit
                byteBuffer.position(lastCommittedPosition);
                byteBuffer.limit(writePos);
                this.fileChannel.position(lastCommittedPosition);
                // 复制写入到fileChannel中
                this.fileChannel.write(byteBuffer);
                // 更新committedPosition
                this.committedPosition.set(writePos);
            } catch (Throwable e) {
                log.error("Error occurred when commit data to FileChannel.", e);
            }
        }
    }

    /**
     * 是否能够flush。满足如下条件任意条件： 1. 文件已经写满 2. flushLeastPages > 0 && 未flush部分超过flushLeastPages 3. flushLeastPages = 0 &&
     * 有新写入部分
     *
     * @param flushLeastPages flush最小分页
     * @return 是否能够写入
     */
    private boolean isAbleToFlush(final int flushLeastPages) {
        int flush = this.flushedPosition.get();
        int write = getReadPosition();

        if (this.isFull()) {
            return true;
        }

        if (flushLeastPages > 0) {
            return ((write / OS_PAGE_SIZE) - (flush / OS_PAGE_SIZE)) >= flushLeastPages;
        }

        return write > flush;
    }

    /**
     * 是否能够commit。满足如下条件任意条件：
     * 1. 映射文件已经写满
     * 2. commitLeastPages > 0 && 未commit部分超过commitLeastPages
     * 3. commitLeastPages = 0 && 有新写入部分
     *
     * @param commitLeastPages commit的最小页数
     */
    protected boolean isAbleToCommit(final int commitLeastPages) {
        int flush = this.committedPosition.get();
        int write = this.wrotePosition.get();

        // 文件已经写满了，可以提交
        if (this.isFull()) {
            return true;
        }

        // 提交的页数 > 0
        if (commitLeastPages > 0) {
            // 未提价的页数大于commitLeastPages
            return ((write / OS_PAGE_SIZE) - (flush / OS_PAGE_SIZE)) >= commitLeastPages;
        }

        // 有新写入的地方
        return write > flush;
    }

    public int getFlushedPosition() {
        return flushedPosition.get();
    }

    public void setFlushedPosition(int pos) {
        this.flushedPosition.set(pos);
    }

    //如果写的位置和要求文件大小一样，则写满了
    public boolean isFull() {
        return this.fileSize == this.wrotePosition.get();
    }

    public SelectMappedBufferResult selectMappedBuffer(int pos, int size) {
        int readPosition = getReadPosition();
        if ((pos + size) <= readPosition) {

            if (this.hold()) {
                ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
                byteBuffer.position(pos);
                ByteBuffer byteBufferNew = byteBuffer.slice();
                byteBufferNew.limit(size);
                return new SelectMappedBufferResult(this.fileFromOffset + pos, byteBufferNew, size, this);
            } else {
                log.warn("matched, but hold failed, request pos: " + pos + ", fileFromOffset: "
                    + this.fileFromOffset);
            }
        } else {
            log.warn("selectMappedBuffer request pos invalid, request pos: " + pos + ", size: " + size
                + ", fileFromOffset: " + this.fileFromOffset);
        }

        return null;
    }

    /**
     * 查找pos到当前最大可读之间的数据
     *
     * @param pos
     * @return
     */
    public SelectMappedBufferResult selectMappedBuffer(int pos) {
        int readPosition = getReadPosition();
        if (pos < readPosition && pos >= 0) {
            if (this.hold()) {
                // 由于整个写入期间，没有调整过mpedByteBuffer，所以这里返回整个mapedfile
                ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
                byteBuffer.position(pos);
                int size = readPosition - pos;
                ByteBuffer byteBufferNew = byteBuffer.slice();
                byteBufferNew.limit(size);
                return new SelectMappedBufferResult(this.fileFromOffset + pos, byteBufferNew, size, this);
            }
        }

        return null;
    }

    /**
     * 清理，监视是否可用，是否已经清理过，条件适当进行清理 更新内存记录以及文件数记录
     *
     * @param currentRef
     * @return
     */
    @Override
    public boolean cleanup(final long currentRef) {
        if (this.isAvailable()) {
            log.error("this file[REF:" + currentRef + "] " + this.fileName
                + " have not shutdown, stop unmapping.");
            return false;
        }

        // 已被清除
        if (this.isCleanupOver()) {
            log.error("this file[REF:" + currentRef + "] " + this.fileName
                + " have cleanup, do not do it again.");
            return true;
        }

        // TODO 真正清理mapedByteBuffer
        clean(this.mappedByteBuffer);
        // 更新统计字段
        TOTAL_MAPPED_VIRTUAL_MEMORY.addAndGet(this.fileSize * (-1));
        TOTAL_MAPPED_FILES.decrementAndGet();
        log.info("unmap file[REF:" + currentRef + "] " + this.fileName + " OK");
        return true;
    }

    /**
     * 参数intervalForcibly代表距离上一次shutdown至少要这么长时间 调用shutdown 如果引用清理干净了删除文件，打log 否则false
     */
    public boolean destroy(final long intervalForcibly) {
        // 关闭mapedfile
        this.shutdown(intervalForcibly);

        //如果引用清理干净了删除文件，打log
        if (this.isCleanupOver()) {
            try {
                this.fileChannel.close();
                log.info("close file channel " + this.fileName + " OK");

                long beginTime = System.currentTimeMillis();
                boolean result = this.file.delete();
                log.info("delete file[REF:" + this.getRefCount() + "] " + this.fileName
                    + (result ? " OK, " : " Failed, ") + "W:" + this.getWrotePosition() + " M:"
                    + this.getFlushedPosition() + ", "
                    + UtilAll.computeEclipseTimeMilliseconds(beginTime));
            } catch (Exception e) {
                log.warn("close file channel " + this.fileName + " Failed. ", e);
            }

            return true;
        } else {
            log.warn("destroy mapped file[REF:" + this.getRefCount() + "] " + this.fileName
                + " Failed. cleanupOver: " + this.cleanupOver);
        }

        return false;
    }

    public int getWrotePosition() {
        return wrotePosition.get();
    }

    public void setWrotePosition(int pos) {
        this.wrotePosition.set(pos);
    }

    /**
     * 返回的是可以读到的最大位置(就是实际写到的位置) 函数名字容易让人误解
     *
     * @return The max position which have valid data
     */
    public int getReadPosition() {
        return this.writeBuffer == null ? this.wrotePosition.get() : this.committedPosition.get();
    }

    public void setCommittedPosition(int pos) {
        this.committedPosition.set(pos);
    }

    /**
     * 预热 mappedByteBuffer 已经通过 mmap 映射，此时操作系统中只是记录了该文件和该 Buffer 的映射关系， 而没有映射到物理内存中。这里就通过对该 MappedFile 的每个 Page Cache
     * 进行写入一个字节， 通过读写操作把 mmap 映射全部加载到物理内存中。
     *
     * 使用mmap建立内存映射后，仅仅只是建立了进程虚拟内存地址与物理内存地址之间的映射关系， 但是并没有将pageCache加载至内存，写数据时如果没有命中写pageCache则发出缺页中断，
     * 为了解决该问题则往每个pageCache写入一个byte，确保在预热时就已经将pageCache加载至内存中。
     *
     * @param type
     * @param pages
     */
    public void warmMappedFile(FlushDiskType type, int pages) {
        long beginTime = System.currentTimeMillis();
        ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
        int flush = 0;
        long time = System.currentTimeMillis();
        for (int i = 0, j = 0; i < this.fileSize; i += MappedFile.OS_PAGE_SIZE, j++) {
            byteBuffer.put(i, (byte) 0);
            // force flush when flush disk type is sync
            // 如果是同步写盘操作，则进行强行刷盘操作
            if (type == FlushDiskType.SYNC_FLUSH) {
                // OS_PAGE_SIZE 为 4K， pages默认为4096， 每隔16M，也就是每写入4096页，就刷新一次
                if ((i / OS_PAGE_SIZE) - (flush / OS_PAGE_SIZE) >= pages) {
                    flush = i;
                    mappedByteBuffer.force();
                }
            }

            // prevent gc
            if (j % 1000 == 0) {
                log.info("j={}, costTime={}", j, System.currentTimeMillis() - time);
                time = System.currentTimeMillis();
                try {

                    Thread.sleep(0);
                } catch (InterruptedException e) {
                    log.error("Interrupted", e);
                }
            }
        }

        // 将剩余数据强制刷新到磁盘中
        // force flush when prepare load finished
        if (type == FlushDiskType.SYNC_FLUSH) {
            log.info("mapped file warm-up done, force to disk, mappedFile={}, costTime={}",
                this.getFileName(), System.currentTimeMillis() - beginTime);
            mappedByteBuffer.force();
        }
        log.info("mapped file warm-up done. mappedFile={}, costTime={}", this.getFileName(),
            System.currentTimeMillis() - beginTime);

        // 锁定内存
        this.mlock();
    }

    public String getFileName() {
        return fileName;
    }

    public MappedByteBuffer getMappedByteBuffer() {
        return mappedByteBuffer;
    }

    public ByteBuffer sliceByteBuffer() {
        return this.mappedByteBuffer.slice();
    }

    public long getStoreTimestamp() {
        return storeTimestamp;
    }

    public boolean isFirstCreateInQueue() {
        return firstCreateInQueue;
    }

    public void setFirstCreateInQueue(boolean firstCreateInQueue) {
        this.firstCreateInQueue = firstCreateInQueue;
    }

    /**
     * 锁定内存，该方法主要是实现文件预热后，防止把预热过的文件被操作系统调到swap空间中。当程序在次读取交换出去的数据的时候会产生缺页异常。
     */
    public void mlock() {
        final long beginTime = System.currentTimeMillis();
        final long address = ((DirectBuffer) (this.mappedByteBuffer)).address();
        Pointer pointer = new Pointer(address);
        {
            // 实现是将锁住指定的内存区域避免被操作系统调到swap空间中。
            int ret = LibC.INSTANCE.mlock(pointer, new NativeLong(this.fileSize));
            log.info("mlock {} {} {} ret = {} time consuming = {}", address, this.fileName, this.fileSize, ret, System.currentTimeMillis() - beginTime);
        }

        {
            // 实现是一次性先将一段数据读入到映射内存区域，这样就减少了缺页异常的产生。
            int ret = LibC.INSTANCE.madvise(pointer, new NativeLong(this.fileSize), LibC.MADV_WILLNEED);
            log.info("madvise {} {} {} ret = {} time consuming = {}", address, this.fileName, this.fileSize, ret, System.currentTimeMillis() - beginTime);
        }
    }

    // 取消锁定内存
    public void munlock() {
        final long beginTime = System.currentTimeMillis();
        final long address = ((DirectBuffer) (this.mappedByteBuffer)).address();
        Pointer pointer = new Pointer(address);
        int ret = LibC.INSTANCE.munlock(pointer, new NativeLong(this.fileSize));
        log.info("munlock {} {} {} ret = {} time consuming = {}", address, this.fileName, this.fileSize, ret, System.currentTimeMillis() - beginTime);
    }

    //testable
    File getFile() {
        return this.file;
    }

    @Override
    public String toString() {
        return this.fileName;
    }
}

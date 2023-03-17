/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.io.cache;

import org.apache.paimon.memory.MemorySegment;
import org.apache.paimon.options.MemorySize;

import org.apache.flink.shaded.guava30.com.google.common.cache.Cache;
import org.apache.flink.shaded.guava30.com.google.common.cache.CacheBuilder;
import org.apache.flink.shaded.guava30.com.google.common.cache.RemovalNotification;

import org.apache.paimon.annotation.VisibleForTesting;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** Cache manager to cache bytes to paged {@link MemorySegment}s. */
public class CacheManager {

    private final int pageSize;
    private final Cache<CacheKey, CacheValue> cache;

    public CacheManager(int pageSize, MemorySize maxMemorySize) {
        this.pageSize = pageSize;
        this.cache =
                CacheBuilder.newBuilder()
                        .weigher(this::weigh)
                        .maximumWeight(maxMemorySize.getBytes())
                        .removalListener(this::onRemoval)
                        .build();
    }

    @VisibleForTesting
    Cache<CacheKey, CacheValue> cache() {
        return cache;
    }

    public int pageSize() {
        return pageSize;
    }

    public MemorySegment getPage(
            RandomAccessFile file, int pageNumber, Consumer<Integer> cleanCallback) {
        CacheKey key = new CacheKey(file, pageNumber);
        CacheValue value;
        try {
            value = cache.get(key, () -> createValue(key, cleanCallback));
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
        return value.segment;
    }

    public void invalidPage(RandomAccessFile file, int pageNumber) {
        cache.invalidate(new CacheKey(file, pageNumber));
    }

    private int weigh(CacheKey cacheKey, CacheValue cacheValue) {
        return cacheValue.segment.size();
    }

    private void onRemoval(RemovalNotification<CacheKey, CacheValue> notification) {
        notification.getValue().cleanCallback.accept(notification.getKey().pageNumber);
    }

    private CacheValue createValue(CacheKey key, Consumer<Integer> cleanCallback)
            throws IOException {
        return new CacheValue(key.read(pageSize), cleanCallback);
    }

    private static class CacheKey {

        private final RandomAccessFile file;
        private final int pageNumber;

        private CacheKey(RandomAccessFile file, int pageNumber) {
            this.file = file;
            this.pageNumber = pageNumber;
        }

        private MemorySegment read(int pageSize) throws IOException {
            long length = file.length();
            long pageAddress = (long) pageNumber * pageSize;
            int len = (int) Math.min(pageSize, length - pageAddress);
            byte[] bytes = new byte[len];
            file.seek(pageAddress);
            file.readFully(bytes);
            return MemorySegment.wrap(bytes);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            CacheKey cacheKey = (CacheKey) o;
            return pageNumber == cacheKey.pageNumber && Objects.equals(file, cacheKey.file);
        }

        @Override
        public int hashCode() {
            return Objects.hash(file, pageNumber);
        }
    }

    private static class CacheValue {

        private final MemorySegment segment;
        private final Consumer<Integer> cleanCallback;

        private CacheValue(MemorySegment segment, Consumer<Integer> cleanCallback) {
            this.segment = segment;
            this.cleanCallback = cleanCallback;
        }
    }
}
/**
 * The BSD License
 *
 * Copyright (c) 2010-2018 RIPE NCC
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   - Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *   - Neither the name of the RIPE NCC nor the names of its contributors may be
 *     used to endorse or promote products derived from this software without
 *     specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package net.ripe.rpki.validator3.util;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Locks {
    public static void locked(Lock lock, Runnable s) {
        lock.lock();
        try {
            s.run();
        } finally {
            lock.unlock();
        }
    }

    public static <T> T locked(Lock lock, Callable<T> c) {
        lock.lock();
        try {
            try {
                return c.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } finally {
            lock.unlock();
        }
    }

    private static ReentrantLock globalLock = new ReentrantLock();
    private static Map<Object, ReentrantLock> perKeyLocks = new HashMap<>();

    public static <T> T lockedPerKey(Object lockKey, Callable<T> c) {
        final ReentrantLock perKeyLock = locked(globalLock, () ->
                perKeyLocks.computeIfAbsent(lockKey, lk -> new ReentrantLock()));
        try {
            return locked(perKeyLock, c);
        } finally {
            locked(globalLock, () -> perKeyLocks.remove(lockKey));
        }
    }

    public static void lockedPerKey(Object lockKey, Runnable r) {
        final ReentrantLock perKeyLock = locked(globalLock, () ->
                perKeyLocks.computeIfAbsent(lockKey, lk -> new ReentrantLock()));
        try {
            locked(perKeyLock, r);
        } finally {
            locked(globalLock, () -> perKeyLocks.remove(lockKey));
        }
    }

}

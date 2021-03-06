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
package net.ripe.rpki.validator3.storage.lmdb;

import lombok.Getter;
import net.ripe.rpki.validator3.storage.data.Key;
import net.ripe.rpki.validator3.storage.encoding.Coder;
import org.apache.commons.lang3.tuple.Pair;
import org.lmdbjava.CursorIterator;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.KeyRange;
import org.lmdbjava.Txn;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.lmdbjava.DbiFlags.MDB_CREATE;
import static org.lmdbjava.DbiFlags.MDB_DUPSORT;

/**
 *
 *
 *
 * @param <T>
 */
public class IxMap<T extends Serializable> extends IxBase<T> {

    private final Map<String, Dbi<ByteBuffer>> indexes;
    private final Map<String, Function<T, Set<Key>>> indexFunctions;
    private final List<BiConsumer<Tx.Write, Key>> onDeleteTriggers = new ArrayList<>();

    public IxMap(final Lmdb lmdb,
                 final String name,
                 final Coder<T> coder,
                 final Map<String, Function<T, Set<Key>>> indexFunctions) {
        super(lmdb, name, coder);
        this.indexFunctions = indexFunctions;
        Pair<Map<String, Dbi<ByteBuffer>>, Boolean> p = lmdb.createIndexes(name, indexFunctions, getIndexDbiFlags());
        indexes = p.getLeft();
        boolean reindex = p.getRight();
        if (reindex) {
            reindex();
        }
    }

    public IxMap(final Lmdb lmdb, String name, Coder<T> coder) {
        this(lmdb, name, coder, Collections.emptyMap());
    }

    private void reindex() {
        final Tx.Write tx = writeTx();
        try {
            Txn<ByteBuffer> txn = tx.txn();
            indexes.forEach((name, idx) -> idx.drop(txn));
            forEach(tx, (k, bb) -> {
                final T value = getValue(k, bb);
                indexFunctions.forEach((n, idxFun) ->
                        idxFun.apply(value).forEach(ik -> {
                            final Dbi<ByteBuffer> idx = getIdx(n);
                            idx.put(txn, ik.toByteBuffer(), k.toByteBuffer());
                        }));
            });
            txn.commit();
        } finally {
            tx.close();
        }
    }

    private Dbi<ByteBuffer> getIdx(String name) {
        checkEnv();
        return indexes.get(name);
    }

    private void dropIndexes(Tx.Write tx) {
        indexes.forEach((name, db) -> db.drop(tx.txn()));
    }

    protected DbiFlags[] getMainDbCreateFlags() {
        return new DbiFlags[]{MDB_CREATE};
    }

    protected DbiFlags[] getIndexDbiFlags() {
        return new DbiFlags[]{MDB_CREATE, MDB_DUPSORT};
    }

    public Optional<T> get(Key primaryKey) {
        return get(readTx(), primaryKey);
    }

    public Optional<T> get(Tx.Read txn, Key primaryKey) {
        verifyKey(primaryKey);
        ByteBuffer bb = getMainDb().get(txn.txn(), primaryKey.toByteBuffer());
        return bb == null ?
                Optional.empty() :
                Optional.of(getValue(primaryKey, bb));
    }

    public List<T> get(Tx.Read txn, Set<Key> primaryKeys) {
        return primaryKeys.stream()
                .map(pk -> get(txn, pk))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    public Map<Key, T> getByIndex(String indexName, Tx.Read tx, Key indexKey) {
        checkNotNull(indexKey, "Index key is null");
        final ByteBuffer idxKey = indexKey.toByteBuffer();
        return getByIndexKeyRange(indexName, tx, KeyRange.closed(idxKey, idxKey));
    }

    public Set<Key> getPkByIndex(String indexName, Tx.Read tx, Key indexKey) {
        checkNotNull(indexKey, "Index key is null");
        final ByteBuffer idxKey = indexKey.toByteBuffer();
        return getPkByIndexKeyRange(indexName, tx, KeyRange.closed(idxKey, idxKey));
    }

    public Map<Key, T> getByIndexLess(String indexName, Tx.Read tx, Key indexKey) {
        final ByteBuffer idxKey = indexKey.toByteBuffer();
        return getByIndexKeyRange(indexName, tx, KeyRange.lessThan(idxKey));
    }

    public Map<Key, T> getByIndexGreater(String indexName, Tx.Read tx, Key indexKey) {
        final ByteBuffer idxKey = indexKey.toByteBuffer();
        return getByIndexKeyRange(indexName, tx, KeyRange.greaterThan(idxKey));
    }

    public Set<Key> getByIndexLessPk(String indexName, Tx.Read tx, Key indexKey) {
        final ByteBuffer idxKey = indexKey.toByteBuffer();
        return getPkByIndexKeyRange(indexName, tx, KeyRange.lessThan(idxKey));
    }

    public Set<Key> getByIndexGreaterPk(String indexName, Tx.Read tx, Key indexKey) {
        final ByteBuffer idxKey = indexKey.toByteBuffer();
        return getPkByIndexKeyRange(indexName, tx, KeyRange.greaterThan(idxKey));
    }

    public Map<Key, T> getByIndexMax(String indexName, Tx.Read tx, Predicate<T> p) {
        return getKeyAtTheMinOrMaxOfIndex(indexName, tx, KeyRange.allBackward(), p);
    }

    public Map<Key, T> getByIndexMin(String indexName, Tx.Read tx, Predicate<T> p) {
        return getKeyAtTheMinOrMaxOfIndex(indexName, tx, KeyRange.all(), p);
    }

    Set<Key> getPkByIndexMax(String indexName, Tx.Read tx) {
        return getKeyAtTheMinOrMaxOfIndex(indexName, tx, KeyRange.allBackward());
    }

    Set<Key> getPkByIndexMin(String indexName, Tx.Read tx) {
        return getKeyAtTheMinOrMaxOfIndex(indexName, tx, KeyRange.all());
    }

    public Optional<T> put(Tx.Write tx, Key primaryKey, T value) {
        checkKeyAndValue(primaryKey, value);
        final Txn<ByteBuffer> txn = tx.txn();
        final Optional<T> oldValue = get(tx, primaryKey);
        final ByteBuffer pkBuf = primaryKey.toByteBuffer();
        final ByteBuffer val = valueBuf(value);
        getMainDb().put(txn, pkBuf, val);
        if (oldValue.isPresent()) {
            indexFunctions.forEach((idxName, idxFun) -> {
                final Set<Key> oldIndexKeys = idxFun.apply(oldValue.get());
                final Set<Key> indexKeys = idxFun.apply(value).stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                final Dbi<ByteBuffer> index = getIdx(idxName);
                oldIndexKeys.stream()
                        .filter(oik -> !indexKeys.contains(oik))
                        .forEach(oik -> index.delete(txn, oik.toByteBuffer(), pkBuf));

                indexKeys.stream()
                        .filter(ik -> !oldIndexKeys.contains(ik))
                        .forEach(ik -> index.put(txn, ik.toByteBuffer(), pkBuf));
            });
            return oldValue;
        } else {
            indexFunctions.forEach((idxName, idxFun) -> {
                final Set<Key> indexKeys = idxFun.apply(value).stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

                indexKeys.forEach(ik -> getIdx(idxName).put(txn, ik.toByteBuffer(), pkBuf));
            });
        }
        return Optional.empty();
    }

    public boolean modify(Tx.Write tx, Key primaryKey, Consumer<T> modifyValue) {
        final Optional<T> t = get(tx, primaryKey);
        t.ifPresent(v -> {
            modifyValue.accept(v);
            put(tx, primaryKey, v);
        });
        return t.isPresent();
    }

    public void delete(Tx.Write tx, Key primaryKey) {
        checkNotNull(primaryKey, "Key is null");
        final Txn<ByteBuffer> txn = tx.txn();
        final Dbi<ByteBuffer> mainDb = getMainDb();
        final ByteBuffer pkBuf = primaryKey.toByteBuffer();
        if (indexFunctions.isEmpty()) {
            mainDb.delete(txn, pkBuf);
        } else {
            final ByteBuffer bb = mainDb.get(txn, pkBuf);
            if (bb != null) {
                // TODO probably avoid deserialization, just store the
                //  index keys next to the serialized value
                final T value = getValue(primaryKey, bb);
                mainDb.delete(txn, pkBuf);
                indexFunctions.forEach((idxName, idxFun) ->
                        idxFun.apply(value).forEach(ix ->
                                getIdx(idxName).delete(txn, ix.toByteBuffer(), pkBuf)));
            }
        }
        try {
            onDeleteTriggers.forEach(bf -> bf.accept(tx, primaryKey));
        } catch (OnDeleteRestrictException o) {
            tx.abort();
        }
    }

    public void onDelete(BiConsumer<Tx.Write, Key> bf) {
        onDeleteTriggers.add(bf);
    }

    @Override
    public void clear(Tx.Write tx) {
        getMainDb().drop(tx.txn());
        dropIndexes(tx);
    }

    private Map<Key, T> getByIndexKeyRange(String indexName, Tx.Read tx, KeyRange keyRange) {
        final Dbi<ByteBuffer> index = getIdx(indexName);
        if (index == null) {
            return Collections.emptyMap();
        }
        final Txn<ByteBuffer> txn = tx.txn();
        final Map<Key, T> m = new HashMap<>();
        final Dbi<ByteBuffer> mainDb = getMainDb();
        try (final CursorIterator<ByteBuffer> iterator = index.iterate(txn, keyRange)) {
            while (iterator.hasNext()) {
                final Key pk = new Key(iterator.next().val());
                if (!m.containsKey(pk)) {
                    final ByteBuffer bb = mainDb.get(txn, pk.toByteBuffer());
                    if (bb != null) {
                        m.put(pk, getValue(pk, bb));
                    }
                }
            }
        }
        return m;
    }

    private Set<Key> getPkByIndexKeyRange(String indexName, Tx.Read tx, KeyRange keyRange) {
        final Dbi<ByteBuffer> index = getIdx(indexName);
        if (index == null) {
            return Collections.emptySet();
        }
        final Txn<ByteBuffer> txn = tx.txn();
        final Set<Key> values = new HashSet<>();
        try (final CursorIterator<ByteBuffer> iterator = index.iterate(txn, keyRange)) {
            while (iterator.hasNext()) {
                values.add(new Key(iterator.next().val()));
            }
        }
        return values;
    }

    private Set<Key> getKeyAtTheMinOrMaxOfIndex(String indexName, Tx.Read tx, KeyRange<ByteBuffer> objectKeyRange) {
        final Dbi<ByteBuffer> index = getIdx(indexName);
        if (index != null) {
            final Txn<ByteBuffer> txn = tx.txn();
            try (final CursorIterator<ByteBuffer> iterator = index.iterate(txn, objectKeyRange)) {
                final Set<Key> primaryKeys = new HashSet<>();
                ByteBuffer currentIndexKey = null;
                while (iterator.hasNext()) {
                    final CursorIterator.KeyVal<ByteBuffer> next = iterator.next();
                    final ByteBuffer indexKey = next.key();
                    if (currentIndexKey != null) {
                        if (!currentIndexKey.equals(indexKey)) {
                            return primaryKeys;
                        }
                        primaryKeys.add(new Key(next.val()));
                    } else {
                        primaryKeys.add(new Key(next.val()));
                        currentIndexKey = indexKey.duplicate();
                    }
                }
                return primaryKeys;
            }
        }
        return Collections.emptySet();
    }

    private Map<Key, T> getKeyAtTheMinOrMaxOfIndex(String indexName, Tx.Read tx, KeyRange<ByteBuffer> keyRange, Predicate<T> p) {
        final Dbi<ByteBuffer> index = getIdx(indexName);
        if (index != null) {
            final Dbi<ByteBuffer> mainDb = getMainDb();
            final Txn<ByteBuffer> txn = tx.txn();
            try (final CursorIterator<ByteBuffer> iterator = index.iterate(txn, keyRange)) {
                final Map<Key, T> m = new HashMap<>();
                Key currentIndexKey = null;
                while (iterator.hasNext()) {
                    final CursorIterator.KeyVal<ByteBuffer> next = iterator.next();
                    final Key indexKey = new Key(next.key());
                    final ByteBuffer pkBuf = next.val();
                    if (currentIndexKey != null) {
                        if (!currentIndexKey.equals(indexKey)) {
                            return m;
                        }
                        final T value = toValue(mainDb.get(txn, pkBuf));
                        if (p.test(value)) {
                            m.put(new Key(pkBuf), value);
                        }
                    } else {
                        final T value = toValue(mainDb.get(txn, pkBuf));
                        if (p.test(value)) {
                            m.put(new Key(pkBuf), value);
                            currentIndexKey = indexKey;
                        }
                    }
                }
                return m;
            }
        }
        return Collections.emptyMap();
    }

    @Override
    public IxBase.Sizes sizeInfo(Tx.Read tx) {
        IxBase.Sizes sizes = super.sizeInfo(tx);
        final Map<String, IxBase.Sizes> indexSizes = new HashMap<>();
        indexes.forEach((name, ignore) -> {
            Dbi<ByteBuffer> idx = getIdx(name);
            AtomicInteger count = new AtomicInteger();
            AtomicInteger size = new AtomicInteger();
            try (final CursorIterator<ByteBuffer> iterator = idx.iterate(tx.txn())) {
                while (iterator.hasNext()) {
                    count.incrementAndGet();
                    final CursorIterator.KeyVal<ByteBuffer> next = iterator.next();
                    size.addAndGet(next.key().remaining() + next.val().remaining());
                }
            }
            long allocatedSize = getAllocatedSize(tx, idx);
            indexSizes.put(name, new IxBase.Sizes(count.get(), size.get(), allocatedSize));
        });
        return new Sizes(sizes.getCount(),
                sizes.getKeysAndValuesBytes(),
                sizes.getAllocatedSize(),
                indexSizes);
    }

    public void verify(Tx.Read tx) {

        final Map<Key, Set<Key>> indexValues = new HashMap<>();
        forEach(tx, (k, bb) -> {
            final T value = getValue(k, bb);
            indexFunctions.forEach((n, idxFun) -> {
                idxFun.apply(value).forEach(ik -> {
                            Set<Key> pks = indexValues.get(ik);
                            if (pks == null) {
                                pks = getPkByIndex(n, tx, ik);
                                indexValues.put(ik, pks);
                            }
                            if (!pks.contains(k)) {
                                throw new RuntimeException("PkBuf blabla");
                            }
                        }
                );
            });
        });
    }

    public static class Sizes extends IxBase.Sizes {
        @Getter
        private Map<String, IxBase.Sizes> indexSizes;

        @Getter
        private long totalKeysAndValuesBytes;

        @Getter
        private long totalAllocatedSize;

        Sizes(int count, long sizeInBytes, long allocatedSize, Map<String, IxBase.Sizes> indexSizes) {
            super(count, sizeInBytes, allocatedSize);
            this.indexSizes = indexSizes.isEmpty() ? null : indexSizes;
            totalKeysAndValuesBytes = sizeInBytes;
            totalAllocatedSize = allocatedSize;
            indexSizes.forEach((n, s) -> {
                totalKeysAndValuesBytes += s.getKeysAndValuesBytes();
                totalAllocatedSize += s.getAllocatedSize();
            });
        }
    }

}

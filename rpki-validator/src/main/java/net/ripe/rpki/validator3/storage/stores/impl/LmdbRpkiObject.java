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
package net.ripe.rpki.validator3.storage.stores.impl;

import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.UnsignedBytes;
import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.commons.crypto.CertificateRepositoryObject;
import net.ripe.rpki.commons.crypto.cms.manifest.ManifestCms;
import net.ripe.rpki.commons.validation.ValidationResult;
import net.ripe.rpki.validator3.storage.Bytes;
import net.ripe.rpki.validator3.storage.lmdb.Lmdb;
import net.ripe.rpki.validator3.storage.data.Key;
import net.ripe.rpki.validator3.storage.data.RpkiObject;
import net.ripe.rpki.validator3.storage.encoding.CoderFactory;
import net.ripe.rpki.validator3.storage.lmdb.IxMap;
import net.ripe.rpki.validator3.storage.lmdb.Tx;
import net.ripe.rpki.validator3.storage.stores.GenericStoreImpl;
import net.ripe.rpki.validator3.storage.stores.RpkiObjectStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@Slf4j
public class LmdbRpkiObject extends GenericStoreImpl<RpkiObject> implements RpkiObjectStore {

    private static final String RPKI_OBJECTS = "rpki-objects";
    private static final String BY_AKI_MFT_INDEX = "by-aki-mft";
    private static final String BY_TYPE_INDEX = "by-type";
    private static final String BY_LAST_REACHABLE_INDEX = "by-last-reachable";

    private static final int SHA256_SIZE_IN_BYTES = 32;

    private final IxMap<RpkiObject> ixMap;

    private Set<Key> lasMarkedReachableKey(RpkiObject rpkiObject) {
        Instant lastMarkedReachableAt = rpkiObject.getLastMarkedReachableAt();
        return lastMarkedReachableAt == null ?
                Collections.emptySet() :
                Key.keys(Key.of(lastMarkedReachableAt.toEpochMilli()));
    }

    private Set<Key> akiMftKey(RpkiObject rpkiObject) {
        byte[] authorityKeyIdentifier = rpkiObject.getAuthorityKeyIdentifier();
        return (rpkiObject.getType() == RpkiObject.Type.MFT && authorityKeyIdentifier != null) ?
                Key.keys(Key.of(authorityKeyIdentifier)) :
                Collections.emptySet();
    }

    private Set<Key> typeKey(RpkiObject rpkiObject) {
        return Key.keys(Key.of(rpkiObject.getType().toString()));
    }

    @Autowired
    public LmdbRpkiObject(Lmdb lmdb) {
        this.ixMap = lmdb.createSameSizeKeyIxMap(
                SHA256_SIZE_IN_BYTES,
                RPKI_OBJECTS,
                ImmutableMap.of(
                        BY_AKI_MFT_INDEX, this::akiMftKey,
                        BY_TYPE_INDEX, this::typeKey,
                        BY_LAST_REACHABLE_INDEX, this::lasMarkedReachableKey),
                CoderFactory.makeCoder(RpkiObject.class));
    }

    @Override
    public RpkiObject put(Tx.Write tx, RpkiObject o) {
        o.setId(Key.of(o.getSha256()));
        ixMap.put(tx, o.key(), o);
        return o;
    }

    @Override
    public void remove(Tx.Write tx, RpkiObject o) {
        ixMap.delete(tx, o.key());
    }

    @Override
    public <T extends CertificateRepositoryObject> Optional<T> findCertificateRepositoryObject(
            Tx.Read tx, Key sha256, Class<T> clazz, ValidationResult validationResult) {
        return ixMap.get(tx, sha256).flatMap(o -> o.get(clazz, validationResult));
    }

    @Override
    public Optional<RpkiObject> get(Tx.Read tx, Key key) {
        return findBySha256(tx, Bytes.toBytes(key.toByteBuffer()));
    }

    @Override
    public Optional<RpkiObject> findBySha256(Tx.Read tx, byte[] sha256) {
        return ixMap.get(tx, Key.of(sha256));
    }

    @Override
    public Map<String, RpkiObject> findObjectsInManifest(Tx.Read tx, ManifestCms manifestCms) {
        final SortedMap<byte[], String> hashes = new TreeMap<>(UnsignedBytes.lexicographicalComparator());
        manifestCms.getFiles().forEach((name, hash) -> hashes.put(hash, name));
        return hashes.keySet().stream()
                .map(sha256 -> findBySha256(tx, sha256))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toMap(
                        x -> hashes.get(x.getSha256()),
                        x -> x
                ));
    }

    @Override
    public Optional<RpkiObject> findLatestMftByAKI(Tx.Read tx, byte[] authorityKeyIdentifier) {
        return ixMap.getByIndex(BY_AKI_MFT_INDEX, tx, Key.of(authorityKeyIdentifier))
                .values()
                .stream()
                .max(Comparator
                        .comparing(RpkiObject::getSerialNumber)
                        .thenComparing(RpkiObject::getSigningTime));
    }

    @Override
    public long deleteUnreachableObjects(Tx.Write tx, Instant unreachableSince) {
        final Set<Key> tooOldPks = ixMap.getByIndexLessPk(BY_LAST_REACHABLE_INDEX, tx, Key.of(unreachableSince.toEpochMilli()));
        tooOldPks.forEach(pk -> ixMap.delete(tx, pk));
        return (long) tooOldPks.size();
    }

    @Override
    public Stream<byte[]> streamObjects(Tx.Read tx, RpkiObject.Type type) {
        final List<byte[]> objectBytes = new ArrayList<>();
        ixMap.forEach(tx, (key, val) -> {
            if (val != null) {
                RpkiObject rpkiObject = ixMap.toValue(val);
                if (type.equals(rpkiObject.getType())) {
                    objectBytes.add(rpkiObject.getEncoded());
                }
            }
        });
        return objectBytes.stream();
    }

    @Override
    public Set<Key> getPkByType(Tx.Read tx, RpkiObject.Type type) {
        return ixMap.getPkByIndex(BY_TYPE_INDEX, tx, Key.of(type.toString()));
    }

    @Override
    protected IxMap<RpkiObject> ixMap() {
        return ixMap;
    }
}

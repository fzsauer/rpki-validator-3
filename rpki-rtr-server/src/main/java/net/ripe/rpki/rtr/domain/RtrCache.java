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
package net.ripe.rpki.rtr.domain;

import fj.data.Either;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.SortedSet;

@Component
@Slf4j
public class RtrCache {
    public static final int SERIAL_BITS = 32;
    public static final Long MAX_SERIAL_NUMBER = ((1L << SERIAL_BITS) - 1);

    @Getter
    private volatile short sessionId;
    private final VersionedSet<RtrDataUnit> data;

    public RtrCache() {
        this(0L);
    }

    public RtrCache(long initialVersion) {
        this.sessionId = (short) new SecureRandom().nextInt();
        this.data = new VersionedSet<>(null, initialVersion);
    }

    public synchronized void updateValidatedPdus(Collection<RtrDataUnit> updatedPdus) {
        if (data.updateValues(updatedPdus)) {
            log.info(
                "{} validated ROAs updated to serial number {} (delta with {} announcements, {} withdrawals)",
                data.size(),
                data.getCurrentVersion(),
                data.getDelta(data.getCurrentVersion() - 1).map(x -> x.getAdditions().size()).orElse(0),
                data.getDelta(data.getCurrentVersion() - 1).map(x -> x.getRemovals().size()).orElse(0)
            );
        } else {
            log.info("no updates to cached data");
        }
    }

    public synchronized Content getCurrentContent() {
        return Content.of(sessionId, getSerialNumber(), data.getValues());
    }

    /**
     * Serial number uses <a href="https://tools.ietf.org/html/rfc1982">RFC1982 serial number arithmetic</a>.
     *
     * @return the current serial number of the RTR cache
     */
    public synchronized int getSerialNumber() {
        return (int) (data.getCurrentVersion() & MAX_SERIAL_NUMBER);
    }

    /**
     * Serial number uses <a href="https://tools.ietf.org/html/rfc1982">RFC1982 serial number arithmetic</a>.
     *
     * @return the delta from the requested <code>serialNumber</code> to the current state
     */
    public synchronized Optional<Delta> getDeltaFrom(int serialNumber) {
        if (serialNumber > getSerialNumber()) {
            return Optional.empty();
        } else if (serialNumber == getSerialNumber()) {
            return Optional.of(Delta.of(sessionId, serialNumber, Collections.emptySortedSet(), Collections.emptySortedSet()));
        } else {
            long version = data.getCurrentVersion();

            // FIXME this is not yet working correctly
            Optional<VersionedSet<RtrDataUnit>.Delta> delta = data.getDelta((long) serialNumber + (version & ~MAX_SERIAL_NUMBER));
            if (delta.isPresent()) {
                return Optional.of(Delta.of(sessionId, getSerialNumber(), delta.get().getAdditions(), delta.get().getRemovals()));
            } else {
                return Optional.empty();
            }
        }
    }

    public synchronized Either<Delta, Content> getDeltaOrContent(int serialNumber) {
        Optional<Delta> maybeDelta = getDeltaFrom(serialNumber);
        if (maybeDelta.isPresent()) {
            return Either.left(maybeDelta.get());
        } else {
            return Either.right(getCurrentContent());
        }
    }

    @Value(staticConstructor = "of")
    public static class Content {
        short sessionId;
        int serialNumber;
        SortedSet<RtrDataUnit> announcements;
    }

    @Value(staticConstructor = "of")
    public static class Delta {
        short sessionId;
        int serialNumber;
        SortedSet<RtrDataUnit> announcements;
        SortedSet<RtrDataUnit> withdrawals;
    }
}

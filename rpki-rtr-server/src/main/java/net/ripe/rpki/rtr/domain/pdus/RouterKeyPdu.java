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
package net.ripe.rpki.rtr.domain.pdus;

import io.netty.buffer.ByteBuf;
import lombok.Value;

@Value(staticConstructor = "of")
public class RouterKeyPdu implements Pdu {
    public static final int PDU_TYPE = 9;

    ProtocolVersion protocolVersion;
    Flags flags;
    byte[] subjectKeyIdentifier;
    byte[] subjectPublicKeyInfo;
    int asn;

    @Override
    public int length() {
        return 8 + subjectKeyIdentifier.length + 4 + subjectPublicKeyInfo.length;
    }

    @Override
    public void write(ByteBuf out) {
        switch (protocolVersion) {
            case V0:
                // Protocol version 0 does not support router keys.
                break;
            case V1:
                out
                    .writeByte(protocolVersion.getValue())
                    .writeByte(PDU_TYPE)
                    .writeByte(flags.getFlags())
                    .writeByte(0)
                    .writeInt(length())
                    .writeBytes(subjectKeyIdentifier)
                    .writeInt(asn)
                    .writeBytes(subjectPublicKeyInfo);
                break;
        }
    }
}

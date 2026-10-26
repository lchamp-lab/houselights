/*
 * Copyright 2016-2018 Dell Inc. or its subsidiaries. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0.txt
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * Changed for Houselights in 2026: nfs-client 1.1.0's RPCRecordDecoder.java, rewritten in Kotlin so that a reply's
 * record fragments are joined into one whatever reads they come in, and a record longer than any reply is refused
 * (issue #88). The License is also in third_party/nfs-client/LICENSE.
 */
package com.emc.ecs.nfsclient.network

import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.channel.Channel
import org.jboss.netty.channel.ChannelHandlerContext
import org.jboss.netty.handler.codec.frame.FrameDecoder
import org.jboss.netty.handler.codec.frame.TooLongFrameException
import java.nio.ByteBuffer

/**
 * Reads each RPC reply off one of nfs-client's connections whole, however many record fragments the server sent it in
 * (RFC 5531, section 11), and hands it on in a single fragment. It stands in for nfs-client 1.1.0's own
 * RPCRecordDecoder, which every connection's pipeline builds (Connection): the build takes that one out of
 * nfs-client's jar (app/build.gradle.kts), so this one is the only class of that name.
 *
 * Why: a NAS's kernel nfsd (Synology, QNAP, TrueNAS, Unraid) sends each reply in one fragment, but NFS servers on
 * libtirpc, unfs3 among them, split any reply above 64 KB into fragments of 64 KB. nfs-client 1.1.0 cannot read those.
 * Its decoder finds the reply's start again by stepping back over the fragments it has passed, but FrameDecoder has
 * dropped the ones an earlier read brought; and RecordMarkingUtil.removeRecordMarking reads the second fragment's mark
 * 4 bytes too early. Every read above 64 KB then failed with « tcp IO error » and the NAS was taken for unreachable:
 * a scan stopped at its first film, whose probe reads 256 KB, and NFOs and artwork over 64 KB were not read. Here the
 * fragments wait in FrameDecoder's buffer until the last one is there, then are read out at once into one fragment,
 * which removeRecordMarking reads correctly. A reply costs no more round trips than one in a single fragment; reading
 * less than 64 KB at a time would have avoided the fragments, at the cost of more round trips on every NAS.
 *
 * Remove this file, and the build's copy of nfs-client's jar without its decoder, once nfs-client joins fragments
 * itself.
 */
class RPCRecordDecoder : FrameDecoder() {
    override fun decode(ctx: ChannelHandlerContext?, channel: Channel?, buffer: ChannelBuffer): Any? {
        val (end, size) = wholeReply(buffer) ?: return null
        val reply = ByteArray(MARK_BYTES + size)
        ByteBuffer.wrap(reply).putInt(LAST or size)
        var at = MARK_BYTES
        while (buffer.readerIndex() < end) {
            val length = buffer.readInt() and LENGTH
            buffer.readBytes(reply, at, length)
            at += length
        }
        return reply
    }

    /**
     * Where the reply that begins [buffer]'s readable bytes ends, and the size of its data, once its last fragment has
     * come in full; null until then, nothing read. Throws as soon as a mark makes the reply longer than
     * [MAX_RECORD_BYTES]: such a mark is a fault of the stream rather than a reply.
     */
    private fun wholeReply(buffer: ChannelBuffer): Pair<Int, Int>? {
        val start = buffer.readerIndex()
        var at = start
        var size = 0
        while (buffer.writerIndex() - at >= MARK_BYTES) {
            val mark = buffer.getInt(at)
            val length = mark and LENGTH
            if (length > MAX_RECORD_BYTES - MARK_BYTES - (at - start)) {
                throw TooLongFrameException("An RPC record longer than $MAX_RECORD_BYTES bytes")
            }
            at += MARK_BYTES + length
            size += length
            if (at > buffer.writerIndex()) return null
            if (mark and LAST != 0) return at to size
        }
        return null
    }

    companion object {
        /**
         * The longest record read, its marks included: a READ answers 1 MB at most (Linux's NFS server, the largest
         * rsize), and the app asks for 256 KB.
         */
        internal const val MAX_RECORD_BYTES = 4 shl 20

        /** A fragment's mark: its length, and in the top bit whether it is the reply's last. */
        private const val MARK_BYTES = 4
        private const val LAST = Int.MIN_VALUE
        private const val LENGTH = Int.MAX_VALUE
    }
}

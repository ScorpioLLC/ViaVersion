/*
 * This file is part of ViaVersion - https://github.com/ViaVersion/ViaVersion
 * Copyright (C) 2016-2022 ViaVersion and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.viaversion.viaversion.bukkit.handlers;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.bukkit.util.NMSUtil;
import com.viaversion.viaversion.exception.CancelCodecException;
import com.viaversion.viaversion.exception.CancelEncoderException;
import com.viaversion.viaversion.exception.InformativeException;
import com.viaversion.viaversion.handlers.ViaCodecHandler;
import com.viaversion.viaversion.util.PipelineUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.lang.reflect.InvocationTargetException;

public class BukkitEncodeHandler extends MessageToByteEncoder<ByteBuf> implements ViaCodecHandler {
    private final UserConnection info;
    private boolean handledCompression;
    public BukkitEncodeHandler(UserConnection info) {
        this.info = info;
    }

    @Override
    protected void encode(final ChannelHandlerContext ctx, ByteBuf input, final ByteBuf output) throws Exception {
        output.writeBytes(input);
        if (!info.checkClientboundPacket()) throw CancelEncoderException.generate(null);
        if (!info.shouldTransformPacket()) return;
        boolean needsCompression = handleCompressionOrder(ctx, output);
        transform(output);
        if (needsCompression) {
            recompress(ctx, output);
        }
    }

    @Override
    public void transform(ByteBuf bytebuf) throws Exception {
        info.transformClientbound(bytebuf, CancelEncoderException::generate);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (PipelineUtil.containsCause(cause, CancelCodecException.class)) return; // ProtocolLib compat

        super.exceptionCaught(ctx, cause);
        if (!NMSUtil.isDebugPropertySet() && PipelineUtil.containsCause(cause, InformativeException.class)
                && (info.getProtocolInfo().getState() != State.HANDSHAKE || Via.getManager().isDebug())) {
            cause.printStackTrace(); // Print if CB doesn't already do it
        }
    }

    private void recompress(ChannelHandlerContext ctx, ByteBuf buffer) throws InvocationTargetException {
        ByteBuf temp = ctx.alloc().buffer().writeBytes(buffer);
        buffer.clear();
        PipelineUtil.callEncode((MessageToByteEncoder) ctx.pipeline().get("compress"),
                ctx.pipeline().context("compress"), temp, buffer);
    }

    private boolean handleCompressionOrder(ChannelHandlerContext ctx, ByteBuf buffer) throws InvocationTargetException {
        if (handledCompression) return false;

        int encoderIndex = ctx.pipeline().names().indexOf("compress");
        if (encoderIndex == -1) return false;
        handledCompression = true;
        if (encoderIndex > ctx.pipeline().names().indexOf("via-encoder")) {
            //This packet has been compressed, we need to decompress to process it.
            ByteBuf decompressed = (ByteBuf) PipelineUtil.callDecode((MessageToMessageDecoder) ctx.pipeline().get("decompress"), ctx.pipeline().context("decompress"), buffer).get(0);
            if (buffer != decompressed) {
                try {
                    buffer.clear().writeBytes(decompressed);
                } finally {
                    decompressed.release();
                }
            }
            // Reorder the pipeline
            ChannelHandler dec = ctx.pipeline().get("via-decoder");
            ChannelHandler enc = ctx.pipeline().get("via-encoder");
            ctx.pipeline().remove(dec);
            ctx.pipeline().remove(enc);
            ctx.pipeline().addAfter("decompress", "via-decoder", dec);
            ctx.pipeline().addAfter("compress", "via-encoder", enc);
            return true;
        }
        return false;
    }
}

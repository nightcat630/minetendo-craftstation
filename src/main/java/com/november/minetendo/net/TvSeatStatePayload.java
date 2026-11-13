// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 席位状态（服务器 → 附近玩家）。
 *
 * <p>{@code holder} 是操作席持有者的 UUID（null/全零 = 空席），{@code youHold} 是给收包人自己看的
 * 便捷字段（避免客户端拿自己的 UUID 去比。客户端拿到包时还不知道自己的 UUID 是否等于 holder，
 * 所以服务器直接算好）。
 */
public record TvSeatStatePayload(BlockPos pos, UUID holder, String holderName, int watchers,
                                 boolean youHold, int revision) implements CustomPacketPayload {

    public static final Type<TvSeatStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("minetendo", "tv_seat_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TvSeatStatePayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                BlockPos.STREAM_CODEC.encode(buf, p.pos());
                buf.writeBoolean(p.holder() != null);
                if (p.holder() != null) buf.writeUUID(p.holder());
                buf.writeUtf(p.holderName() == null ? "" : p.holderName(), 40);
                buf.writeVarInt(p.watchers());
                buf.writeBoolean(p.youHold());
                buf.writeVarInt(p.revision());
            },
            buf -> {
                BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                UUID holder = buf.readBoolean() ? buf.readUUID() : null;
                String name = buf.readUtf(40);
                int watchers = buf.readVarInt();
                boolean youHold = buf.readBoolean();
                int revision = buf.readVarInt();
                return new TvSeatStatePayload(pos, holder, name, watchers, youHold, revision);
            });

    public boolean free() { return holder == null; }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

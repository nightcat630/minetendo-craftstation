// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo;

import net.neoforged.bus.api.IEventBus;

/** 物品/方块统一在 client.CrtTvRegistry 注册；这里保留空壳以兼容入口调用。 */
public final class MinetendoItems {
    private MinetendoItems() {}
    public static void register(IEventBus modBus) {}
}
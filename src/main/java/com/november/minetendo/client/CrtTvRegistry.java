// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

import com.november.minetendo.Minetendo;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 方块与物品注册：CRT 电视 + 四台游戏机（SFC / GBA / NDS / 3DS）。 */
public final class CrtTvRegistry {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Minetendo.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BE_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Minetendo.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, Minetendo.MOD_ID);

    // ── CRT 电视（纯显示器）──
    public static final DeferredBlock<Block> TV = BLOCKS.register("crt_tv",
            () -> new CrtTvBlock(BlockBehaviour.Properties.of()
                    .strength(2.0f).sound(SoundType.METAL).noOcclusion()));

    // ── 横向平板电视 4×3（配 PS2 这类家用机）──
    public static final DeferredBlock<Block> LCD_TV = BLOCKS.register("lcd_tv",
            () -> new LcdTvBlock(BlockBehaviour.Properties.of()
                    .strength(1.6f).sound(SoundType.METAL).noOcclusion()));

    // ── 四台游戏机 ──
    public static final DeferredBlock<Block> SFC_CONSOLE = BLOCKS.register("sfc_console",
            () -> new ConsoleBlock(Core.SFC, ConsoleBlock.flatShape(),
                    BlockBehaviour.Properties.of().strength(1.2f).sound(SoundType.WOOD).noOcclusion()));

    public static final DeferredBlock<Block> GBA_CONSOLE = BLOCKS.register("gba_console",
            () -> new ConsoleBlock(Core.GBA, ConsoleBlock.clamshellShape(),
                    BlockBehaviour.Properties.of().strength(1.0f).sound(SoundType.WOOD).noOcclusion()));

    public static final DeferredBlock<Block> NDS_CONSOLE = BLOCKS.register("nds_console",
            () -> new ConsoleBlock(Core.NDS, ConsoleBlock.flatShape(),
                    BlockBehaviour.Properties.of().strength(1.0f).sound(SoundType.WOOD).noOcclusion()));

    public static final DeferredBlock<Block> NDS3_CONSOLE = BLOCKS.register("nds3_console",
            () -> new ConsoleBlock(Core.THREE_DS, ConsoleBlock.clamshellShape(),
                    BlockBehaviour.Properties.of().strength(1.0f).sound(SoundType.WOOD).noOcclusion()));

    public static final DeferredBlock<Block> PS2_CONSOLE = BLOCKS.register("ps2",
            () -> new ConsoleBlock(Core.PS2, ConsoleBlock.flatShape(),
                    BlockBehaviour.Properties.of().strength(1.2f).sound(SoundType.STONE).noOcclusion()));

    public static final DeferredBlock<Block> PSP_CONSOLE = BLOCKS.register("psp",
            () -> new ConsoleBlock(Core.PSP, ConsoleBlock.flatShape(),
                    BlockBehaviour.Properties.of().strength(0.8f).sound(SoundType.STONE).noOcclusion()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CrtTvBlockEntity>> TV_BE =
            BE_TYPES.register("crt_tv", () -> BlockEntityType.Builder.of(CrtTvBlockEntity::new, TV.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CrtTvBlockEntity>> LCD_TV_BE =
            BE_TYPES.register("lcd_tv", () -> BlockEntityType.Builder.of(CrtTvBlockEntity::new, LCD_TV.get()).build(null));

    // ── 物品：右键全屏玩、Shift+右键放置 ──
    public static final DeferredHolder<Item, Item> TV_ITEM =
            ITEMS.register("crt_tv", () -> new BlockItem(TV.get(), new Item.Properties()));
    public static final DeferredHolder<Item, Item> LCD_TV_ITEM =
            ITEMS.register("lcd_tv", () -> new BlockItem(LCD_TV.get(), new Item.Properties()));
    public static final DeferredHolder<Item, Item> SFC_CONSOLE_ITEM =
            ITEMS.register("sfc_console", () -> new ConsoleBlockItem(SFC_CONSOLE.get(), Core.SFC, new Item.Properties()));
    public static final DeferredHolder<Item, Item> GBA_CONSOLE_ITEM =
            ITEMS.register("gba_console", () -> new ConsoleBlockItem(GBA_CONSOLE.get(), Core.GBA, new Item.Properties()));
    public static final DeferredHolder<Item, Item> NDS_CONSOLE_ITEM =
            ITEMS.register("nds_console", () -> new ConsoleBlockItem(NDS_CONSOLE.get(), Core.NDS, new Item.Properties()));
    public static final DeferredHolder<Item, Item> NDS3_CONSOLE_ITEM =
            ITEMS.register("nds3_console", () -> new ConsoleBlockItem(NDS3_CONSOLE.get(), Core.THREE_DS, new Item.Properties()));

    public static final DeferredHolder<Item, Item> PS2_CONSOLE_ITEM =
            ITEMS.register("ps2", () -> new ConsoleBlockItem(PS2_CONSOLE.get(), Core.PS2, new Item.Properties()));

    public static final DeferredHolder<Item, Item> PSP_CONSOLE_ITEM =
            ITEMS.register("psp", () -> new ConsoleBlockItem(PSP_CONSOLE.get(), Core.PSP, new Item.Properties()));

    // ── 游戏手柄（操作电视必需）──
    public static final DeferredHolder<Item, Item> GAMEPAD =
            ITEMS.register("gamepad", () -> new GamepadItem(new Item.Properties().stacksTo(1)));

    private CrtTvRegistry() {}

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        BE_TYPES.register(modBus);
        ITEMS.register(modBus);
    }
}
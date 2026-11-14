// SPDX-License-Identifier: GPL-3.0-or-later
package com.november.minetendo.client;

/**
 * 电视屏幕在模型空间里的矩形（方块坐标，1 = 一格）。
 *
 * <p>不同大小的电视（2×2 老电视机、3×4 大电视机）屏幕位置和尺寸都不同，
 * 投屏渲染不能再用全局常量，必须向方块本体问"你的屏幕在哪"。
 */
public interface TvScreen {

    /** 屏幕中心 X（方块坐标） */
    float screenCenterX();

    /** 屏幕中心 Y（方块坐标） */
    float screenCenterY();

    /** 屏幕半宽（方块坐标） */
    float screenHalfW();

    /** 屏幕半高（方块坐标） */
    float screenHalfH();

    /** 玻璃外表面 Z（画面贴在它外侧一点点） */
    float screenFaceZ();
}

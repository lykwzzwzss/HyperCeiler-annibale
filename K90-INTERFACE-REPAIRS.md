# K90 接口修复记录

目标：REDMI K90（annibale），HyperOS 4 OS4.0.0.32.XPKCNXM，Android 17 / API 37。使用 3 个 GPT-6 Luna 高推理子代理分组实施，并做交叉检查。

本次覆盖原审计的 **44 个明确设置项、2 个父入口受影响子项、33 个遗留设置项**，另修复 2 个相关导航入口。

44 个明确项中：**41 个已修正或迁移代码，3 个当前无法可靠恢复，已从 K90 页面与搜索隐藏**。2 个子项已恢复代码路径。33 个遗留项保留旧系统有效支持，K90 使用现有原生实现或隐藏/禁用门槛，搜索同步排除无效入口。

无法可靠恢复的 3 项是桌面文件夹背景、桌面今日推荐、侧边栏推荐屏蔽。当前桌面已转原生/Rust，侧边栏静态证据没有找到对应推荐行的可靠消费者。隐藏入口不等于恢复功能。

修复包含：系统框架 B 路由、启动参数与调用方身份保留、小窗传送门直接参数传递、链式启动线程隔离、清理策略返回类型检查、USB 当前选择器与反向充电电源角色、息屏设置新控件、SystemUI/AOD 入口、媒体键与中文标签、共享媒体对象复制、旧控制中心分页测量顺序、弹窗新生命周期和搜索门槛。

已有音量键阶数修复、150 原生音量范围、root/手电筒/亮度策略、初始化验证码与首页提示移除保留。

**验证边界：**已核对当前 ROM 类/方法及导航资源，尚未逐项在安装新版后的手机上开关验证，不能据此宣称全部功能实测成功。统一编译与单元测试结果见对应 GitHub Actions。

| 原分类 | 选项 | 处置 |
|---|---|---|
| 明确问题 | 解锁息屏显示时长限制 (`prefs_key_aod_unlock_always_on_display_hyper`) | 代码已修正/迁移，待实机 |
| 明确问题 | 记住状态及位置 (`prefs_key_system_framework_freeform_sticky`) | 代码已修正/迁移，待实机 |
| 明确问题 | 跳转应用使用小窗打开 (`prefs_key_system_framework_freeform_jump`) | 代码已修正/迁移，待实机 |
| 父入口子项 | 传送门 (`prefs_key_system_framework_freeform_content_extension`) | 代码已修正/迁移，待实机 |
| 父入口子项 | 分享至三方应用 (`prefs_key_system_framework_freeform_app_share`) | 代码已修正/迁移，待实机 |
| 明确问题 | 禁止自动终止后台应用进程 (`prefs_key_system_framework_other_disable_cleaner`) | 代码已修正/迁移，待实机 |
| 明确问题 | 限制链豁免 (`prefs_key_system_framework_auto_start_menu`) | 代码已修正/迁移，待实机 |
| 明确问题 | 机型伪装 (`prefs_key_market_device_modify_new1`) | 代码已修正/迁移，待实机 |
| 遗留项 | 禁止使用第三方图标 (`prefs_key_market_disable_new_icon`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 隐藏 Dock 栏最近应用的图标 (`prefs_key_home_dock_disable_recents_icon`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 隐藏 Dock 栏 (`prefs_key_home_dock_hide_dock`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 上滑只展示 Dock 栏 (`prefs_key_home_dock_slide_up_only_show_dock`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 明确问题 | 颜色覆盖 (`prefs_key_home_folder_shade`) | K90 隐藏，功能未恢复 |
| 明确问题 | 隐藏 \"今日推荐\" 开关 (`prefs_key_home_folder_recommend_apps_switch`) | K90 隐藏，功能未恢复 |
| 遗留项 | 解锁布局 (无字模式) (`prefs_key_home_layout_unlock_grids_no_word`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 图标缩放 (`pref_key_home_title_icon_scale`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 应用动画背景模糊 (`prefs_key_home_title_app_blur_enable`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 一次模糊遮罩模糊半径 (`prefs_key_home_title_app_blur_radius`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 一次模糊遮罩背景不透明度 (`prefs_key_home_title_app_dim_alpha`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 二次模糊遮罩模糊半径 (`prefs_key_home_title_wallpaper_blur_radius`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 二次模糊遮罩背景不透明度 (`prefs_key_home_title_wallpaper_dim_alpha`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | home_title_minus_blur_radius (`prefs_key_home_title_minus_blur_radius`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | home_title_minus_dim_alpha (`prefs_key_home_title_minus_dim_alpha`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 额外修复小窗动画 (`prefs_key_home_title_fix_small_window`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 文字滚动 (`prefs_key_home_title_title_marquee`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 快速启动游戏 (`prefs_key_security_center_game_speed`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 禁止展示推荐应用 (`prefs_key_security_center_sidebar_show_suggest`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 明确问题 | 禁止展示推荐应用 (`prefs_key_disable_security_center_sidebar_show_suggest`) | K90 隐藏，功能未恢复 |
| 明确问题 | 允许永不锁屏 (`prefs_key_system_settings_allow_never_lock_screen`) | 代码已修正/迁移，待实机 |
| 明确问题 | 接入 USB 时不弹窗 (`prefs_key_system_settings_usb_mode`) | 代码已修正/迁移，待实机 |
| 明确问题 | USB 默认选项 (`prefs_key_system_settings_usb_mode_choose`) | 代码已修正/迁移，待实机 |
| 明确问题 | 通知与控制中心头部渐变模糊 (`prefs_key_system_ui_shade_header_gradient_blur`) | 代码已修正/迁移，待实机 |
| 明确问题 | 移除通知数量限制 (`prefs_key_system_ui_control_center_remove_notif_num_limit`) | 代码已修正/迁移，待实机 |
| 明确问题 | 强制背景模糊 (`prefs_key_system_ui_control_center_statusbar_blur`) | 代码已修正/迁移，待实机 |
| 明确问题 | 自定义行列数 (`prefs_key_system_control_center_old_enable`) | 代码已修正/迁移，待实机 |
| 明确问题 | 行数 (`prefs_key_system_control_center_old_qs_rows`) | 代码已修正/迁移，待实机 |
| 明确问题 | 行数（横屏） (`prefs_key_system_control_center_old_qs_rows_horizontal`) | 代码已修正/迁移，待实机 |
| 明确问题 | 列数 (`prefs_key_system_control_center_old_qs_columns`) | 代码已修正/迁移，待实机 |
| 明确问题 | 列数（横屏） (`prefs_key_system_control_center_old_qs_columns_horizontal`) | 代码已修正/迁移，待实机 |
| 明确问题 | 折叠面板中的列数（横屏） (`prefs_key_system_control_center_old_qs_grid_columns_horizontal`) | 代码已修正/迁移，待实机 |
| 明确问题 | 启用字体修改 (`prefs_key_system_ui_control_center_media_control_media_button_size_switch`) | 代码已修正/迁移，待实机 |
| 明确问题 | 标题字体大小 (`prefs_key_system_ui_control_center_media_control_title_size`) | 代码已修正/迁移，待实机 |
| 明确问题 | 艺术家字体大小 (`prefs_key_system_ui_control_center_media_control_artist_size`) | 代码已修正/迁移，待实机 |
| 明确问题 | 时间预览文本大小 (`prefs_key_system_ui_control_center_media_control_time_view_text_size`) | 代码已修正/迁移，待实机 |
| 明确问题 | 隐藏勿扰模式通知 (`prefs_key_system_ui_lock_screen_not_disturb_mode`) | 代码已修正/迁移，待实机 |
| 遗留项 | 锁屏底部显示充电信息 (`prefs_key_system_ui_lock_screen_show_charging_cv`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 显示详细电流 (`prefs_key_system_ui_show_charging_c_more`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 显示电池温度 (`prefs_key_system_ui_show_battery_temperature`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 显示刷新间隔 (`prefs_key_system_ui_lock_screen_show_spacing_value`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | prefs_key_system_ui_lock_screen_show_spacing (`prefs_key_system_ui_lock_screen_show_spacing`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 覆盖联动息屏壁纸动画参数 (`prefs_key_system_ui_lock_screen_linkage_anim`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 亮屏动画速率 (`prefs_key_system_ui_lock_screen_linkage_anim_on`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 息屏动画速率 (`prefs_key_system_ui_lock_screen_linkage_anim_off`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 明确问题 | 自定义导航栏 (`prefs_key_system_ui_navigation_custom`) | 代码已修正/迁移，待实机 |
| 明确问题 | 竖屏导航栏高度 (`prefs_key_system_ui_navigation_custom_height`) | 代码已修正/迁移，待实机 |
| 明确问题 | 横屏导航栏高度 (`prefs_key_system_ui_navigation_custom_height_land`) | 代码已修正/迁移，待实机 |
| 明确问题 | 竖屏导航栏框架高度 (`prefs_key_system_ui_navigation_frame_custom_height`) | 代码已修正/迁移，待实机 |
| 明确问题 | 横屏导航栏框架高度 (`prefs_key_system_ui_navigation_frame_custom_height_land`) | 代码已修正/迁移，待实机 |
| 明确问题 | 自定义手势提示线 (`prefs_key_system_ui_navigation_handle_custom`) | 代码已修正/迁移，待实机 |
| 明确问题 | 粗细 (`prefs_key_system_ui_navigation_handle_custom_thickness`) | 代码已修正/迁移，待实机 |
| 遗留项 | 高度 (`prefs_key_system_ui_navigation_handle_custom_height`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 竖屏宽度 (`prefs_key_system_ui_navigation_handle_custom_width`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 横屏宽度 (`prefs_key_system_ui_navigation_handle_custom_width_land`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 明确问题 | 深色背景下颜色 (`prefs_key_system_ui_navigation_handle_custom_color_dark`) | 代码已修正/迁移，待实机 |
| 明确问题 | 浅色背景下颜色 (`prefs_key_system_ui_navigation_handle_custom_color`) | 代码已修正/迁移，待实机 |
| 明确问题 | Toast 高级材质 (`prefs_key_system_framework_background_blur_toast`) | 代码已修正/迁移，待实机 |
| 遗留项 | 将 statusbar_gestures.dat 移动到 MIUI 目录 (`prefs_key_system_ui_move_log_to_miui`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 明确问题 | 小爱音箱组合立体声 (`prefs_key_system_ui_status_bar_icon_soundbox_group_stereo`) | 代码已修正/迁移，待实机 |
| 明确问题 | 氛围光效 (`prefs_key_system_ui_island_media_control_ambient_light`) | 代码已修正/迁移，待实机 |
| 明确问题 | 固有动作按钮大小 (`prefs_key_system_ui_island_media_control_media_button`) | 代码已修正/迁移，待实机 |
| 明确问题 | 自定义动作按钮大小 (`prefs_key_system_ui_island_media_control_media_button_custom`) | 代码已修正/迁移，待实机 |
| 明确问题 | 启用字体修改 (`prefs_key_system_ui_island_media_control_media_button_size_switch`) | 代码已修正/迁移，待实机 |
| 明确问题 | 标题字体大小 (`prefs_key_system_ui_island_media_control_title_size`) | 代码已修正/迁移，待实机 |
| 明确问题 | 艺术家字体大小 (`prefs_key_system_ui_island_media_control_artist_size`) | 代码已修正/迁移，待实机 |
| 明确问题 | 时间预览文本大小 (`prefs_key_system_ui_island_media_control_time_view_text_size`) | 代码已修正/迁移，待实机 |
| 遗留项 | 隐藏状态栏时钟 (`prefs_key_system_ui_statusbar_music_hide_clock`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 调整检测版本 (`prefs_key_theme_manager_new_version_code_modify`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | 添加清空剪贴板按钮 (`prefs_key_add_clipboard_clear`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |
| 遗留项 | prefs_key_various_enable_super_function (`prefs_key_various_enable_super_function`) | 保留有效旧支持，K90 入口/搜索按当前门槛处理 |


## 4700 启动日志后的补充修复

4700 已在 K90 安装并重启，ADB 启动日志发现两条本模块接口错误。本次修复快速截图的 `getScreenshotChordLongPressDelay():long` 目标迁移到 `KeyGestureController`，并保留旧 `PhoneWindowManager` 的精确签名回退；温控事件服务迁移到 `power.thermal.ThermalManagerService.postEventListenerLocked(Temperature, IThermalEventListener, Integer):void`，保留旧路径。

温控事件选项只沿用原选项的事件回调屏蔽范围；独立的“禁止温控降低亮度上限”仍由显示策略 Hook 处理。温度记录、关机处理、status/headroom 回调及 HAL 未被这次修改。

导航条颜色复查保留原映射：浅色背景颜色对应 ROM 的 dark 图标资源，深色背景颜色对应 light 图标资源。不能仅按资源 dark/light 名称反转偏好。

用户选择自行手动测试。4700 的启动、首页和原生媒体音量上限 150 已读取确认；各项实际效果和本次补充 Hook 的运行结果仍待新版手动验收。

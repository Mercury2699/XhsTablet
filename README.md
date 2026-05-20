# XhsTablet

LSPosed/Xposed 模块，强制小红书以平板身份运行，占据 pad 登录槽位，实现手机 + "平板"双端同时在线。

## 原理

小红书的多端登录策略为每类设备（手机/平板）各允许一台同时在线。本模块将第二台手机伪装为三星 Galaxy Tab S9 FE（SM-X510），使服务端将其归入 pad 槽位，从而不踢掉主力手机。

### Hook 点

| 目标 | 修改 | 作用 |
|------|------|------|
| `Build.MODEL/BRAND/DEVICE/...` | → Samsung Tab S9 FE | 欺骗 libshield native 层上报 |
| `DeviceInfoContainer.isPad()` | → `true` | UI 走平板布局 |
| `DeviceInfoContainer.getDeviceType()` | → `"pad"` | 上报服务端 |
| `DeviceInfoContainer.getDeviceAccurateModel()` | → `"SM-X510"` | device_type API 查询参数 |
| `DeviceInfoContainer.updateCloudDeviceType()` | 强制 `"pad"` | 防止服务端覆盖 |
| `SystemProperties.get("ro.build.characteristics")` | → `"tablet"` | 系统属性一致 |

## 安装

1. 从 [Actions](../../actions) 下载最新 APK
2. 安装到**第二台手机**（要伪装为平板的那台）
3. 在 LSPosed 中启用模块，勾选作用域 `com.xingin.xhs`
4. 强制停止小红书，重新打开

## 首次使用建议

先退出小红书账号 → 启用模块 → 重新登录。这样服务端从一开始就将此设备注册为 pad。

## 编译

```bash
./gradlew assembleRelease
# 输出: app/build/outputs/apk/release/app-release-unsigned.apk
```

## 要求

- Android 8.0+
- LSPosed / Xposed 框架
- 小红书 8.0.40+（测试版本：9.31.0）

## ⚠️ 注意

- 仅在第二台手机安装，主力手机保持正常
- 伪装型号为真实存在的平板，服务端会正常返回 `"pad"`
- 如遇风控，建议清除小红书数据后重新登录

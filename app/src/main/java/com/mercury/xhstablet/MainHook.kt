package com.mercury.xhstablet

import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "XhsTablet"
        const val XHS_PACKAGE = "com.xingin.xhs"

        // 伪装为三星 Galaxy Tab S9 FE
        const val TABLET_MODEL = "SM-X510"
        const val TABLET_BRAND = "samsung"
        const val TABLET_MANUFACTURER = "samsung"
        const val TABLET_DEVICE = "gts9fe"
        const val TABLET_PRODUCT = "gts9fewifixx"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != XHS_PACKAGE) return

        XposedBridge.log("[$TAG] Loaded in ${lpparam.packageName}")

        try {
            hookBuildFields()
            hookDeviceInfoContainer(lpparam.classLoader)
            hookReporting(lpparam.classLoader)
            hookSystemProperties(lpparam.classLoader)
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] Error: ${e.message}")
            XposedBridge.log(e)
        }
    }

    /**
     * Hook Build 字段，让 libshield.so native 层也读到平板型号
     */
    private fun hookBuildFields() {
        try {
            XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", TABLET_MODEL)
            XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", TABLET_BRAND)
            XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", TABLET_MANUFACTURER)
            XposedHelpers.setStaticObjectField(Build::class.java, "DEVICE", TABLET_DEVICE)
            XposedHelpers.setStaticObjectField(Build::class.java, "PRODUCT", TABLET_PRODUCT)
            XposedBridge.log("[$TAG] Build fields spoofed to $TABLET_MODEL")
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] Failed to hook Build fields: ${e.message}")
        }
    }

    /**
     * Hook DeviceInfoContainer - 核心判断类
     */
    private fun hookDeviceInfoContainer(classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClass(
            "com.xingin.adaptation.device.DeviceInfoContainer", classLoader
        )

        // isPad() → 由 hookReporting 中的 stack-aware hook 处理
        // UI 调用返回 false（手机布局），上报调用返回 true（平板身份）

        // getDeviceType() → "pad" (服务端仍认为是平板，占 pad 槽位)
        XposedHelpers.findAndHookMethod(clazz, "getDeviceType",
            XC_MethodReplacement.returnConstant("pad"))
        XposedBridge.log("[$TAG] Hooked getDeviceType() → pad (server slot)")

        // predictPad() → true
        XposedHelpers.findAndHookMethod(clazz, "predictPad",
            XC_MethodReplacement.returnConstant(true))

        // systemPropertyIsTablet() → true
        XposedHelpers.findAndHookMethod(clazz, "systemPropertyIsTablet",
            XC_MethodReplacement.returnConstant(true))

        // getDeviceAccurateModel() → 平板型号
        XposedHelpers.findAndHookMethod(clazz, "getDeviceAccurateModel",
            XC_MethodReplacement.returnConstant(TABLET_MODEL))
        XposedBridge.log("[$TAG] Hooked getDeviceAccurateModel() → $TABLET_MODEL")

        // updateCloudDeviceType(String) → 强制写入 "pad"
        XposedHelpers.findAndHookMethod(clazz, "updateCloudDeviceType",
            String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val original = param.args[0] as? String
                    if (original != "pad") {
                        XposedBridge.log("[$TAG] Intercepted updateCloudDeviceType($original) → pad")
                        param.args[0] = "pad"
                    }
                }
            })

        // isPhone() → true (保持手机身份用于 UI)
        XposedHelpers.findAndHookMethod(clazz, "isPhone",
            XC_MethodReplacement.returnConstant(true))
    }

    /**
     * Hook 上报层，确保 isTablet/isPad 字段对服务端报 true
     * bcc/c0.java: hashMap.put("isTablet", deviceInfoContainer.isPad())
     * js.g.b(context): 读取 monitor SharedPreferences 判断 isPad
     */
    private fun hookReporting(classLoader: ClassLoader) {
        // Hook js.g.b(context) → true (埋点上报中的 isPad 判断)
        try {
            val monitorClass = XposedHelpers.findClass("js.g", classLoader)
            XposedHelpers.findAndHookMethod(monitorClass, "b",
                android.content.Context::class.java,
                XC_MethodReplacement.returnConstant(true))
            XposedBridge.log("[$TAG] Hooked js.g.b() → true (reporting)")
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] js.g.b hook failed: ${e.message}")
        }

        // Hook HashMap.put 在 Bridge 调用中拦截 "isTablet" key
        // 注意：只 hook DeviceInfoContainer.isPad 的调用方，避免全局 HashMap hook 的性能问题
        // 由于 isPad() 已被 hook 为 false（UI 用途），我们需要在上报时覆盖
        // 方案：hook bcc.c0 中的上报方法太脆弱（混淆类名会变），
        // 改为让 isPad() 根据调用栈动态返回
        // 最终方案：移除 HashMap 全局 hook，改为让 isPad() 在非 UI 调用时返回 true
        val clazz = XposedHelpers.findClass(
            "com.xingin.adaptation.device.DeviceInfoContainer", classLoader
        )
        // 覆盖之前的 isPad hook：根据调用栈判断
        XposedHelpers.findAndHookMethod(clazz, "isPad", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val stack = Throwable().stackTrace
                val isUiCall = stack.any {
                    it.className.contains("Activity") ||
                    it.className.contains("Fragment") ||
                    it.className.contains("View") ||
                    it.className.contains("Adapter") ||
                    it.className.contains("Layout") ||
                    it.className.contains("Presenter") ||
                    it.className.contains("ui.", ignoreCase = true)
                }
                // UI 调用返回 false（手机布局），其他调用返回 true（上报为平板）
                param.result = !isUiCall
            }
        })
        XposedBridge.log("[$TAG] Hooked isPad() with stack-aware logic")
    }

    /**
     * Hook SystemProperties.get() 让 ro.build.characteristics 返回 "tablet"
     */
    private fun hookSystemProperties(classLoader: ClassLoader) {
        try {
            val sysPropClass = XposedHelpers.findClass(
                "android.os.SystemProperties", classLoader
            )
            XposedHelpers.findAndHookMethod(sysPropClass, "get",
                String::class.java, String::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String
                        if (key == "ro.build.characteristics") {
                            param.result = "tablet"
                        }
                    }
                })
            XposedBridge.log("[$TAG] Hooked SystemProperties for tablet characteristics")
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] SystemProperties hook failed: ${e.message}")
        }
    }
}

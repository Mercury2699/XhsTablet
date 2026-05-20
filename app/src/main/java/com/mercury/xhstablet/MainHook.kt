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

        // isPad() → true
        XposedHelpers.findAndHookMethod(clazz, "isPad",
            XC_MethodReplacement.returnConstant(true))
        XposedBridge.log("[$TAG] Hooked isPad() → true")

        // getDeviceType() → "pad"
        XposedHelpers.findAndHookMethod(clazz, "getDeviceType",
            XC_MethodReplacement.returnConstant("pad"))
        XposedBridge.log("[$TAG] Hooked getDeviceType() → pad")

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

        // isPhone() → false
        XposedHelpers.findAndHookMethod(clazz, "isPhone",
            XC_MethodReplacement.returnConstant(false))
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

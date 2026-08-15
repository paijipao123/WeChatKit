package com.wechathook.features.system

import android.content.pm.PackageInfo
import android.content.pm.Signature
import com.wechathook.core.DexKitFinder
import com.wechathook.core.Feature
import com.wechathook.core.Logger
import com.wechathook.core.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 签名绕过（嵌入版/改包环境登录用）。
 *
 * 免 root 嵌入版（VirtualXposed/太极等）或改包环境里，微信 APK 的签名与官方不同，
 * 微信登录/运行时的签名自检会失败，导致无法登录。本功能把
 * `PackageManager.getPackageInfo` 返回的微信签名替换为官方签名证书
 * （从官方 8.0.76 APK 提取，Tencent 证书），并让 `hasSigningCertificate` 校验通过，
 * 使微信认为自身签名正常。
 *
 * 参考：xihan123/SignHook（hook ApplicationPackageManager.getPackageInfo /
 * getPackageInfoAsUser / hasSigningCertificate）。
 *
 * 仅嵌入版/改包环境需要，正常环境请保持关闭。
 */
object SignatureBypassFeature : Feature {

    override val key = "signature_bypass"
    override val name = "签名绕过（嵌入版登录）"

    override fun defaultEnabled() = false

    override fun isProcessSafe() = false

    override fun needsDexKit() = false

    private const val PKG_WECHAT = "com.tencent.mm"

    /** 微信官方签名证书（DER 编码 hex，从官方 8.0.76 APK 的 APK Signing Block 提取）。 */
    private const val WECHAT_CERT_HEX =
        "308202EB30820254A00302010202044D36F7A4300D06092A864886F70D01010505003081B9310B300906035504061302383631123010060355040813094775616E67646F6E673111300F060355040713085368656E7A68656E31353033060355040A132C54656E63656E7420546563686E6F6C6F6779285368656E7A68656E2920436F6D70616E79204C696D69746564313A3038060355040B133154656E63656E74204775616E677A686F7520526573656172636820616E6420446576656C6F706D656E742043656E7465723110300E0603550403130754656E63656E74301E170D3131303131393134333933325A170D3431303131313134333933325A3081B9310B300906035504061302383631123010060355040813094775616E67646F6E673111300F060355040713085368656E7A68656E31353033060355040A132C54656E63656E7420546563686E6F6C6F6779285368656E7A68656E2920436F6D70616E79204C696D69746564313A3038060355040B133154656E63656E74204775616E677A686F7520526573656172636820616E6420446576656C6F706D656E742043656E7465723110300E0603550403130754656E63656E7430819F300D06092A864886F70D010101050003818D0030818902818100C05F34B231B083FB1323670BFBE7BDAB40C0C0A6EFC87EF2072A1FF0D60CC67C8EDB0D0847F210BEA6CBFAA241BE70C86DAF56BE08B723C859E52428A064555D80DB448CDCACC1AEA2501EBA06F8BAD12A4FA49D85CACD7ABEB68945A5CB5E061629B52E3254C373550EE4E40CB7C8AE6F7A8151CCD8DF582D446F39AE0C5E930203010001300D06092A864886F70D0101050500038181009C8D9D7F2F908C42081B4C764C377109A8B2C70582422125CE545842D5F520AEA69550B6BD8BFD94E987B75A3077EB04AD341F481AAC266E89D3864456E69FBA13DF018ACDC168B9A19DFD7AD9D9CC6F6ACE57C746515F71234DF3A053E33BA93ECE5CD0FC15F3E389A3F365588A9FCB439E069D3629CD7732A13FFF7B891499"

    private val fakeSignature: Signature? by lazy { runCatching { Signature(hexToBytes(WECHAT_CERT_HEX)) }.getOrNull() }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    override fun hook(classLoader: ClassLoader, finder: DexKitFinder?) {
        if (!Prefs.getBoolean("feat_signature_bypass", false)) return
        val sig = fakeSignature ?: run {
            Logger.e("[$name] 官方签名解析失败")
            return
        }
        Logger.i("[$name] 开始 Hook")

        runCatching {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)

            // 1. getPackageInfo / getPackageInfoAsUser：替换微信签名
            val replaceHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val pkg = param.args.getOrNull(0) as? String ?: return
                        if (pkg != PKG_WECHAT) return
                        val pi = param.result as? PackageInfo ?: return
                        pi.signatures = arrayOf(sig)
                        // signingInfo（API 28+）尽力伪造
                        runCatching { fillSigningInfo(pi, arrayOf(sig)) }.onFailure { }
                    }
                }
            }
            runCatching { XposedBridge.hookAllMethods(pmClass, "getPackageInfo", replaceHook) }.onFailure { }
            runCatching { XposedBridge.hookAllMethods(pmClass, "getPackageInfoAsUser", replaceHook) }.onFailure { }
            Logger.i("[$name] getPackageInfo 签名替换已挂载")

            // 2. hasSigningCertificate：让微信官方签名通过校验
            runCatching {
                XposedBridge.hookAllMethods(pmClass, "hasSigningCertificate", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val pkg = param.args.getOrNull(0) as? String ?: return
                            if (pkg != PKG_WECHAT) return
                            param.setResult(true)
                        }
                    }
                })
                Logger.i("[$name] hasSigningCertificate 已挂载")
            }.onFailure { }
        }.onFailure { Logger.e("[$name] Hook 失败: $it") }
    }

    /** 伪造 PackageInfo.signingInfo（反射构造 SigningDetails + SigningInfo）。 */
    private fun fillSigningInfo(pi: PackageInfo, sigs: Array<Signature>) {
        runCatching {
            val signingDetailsClass = runCatching {
                Class.forName("android.content.pm.SigningDetails")
            }.getOrElse { Class.forName("android.content.pm.PackageParser\$SigningDetails") }

            // 用最多的构造参数构造 SigningDetails
            val ctor = signingDetailsClass.declaredConstructors
                .sortedByDescending { it.parameterTypes.size }
                .firstOrNull { c ->
                    c.parameterTypes.firstOrNull() == sigs.javaClass &&
                        c.parameterTypes.getOrNull(1) == Int::class.javaPrimitiveType
                } ?: return
            ctor.isAccessible = true
            val args: Array<Any?> = when (ctor.parameterTypes.size) {
                5 -> arrayOf(sigs, 2, 0, null, null)
                4 -> arrayOf(sigs, 2, null, null)
                2 -> arrayOf(sigs, 2)
                else -> return
            }
            val sd = ctor.newInstance(*args)

            // SigningInfo(SigningDetails)
            val siClass = Class.forName("android.content.pm.SigningInfo")
            val siCtor = siClass.declaredConstructors.firstOrNull {
                it.parameterTypes.singleOrNull() == signingDetailsClass
            } ?: return
            siCtor.isAccessible = true
            pi.signingInfo = siCtor.newInstance(sd) as android.content.pm.SigningInfo
        }
    }
}

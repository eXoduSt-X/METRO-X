package com.arthenica.ffmpegkit

object AbiDetect {
    @JvmStatic
    external fun getNativeAbi(): String

    @JvmStatic
    external fun getNativeCpuAbi(): String

    @JvmStatic
    external fun isNativeLTSBuild(): Boolean

    @JvmStatic
    external fun getNativeBuildConf(): String

    @JvmStatic
    external fun getNativeVersion(): String

    @JvmStatic
    fun getAbi(): String {
        return try {
            getNativeAbi()
        } catch (e: UnsatisfiedLinkError) {
            "unknown"
        }
    }
}

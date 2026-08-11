package com.arthenica.ffmpegkit

import android.util.Log

fun interface ExecuteCallback {
    fun apply(session: FFmpegSession)
}

fun interface StatisticsCallback {
    fun apply(statistics: Statistics)
}

data class Statistics(
    val sessionId: Long,
    val videoFrameNumber: Int,
    val videoFps: Float,
    val videoQuality: Float,
    val size: Long,
    val time: Double,
    val bitrate: Double,
    val speed: Double
)

object FFmpegKit {
    private var isLoaded = false
    private var loadError: String? = null
    private val sessions = mutableMapOf<Long, FFmpegSession>()

    init {
        try {
            System.loadLibrary("ffmpegkit_abidetect")
            System.loadLibrary("ffmpegkit")
            isLoaded = true
            Log.d("FFmpegKit", "Librerías nativas cargadas correctamente con soporte AbiDetect")
        } catch (e: UnsatisfiedLinkError) {
            loadError = e.message
            Log.e("FFmpegKit", "Error crítico JNI: ${e.message}")
        }
    }

    private fun registerSession(session: FFmpegSession) {
        sessions[session.sessionId] = session
    }

    fun getSession(sessionId: Long): FFmpegSession? = sessions[sessionId]

    @JvmStatic
    fun execute(command: String, statsCallback: StatisticsCallback? = null): FFmpegSession {
        if (!isLoaded) {
            Log.e("FFmpegKit", "Error: Librería nativa no cargada. Último error: $loadError")
            return FFmpegSession(-1)
        }
        val session = FFmpegSession()
        session.statisticsCallback = statsCallback
        registerSession(session)

        val arguments = parseArguments(command)
        val resultCode = FFmpegKitConfig.nativeFFmpegExecute(session.sessionId, arguments)
        session.resultCode = resultCode
        sessions.remove(session.sessionId)
        return session
    }

    @JvmStatic
    fun executeAsync(
        command: String, 
        callback: ExecuteCallback, 
        statsCallback: StatisticsCallback? = null
    ): FFmpegSession {
        if (!isLoaded) {
            Log.e("FFmpegKit", "Error: Librería nativa no cargada (Async)")
            val session = FFmpegSession(-1)
            callback.apply(session)
            return session
        }
        val session = FFmpegSession()
        session.statisticsCallback = statsCallback
        registerSession(session)

        Thread {
            try {
                val arguments = parseArguments(command)
                val resultCode = FFmpegKitConfig.nativeFFmpegExecute(session.sessionId, arguments)
                session.resultCode = resultCode
                Log.d("FFmpegKit", "Ejecución finalizada con código: $resultCode")
            } catch (e: Exception) {
                Log.e("FFmpegKit", "Error en hilo de ejecución: ${e.message}")
                session.resultCode = -1
            } finally {
                callback.apply(session)
                sessions.remove(session.sessionId)
            }
        }.start()
        return session
    }

    private fun parseArguments(command: String): Array<String> {
        val arguments = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (c in command) {
            if (c == '\"') {
                inQuotes = !inQuotes
            } else if (c == ' ' && !inQuotes) {
                if (sb.isNotEmpty()) {
                    arguments.add(sb.toString())
                    sb.setLength(0)
                }
            } else {
                sb.append(c)
            }
        }
        if (sb.isNotEmpty()) {
            arguments.add(sb.toString())
        }
        return arguments.toTypedArray()
    }
}

object FFmpegKitConfig {
    @JvmStatic
    fun init() {}

    @JvmStatic
    fun getVersion(): String = "6.0"

    @JvmStatic external fun disableNativeRedirection()
    @JvmStatic external fun enableNativeRedirection()
    @JvmStatic external fun getNativeBuildDate(): String
    @JvmStatic external fun getNativeFFmpegVersion(): String
    @JvmStatic external fun getNativeLogLevel(): Int
    @JvmStatic external fun getNativeVersion(): String
    @JvmStatic external fun ignoreNativeSignal(signal: Int)
    @JvmStatic external fun messagesInTransmit(sessionId: Long): Int
    @JvmStatic external fun nativeFFmpegCancel(sessionId: Long)
    @JvmStatic external fun nativeFFmpegExecute(sessionId: Long, arguments: Array<String>): Int
    @JvmStatic external fun nativeFFprobeExecute(sessionId: Long, arguments: Array<String>): Int
    @JvmStatic external fun registerNewNativeFFmpegPipe(pipeName: String): Int
    @JvmStatic external fun setNativeEnvironmentVariable(variableName: String, variableValue: String): Int
    @JvmStatic external fun setNativeLogLevel(level: Int)

    @JvmStatic
    fun log(sessionId: Long, level: Int, messageBytes: ByteArray) {
        val message = String(messageBytes)
        Log.d("FFmpegKitNativo", "[$level] Sesión $sessionId: $message")
    }

    @JvmStatic
    fun statistics(sessionId: Long, videoFrameNumber: Int, videoFps: Float, videoQuality: Float, size: Long, time: Double, bitrate: Double, speed: Double) {
        val stats = Statistics(sessionId, videoFrameNumber, videoFps, videoQuality, size, time, bitrate, speed)
        FFmpegKit.getSession(sessionId)?.statisticsCallback?.apply(stats)
    }

    @JvmStatic
    fun statisticsWithCallback(sessionId: Long, statisticsAddress: Long) {}

    @JvmStatic
    fun safOpen(fd: Int): Int = fd

    @JvmStatic
    fun safClose(fd: Int): Int = 0

    @JvmStatic fun enableRedirection() {}
    @JvmStatic fun disableRedirection() {}
}

class FFmpegSession(initialCode: Int = 0) {
    val sessionId: Long = System.currentTimeMillis()
    var resultCode: Int = initialCode
    var statisticsCallback: StatisticsCallback? = null
    val returnCode: ReturnCode get() = ReturnCode(resultCode)
    val allLogsAsString: String = "Conversión finalizada de forma nativa."
}

class ReturnCode(val value: Int) {
    fun isSuccess(): Boolean = value == 0
    fun isCancel(): Boolean = value == 255
    
    companion object {
        @JvmStatic fun isSuccess(returnCode: ReturnCode?): Boolean = returnCode?.isSuccess() == true
    }
}

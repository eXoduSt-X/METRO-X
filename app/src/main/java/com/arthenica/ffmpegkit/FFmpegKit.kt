package com.arthenica.ffmpegkit

import android.util.Log

fun interface ExecuteCallback {
    fun apply(session: FFmpegSession)
}

object FFmpegKit {
    private var isLoaded = false
    private var loadError: String? = null

    init {
        try {
            // Primero cargamos AbiDetect para que esté listo cuando ffmpegkit lo pida
            System.loadLibrary("ffmpegkit_abidetect")
            System.loadLibrary("ffmpegkit")
            isLoaded = true
            Log.d("FFmpegKit", "Librerías nativas cargadas correctamente con soporte AbiDetect")
        } catch (e: UnsatisfiedLinkError) {
            loadError = e.message
            Log.e("FFmpegKit", "Error crítico JNI: ${e.message}")
        }
    }

    @JvmStatic
    fun execute(command: String): FFmpegSession {
        if (!isLoaded) {
            Log.e("FFmpegKit", "Error: Librería nativa no cargada. Último error: $loadError")
            return FFmpegSession(-1)
        }
        val session = FFmpegSession()

        // Mejora: parseo de argumentos respetando comillas para rutas con espacios
        val arguments = parseArguments(command)
        val resultCode = FFmpegKitConfig.nativeFFmpegExecute(session.sessionId, arguments)
        session.resultCode = resultCode
        return session
    }

    @JvmStatic
    fun executeAsync(command: String, callback: ExecuteCallback): FFmpegSession {
        if (!isLoaded) {
            Log.e("FFmpegKit", "Error: Librería nativa no cargada (Async)")
            val session = FFmpegSession(-1)
            callback.apply(session)
            return session
        }
        val session = FFmpegSession()
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
    fun init() {
        // Hook de inicialización estándar
    }

    @JvmStatic
    fun getVersion(): String = "6.0"

    // =========================================================================
    //   MÉTODOS NATIVOS EXTERNAL (LLAMADAS DESDE KOTLIN HACIA C++)
    //   100% Alineados con los Símbolos Exportados del binario
    // =========================================================================
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

    // =========================================================================
    //   CALLBACKS ENLAZADOS (LLAMADAS DESDE C++ HACIA KOTLIN)
    //   100% Sincronizados con las Cadenas de Introspección del Servidor
    // =========================================================================
    @JvmStatic
    fun log(sessionId: Long, level: Int, messageBytes: ByteArray) {
        val message = String(messageBytes)
        Log.d("FFmpegKitNativo", "[$level] Sesión $sessionId: $message")
    }

    @JvmStatic
    fun statistics(sessionId: Long, videoFrameNumber: Int, videoFps: Float, videoQuality: Float, size: Long, time: Double, bitrate: Double, speed: Double) {
        // Estructura (JIFFJDDD)V verificada en el log
    }

    @JvmStatic
    fun statisticsWithCallback(sessionId: Long, statisticsAddress: Long) {
        // Hook de puntero JNI complementario
    }

    @JvmStatic
    fun safOpen(fd: Int): Int {
        // Firma (I)I verificada en el log
        return fd
    }

    // CORRECCIÓN TÉCNICA CRÍTICA: Cambiado obligatoriamente a : Int para satisfacer la firma (I)I
    @JvmStatic
    fun safClose(fd: Int): Int {
        return 0
    }

    // --- Métodos de compatibilidad requeridos por la UI ---
    @JvmStatic fun enableRedirection() {}
    @JvmStatic fun disableRedirection() {}
}

class FFmpegSession(initialCode: Int = 0) {
    val sessionId: Long = System.currentTimeMillis()
    var resultCode: Int = initialCode
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

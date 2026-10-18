package com.riptv.tv

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Guarda el motivo del último cierre inesperado para enseñarlo en el siguiente arranque: en una tele no hay
 * forma cómoda de ver el registro del sistema, y así se puede saber por qué se cerró la app.
 */
object CrashReport {
    private const val PREFS = "riptv_crash"
    private const val KEY = "last_crash"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                val device = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                    "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SUPPORTED_ABIS.firstOrNull()}"
                val report = "$device\nHilo: ${thread.name}\n\n$trace".take(6_000)
                // commit() y no apply(): el proceso se cierra justo después.
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, report).commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Devuelve el informe del último cierre inesperado, si lo hubo, y lo borra. */
    fun consume(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val report = prefs.getString(KEY, null)
        if (report != null) prefs.edit().remove(KEY).commit()
        return report
    }
}

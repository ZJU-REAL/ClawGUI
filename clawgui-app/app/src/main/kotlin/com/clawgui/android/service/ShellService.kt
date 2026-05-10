package com.clawgui.android.service

import com.clawgui.android.IShellService
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

/**
 * Shizuku UserService — runs shell commands under shell/root privilege.
 * Borrowed from roubao (MIT). Only the package name was changed.
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val output = reader.readText()
            val error = errorReader.readText()

            process.waitFor()
            reader.close()
            errorReader.close()

            if (output.isNotEmpty()) output else error
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

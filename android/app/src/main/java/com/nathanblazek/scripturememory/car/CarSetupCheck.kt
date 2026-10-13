package com.nathanblazek.scripturememory.car

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** What Android Auto looks for in this app, read back from the installed package. */
object CarSetupCheck {
    fun report(context: Context): String {
        val pm = context.packageManager
        val lines = mutableListOf<String>()

        val intent = Intent("androidx.car.app.CarAppService").setPackage(context.packageName)
        val services = pm.queryIntentServices(intent, PackageManager.GET_RESOLVED_FILTER)
        lines += if (services.isEmpty()) "✗ Car app service: not found" else "✓ Car app service: ${services.first().serviceInfo.name.substringAfterLast('.')}"
        services.firstOrNull()?.filter?.let { f ->
            val categories = (0 until f.countCategories()).map { f.getCategory(it).substringAfterLast('.') }
            lines += "  Category: ${categories.joinToString().ifEmpty { "none" }}"
        }

        val meta = runCatching {
            pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        }.getOrNull()
        lines += if (meta?.containsKey("com.google.android.gms.car.application") == true) "✓ Android Auto descriptor" else "✗ Android Auto descriptor missing"
        lines += "  Min car API level: ${meta?.get("androidx.car.app.minCarApiLevel") ?: "not set"}"

        val installer = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(context.packageName).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
        }.getOrNull()
        lines += "  Installed by: ${installer ?: "unknown"}"

        val aa = runCatching { pm.getPackageInfo("com.google.android.projection.gearhead", 0).versionName }.getOrNull()
        lines += "  Android Auto: ${aa ?: "not found"}"
        return lines.joinToString("\n")
    }
}

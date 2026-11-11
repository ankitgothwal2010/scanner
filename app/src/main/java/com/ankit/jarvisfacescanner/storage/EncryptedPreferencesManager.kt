package com.ankit.jarvisfacescanner.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class EncryptedPreferencesManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "jarvis_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveTapoConfig(ip: String, port: Int, user: String, pass: String, stream: String) {
        prefs.edit()
            .putString("tapo_ip", ip)
            .putInt("tapo_port", port)
            .putString("tapo_user", user)
            .putString("tapo_pass", pass)
            .putString("tapo_stream", stream)
            .apply()
    }

    fun getRtspUrl(): String {
        val ip = prefs.getString("tapo_ip", "") ?: ""
        val port = prefs.getInt("tapo_port", 554)
        val user = prefs.getString("tapo_user", "") ?: ""
        val pass = prefs.getString("tapo_pass", "") ?: ""
        val stream = prefs.getString("tapo_stream", "stream2") ?: "stream2"

        if (ip.isBlank() || user.isBlank() || pass.isBlank()) return ""
        return "rtsp://$user:$pass@$ip:$port/$stream"
    }

    fun getThreshold(): Float = prefs.getFloat("matching_threshold", 0.68f)

    fun setThreshold(threshold: Float) {
        prefs.edit().putFloat("matching_threshold", threshold).apply()
    }

    fun getCameraSource(): String = prefs.getString("camera_source", "PHONE") ?: "PHONE"

    fun setCameraSource(source: String) {
        prefs.edit().putString("camera_source", source).apply()
    }

    fun getTapoIp(): String = prefs.getString("tapo_ip", "") ?: ""
    fun getTapoUser(): String = prefs.getString("tapo_user", "") ?: ""
    fun getTapoPass(): String = prefs.getString("tapo_pass", "") ?: ""
    fun getTapoStream(): String = prefs.getString("tapo_stream", "stream2") ?: "stream2"
}

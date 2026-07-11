package com.santaklouse.example.wifibruteforcer

import android.content.Context
import android.content.SharedPreferences

object PasswordManager {

    private const val PREF_NAME = "wifi_passwords"
    private const val KEY_PASSWORDS = "password_list"

    fun savePasswords(context: Context, passwords: List<String>) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_PASSWORDS, passwords.toSet()).apply()
    }

    fun getPasswords(context: Context): List<String> {
        val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_PASSWORDS, emptySet())?.toList() ?: emptyList()
    }
}
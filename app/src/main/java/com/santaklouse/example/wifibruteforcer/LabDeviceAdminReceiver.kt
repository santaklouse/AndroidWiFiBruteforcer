package com.santaklouse.example.wifibruteforcer

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class LabDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, LabDeviceAdminReceiver::class.java)
        if (manager.isProfileOwnerApp(context.packageName)) {
            manager.setProfileName(admin, context.getString(R.string.work_profile_name))
            manager.setProfileEnabled(admin)
        }
    }
}

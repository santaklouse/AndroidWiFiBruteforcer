package com.santaklouse.example.wifibruteforcer

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle

class ProfileProvisioningActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> returnProvisioningMode()
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE,
            DevicePolicyManager.ACTION_PROVISIONING_SUCCESSFUL -> completeProvisioning()
            else -> finishWithResult(RESULT_CANCELED)
        }
    }

    private fun returnProvisioningMode() {
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val allowed = intent.getIntegerArrayListExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES
            ).orEmpty()
            when {
                allowed.contains(DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE_ON_PERSONAL_DEVICE) ->
                    DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE_ON_PERSONAL_DEVICE
                else -> DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE
            }
        } else {
            DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE
        }
        finishWithResult(
            RESULT_OK,
            Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, mode)
        )
    }

    private fun completeProvisioning() {
        val manager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, LabDeviceAdminReceiver::class.java)
        if (manager.isProfileOwnerApp(packageName)) {
            manager.setProfileName(admin, getString(R.string.work_profile_name))
            manager.setProfileEnabled(admin)
            finishWithResult(RESULT_OK)
        } else {
            finishWithResult(RESULT_CANCELED)
        }
    }

    private fun finishWithResult(result: Int, data: Intent? = null) {
        if (data == null) setResult(result) else setResult(result, data)
        finish()
    }
}

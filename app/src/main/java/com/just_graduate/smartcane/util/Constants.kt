package com.just_graduate.smartcane

import android.Manifest

object Constants{
    const val REQUEST_ALL_PERMISSION = 1
    const val SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"

    /**
     * 블루투스 권한 취득
     */
    val PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    val DEVICE_NAME = "SmartCane"

}

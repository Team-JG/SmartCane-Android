package com.just_graduate.smartcane.util

import android.widget.Toast
import com.just_graduate.smartcane.MyApplication

class Util {
    companion object{
        fun showNotification(msg: String) {
            Toast.makeText(MyApplication.applicationContext(), msg, Toast.LENGTH_SHORT).show()
        }
    }
}
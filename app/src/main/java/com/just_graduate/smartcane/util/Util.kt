package com.just_graduate.smartcane.util

import android.speech.tts.TextToSpeech
import android.widget.Toast
import com.just_graduate.smartcane.MyApplication
import java.util.*

class Util {
    companion object{
        fun showToast(msg: String) {
            Toast.makeText(MyApplication.applicationContext(), msg, Toast.LENGTH_LONG).show()
        }

        fun textToSpeech(text: String) {
            var tts: TextToSpeech? = null
            tts =
                TextToSpeech(MyApplication.applicationContext(), TextToSpeech.OnInitListener { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        val result: Int? = tts?.setLanguage(Locale.KOREA)

                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            Toast.makeText(
                                MyApplication.applicationContext(),
                                "지원하지 않는 언어입니다",
                                Toast.LENGTH_LONG
                            ).show()
                        }else{
                            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, null)
                        }
                    }
                })
        }
    }
}
package com.baseerat.vision.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.baseerat.vision.R
import com.baseerat.vision.util.BitmapUtil
import com.baseerat.vision.util.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Invisible share target. Copies the shared image into our own cache (so no URI-permission
 * problems) and hands it to DescribeActivity. Works whether or not the app is already open.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (uri == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                val f = withContext(Dispatchers.IO) { BitmapUtil.copyToCache(this@ShareReceiverActivity, uri) }
                startActivity(DescribeActivity.intent(this@ShareReceiverActivity, f.absolutePath, "describe", "share"))
            } catch (_: Exception) {
                Toast.makeText(this@ShareReceiverActivity, getString(R.string.err_open_image), Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }
}

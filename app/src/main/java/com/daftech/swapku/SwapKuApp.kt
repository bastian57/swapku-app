package com.daftech.swapku

import android.app.Application

/**
 * Plain Application subclass, referenced by AndroidManifest.xml.
 * No SDK, no upload, no background work is started here. All processing
 * is on-device and started only from the UI, after the consent gate.
 */
class SwapKuApp : Application()

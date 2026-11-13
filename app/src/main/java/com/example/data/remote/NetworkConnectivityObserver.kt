package com.example.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.example.data.local.TrackEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

fun isTrackPlayableOffline(track: TrackEntity): Boolean {
    val cachedPath = track.cachedFilePath
    if (!cachedPath.isNullOrBlank() && runCatching { File(cachedPath).exists() }.getOrDefault(false)) {
        return true
    }
    val sourceUri = track.sourceUri?.trim().orEmpty()
    if (sourceUri.startsWith("content://") ||
        sourceUri.startsWith("file://") ||
        sourceUri.startsWith("asset://") ||
        sourceUri.startsWith("/")
    ) {
        return true
    }
    return false
}

fun isNetworkCurrentlyAvailable(context: Context): Boolean {
    return runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val notSuspended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        } else {
            true
        }
        hasInternet && notSuspended
    }.getOrDefault(true)
}

class NetworkConnectivityObserver(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isOnline = MutableStateFlow(isNetworkCurrentlyAvailable(appContext))
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private var registered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isOnline.value = true
        }

        override fun onLost(network: Network) {
            _isOnline.value = isNetworkCurrentlyAvailable(appContext)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val notSuspended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            } else {
                true
            }
            _isOnline.value = hasInternet && notSuspended
        }
    }

    fun start() {
        if (registered) return
        val cm = connectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(networkCallback)
            registered = true
        }
        _isOnline.value = isNetworkCurrentlyAvailable(appContext)
    }

    fun stop() {
        if (!registered) return
        val cm = connectivityManager ?: return
        runCatching {
            cm.unregisterNetworkCallback(networkCallback)
        }
        registered = false
    }

    fun refresh(): Boolean {
        val current = isNetworkCurrentlyAvailable(appContext)
        _isOnline.value = current
        return current
    }
}

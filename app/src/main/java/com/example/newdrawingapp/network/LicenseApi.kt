package com.example.newdrawingapp.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class LicenseApi {
    enum class LicenseStatus {
        FIRST_ACTIVATION,
        ALREADY_ACTIVATED,
        SERVER_ERROR,
        NETWORK_ERROR
    }

    companion object {
        private const val BASE_URL = "https://yaomagicserver.prodesignspain.com/api/board/"
        private const val TAG = "LicenseApi"

        private const val STATUS_FIRST_ACTIVATION = 200
        private const val STATUS_ALREADY_ACTIVATED = 205
        private const val STATUS_ERROR = 500

        suspend fun verifyLicenseStatus(licenseKey: String): LicenseStatus = withContext(Dispatchers.IO) {
            try {
                val urlString = "${BASE_URL}activate/$licenseKey"
                Log.d(TAG, "Requesting URL: $urlString")

                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection

                connection.apply {
                    requestMethod = "GET"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                Log.d(TAG, "Sending request...")
                val responseCode = connection.responseCode
                Log.d(TAG, "Response code: $responseCode")

                when (responseCode) {
                    STATUS_FIRST_ACTIVATION -> {
                        Log.d(TAG, "First activation successful")
                        LicenseStatus.FIRST_ACTIVATION
                    }
                    STATUS_ALREADY_ACTIVATED -> {
                        Log.d(TAG, "Device already activated and valid")
                        LicenseStatus.ALREADY_ACTIVATED
                    }
                    STATUS_ERROR -> {
                        val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() }
                        Log.e(TAG, "Error response: $errorResponse")
                        LicenseStatus.SERVER_ERROR
                    }
                    else -> {
                        Log.e(TAG, "Unexpected response code: $responseCode")
                        LicenseStatus.SERVER_ERROR
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Verification failed", e)
                LicenseStatus.NETWORK_ERROR
            }
        }

        suspend fun verifyLicense(licenseKey: String): Boolean {
            return when (verifyLicenseStatus(licenseKey)) {
                LicenseStatus.FIRST_ACTIVATION,
                LicenseStatus.ALREADY_ACTIVATED -> true
                else -> false
            }
        }
    }
}


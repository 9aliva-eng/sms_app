package com.smsapp

import androidx.lifecycle.MutableLiveData

/**
 * Singleton LiveData bus shared between SmsService and UI fragments.
 * Updated from background threads via postValue().
 */
object SmsServiceState {
    val isRunning   = MutableLiveData(false)
    val sim1Sent    = MutableLiveData(0)
    val sim2Sent    = MutableLiveData(0)
    val sim1Active  = MutableLiveData(true)
    val sim2Active  = MutableLiveData(true)
    val statusText  = MutableLiveData("Остановлено")
    val event       = MutableLiveData<ServiceEvent>()
}

package com.gios.brightfantasylite

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

/**
 * No push and no background work: ESPN has no push channel a tool can subscribe to, and
 * the tool refreshes only when it is opened or asked. The object exists so the SDK's KSP
 * processor has an entry point to register.
 */
@EntryPoint
object FantasyEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) = Unit

    override suspend fun onPushNotification(data: ByteArray) = Unit
}

package com.chunkytofustudios.native_geofence.model

import kotlinx.serialization.Serializable

@Serializable
enum class GeofenceStatus {
    PENDING,
    ACTIVE,
    FAILED,
}
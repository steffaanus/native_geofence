package com.steffaanus.native_geofence.model

import kotlinx.serialization.Serializable

@Serializable
enum class GeofenceStatus {
    PENDING_ADD,
    ACTIVE,
    PENDING_REMOVE,
    FAILED,
}
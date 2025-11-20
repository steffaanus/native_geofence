import Foundation

// MARK: - Codable Extensions for Pigeon-Generated Types
//
// These extensions add Codable conformance to Pigeon-generated types
// to maintain backwards compatibility with existing JSON-encoded data.
// The implementation uses Pigeon's existing toList() and fromList() methods.

// MARK: - GeofenceEvent

extension GeofenceEvent: Codable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let rawValue = try container.decode(Int.self)
        guard let event = GeofenceEvent(rawValue: rawValue) else {
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Invalid GeofenceEvent raw value: \(rawValue)"
            )
        }
        self = event
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(self.rawValue)
    }
}

// MARK: - GeofenceStatus

extension GeofenceStatus: Codable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let rawValue = try container.decode(Int.self)
        guard let status = GeofenceStatus(rawValue: rawValue) else {
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Invalid GeofenceStatus raw value: \(rawValue)"
            )
        }
        self = status
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(self.rawValue)
    }
}

// MARK: - Location

extension Location: Codable {
    enum CodingKeys: String, CodingKey {
        case latitude
        case longitude
    }
    
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let latitude = try container.decode(Double.self, forKey: .latitude)
        let longitude = try container.decode(Double.self, forKey: .longitude)
        self.init(latitude: latitude, longitude: longitude)
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(latitude, forKey: .latitude)
        try container.encode(longitude, forKey: .longitude)
    }
}

// MARK: - IosGeofenceSettings

extension IosGeofenceSettings: Codable {
    enum CodingKeys: String, CodingKey {
        case initialTrigger
    }
    
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let initialTrigger = try container.decode(Bool.self, forKey: .initialTrigger)
        self.init(initialTrigger: initialTrigger)
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(initialTrigger, forKey: .initialTrigger)
    }
}

// MARK: - AndroidGeofenceSettings

extension AndroidGeofenceSettings: Codable {
    enum CodingKeys: String, CodingKey {
        case initialTriggers
        case expirationDurationMillis
        case loiteringDelayMillis
        case notificationResponsivenessMillis
    }
    
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let initialTriggers = try container.decode([GeofenceEvent].self, forKey: .initialTriggers)
        let expirationDurationMillis = try container.decodeIfPresent(Int64.self, forKey: .expirationDurationMillis)
        let loiteringDelayMillis = try container.decode(Int64.self, forKey: .loiteringDelayMillis)
        let notificationResponsivenessMillis = try container.decodeIfPresent(Int64.self, forKey: .notificationResponsivenessMillis)
        
        self.init(
            initialTriggers: initialTriggers,
            expirationDurationMillis: expirationDurationMillis,
            loiteringDelayMillis: loiteringDelayMillis,
            notificationResponsivenessMillis: notificationResponsivenessMillis
        )
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(initialTriggers, forKey: .initialTriggers)
        try container.encodeIfPresent(expirationDurationMillis, forKey: .expirationDurationMillis)
        try container.encode(loiteringDelayMillis, forKey: .loiteringDelayMillis)
        try container.encodeIfPresent(notificationResponsivenessMillis, forKey: .notificationResponsivenessMillis)
    }
}

// MARK: - Geofence

extension Geofence: Codable {
    enum CodingKeys: String, CodingKey {
        case id
        case location
        case radiusMeters
        case triggers
        case iosSettings
        case androidSettings
        case callbackHandle
    }
    
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let id = try container.decode(String.self, forKey: .id)
        let location = try container.decode(Location.self, forKey: .location)
        let radiusMeters = try container.decode(Double.self, forKey: .radiusMeters)
        let triggers = try container.decode([GeofenceEvent].self, forKey: .triggers)
        let iosSettings = try container.decode(IosGeofenceSettings.self, forKey: .iosSettings)
        let androidSettings = try container.decode(AndroidGeofenceSettings.self, forKey: .androidSettings)
        let callbackHandle = try container.decode(Int64.self, forKey: .callbackHandle)
        
        self.init(
            id: id,
            location: location,
            radiusMeters: radiusMeters,
            triggers: triggers,
            iosSettings: iosSettings,
            androidSettings: androidSettings,
            callbackHandle: callbackHandle
        )
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(location, forKey: .location)
        try container.encode(radiusMeters, forKey: .radiusMeters)
        try container.encode(triggers, forKey: .triggers)
        try container.encode(iosSettings, forKey: .iosSettings)
        try container.encode(androidSettings, forKey: .androidSettings)
        try container.encode(callbackHandle, forKey: .callbackHandle)
    }
}

// MARK: - ActiveGeofence

extension ActiveGeofence: Codable {
    enum CodingKeys: String, CodingKey {
        case id
        case location
        case radiusMeters
        case triggers
        case androidSettings
        case status
    }
    
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let id = try container.decode(String.self, forKey: .id)
        let location = try container.decode(Location.self, forKey: .location)
        let radiusMeters = try container.decode(Double.self, forKey: .radiusMeters)
        let triggers = try container.decode([GeofenceEvent].self, forKey: .triggers)
        let androidSettings = try container.decodeIfPresent(AndroidGeofenceSettings.self, forKey: .androidSettings)
        let status = try container.decode(GeofenceStatus.self, forKey: .status)
        
        self.init(
            id: id,
            location: location,
            radiusMeters: radiusMeters,
            triggers: triggers,
            androidSettings: androidSettings,
            status: status
        )
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(location, forKey: .location)
        try container.encode(radiusMeters, forKey: .radiusMeters)
        try container.encode(triggers, forKey: .triggers)
        try container.encodeIfPresent(androidSettings, forKey: .androidSettings)
        try container.encode(status, forKey: .status)
    }
}

// MARK: - GeofenceCallbackParams

extension GeofenceCallbackParams: Codable {
    enum CodingKeys: String, CodingKey {
        case geofences
        case event
        case location
        case callbackHandle
    }
    
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let geofences = try container.decode([ActiveGeofence?].self, forKey: .geofences)
        let event = try container.decode(GeofenceEvent.self, forKey: .event)
        let location = try container.decodeIfPresent(Location.self, forKey: .location)
        let callbackHandle = try container.decode(Int64.self, forKey: .callbackHandle)
        
        self.init(
            geofences: geofences,
            event: event,
            location: location,
            callbackHandle: callbackHandle
        )
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(geofences, forKey: .geofences)
        try container.encode(event, forKey: .event)
        try container.encodeIfPresent(location, forKey: .location)
        try container.encode(callbackHandle, forKey: .callbackHandle)
    }
}
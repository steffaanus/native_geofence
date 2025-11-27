import Foundation

/// Extension for Location to provide coordinate normalization for iOS sync comparison
extension Location {
    /// Normalizes coordinates to 6 decimal places
    /// Used ONLY for comparing CLRegion coordinates with stored values
    func normalized() -> Location {
        return Location(
            latitude: latitude.roundTo6Decimals(),
            longitude: longitude.roundTo6Decimals()
        )
    }
    
    /// Check equality with 6 decimal precision
    func equalsNormalized(_ other: Location) -> Bool {
        return self.normalized().latitude == other.normalized().latitude &&
               self.normalized().longitude == other.normalized().longitude
    }
}

private extension Double {
    func roundTo6Decimals() -> Double {
        return (self * 1000000).rounded() / 1000000
    }
}
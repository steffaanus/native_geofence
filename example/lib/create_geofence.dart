import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:native_geofence/native_geofence.dart';
import 'package:native_geofence_example/callback.dart';
import 'package:permission_handler/permission_handler.dart';

class CreateGeofence extends StatefulWidget {
  const CreateGeofence({super.key});

  @override
  CreateGeofenceState createState() => CreateGeofenceState();
}

class CreateGeofenceState extends State<CreateGeofence> {
  static final Location _timesSquare =
      Location(latitude: 53.164677890945015, longitude: 5.445930351661604);

  List<String> activeGeofences = [];
  late Geofence data;

  @override
  void initState() {
    super.initState();
    data = Geofence(
        id: 'zone1',
        location: _timesSquare,
        radiusMeters: 500,
        triggers: [
          GeofenceEvent.enter,
          GeofenceEvent.exit,
        ],
        iosSettings: IosGeofenceSettings(
          initialTrigger: true,
        ),
        androidSettings: AndroidGeofenceSettings(
          initialTriggers: [GeofenceEvent.enter],
          loiteringDelayMillis: 1000,
        ),
        callbackHandle: PluginUtilities.getCallbackHandle(geofenceTriggered)!.toRawHandle()
    );
    _updateRegisteredGeofences();
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Text('Active Geofences: $activeGeofences'),
        SizedBox(height: 40),
        Form(
          child: Column(
            children: [
              Text('Create/Remove Geofence',
                  style: Theme.of(context).textTheme.headlineSmall),
              SizedBox(height: 16),
              TextFormField(
                  decoration: InputDecoration(labelText: 'ID'),
                  initialValue: data.id,
                  onChanged: (String value) => data = Geofence(
                      id: value,
                      location: data.location,
                      radiusMeters: data.radiusMeters,
                      triggers: data.triggers,
                      iosSettings: data.iosSettings,
                      androidSettings: data.androidSettings,
                      callbackHandle: PluginUtilities.getCallbackHandle(geofenceTriggered)!.toRawHandle()
                  )),
              SizedBox(height: 16),
              TextFormField(
                decoration: InputDecoration(labelText: 'Latitude'),
                initialValue: data.location.latitude.toString(),
                onChanged: (String value) {
                  data = Geofence(
                      id: data.id,
                      location: Location(
                          latitude: double.parse(value),
                          longitude: data.location.longitude),
                      radiusMeters: data.radiusMeters,
                      triggers: data.triggers,
                      iosSettings: data.iosSettings,
                      androidSettings: data.androidSettings,
                    callbackHandle: PluginUtilities.getCallbackHandle(geofenceTriggered)!.toRawHandle()
                  );
                },
              ),
              SizedBox(height: 10),
              TextFormField(
                decoration: InputDecoration(labelText: 'Longitude'),
                initialValue: data.location.longitude.toString(),
                onChanged: (String value) {
                  data = Geofence(
                      id: data.id,
                      location: Location(
                          latitude: data.location.latitude,
                          longitude: double.parse(value)),
                      radiusMeters: data.radiusMeters,
                      triggers: data.triggers,
                      iosSettings: data.iosSettings,
                      androidSettings: data.androidSettings,
                      callbackHandle: PluginUtilities.getCallbackHandle(geofenceTriggered)!.toRawHandle()
                  );
                },
              ),
              SizedBox(height: 16),
              TextFormField(
                decoration: InputDecoration(labelText: 'Radius (meters)'),
                initialValue: data.radiusMeters.toString(),
                onChanged: (String value) {
                  data = Geofence(
                      id: data.id,
                      location: data.location,
                      radiusMeters: double.parse(value),
                      triggers: data.triggers,
                      iosSettings: data.iosSettings,
                      androidSettings: data.androidSettings,
                      callbackHandle: PluginUtilities.getCallbackHandle(geofenceTriggered)!.toRawHandle()
                  );
                },
              ),
              SizedBox(height: 22),
              ElevatedButton(
                onPressed: () async {
                  if (!(await _checkPermissions())) {
                    if (!context.mounted) return;
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('Lacking permissions!')),
                    );
                    return;
                  }
                  await NativeGeofenceManager.instance
                      .createGeofence(data, geofenceTriggered);
                  debugPrint('Geofence created: ${data.id}');
                  await _updateRegisteredGeofences();
                  await Future.delayed(const Duration(seconds: 1));
                  await _updateRegisteredGeofences();
                },
                child: const Text('Register'),
              ),
              SizedBox(height: 22),
              ElevatedButton(
                onPressed: () async {
                  await NativeGeofenceManager.instance.removeGeofence(data);
                  debugPrint('Geofence removed: ${data.id}');
                  await _updateRegisteredGeofences();
                  await Future.delayed(const Duration(seconds: 1));
                  await _updateRegisteredGeofences();
                },
                child: const Text('Unregister'),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Future<void> _updateRegisteredGeofences() async {
    final List<String> geofences =
        await NativeGeofenceManager.instance.getRegisteredGeofenceIds();
    setState(() {
      activeGeofences = geofences;
    });
    debugPrint('Active geofences updated.');
  }
}

Future<bool> _checkPermissions() async {
  final locationPerm = await Permission.location.request();
  final backgroundLocationPerm = await Permission.locationAlways.request();
  final notificationPerm = await Permission.notification.request();
  return locationPerm.isGranted &&
      backgroundLocationPerm.isGranted &&
      notificationPerm.isGranted;
}

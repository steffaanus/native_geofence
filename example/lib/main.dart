import 'dart:async';
import 'dart:isolate';
import 'dart:ui';

import 'package:flutter/material.dart';

import 'package:native_geofence/native_geofence.dart';
import 'package:native_geofence_example/create_geofence.dart';

import 'notifications_repository.dart';

void main() => runApp(MyApp());

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  MyAppState createState() => MyAppState();
}

class MyAppState extends State<MyApp> {
  String geofenceState = 'N/A';
  ReceivePort port = ReceivePort();
  StreamSubscription<NativeLogEntry>? _logSubscription;
  final List<String> _recentLogs = [];

  @override
  void initState() {
    super.initState();
    unawaited(NotificationsRepository().init());
    IsolateNameServer.registerPortWithName(
      port.sendPort,
      'native_geofence_send_port',
    );
    port.listen((dynamic data) {
      debugPrint('Event: $data');
      setState(() {
        geofenceState = data;
      });
    });
    initPlatformState();
    _setupLogStreaming();
  }

  void _setupLogStreaming() {
    // Subscribe to native log stream
    _logSubscription = NativeGeofenceManager.instance.nativeLogStream.listen(
      (log) {
        final timestamp = DateTime.fromMillisecondsSinceEpoch(
            log.timestampMillis.toInt());
        final levelStr = log.level == NativeLogLevel.warning ? 'WARN' : 'ERROR';
        final logMessage =
            '[${timestamp.toIso8601String()}] [${log.platform}/${log.category}] $levelStr: ${log.message}';

        debugPrint('Native Log: $logMessage');

        setState(() {
          _recentLogs.insert(0, logMessage);
          // Keep only last 10 logs in UI
          if (_recentLogs.length > 10) {
            _recentLogs.removeLast();
          }
        });
      },
      onError: (error) {
        debugPrint('Error in log stream: $error');
      },
    );
  }

  @override
  void dispose() {
    _logSubscription?.cancel();
    super.dispose();
  }

  Future<void> initPlatformState() async {
    debugPrint('Initializing...');
    await NativeGeofenceManager.instance.initialize(
      foregroundServiceConfig: ForegroundServiceConfiguration(
        notificationTitle: 'Locatie bewaking actief',
        notificationText:
            'We controleren je locatie voor geofence gebeurtenissen',
      ),
    );
    debugPrint('Initialization done');
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Native Geofence'),
        ),
        body: Container(
          padding: const EdgeInsets.all(20.0),
          child: SingleChildScrollView(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: <Widget>[
                Text('Current state: $geofenceState'),
                const SizedBox(height: 20),
                CreateGeofence(),
                const SizedBox(height: 20),
                const Divider(),
                const Text(
                  'Native Logs (Warnings & Errors)',
                  style: TextStyle(fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 10),
                Container(
                  height: 200,
                  decoration: BoxDecoration(
                    border: Border.all(color: Colors.grey),
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: _recentLogs.isEmpty
                      ? const Center(
                          child: Text('No logs yet'),
                        )
                      : ListView.builder(
                          itemCount: _recentLogs.length,
                          itemBuilder: (context, index) {
                            final log = _recentLogs[index];
                            final isError = log.contains('ERROR');
                            return Container(
                              padding: const EdgeInsets.all(4),
                              color: isError
                                  ? Colors.red.withOpacity(0.1)
                                  : Colors.orange.withOpacity(0.1),
                              child: Text(
                                log,
                                style: TextStyle(
                                  fontSize: 10,
                                  color: isError ? Colors.red : Colors.orange,
                                  fontFamily: 'monospace',
                                ),
                              ),
                            );
                          },
                        ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

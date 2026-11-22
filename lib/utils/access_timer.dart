import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:pi_hole_client/ui/core/l10n/generated/app_localizations.dart';
import 'package:pi_hole_client/utils/logger.dart';

/// Settings and state of the "Internet access timer".
///
/// - [disableHours] (X): duration of a disable triggered from a widget.
/// - [cooldownHours] (Y): Pi-hole can be disabled at most once every Y hours,
///   counted from the start of the previous disable (0 = no limit).
///
/// The values live on the Android side (shared with the home screen widgets)
/// and are accessed through the `pihole/widget` MethodChannel. On other
/// platforms the feature is not available and no limit is applied.
class AccessTimerState {
  const AccessTimerState({
    required this.disableHours,
    required this.cooldownHours,
    required this.cooldownRemaining,
    required this.disablePresetsMinutes,
  });

  final int disableHours;
  final int cooldownHours;
  final Duration cooldownRemaining;

  /// Durations (minutes) offered by the app's disable menu, ascending.
  /// "Indefinitely" is always added by the menu itself.
  final List<int> disablePresetsMinutes;

  bool get canDisable => cooldownRemaining == Duration.zero;
}

class AccessTimer {
  static const MethodChannel _channel = MethodChannel('pihole/widget');

  static const defaultDisableHours = 1;
  static const defaultCooldownHours = 4;
  static const minDisableHours = 1;
  static const maxDisableHours = 24;
  static const minCooldownHours = 0;
  static const maxCooldownHours = 168;
  static const defaultDisablePresetsMinutes = [10, 20, 40, 60];
  static const disablePresetsCount = 4;
  static const minPresetMinutes = 1;
  static const maxPresetMinutes = 1440;

  /// Test-only switch for [isSupported]. Never set outside tests.
  @visibleForTesting
  static bool? debugIsSupportedOverride;

  static bool get isSupported =>
      debugIsSupportedOverride ?? (!kIsWeb && Platform.isAndroid);

  static const _fallback = AccessTimerState(
    disableHours: defaultDisableHours,
    cooldownHours: defaultCooldownHours,
    cooldownRemaining: Duration.zero,
    disablePresetsMinutes: defaultDisablePresetsMinutes,
  );

  static Future<AccessTimerState> get() async {
    if (!isSupported) return _fallback;
    try {
      final raw = await _channel.invokeMapMethod<String, dynamic>(
        'getAccessTimer',
      );
      if (raw == null) return _fallback;
      return AccessTimerState(
        disableHours: (raw['disableHours'] as num?)?.toInt() ??
            defaultDisableHours,
        cooldownHours: (raw['cooldownHours'] as num?)?.toInt() ??
            defaultCooldownHours,
        cooldownRemaining: Duration(
          milliseconds: (raw['cooldownRemainingMs'] as num?)?.toInt() ?? 0,
        ),
        disablePresetsMinutes: _parsePresets(raw['disablePresetsMinutes']),
      );
    } catch (e) {
      logger.w('AccessTimer get failed: $e');
      return _fallback;
    }
  }

  static List<int> _parsePresets(Object? raw) {
    if (raw is! List) return defaultDisablePresetsMinutes;
    final values = raw.whereType<num>().map((e) => e.toInt()).toList();
    if (values.length != disablePresetsCount) {
      return defaultDisablePresetsMinutes;
    }
    return values;
  }

  static Future<bool> set({
    required int disableHours,
    required int cooldownHours,
    required List<int> disablePresetsMinutes,
  }) async {
    if (!isSupported) return false;
    try {
      await _channel.invokeMethod('setAccessTimer', {
        'disableHours': disableHours,
        'cooldownHours': cooldownHours,
        'disablePresetsMinutes': disablePresetsMinutes,
      });
      return true;
    } catch (e) {
      logger.w('AccessTimer set failed: $e');
      return false;
    }
  }

  static Future<void> recordDisable() async {
    if (!isSupported) return;
    try {
      await _channel.invokeMethod('recordAccessTimerDisable');
    } catch (e) {
      logger.w('AccessTimer recordDisable failed: $e');
    }
  }

  /// Formats a remaining time as e.g. "3h 05m" or "12m" (rounded up).
  static String formatRemaining(Duration d) {
    final totalMinutes = (d.inSeconds + 59) ~/ 60;
    final minutes = totalMinutes < 1 ? 1 : totalMinutes;
    final h = minutes ~/ 60;
    final m = minutes % 60;
    if (h > 0) return '${h}h ${m.toString().padLeft(2, '0')}m';
    return '${m}m';
  }

  /// Label for a disable duration in minutes: "10 minutes", "1 hour",
  /// "2 hours", "90 minutes".
  static String formatMinutes(int minutes, AppLocalizations loc) {
    if (minutes % 60 == 0) {
      final hours = minutes ~/ 60;
      return hours == 1 ? loc.hour1 : '$hours ${loc.hours}';
    }
    return '$minutes ${loc.minutes}';
  }

  /// Asks for the notification permission (Android 13+).
  ///
  /// Android hides toasts from apps in the background when their
  /// notifications are off, so without it the widget's "can be disabled again
  /// in ..." message never shows up. Asks only while the user has not
  /// refused permanently.
  static Future<void> requestNotificationPermission() async {
    if (!isSupported) return;
    try {
      final status = await Permission.notification.status;
      if (status.isDenied) {
        await Permission.notification.request();
      }
    } catch (e) {
      logger.w('AccessTimer notification permission failed: $e');
    }
  }
}

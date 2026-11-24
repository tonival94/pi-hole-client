import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pi_hole_client/ui/core/view_models/app_config_viewmodel.dart';
import 'package:pi_hole_client/ui/settings/app_settings/advanced_settings/widgets/disable_timers_screen.dart';

import '../../testing/fakes/repositories/local/fake_app_config_repository.dart';
import '../../testing/test_app.dart';

Future<void> loadFont(String family, List<String> files) async {
  final loader = FontLoader(family);
  for (final f in files) {
    if (!File(f).existsSync()) continue;
    final bytes = File(f).readAsBytesSync();
    loader.addFont(Future.value(ByteData.view(bytes.buffer)));
  }
  await loader.load();
}

void main() async {
  await initTestApp();
  final root = Platform.environment['FLUTTER_ROOT']!;
  final fonts = '$root/bin/cache/artifacts/material_fonts';

  testWidgets('shot', (tester) async {
    await loadFont('Roboto', [
      for (final w in ['Regular', 'Medium', 'Bold', 'Light'])
        '$fonts/Roboto-$w.ttf',
    ]);
    await loadFont('MaterialIcons', ['$fonts/MaterialIcons-Regular.otf']);
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 2.625;
    final vm = AppConfigViewModel(FakeAppConfigRepository());
    await tester.pumpWidget(
      buildTestApp(const DisableTimersScreen(), appConfigViewModel: vm),
    );
    await tester.pumpAndSettle();
    await expectLater(find.byType(MaterialApp), matchesGoldenFile('s1.png'));
    for (final l in ['de', 'es', 'pl', 'ja']) {
      await tester.pumpWidget(
        buildTestApp(
          Builder(
            builder: (context) => Localizations.override(
              context: context,
              locale: Locale(l),
              child: DisableTimersScreen(key: ValueKey(l)),
            ),
          ),
          appConfigViewModel: vm,
        ),
      );
      await tester.pumpAndSettle();
      await expectLater(find.byType(MaterialApp), matchesGoldenFile('$l.png'));
    }
    await tester.pumpWidget(
      buildTestApp(const DisableTimersScreen(), appConfigViewModel: vm),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('disableDurationUnit0-seconds')));
    await tester.pumpAndSettle();
    await expectLater(find.byType(MaterialApp), matchesGoldenFile('s2.png'));
  });
}

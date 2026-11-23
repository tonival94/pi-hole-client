import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:pi_hole_client/ui/core/l10n/generated/app_localizations.dart';
import 'package:pi_hole_client/ui/core/ui/helpers/snackbar.dart';
import 'package:pi_hole_client/ui/core/view_models/app_config_viewmodel.dart';
import 'package:pi_hole_client/utils/access_timer.dart';
import 'package:provider/provider.dart';

/// Settings > App > "Pi-hole timers by Toni".
///
/// X = how long a disable from the widget lasts (hours).
/// Y = Pi-hole can be disabled at most once every Y hours (0 = no limit).
/// Durations (minutes) offered by the app's disable menu.
class AccessTimerScreen extends StatefulWidget {
  const AccessTimerScreen({super.key});

  @override
  State<AccessTimerScreen> createState() => _AccessTimerScreenState();
}

class _AccessTimerScreenState extends State<AccessTimerScreen> {
  final TextEditingController _disableController = TextEditingController();

  /// X > 0: the widget re-enables Pi-hole after X hours. Off = indefinitely.
  bool _autoEnable = true;
  final TextEditingController _cooldownController = TextEditingController();
  final List<TextEditingController> _presetControllers = List.generate(
    AccessTimer.disablePresetsCount,
    (_) => TextEditingController(),
  );
  AccessTimerState? _state;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _disableController.dispose();
    _cooldownController.dispose();
    for (final c in _presetControllers) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _load() async {
    final state = await AccessTimer.get();
    if (!mounted) return;
    setState(() {
      _state = state;
      _autoEnable = state.disableHours > 0;
      _disableController.text = (state.disableHours > 0
              ? state.disableHours
              : AccessTimer.defaultDisableHours)
          .toString();
      _cooldownController.text = state.cooldownHours.toString();
      for (var i = 0; i < _presetControllers.length; i++) {
        _presetControllers[i].text = state.disablePresetsMinutes[i].toString();
      }
    });
  }

  int? _parse(String text, int min, int max) {
    final value = int.tryParse(text.trim());
    if (value == null || value < min || value > max) return null;
    return value;
  }

  /// 0 = indefinitely; null if the entered hours are not valid.
  int? get _disableHours => _autoEnable
      ? _parse(
          _disableController.text,
          AccessTimer.minDisableHours,
          AccessTimer.maxDisableHours,
        )
      : 0;

  int? get _cooldownHours => _parse(
    _cooldownController.text,
    AccessTimer.minCooldownHours,
    AccessTimer.maxCooldownHours,
  );

  int? _presetMinutes(int index) => _parse(
    _presetControllers[index].text,
    AccessTimer.minPresetMinutes,
    AccessTimer.maxPresetMinutes,
  );

  bool get _presetsValid => List.generate(
    _presetControllers.length,
    _presetMinutes,
  ).every((v) => v != null);

  Future<void> _save() async {
    final disableHours = _disableHours;
    final cooldownHours = _cooldownHours;
    if (disableHours == null || cooldownHours == null || !_presetsValid) {
      return;
    }
    final presets =
        List.generate(_presetControllers.length, (i) => _presetMinutes(i)!)
          ..sort();

    FocusScope.of(context).unfocus();
    setState(() => _saving = true);
    final ok = await AccessTimer.set(
      disableHours: disableHours,
      cooldownHours: cooldownHours,
      disablePresetsMinutes: presets,
    );
    if (!mounted) return;
    setState(() => _saving = false);

    final appConfigViewModel = context.read<AppConfigViewModel>();
    final loc = AppLocalizations.of(context);
    if (ok) {
      showSuccessSnackBar(
        context: context,
        appConfigViewModel: appConfigViewModel,
        label: loc.accessTimerSaved,
      );
      await _load();
    } else {
      showErrorSnackBar(
        context: context,
        appConfigViewModel: appConfigViewModel,
        label: loc.accessTimerSaveError,
      );
    }
  }

  Widget _numberField({
    required TextEditingController controller,
    required String label,
    required int min,
    required int max,
    required bool valid,
    String? helper,
  }) {
    final loc = AppLocalizations.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
      child: TextField(
        controller: controller,
        keyboardType: TextInputType.number,
        inputFormatters: [FilteringTextInputFormatter.digitsOnly],
        onChanged: (_) => setState(() {}),
        decoration: InputDecoration(
          labelText: label,
          helperText: helper,
          helperMaxLines: 4,
          errorText: valid ? null : loc.accessTimerInvalidRange(min, max),
          errorMaxLines: 2,
          border: const OutlineInputBorder(
            borderRadius: BorderRadius.all(Radius.circular(10)),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final loc = AppLocalizations.of(context);
    final state = _state;

    final Widget body;
    if (!AccessTimer.isSupported) {
      body = Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(loc.accessTimerNotSupported, textAlign: TextAlign.center),
        ),
      );
    } else if (state == null) {
      body = const Center(child: CircularProgressIndicator());
    } else {
      final disableValid = _disableHours != null;
      final cooldownValid = _cooldownHours != null;
      body = ListView(
        children: [
          const SizedBox(height: 8),
          ListTile(
            leading: Icon(
              state.canDisable
                  ? Icons.lock_open_rounded
                  : Icons.hourglass_bottom_rounded,
            ),
            title: Text(
              state.canDisable
                  ? loc.accessTimerStatusAvailable
                  : loc.accessTimerCooldownActive(
                      AccessTimer.formatRemaining(state.cooldownRemaining),
                    ),
            ),
          ),
          const SizedBox(height: 8),
          SwitchListTile(
            title: Text(loc.accessTimerAutoEnable),
            subtitle: Text(loc.accessTimerAutoEnableHelper),
            value: _autoEnable,
            onChanged: (value) => setState(() => _autoEnable = value),
          ),
          if (_autoEnable)
            _numberField(
              controller: _disableController,
              label: loc.accessTimerDisableHours,
              helper: loc.accessTimerDisableHelper,
              min: AccessTimer.minDisableHours,
              max: AccessTimer.maxDisableHours,
              valid: disableValid,
            ),
          _numberField(
            controller: _cooldownController,
            label: loc.accessTimerCooldownHours,
            helper: loc.accessTimerCooldownHelper,
            min: AccessTimer.minCooldownHours,
            max: AccessTimer.maxCooldownHours,
            valid: cooldownValid,
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
            child: Text(
              loc.accessTimerPresetsTitle,
              style: Theme.of(context).textTheme.titleMedium,
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: Text(
              loc.accessTimerPresetsHelper,
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
          for (var i = 0; i < _presetControllers.length; i++)
            _numberField(
              controller: _presetControllers[i],
              label: loc.accessTimerPresetLabel(i + 1),
              min: AccessTimer.minPresetMinutes,
              max: AccessTimer.maxPresetMinutes,
              valid: _presetMinutes(i) != null,
            ),
          Padding(
            padding: const EdgeInsets.all(16),
            child: FilledButton(
              onPressed: disableValid && cooldownValid && _presetsValid && !_saving
                  ? _save
                  : null,
              child: Text(loc.save),
            ),
          ),
        ],
      );
    }

    return Scaffold(
      appBar: AppBar(title: Text(loc.internetAccessTimer)),
      body: SafeArea(child: body),
    );
  }
}

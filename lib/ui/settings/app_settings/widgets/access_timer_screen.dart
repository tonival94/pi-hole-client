import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:pi_hole_client/ui/core/l10n/generated/app_localizations.dart';
import 'package:pi_hole_client/ui/core/ui/helpers/snackbar.dart';
import 'package:pi_hole_client/ui/core/view_models/app_config_viewmodel.dart';
import 'package:pi_hole_client/utils/access_timer.dart';
import 'package:provider/provider.dart';

/// Settings > App > Internet access timer.
///
/// X = how long a disable from the widget lasts (hours).
/// Y = Pi-hole can be disabled at most once every Y hours (0 = no limit).
class AccessTimerScreen extends StatefulWidget {
  const AccessTimerScreen({super.key});

  @override
  State<AccessTimerScreen> createState() => _AccessTimerScreenState();
}

class _AccessTimerScreenState extends State<AccessTimerScreen> {
  final TextEditingController _disableController = TextEditingController();
  final TextEditingController _cooldownController = TextEditingController();
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
    super.dispose();
  }

  Future<void> _load() async {
    final state = await AccessTimer.get();
    if (!mounted) return;
    setState(() {
      _state = state;
      _disableController.text = state.disableHours.toString();
      _cooldownController.text = state.cooldownHours.toString();
    });
  }

  int? _parse(String text, int min, int max) {
    final value = int.tryParse(text.trim());
    if (value == null || value < min || value > max) return null;
    return value;
  }

  int? get _disableHours => _parse(
    _disableController.text,
    AccessTimer.minDisableHours,
    AccessTimer.maxDisableHours,
  );

  int? get _cooldownHours => _parse(
    _cooldownController.text,
    AccessTimer.minCooldownHours,
    AccessTimer.maxCooldownHours,
  );

  Future<void> _save() async {
    final disableHours = _disableHours;
    final cooldownHours = _cooldownHours;
    if (disableHours == null || cooldownHours == null) return;

    FocusScope.of(context).unfocus();
    setState(() => _saving = true);
    final ok = await AccessTimer.set(
      disableHours: disableHours,
      cooldownHours: cooldownHours,
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

  Widget _hoursField({
    required TextEditingController controller,
    required String label,
    required String helper,
    required int min,
    required int max,
    required bool valid,
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
          _hoursField(
            controller: _disableController,
            label: loc.accessTimerDisableHours,
            helper: loc.accessTimerDisableHelper,
            min: AccessTimer.minDisableHours,
            max: AccessTimer.maxDisableHours,
            valid: disableValid,
          ),
          _hoursField(
            controller: _cooldownController,
            label: loc.accessTimerCooldownHours,
            helper: loc.accessTimerCooldownHelper,
            min: AccessTimer.minCooldownHours,
            max: AccessTimer.maxCooldownHours,
            valid: cooldownValid,
          ),
          Padding(
            padding: const EdgeInsets.all(16),
            child: FilledButton(
              onPressed: disableValid && cooldownValid && !_saving
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

import 'package:flutter/material.dart';
import 'package:pi_hole_client/ui/core/l10n/generated/app_localizations.dart';
import 'package:pi_hole_client/ui/core/ui/components/option_box.dart';
import 'package:pi_hole_client/utils/access_timer.dart';

class DisableModal extends StatefulWidget {
  const DisableModal({
    required this.onDisable,
    required this.window,
    super.key,
  });

  final void Function(int) onDisable;
  final bool window;

  @override
  State<DisableModal> createState() => _DisableModalState();
}

class _DisableOption {
  const _DisableOption(this.seconds, this.labelBuilder);

  /// Disable duration in seconds; 0 means indefinitely.
  final int seconds;
  final String Function(AppLocalizations) labelBuilder;
}

class _DisableModalState extends State<DisableModal> {
  int? selectedOption;

  /// Timed options come from Settings > "Pi-hole timers by Toni";
  /// "indefinitely" is always the last one.
  List<_DisableOption> _options = _buildOptions(
    AccessTimer.defaultDisablePresetsMinutes,
  );

  static List<_DisableOption> _buildOptions(List<int> presetsMinutes) => [
    for (final m in presetsMinutes)
      _DisableOption(m * 60, (loc) => AccessTimer.formatMinutes(m, loc)),
    _DisableOption(0, (loc) => loc.indefinitely),
  ];

  @override
  void initState() {
    super.initState();
    _loadPresets();
  }

  Future<void> _loadPresets() async {
    final state = await AccessTimer.get();
    if (!mounted) return;
    setState(() {
      _options = _buildOptions(state.disablePresetsMinutes);
    });
  }

  void _updateRadioValue(int? value) {
    setState(() {
      selectedOption = value;
    });
  }

  bool _selectionIsValid() => selectedOption != null;

  int _getTime() =>
      selectedOption == null ? 0 : _options[selectedOption!].seconds;

  @override
  Widget build(BuildContext context) {
    final mediaQueryData = MediaQuery.of(context);
    final loc = AppLocalizations.of(context);

    Widget optionBox(int index) {
      final isLast = index == _options.length - 1;
      final isLeft = index.isEven;
      final isFirstRow = index < 2;

      return FractionallySizedBox(
        // The last option (indefinitely) takes the full width of its row.
        widthFactor: isLast ? 1 : 0.5,
        child: Container(
          padding: EdgeInsets.only(
            top: isFirstRow ? 0 : 8,
            left: isLeft ? 16 : 8,
            right: isLeft && !isLast ? 8 : 16,
            bottom: isLast ? 0 : 8,
          ),
          child: OptionBox(
            optionsValue: selectedOption,
            itemValue: index,
            onTap: _updateRadioValue,
            child: Center(
              child: AnimatedDefaultTextStyle(
                duration: const Duration(milliseconds: 250),
                style: TextStyle(
                  fontWeight: FontWeight.bold,
                  fontSize: 14,
                  color: selectedOption == index
                      ? Theme.of(context).colorScheme.onSecondaryContainer
                      : Theme.of(context).colorScheme.onSurfaceVariant,
                ),
                child: Text(_options[index].labelBuilder(loc)),
              ),
            ),
          ),
        ),
      );
    }

    Widget options() {
      return Wrap(
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Column(
                children: [
                  Padding(
                    padding: const EdgeInsets.only(top: 16),
                    child: Icon(
                      Icons.gpp_bad_rounded,
                      size: 24,
                      color: Theme.of(context).colorScheme.secondary,
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.all(20),
                    child: Text(
                      loc.disable,
                      style: const TextStyle(fontSize: 24),
                    ),
                  ),
                ],
              ),
            ],
          ),
          for (var i = 0; i < _options.length; i++) optionBox(i),
        ],
      );
    }

    Widget buttons() {
      return Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.end,
          children: [
            TextButton(
              onPressed: () => Navigator.maybePop(context),
              child: Text(loc.cancel),
            ),
            const SizedBox(width: 20),
            TextButton(
              onPressed: _selectionIsValid()
                  ? () {
                      Navigator.maybePop(context);
                      widget.onDisable(_getTime());
                    }
                  : null,
              style: ButtonStyle(
                foregroundColor: _selectionIsValid()
                    ? WidgetStateProperty.all(
                        Theme.of(context).colorScheme.primary,
                      )
                    : WidgetStateProperty.all(Colors.grey),
              ),
              child: Text(loc.accept),
            ),
          ],
        ),
      );
    }

    final content = Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Flexible(child: SingleChildScrollView(child: options())),
        buttons(),
      ],
    );

    if (widget.window) {
      return Dialog(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 500),
          child: Padding(padding: mediaQueryData.viewInsets, child: content),
        ),
      );
    }

    return Padding(
      padding: mediaQueryData.viewInsets,
      child: SafeArea(child: content),
    );
  }
}

# pages/

- `/mobscaling ui` is the only entry point.
- A new knob is a `FieldSpec` in the matching `buildXSpecs()` list plus label and hint keys in all nine locales (`ScalingLangTest`); never a hand-written `.ui` row or a new `EventData` key.
- Never reopen the page; answer every event with a partial `sendUpdate`.
- Editors seed from the authored body (`authoredById`), never the `Parent`-merged view, or a save materializes inherited leaves.
- Consequences show only in the Global tab's preview column, computed by `ScalingPreview` through `MobScalingConfig.buildCurve` and `buildClamps`; no per-field computed rows.
- Namespace every element id `#Mmoscaling*` (the client UI id namespace is flat across mods).
- The escalation-origin fields stay `TEXT` (`NUMBER` rejects a minus sign, `INT` cannot be blank on the Global form); do not generalize blank-is-inherit to the Global tab.

# Changelog

All notable changes to MMO Mob Scaling. Newest first. No em-dashes.

## 1.2.1 - unreleased

Requires Ziggfreed Common 2.2.0 and MMO Skill Tree 1.7.0; the three ship together.

- **A rarity or variant is a multiplier on DIFFICULTY, not a set of stat multipliers (HARD BREAK, schema).** `RarityAsset` and `VariantAsset` each gain a top-level `DifficultyMultiplier`; `MobScaleFold` evaluates the curve at `dEff = difficulty * rarity.DifficultyMultiplier * variant.DifficultyMultiplier` (`MobScaleFold.curveDifficulty`, named apart from the SPOT difficulty a spawn resolves to, which is what `MobScaleResult.difficulty`, the HUD, the inspector and `/mobscaling inspect` carry), and `dEff` is deliberately NOT re-clamped to `Difficulty.MaxCap` (the cap bounds the ZONE difficulty a spawn resolves to; the curve's own rails bound the fold, and re-clamping is what would fold every tier onto one number at the top of the band). `Multipliers` keeps only `Loot` and `Xp`; `Hp`, `OutDamage` and `InDamage` are not leaves of the schema at all, so a rarity or variant file still authoring one gets the server's own `Unused key(s) in '<id>' file <path>: Hp` load warning (the engine reports every key no codec claimed) and folds on whatever `DifficultyMultiplier` it authors (1.0 when none). There is no honest conversion of three per-stat multipliers onto one difficulty multiplier that holds across the band, so nothing is converted: move the tier's strength onto `DifficultyMultiplier` and delete the three keys. Shipped: Rare 1.35, Epic 1.8, Legendary 2.4, Boss 3.0 (threat ratios, the geometric mean of the toughness lead and the damage lead over a plain mob, of about 1.32x / 1.75x / 2.36x / 3.0x at difficulty 30, rising slowly to about 1.42x / 1.98x / 2.78x / 3.62x at 200 because the damage axis bends upward while the tank axis is straight, so a tier's lead widens a little with difficulty where the old per-stat multipliers collapsed onto the clamps from difficulty 46 up); Horrific 1.25 (under the entry tier's step, and lifting a Horrific Legendary exactly to a Boss's fold). `Rarity.compareStrength` and so `RarityRoster.tierOf` / the forced-tier floor order the ladder by it.
- **One effective-HP curve, split geometrically, with one composite rail (HARD BREAK, schema).** `Difficulty.StatCurve` is `EffectiveHpPerPoint` (the one tank slope), `VisibleHpShare` (how much of the toughness shows on the health bar), `OutDamageScale` and `OutDamageShape` (the damage axis, next bullet), `MaxEffectiveHpMult` (the composite rail) and `MaxOutDamageMult`: `ehp = clamp(1 + (dEff - 1) * EffectiveHpPerPoint, 1, MaxEffectiveHpMult)`, `hp = ehp ^ share`, `in = ehp ^ (share - 1)`, so `hp / in == ehp` exactly for any share in [0, 1] and the two axes can no longer fight each other's clamps. `HpPerPoint`, `InDamageReductionPerPoint`, `MaxHpMult` and `MinInDamageMult` are retired with the `1 - (d - 1) * k` pole at d = 501 that was `MinInDamageMult`'s only reason to exist. Affix `FoldDeltas` stay multiplicative (`1 + sum(delta)` over the curve) and the composite rail is enforced LAST: when `hp / (in * (1 - resistance))` exceeds `MaxEffectiveHpMult` the correction lands on `in` (raised), never on `hp`, because `hp` is what the inspector shows and the health bar reflects while `in` is invisible. `resistance` is the sum of the affixes' new `FoldDeltas.ResistancePercent`, a DECLARED mirror of each effect's percent `DamageResistance` (0.15 on Armored, 0.4 on each of the six wards; the fold cannot read the effect asset), and `ScalingContentValidator.validateAffixResistanceMirrors` compares every mirror against the live `EntityEffect` at boot and reports drift in either direction. Because the rail counts the declared number as the share of every hit turned away and corrects on `in` for every cause at once, a mirror LARGER than the effect really grants makes the mob take more damage from everything the effect does not resist, and a smaller one lets it sit past the rail; the leaf's editor text says so. `MobScaleFold.DifficultyStatCurve.NONE` is the genuine identity now (every factor 1.0 at every difficulty), not a fail-safe carrying the old 4.5 / 3.0 / 0.5 tuning. `MobScalingConfig.buildCurve` is public and the admin page's preview builds through it, so the preview and a Save can no longer disagree on the sanity clamps.
- **The damage axis is a power curve, not a line (HARD BREAK, schema).** `out = clamp(1 + OutDamageScale * (dEff - 1) ^ OutDamageShape, 1, MaxOutDamageMult)` in `MobScaleFold.DifficultyStatCurve.outFactor`; `OutDamagePerPoint` is replaced by the pair. A shape of exactly 1.0 reduces to the straight line `1 + (dEff - 1) * scale`, so the scale is then a plain per-point slope and the knob has an obvious neutral value (`MobScaleFoldTest.aShapeOfOneIsExactlyTheStraightLineSlope` pins the identity to 1e-12); a non-positive shape is not a curve at all and `buildCurve` (and the record itself) read it as 1.0. The tank axis stays linear and untouched by the shape. Why: against the re-measured power map (fresh 1, L20 32, L50 100, L80 167, L100 200) the parity requirement on the damage axis is convex, because a player's effective HP has grown 5.19x by power 32 while power has covered 16% of its range (mitigation collapses early and power barely tracks it), so a straight line anchored at the top overshoots by +89% at power 32 and +47% at power 60, which is the newcomer-overwhelmed failure the mod exists to prevent. Fitted on the two anchors (power 32 -> 5.19, power 200 -> 57.70): shape 1.40 and scale 0.0342, with the residual against the requirement -0.1% at 32, -6.4% at 60, -17.2% at 100, -1.0% at 167 and -0.3% at 200, every milestone at or under the requirement, so the mob never hits harder than a fair fight at any measured point; a least-squares or minimax fit halves the worst error but puts +9% onto the newcomer end and was rejected for that. The pair sits in the `StatCurve` group beside the tank slope (`MobScalingSettingsAsset.StatCurve`), folds per leaf like every other leaf (global, preset, per-world through `ResolvedWorldSettings`), is on both admin forms (`outScale` / `outShape` and `wOutScale` / `wOutShape`, nine locales of `ui.curve.out_scale` / `out_shape` and their hints), and the Global tab's skeleton preview builds through the same `buildCurve`, so it previews the bent curve. `MobScalingConfigTest`'s rail check reads the plain top through the shape.
- **`Intensity` is deleted, and an owner's value is REPORTED at boot, never rewritten.** A multiplier over the slopes hid what the numbers were; owners author the curve directly. It leaves `MobScalingSettingsAsset`, `WorldSettings`, `MobScalingConfig` (the field, the fold, `getIntensity`, `setIntensityRuntime`, the `buildCurve` parameter), `ResolvedWorldSettings`, `MobScalingOwnerWriter.saveIntensity`, `/mobscaling intensity` with its `--intensity` argument and nine locales of keys, the admin page's global and per-world fields and the world summary line, and `ScalingContentValidator`'s two bounds checks (`validateSettings` is gone with them). Nothing is carried: the slopes `Intensity` multiplied on the tank axis (`HpPerPoint`, `InDamageReductionPerPoint`) no longer exist, so no number reproduces an old value on `EffectiveHpPerPoint` + `VisibleHpShare`, and writing one would hand the owner a third curve that is neither the one they had nor the file untouched. `config/LegacyIntensityReport` runs from the boot audit (`MobScalingAssetRegistrar.runBootAudit`, which registers outside the zero-cost gate, so the report itself runs enabled or not) and names, in ONE warning per file, every layer that still authors `Intensity` or one of the four retired `StatCurve` leaves (`HpPerPoint`, `InDamageReductionPerPoint`, `MaxHpMult`, `MinInDamageMult`, each with its value): the owner file, every owner world file (by the path its id resolves to), every jar or pack world body no owner file shadows (by id, with the owner-copy route; a shadowed body is inert), and every `Server/MmoMobScaling/Settings/*.json` in every loaded asset pack, a pack's own preset and a pack's override of `Default.json` alike, read raw off `AssetPack.getRoot()` because the settings codec keeps no key it does not declare. What the report can SEE follows the gate, which stays as it is (a disabled mod registers nothing and costs nothing): the owner file, the owner world files (their folder is adopted and scanned at `setup()`, before the gate) and every pack's settings files (raw off the pack root, no store needed) are reported enabled or disabled, while a jar or pack WORLD body reaches `WorldSettingsConfig` only through the `Worlds` store's `LoadedAssetsEvent`, and that store registers in the enabled branch, so those bodies are reported only while the mod is enabled. Each notice says the file was left as it is, names the leaves that replaced what the multiplier scaled with the values the file folds to today, and offers ONE starting point for the surviving damage axis, marked as a suggestion and not a conversion: `OutDamageScale` times the old `Intensity` (multiplying the coefficient scales the curve's growth the way `Intensity` scaled the old slope), the shape left as it is; the tank axis gets no number. The report repeats at every boot until the owner acts. `LegacyIntensityReportTest` covers each layer and asserts the files are byte-for-byte untouched and no slope is multiplied; `MobScalingConfigTest`'s legacy `WorldOverrides` migration test runs the report after the migration and asserts the same. An `Intensity` written as a JSON string (`"Intensity": "high"`) is named too, as the retired key it is, with no starting point offered because there is no number to scale (`Finding.intensity` is the element as written, `intensityNumber()` the finite number when it is one); before, such a file drew no notice at all, since the settings codec skips an unknown key silently whatever its type.
- **The Java balance constants leave `MobScaleFold` for the `Difficulty.Clamps` asset group.** `MAX_HEALTH_MULT`, `MIN_HP_MULT`, `IN_DMG_MIN`, `IN_DMG_MAX`, `OUT_DMG_MIN`, `OUT_DMG_MAX`, `LOOT_MULT_MIN` and `LOOT_MULT_MAX` are gone; the fail-safe floors and ceilings that are not the curve's shape are `Clamps.MinHpMult` 0.1, `MaxInDamageMult` 1.0, `MinOutDamageMult` 0.5, `MinLootMult` 0.5 and `MaxLootMult` **4.0** (the old Java 3.0 silently discarded the Horrific variant's `Loot` 1.3 on a Boss: 3.0 x 1.3 = 3.9 clamped to 3.0; a pack can raise it further), per world like every other `Difficulty` leaf and on both admin forms. `MobScaleFold.Clamps.NONE` is the rail that never binds. `SpawnScalingSettings` gains `clampsModel()` beside `statCurveModel()`.
- **The shipped numbers are re-derived for parity with a bounded player, and the tank axis comes DOWN while the damage axis goes UP.** `Settings/Default.json`: `EffectiveHpPerPoint` 0.0525 (from `playerDPS(L100) / playerDPS(L1)` = 185.5 / 16.2 = 11.45 at difficulty 200), `OutDamageScale` 0.0342 with `OutDamageShape` 1.4 (fitted to `playerEffHP(power) / playerEffHP(1)` at the two anchors, 5.19 at power 32 and 57.7 at 200; the damage-axis bullet has the fit and the residuals), `VisibleHpShare` 0.85, `MaxEffectiveHpMult` 34.4 (11.45 x 3) and `MaxOutDamageMult` 270 (just past the 266 a Boss reads at 600, below). The old curve produced 28.1x effective HP at the top where parity wants 11.45x, and 37.8x damage where parity wants 57.7x: a player's defensive growth outpaces their offensive growth by 5x over a full progression, so a fair mob must hit much harder without becoming a sponge. The old damage pair was the most wrong number in the system: computed against a power-200 player it fell short of break-even by 1.6x to 27x depending on the mob's base hit, so at the top of the scale no mob could win however high `Difficulty.MaxCap` went, because every stat clamp saturated first ([[power-difficulty-parity]]); the new one only works beside the MMO 1.7.0 mitigation bound that ships with it (the required multiplier is 1 / the player's surviving fraction, and that fraction varied 16.7x across the live population, so no single value served both a fresh and a fully geared character), which is why the manifest floors MMO Skill Tree at 1.7.0. Each rail sits just past what a Boss reads at maximum difficulty, on purpose (a rail at the plain-mob requirement is what flattens the ladder, since every tier reaches it): a Boss at MaxCap reads the curve at 600, about 32x an UNSCALED mob's effective HP and 266x its hit, under 34.4 and 270, so the shipped ladder never touches either. On the linear tank axis that is about three times the plain-mob top (11.45 x 3); on the bent damage axis it is about 4.6 times it (57.5 x 4.6), which is why the two rails no longer share one multiple. The plain mob standing beside that boss at 200 is itself at 11.45x and 57.5x, so against IT the boss is 2.8x as tough and hits 4.6x as hard; a comment that quotes the rail multipliers as a plain mob's is off by the plain mob's own scaling. `DistanceEscalation.BlocksPerPoint` 500 -> **87**: from the 5000-block start, the highest shipped zone floor of 28 needs 172 more points to reach `MaxCap` 200, and 15000 / 172 = 87.2, so the cap sits at 5000 + 172 x 87 = 19,964 blocks out. The old pair put it at 15000 + 172 x 500 = about 101,000 blocks out; 86,000 was the length of that ramp, not its distance from spawn. `RarityChancePerPoint` is unchanged at 0.01, which now saturates the rarity chance at 12,656 blocks (88 points). The three presets are re-derived as deliberate multiples of the new Default rather than carried across: Casual 0.75x the toughness slope and the damage scale (a comfortable win at matched power: 0.039 / 0.0257, rails 26.3 / 203), Hardcore 1.4x (0.0735 / 0.0479, rails 46.9 / 378), Playtest 1.5x (0.079 / 0.0513, rails 50.2 / 405); the shape is the curve's, every preset inherits Default's 1.4, and the two rails are derived differently: the damage rail sits just past a Boss at that preset's own top of the scale (its curve read at 600, plus about two percent), while the tank rail is three times that preset's plain-mob reading at `MaxCap`, which lands it four to eight percent past the boss figure. `Settings/Default.json` authors `OpenWorld.PlayerScalingEnabled: true` (`MobScalingConfigTest.shippedDefaultAuthorsEveryLeaf` refuses a Default leaf the fold supplies through its fallback alone); the three presets carry only the keys that differ from Default, and that leaf is not one of them.
- **The zone card's tier word is the derived threat ratio, not a fixed power gap.** `hud/ZoneTier` classifies `outFactor(difficulty) / outFactor(power)` (`ZoneTier.threatRatio` + `fromThreatRatio`, composed by `classify`), both read off the world's own `DifficultyStatCurve` (`ZoneDifficultyHud.pushUpdate` passes `SpawnScalingSettings.statCurveModel()`, the curve that world's spawns fold on), so the word on the card and the mob's hit can never disagree. The ratio is how many times faster the viewer dies here than in a matched fight, because parity holds a matched fight's time-to-die constant and time-to-die scales as the inverse of the outgoing multiplier. `fromDelta` and its four absolute thresholds (-15 / -5 / +5 / +15) are gone: an absolute delta cannot be scale-invariant over a curve that starts from 1, and on the shipped shape the same +15 points is about a 2.3x hit over power 10 and about 1.2x over power 100, so one word meant a different fight at every level. The bands are DERIVED from time-to-die and written down in the class javadoc: FAIR is dying at most a quarter faster, or a quarter slower, than in a matched fight (the open ratio band (0.8, 1.25); the quarter is a quarter of the RATE, which is what the curve moves, and reads as a fifth less time at the hard edge; geometric so the two sides read the same distance; a quarter is the first clean fraction above the model's own noise, since two builds at one power already differ by more than a tenth in what they can take), HARD and EASY run out to a doubling of that edge (2.5 times faster, or 2.5 times longer; under parity a matched plain mob takes a fixed fraction of the player's health per hit, so each band edge takes about a hit off the count), DEADLY and TRIVIAL lie beyond. The edges are a classification of a ratio, not a tuning of any fight (they move no mob's health, damage, loot or XP, and a curve retune moves zones between bands without moving them), which is why they are derived in code (`FAIR_TOLERANCE` 1.25, `BAND_MULTIPLE` 2.0) and not an asset leaf. Every edge case is deliberate and tested: the curve is read at the RAW power, never clamped to `Difficulty.MinCap` / `MaxCap` (a power past the cap reads a capped zone as easy, which is true, since the zone cannot reach them; a power below the floor is not a separate case, because the floor and the curve's origin are the same difficulty, so such a player reads the floor as a matched fight); a power at or below the curve's origin (0, negative, or the MMO's no-data minimum, which is what `getPowerLevel` answers for a character it holds nothing on) reads at difficulty 1, the fresh character's word, never an accidental EASY; a railed curve is read railed, exactly as the fold reads it, so two zones both past `MaxOutDamageMult` read alike because the mob hits alike there and a zone past the rail against a power below it reads the rail's own lead and no more (the rail caps what the word can say, which is the rail flattening the fight, not the card misreading it; a rail set past the curve's own top, where the shipped one sits, is never reached by a plain spot); the identity curve reads FAIR everywhere; a ratio that is not a positive finite number reads as matched rather than falling through to DEADLY. The five tiers, their ids, lang keys and colours are unchanged, no lang file moves, and the number beside the word stays the SPOT difficulty; every other difficulty readout (the inspector, `/mobscaling inspect`, the XP underdog gap, the `mob_difficulty` factor, the `MinDifficulty` gates) is untouched. `ZoneTierTest` sweeps power 0 to 400 asserting a matched fight reads FAIR at every power on a straight, a bent, a railed and the identity curve, asserts the same +15 reads HARD over power 10 and FAIR over power 100 on the straight and the bent curve alike, pins the band edges, their mirror symmetry and monotonicity, and each edge case above.
- **A `DISABLED` world holds no opinion about region power, which is not zero.** With each world folding under its own `AggregationMode`, a world authoring `OpenWorld.AggregationMode: DISABLED` fed a confident `0.0` to the boss framework's `EncounterPowerSource` seam and to the `mmomobscaling:region_power` factor, which is exactly the confident zero `EncounterPowerFill`'s contract exists to prevent. `RegionPowerTracker.holdsOpinion` is the one test both absent-answering reads make: the world has DECLARED its fold (`adoptWorldFold`, which the presence tick does before its first read) and it is not `DISABLED`. `scalarIfTracked` (the seam's read) answers null otherwise, and the new `readingFor` (the factor's read) answers null otherwise and the genuine `0.0` for a cold region in a declared, tracked world. A world no presence tick has declared yet therefore answers absent too, so a `DISABLED` world nobody has entered is indistinguishable from a cold miss instead of answering `0.0` before its first tick; presence is still tracked under `DISABLED`, so a live switch to another mode refolds in place, and the spawn path's `scalarFor` keeps its zero delta. `RegionPowerTrackerTest` covers both reads, declared and undeclared.
- **An owner file is found by its id ignoring case.** `OwnerFiles.sanitizeFileId` lower-cases the name a NEW file is created under, and a hand-named `Arena.json` is not that path on a case-sensitive filesystem (most production servers are Linux), so a save for `arena` would have written a second file beside it and a delete would have missed it. Every read, write and delete in both owner folders (`worlds/`, `difficulty/`) resolves through `OwnerFiles.resolveFile`, which finds the existing file whose stem matches ignoring case before falling back to the canonical lower-cased name; the legacy `WorldOverrides` migration's never-clobber check goes through it too. Two files differing only in case (a case-sensitive folder allows it) are settled the same way on read and on write: the one spelled exactly the canonical way wins, else the first in code-point order, with ONE warning naming every variant and the winner (`OwnerFiles.chooseAmongSameIdFiles`, a pure chooser so the policy is unit-tested on a filesystem that cannot hold the pair); the scan lists a folder in code-point order for the same reason. The scan and the resolve key a file through ONE function, `OwnerFiles.idKey` (the sanitizer: lower-cased, the trailing `*` dropped, every character outside `[a-z0-9._-]` rewritten to `_`): the scan files a body under the key of its stem and `resolveFile` finds the existing file whose stem has the same key, so a hand-named `Arena Big.json` is read under `arena_big` AND written at `Arena Big.json`; keying the scan by the raw lower-cased stem and the resolve by the sanitized name read such a file under one id and wrote a second file beside it, forking the rule. `WorldSettingsConfig` and `DifficultyConfig` key their pools and every id lookup by the same function, and a `Parent` reference is keyed by it before the resolver looks it up, so `"Parent": "Arena Big"` reaches that body. `OwnerFilesTest` (a stem the sanitizer rewrites among them), `MobScalingOwnerWriterTest` and `DifficultyOwnerLayerTest` cover it.
- **`DifficultyConfig` keys every layer by the one owner-file id key.** Its own `packLayer` was keyed by `OwnerFiles.idKey` while the base `AbstractKeyedAssetConfig` still keyed the pack and owner layers it folds by a plain lower-case, so a mapping whose id the sanitizer rewrites (`Zone2 Big` keys to `zone2_big`) landed in `all()` and `ids()` twice, once per spelling, with the derived index reading both. Both layer mutators now re-key what they are handed through `OwnerFiles.idKey` before the base fold sees it (its lower-case is then the identity), and `resolve` keys its argument the same way, so one id is one entry however it is spelled; two distinct ids meeting at one key keep the later one, as two owner files keying alike settle to one. `DifficultyOwnerLayerTest` covers a pack id with a space in it overlaid by an owner file named the same way: one id listed, one entry, the owner floor on top, resolvable by either spelling.
- **An authored EMPTY name-key prefix survives the world form.** `ZoneHud.ZoneNameKeyPrefix` and `BiomeNameKeyPrefix` are TEXT fields on the per-world form where blank means inherit, and an authored empty string seeds as the same blank, so a Save of a world that deliberately authors no prefix removed the leaf and switched it to the inherited prefix. `pages/WorldFormLeaves.keepAuthoredEmptyText` puts an authored empty string back where the form left a blank (a blank over an authored non-empty prefix still means inherit), with its own test.
- **`DifficultyConfig` drops its unused `loadDefaults` override.** Nothing calls the Java-baseline layer (config is never Java-baked here), so the override existed only to keep an index in step with a mutator that never ran. `DifficultyOwnerLayerTest` now exercises the full `jar < pack < owner` chain per leaf: a pack replaces a jar mapping by id wholesale, and an owner file then inherits the PACK's target while overriding its floor.
- **A `Difficulty/*.json` without a `Floor` is skipped, not folded at 0.** `DifficultyMappingAsset.Floor` became nullable so a partial owner file can inherit it, and a shipped or pack mapping that authors no `Floor` now decodes to no mapping and is skipped with a warning naming it, where it used to fold at 0.0 and silently floor its zone at nothing. A complete file is unaffected.
- **The shipped comments and the schema documentation say what the numbers do.** `Settings/Default.json`'s `$Comment` is rewritten around what the numbers mean: how a spot's difficulty is found and multiplied, the parity derivation behind the two slopes and why they point in opposite directions, what `VisibleHpShare` changes and does not, what the damage shape does to the early game against the late game (1.0 is a straight line), why each rail sits just past a Boss at the top of the scale rather than at the plain-mob value, the escalation arithmetic, and what the clamps are for. `Rarities/Boss.json`'s `$Comment` states where the boss sits against the rails, quoted against the right baseline: the rail multipliers are over an UNSCALED mob (32x / 266x at the top of the shipped scale, just under 34.4 / 270), and the plain mob beside the boss is itself scaled, so the comment also gives the boss against it (2.8x as tough, 4.6x the hit). The tier comments' ratios were re-read through the bent curve: Legendary at 30 takes nearly twice as long to kill as a plain mob and hits nearly three times as hard; Rare, Epic and the Horrific overlay keep their stated leads. `AffixAsset`'s schema example is the shipped Armored exactly (`FoldDeltas` carrying only `ResistancePercent`), and the `ResistancePercent` editor text says what an over-declared mirror costs through the composite rail.
- **A scaled mob could be immune to a weapon rather than tough.** `DamageSystems.ApplyDamage` applies damage as `Math.round(amount)` and cancels only an already-dead target, so a hit mitigated below half a point reaches the health stat as exactly zero as a completed, uncancelled hit (verified in the installed 0.6.8 bytecode: `invokestatic Math.round:(F)I` then `subtractStatValue`). `MobScalingDamageFilter` is the only thing in the stack that turns an integer damage amount into a fraction, and it is pinned BEFORE `ArmorDamageReduction`, so its scalar plus the mob's flat and percent resistances could hold an ordinary weapon under that line for good: cause-specific and weapon-specific, which is why one player killed a mob normally and another never could. `keepLandingHitsLanding` now floors the filter's own output at one point whenever the hit arriving was already worth at least that, so this mod can no longer scale a landing hit into nothing; the engine's armor still subtracts afterwards and may still reach zero, which is ordinary armor behaviour and readable from the mob's own resistances. The single point is the engine's rounding granularity, not a balance figure, so it is not an authored leaf; how tough a mob is stays a matter of `Difficulty.StatCurve.EffectiveHpPerPoint` and `VisibleHpShare`.
- **The ordering javadoc had its arithmetic backwards.** The class comment and the dependency's inline comment both claimed a post-armor multiply would "over-weight flat armor". The reverse is true for the incoming axis: with a raw 10 against flat 2 at a 0.45 scalar, multiplying first leaves 2.5 and multiplying last leaves 3.6, so running first makes flat resistance weigh MORE, and it is the only order that can drive a hit to zero. The pin is unchanged (the scalar should apply to the hit as rolled); what it costs is now stated, and it points at the floor.
- **`Mmoscaling_Affix_Armored` was inert against most melee.** The inbound resistance lookup is an exact leaf-cause match that early-returns on a miss (`DamageSystems.java`: `resistances.get(damage.getCause())`, then the `Inherits` walk only stacks on top of an entry that already matched), so keying only `Physical` and `Projectile` did nothing against `Bludgeoning` or `Slashing`. Both are added at the same 0.15. `Crush` is NOT added: it exists in the shared source but not in 0.6.8's `Assets.zip`, and a `DamageResistance` key naming an unknown cause fails the asset's validation and drops the whole mod at boot (which is exactly what the first attempt did). The six `Mmoscaling_Ward_*` effects were already correct, keying leaf causes the MMO jar ships early support for.
- **The mob inspector shows the resolved multipliers.** `TargetSnapshot` carries `hpMult` / `inDmgMult` / `outDmgMult` and renders them as whole percentages through the new `hud.inspect.mults` key (nine locales), on a `#MmoscalingInspectMults` label beside the difficulty row; the repaint key includes them at the precision shown. An owner facing an unkillable mob previously had no instrument at all, since the panel named the rarity, variant, affixes and difficulty and never what they added up to.
- **`/mobscaling purge` is console-runnable.** A `PlayerRef` sender sweeps its own world and replies in chat, as before; the console sweeps every loaded world through `Universe.get().getWorlds()` and reports per world through the log, since a `CommandContext` is not held across the hop onto the world thread. The documented uninstall flow was unusable headless.
- **A missing ordering target no longer fails the plugin.** `MobScalingDamageFilter`'s constructor hard-references the MMO's non-frozen `event.CombatDamageEventSystem` and declares a dependency on the engine's `@Deprecated` `DamageSystems.ArmorDamageReduction` ("Move to modifiers"), and `ComponentRegistry.registerSystem` validates a named class at registration. An unguarded throw there fails the whole plugin, and `PluginBase.cleanup` runs `commandRegistry.shutdown()`, which destroys the `/mobscaling purge` escape hatch registered outside the enabled gate precisely for that case. Its registration is now guarded on its own with a SEVERE naming the cause, so scaling degrades to no damage multiply instead of no mod.
- **The disable / uninstall caveat in the router was wrong about the auras.** `ActiveEntityEffect` persists the effect's id as a string and drops any effect whose id no longer resolves on load, and the effects ship in this jar's asset pack, so removing the jar removes them by itself. `purge` is for the persisted `mmoscaling_hp` stat modifier and for the config-disabled-but-installed case. Recorded there too: disabling strips the modifier and lets the engine clamp current HP to the un-scaled max (a 20x mob at 1900/2000 becomes 100/100) while re-enabling maximizes it back to full, and the fix is a fraction-preserving reconcile in ziggfreed-common's `HealthUtil` rather than here.
- **Distance escalation never engaged at the shipped default, so the far zones stayed low.** `Difficulty.DistanceEscalation.StartDistanceBlocks` shipped at 15000, beyond the range players actually travel in the shipped world, so the bonus was always 0 and a Zone4 spawn sat at whatever its `Difficulty/*.json` mapping said (28 at `Zone4_Tier5`, which is under Legendary's `MinDifficulty` 50 - the tier was unreachable). It ships at 5000 now, matching `OpenWorld.PlayerScalingStartRingBlocks`, so outside the protected ring the player/group delta and the distance ramp begin together. Owner overrides are untouched. Reported by a server owner who had to work this out by testing.
- **The units are right and the resolution is documented.** `ZoneDifficultyResolver` measures from `centerBlock(chunkCoord)`, so the distance genuinely is in BLOCKS; what it is not is per-block, because the read comes from the spawning chunk's centre and therefore steps `ChunkUtil.SIZE` (32) at a time. Both the codec javadoc and the shipped `$Comment` now say so, along with the fact that escalation ADDS to the zone gradient rather than replacing it and that `/mobscaling inspect` reports the distance to read off. No behaviour change: the reporter's "seems to apply in chunk increments" was the 32-block step seen while testing at close range.
- **A difficulty ceiling above the MMO's player-power ceiling is supported instead of scolded.** `validateDifficultyCaps` warned on ANY divergence between `Difficulty.MaxCap` and the MMO's `PowerLevel` `Clamp.MaxPower` and told the owner to align them, which is precisely backwards for the owner who wants mobs to out-scale a fully geared group: player power cannot exceed its own maximum, so the only way for difficulty to go past it is the zone floor and the distance escalation, and nothing in the code ever clamped `MaxCap` to it. It warns only when `MaxCap` is BELOW the power ceiling now (where region power really can exceed the cap the group delta is clamped to, flattening every strong group onto one difficulty), and the message says that setting it higher is fine and that `StatCurve.MaxOutDamageMult` is then what limits how hard those mobs hit. `ScalingContentValidatorTest` asserts both directions.
- **Four settings leaves nothing read are gone.** `PresetMode`, `OpenWorld.AllowDifficultyIncreaseOnPartyJoin`, `OpenWorld.LateArrivalBumpFactor` and `OpenWorld.CompositionEnabled` decoded, folded, overlaid per world and sat on the admin page, and no gameplay path ever read one of them (the composition system the last was meant to gate was never built). They leave the codec, `MobScalingConfig`, `SpawnScalingSettings`, `ResolvedWorldSettings`, the admin page, `Settings/Default.json` and the nine lang files together. An owner file or a world file still carrying one stays valid: `BuilderCodec.readUnknownField` skips an unknown key, so nothing migrates and nothing warns.
- **A per-world `OpenWorld.AggregationMode` folds for real.** `MobScalingPresenceSystem` handed `RegionPowerTracker` the GLOBAL mode on every presence update and removal, and that mode is what a bucket's cached scalar is folded under; the spawn path then read the per-world mode, but over a one-element array, where every mode but DISABLED is the identity, so a world authoring PEAK or WEIGHTED folded exactly like AVERAGE. The presence tick reads the per-world view now (`spawnSettingsFor(world)`, every axis) and declares the world's fold to the tracker before it reads or writes a bucket (`RegionPowerTracker.adoptWorldFold`: the mode and the grid size); every bucket in a world folds under its world's declared mode on update and on removal alike, so the removal hook, which holds no world, resolves nothing and the name-only lookup with its missing `GameplayConfig` axis is never needed; a live mode change refolds the world's buckets in place. `RegionPowerTrackerTest` pins the same two players folding to the strongest under PEAK and to the mean under AVERAGE.
- **Every per-world leaf the schema decodes is consumed.** `OpenWorld.RegionSizeChunks` is per world: a region bucket is keyed by world, so the proximity grid only has to agree within one, and a declared grid-size change purges that world's tracked presence (every key composed under the old size is stale; the next tick re-registers everyone under the new one). The whole `ZoneHud` group (`Position`, `OffsetX`, `OffsetY`, `ShowLocationName`, `ZoneNameKeyPrefix`, `BiomeNameKeyPrefix`) and the whole `InspectorHud` group (`Position`, `OffsetX`, `OffsetY`, `RangeBlocks`, `PortraitEnabled`) resolve through `ResolvedWorldSettings` like every other group, and the HUDs read the view of the world the viewing player stands in: the corner at build and on the live reposition (`ScalingHud.worldSettings()`; `refreshPositionForAllOnline()` re-anchors each online HUD to its own world's corner instead of pushing one global one), the location line and the name-key prefixes on the zone card, the raycast reach and the portrait on the inspector (the system leaves the snapshot's `modelRole` null when the world's portrait toggle is off, so the card reads no config of its own). The admin page's Worlds tab exposes all of them, the encounter power fill and the `region_power` factor read the grid size off the same per-world view, and `pages/CLAUDE.md` no longer claims the non-exposure was deliberate.
- **The zone and biome floors have an owner layer.** `DifficultyConfig` overrode `mergeOwnerLayer` and nothing ever called it, so the one number a server owner most often needs to move had no owner file. `config/DifficultyOwnerLayer` scans `mods/MmoMobScaling/difficulty/<id>.json`, one file per mapping on the `worlds/` convention (the filename is the id, a bare `DifficultyMappingAsset` body is canonical, a `Payload` wrapper is peeled, a `README.txt` is seeded); a file overlays the shipped mapping of the same id PER LEAF (`Zone2.json` holding `{"Floor": 60.0}` retunes the shipped Zone2 and inherits its target; a new id must carry all three leaves or is skipped with a warning naming the missing one), and `DifficultyMappingAsset.Floor` is a nullable leaf like every other so a partial file can inherit it. The layer is scaffolded at `setup()`, scanned on the difficulty store's `LoadedAssetsEvent` (the shipped mappings a partial file inherits from arrive there) and after every `MobScalingOwnerWriter.saveDifficultyMapping` / `saveDifficultyFloor` / `deleteDifficultyMapping`, so a runtime edit persists and applies live like every other knob; the boot INFO line names the folder. The folder mechanics both owner folders share (the filename sanitizer, the case-insensitive file resolution, the scaffold, the scan, the atomic write) are lifted out of `WorldSettingsConfig` into `config/OwnerFiles`.
- **The admin page covers every consumed leaf, edits the floors, and its preview shows the consequence.** A leaf-by-leaf audit of `MobScalingSettingsAsset` + `WorldSettings` against the page's spec tables found two consumed leaves with no control, `Where.GameplayConfig` and `Where.ExcludeMatch`; the world editor now carries all three `Where` axes as CSV fields (`Match` too, so a rule with several patterns round-trips whole and the `List.of` wrap in `handleSaveWorld` is gone; the save id falls back to the first pattern, then the first config key), and `MobScalingOwnerWriter` gains the `WHERE_GAMEPLAY_CONFIG` / `WHERE_EXCLUDE_MATCH` leaf constants. A fifth tab, Floors, lists every folded `Difficulty/*.json` mapping (`DifficultyConfig.all()`, zones before biomes, wildcards last) with a shipped/override badge and edits one through `MobScalingOwnerWriter.saveDifficultyMapping` / `deleteDifficultyMapping`: the editor seeds from the owner file's OWN leaves (`DifficultyOwnerLayer.authoredById`, new), blank fields inherit the shipped mapping per leaf and the hints say what they inherit, a new id is refused unless all three leaves are set. The Global tab's preview column (still the ONE place a consequence lives, still hand-built, now scrolling) grows from multipliers to the consequence: each row is two lines, `D{diff}   HP {hp}   Hit {out}   Taken {in}%` over `Kill time x{ttk}` plus an amber rail marker when `MaxEffectiveHpMult` / `MaxOutDamageMult` is what decided the row (`DifficultyStatCurve.effectiveHpRailed` / `outRailed`, new); the probe row and a RARITY LADDER at the probed difficulty (the baseline floor while nothing is typed; plain first, then every folded tier at `difficulty * DifficultyMultiplier`, a pack's tier in its place) follow, and a read-only MMO panel under them shows the player power floor and ceiling this mod can read through the frozen API (`config/MmoPowerBounds`, the same guarded read the boot caps cross-check now uses), the viewer's own power, how the form's caps sit against them and where those numbers are edited, degrading to one "not available" line on a missing or older MMO jar. Every figure comes from the new engine-free `pages/ScalingPreview` over the real `MobScaleFold` with a curve and clamps built through `MobScalingConfig.buildCurve` / `buildClamps`, and every number is a typed param. The Hit cell's absolute number is the LAST hit a mob of the sample role landed, fed to the new `RoleBaseHitResolver` by `MobScalingDamageFilter` before its own multiply: a role's hit is not a role field (it is the held weapon's attack chain, or the role's `InteractionVars.Melee_Damage` for an unarmed one), so unlike health there is no honest template read of it. `EventData`'s `WorldId` key is `Id` (the row id of either list); `WorldSettings.firstMatchPattern` stays as a read-side convenience. `ScalingLangTest` now holds all nine locales key-complete with matching placeholders.
- **Fixed: an `Item`, `Lootable` or `Stamped_Item` reward in a rarity's or variant's `Loot` block paid nothing.** `MobScalingLootDropSystem` handed the reward pass a `Subject` whose handle was the killer's bare `PlayerRef`, and every one of those library kinds asks the subject for a live `Player`, so each failed and counted lost on every kill. The subject is built through Ziggfreed Common's `PlayerRefSubjectHandle.subjectFor` now (the pure `rewardSubject` seam), whose handle answers for the `Player` and the `PlayerRef` alike; it takes the killer's id from the `PlayerRef` (the persisted-UUID read it replaced is gone). A roll's own `Items`, `DropLists` and `Commands` grants were unaffected; only its `Rewards` entries were. `MobScalingRewardSubjectTest` pins the handle and, read off the source, that the sink builds its subject through the seam only, since no test can build a real player.
- **Version 1.2.1; the pins name the jars the monorepo builds.** `ziggfreedCommonVersion` reads 2.2.0 and `mmoSkillTreeVersion` reads 1.7.0, with the manifest floors matching. The old `1.6.1` pin named a jar the repo no longer produces, so the compile resolved to a missing file and failed with "package com.ziggfreed.mmoskilltree.api does not exist" for every MMO import rather than saying the jar was absent.

- **New: the distance-escalation ORIGIN is authored, and the spawn point it defaults to is read through a live engine call.** `Difficulty.DistanceEscalation` gains a nested `Origin` group (`MobScalingSettingsAsset.EscalationOrigin`: `X` and `Z`, block coordinates, both NULLABLE `Codec.DOUBLE` leaves; deliberately no `Y`, the measure is XZ Euclidean from the spawning chunk's centre, so a height would be a leaf nothing reads). Unset at every layer an axis reads the world's own spawn point, exactly where every server measured from before; an authored axis pins it (a negative coordinate is legal), PER AXIS, folded owner-over-pack-over-jar like every nested leaf and overlaid per world through `ResolvedWorldSettings` (`SpawnScalingSettings.getEscalationOriginX/Z`, nullable). The shipped `Settings/Default.json` authors the group EMPTY, carrying only its `$Comment`, and pins nothing (`MobScalingConfigTest.shippedDefaultAuthorsEveryLeaf` now allow-lists exactly these two leaves as optional and asserts they ARE unset there). `ZoneDifficultyResolver` keeps reading the spawn point through the deprecated `ISpawnProvider.getSpawnPoints()[0]`, documented in place: it is the only member that names the world's OWN first point, which is the one stable origin the ramp needs, and it is the SURVIVING call of the pair, since the engine's next version keeps it deprecated and removes the synchronous `getSpawnPoint(World, UUID)` in favour of an async future. Its sibling answers a point FOR AN ENTITY (a multi-spawn `IndividualSpawnProvider` hashes the UUID into a choice among its points), so it cannot name the first one and a constant UUID would silently re-centre the ramp on such a world; `isWithinSpawnDistance` yields no coordinates and `World` exposes no origin. Every server measures from exactly where it did before. The protected ring (`OpenWorld.PlayerScalingStartRingBlocks`) and `ResolvedFloor.distanceFromSpawn` keep measuring from the spawn point; only the escalation ramp moves with the origin, and the resolver's per-chunk memo, its zoneless-world fallback and its live re-read of settings are unchanged (an origin edit needs no memo invalidation). Admin page: an `Origin X` / `Origin Z` TEXT pair on the Global tab and the Worlds editor (the one optional, possibly negative number on the Global tab, which no shared NUMBER/INT kind can spell there; the pure `pages/EscalationOriginLeaves` parses the pair on save, blank = the override removed, a non-number refuses the save naming the field via `ui.status.invalid_origin`), the world hint reading "Inherits: the world spawn point" when no layer authors one; nine-locale keys `ui.global.esc_origin_x/z`, `ui.hint.esc_origin_x/z`, `ui.world.inherits_spawn`, `ui.status.invalid_origin`, and the `ui.hint.esc_start` wording now says "from the origin".
- **Technical: a deprecation gate in the build.** `gradle/deprecation-gate.gradle` compiles each source set a second time with `-Xlint:deprecation` and fails `check` on any deprecation warning whose line, or the line above it, carries no `// DEPRECATION-KEPT: <why>` comment. `compileJava` keeps `-Xlint:removal -Werror` as it was. The two calls with no successor carry the comment where they are made: `ISpawnProvider.getSpawnPoints()` in `ZoneDifficultyResolver` and the `DamageSystems.ArmorDamageReduction` ordering target in `MobScalingDamageFilter`. The gate file is a byte copy of the MMO's, so `.gitattributes` keeps it LF on every checkout.
- **Technical: the bonus-loot sinks and the pass count come from Ziggfreed Common.** `MobScalingLootDropSystem` builds its items and drop-list sinks with the library's `GroundSpillSinks` (ground only, at the corpse with its facing, one pile per hand-over, every spilled stack counted as landed, as before) instead of its own pair and `spill`, and `lootPulls` resolves the folded loot multiplier through `StochasticCount.resolve` with the per-mob roll as its one draw. A multiplier at or past `2^31` still buys no passes, now said outright (`PULLS_CEILING`) where the old count wrapped negative. Nothing a player sees changes; `MobScalingLootPullsTest` passes unchanged.

## 1.2.0 - 2026-09-12

Requires Ziggfreed Common 2.1.0 and MMO Skill Tree 1.6.1 or newer; the three ship together.

- **The mob inspector's affix chips draw their item icons.** Each chip declared an `ItemIcon` widget, a type the client does not have, so an affix pictured by an item id laid out a blank gap before its name; an affix pictured by a texture path was unaffected. The chips declare a one-slot `ItemGrid` now, sized to the chip's own 18-pixel height and styled from Ziggfreed Common's shared `@ZigIconGrid18` (`Common/ZigButtons.ui`, reached from `Hud/` as `../Common/`; a grid with no `Style` draws nothing), fed by the same `IconRenderer` call as before. Needs Ziggfreed Common 2.1.0, where the seam itself was corrected.
- **The mob's health bar gets the same finish as every other bar in the family.** It was a flat red rectangle in a flat black slot while the quest tracker, the zone card and the shared progress-bar panels had moved on to a sunk well behind the fill, a gloss across its top and a shade along its bottom. The overlays sit inside the fill, so they follow the width the inspector already pushes and no Java changed. The frame texture is no longer carried here: every pack merges into one Custom UI namespace and Ziggfreed Common, which this mod requires, ships that file at the same path, so the bare filename in both documents resolves to it.
- **The rarity roll happens one tick after the spawn, on the mob's own entity reference.** The spawn hook still runs on the pre-add holder and still decides the gates (the mod and per-world switches, the exclusion, the ambient-boss scope); what it no longer does is roll. It stamps a one-tick `PendingRollComponent` and `MobScalingRollSystem` rolls on the first tick after the add, where a valid `Ref` exists. The roll itself is unchanged (the same seed off the entity's uuid and the world seed, the same rosters, the same family and pool gates, the same fold), so a given mob still gets the rarity, affixes and variant it always got. The one thing a player can see: a freshly spawned scaled mob enters the world at its base maximum health and is reconciled to its scaled maximum a tick later (the first apply heals to the new max).
- **A scripted spawn and an encounter's own boss are left alone.** An NPC raised by a `ManualTrigger` spawn marker (what an encounter script's `TriggerSpawners` fires for a boss and its adds) is skipped, and so is a mob a live encounter has bound as its subject: neither gets a rarity, an affix, a name decoration, an aura or this mod's health modifier, and a stale modifier or aura from an earlier save is stripped off it. A server owner wants this because a boss fight's health is the fight's own, scaled by the encounter framework per party member and per power point; a second owner writing the same maximum would fight it every phase. Both reads are only possible on the deferred tick: the engine attaches a mob's spawn-marker reference after the store's add, and the framework indexes a bound boss on its own tick. A ziggfreed-common jar older than the framework has no `EncounterRuntime`; the skip degrades to "roll as before" with one warning.
- **A multi-phase boss is rolled at most once per life.** An in-place role change is a remove and a re-add through every registered holder system with a fresh reference, so without a guard every phase swap would re-roll and re-scale the boss mid-fight. A holder that already carries a `ScaledMobComponent` was decided in an earlier life of that same holder and is left as it is. (A chunk reload is a different case: the component is transient, so a reloaded mob is rolled again from its stable seed to the identical result, which is what reconciles a retune onto a saved mob.)
- **`Mmoscaling_Bosses` means ambient world bosses.** The tagset's five roles are unchanged; what the file says, in its `$Comment` and in the classifier, is that it is the elite scope for a boss nobody scripted. A boss an encounter runs declares itself by being bound and is never classified by this mod, so listing its role here changes nothing.
- **A bound fight reads the region power at its boss.** This mod fills ziggfreed-common's `EncounterPowerSource` seam (`factor/EncounterPowerFill`), so a binding row's `Scale.HealthPerPowerPoint` has a number to multiply: the tracked player power of the region the SUBJECT stands in, read at the boss's own world and chunk through the same `RegionKeys` the presence tick writes with. It is not an aggregate over the members (the row's `Scale.HealthPerMember` already pays for them, and the tracker is region-keyed), and a region nobody is tracked in answers null, "nothing is known", never a confident zero (`RegionPowerTracker.scalarIfTracked`, beside the zero-delta `scalarFor` the spawn read keeps). Filled inside the enabled branch, so a switched-off mod leaves the seam on its own unfilled posture.
- **The three Dungeon of Fear world rules cannot shadow each other.** `*dungeon_of_fear_i*`, `*dungeon_of_fear_ii*` and `*dungeon_of_fear_iii*` nested by construction (`i` inside `ii` inside `iii`), so the content validator warned that removing a more specific rule would hand its worlds to the shorter one, and that the Kweebec rule tied with the `_i` rule on specificity. The patterns are `*dungeon_of_fear_i-*`, `*dungeon_of_fear_ii-*` and `*dungeon_of_fear_iii-*`: the `-` is the delimiter the engine puts between an instance's name and its id (`instance-<name>-<uuid>`), so each core ends where its own name ends and matches its own instance alone. Both warnings are gone.
- **Version 1.2.0; the family moves in lockstep.** The manifest floors rise to Ziggfreed Common `>=2.1.0` (the boss framework: `EncounterRuntime` and the power seam, neither of which exists in 2.0.x) and MMO Skill Tree `>=1.6.1`, and the compile pins name the same jars. The floors are the versions this mod is built against; the `LinkageError` guards around the newer seams are a safety net for a mis-installed server, not advertised compatibility with an older jar.
- **Role reads go through the shared library.** `MobScalingCasterArmSystem` and `MobScalingHudSystem` each fetched the `NPCEntity` component themselves purely to call `getRoleName()`, which is exactly the read `ziggfreed-common`'s `EntityIdentifierUtil.roleName` exists to own; both now ask it, and the arm system asks through the `CommandBuffer` it was handed. Behaviour is identical - this mod already keyed its rosters, affixes and classification on the role name, which is now the identity the whole family uses. The spawn hook keeps its own reads, since it holds the `NPCEntity` for the classifier anyway.

## 1.1.0 - 2026-08-31

- **The Asset Editor shows the real multiplier baselines and offers dropdowns on the closed vocabularies.** A rarity's and a variant's `Multipliers` leaves declare their neutral 1.0 default in the exported schema (an unauthored leaf renders 1.0, not 0), and the settings `PresetMode` (SIMPLE/TUNED/ADVANCED) and open-world `AggregationMode` (SOLO/AVERAGE/PEAK/WEIGHTED/DISABLED) export their closed value sets as dropdowns with a one-line meaning per value. Decode is unchanged.

NPC caster rosters: a Pattern-A asset (Server/MmoMobScaling/CasterRosters/*.json) binding a Role
selector (exact Id XOR glob) to a list of abilities a matching, gate-eligible mob arms at spawn and
fires on its own cadence+jitter. Each entry is AbilityId (cast via the MMO's new
MMOSkillTreeAPI.castNpcAbility(Store,Ref,String), requires MMO Skill Tree 1.6.0) XOR NativeChain (a
RootInteraction id armed via native CombatSupport.addAttackOverride), gated by MinDifficulty/Rarities/
Scope (HOSTILE|BOSS|ANY) against the frozen MobScaleResult - the gate model matches MobScaleResult
exactly (a difficulty float, a rarity id string, a scope byte), no integer tier concept introduced.
The manifest runtime requirement stays ">=1.5.0" (unchanged, deliberate); only the ABILITY caster
entries need MMO 1.6.0, and they degrade gracefully with one warning on an older jar instead of
refusing to load the whole mod - see CasterFeatureState.

- New: this mod PUBLISHES what it knows about a mob as ordinary factor readings, so any other mod's
  authored content can gate and scale on them with no dependency in either direction. Five ids,
  claimed through ziggfreed-common's process-wide contribution door at setup:
  `mmomobscaling:mob_rarity_tier` (the mob's place on the rarity ladder, 0 for plain and one step per
  authored tier, derived from the tiers themselves so inserting one moves everything above it up),
  `mmomobscaling:mob_rarity` (Param = a rarity id, for content that means one specific tier),
  `mmomobscaling:mob_affix` (Param = an affix id), `mmomobscaling:mob_difficulty`, and
  `mmomobscaling:region_power` (the tracked player power in the region the moment happened in). The
  four mob readings are about the entity the moment happened TO, never the one acting, so a mob-kill
  formula can weigh the mob's rarity and the killer's own luck in one expression without either
  question reading the other's entity; `region_power` is about a PLACE, so it reads the target's
  position and falls back to the subject's when there is no target. On a server without this mod - or with it switched off,
  since the claim is made inside the enabled branch - nothing answers the ids, so a gate on one stays
  shut and a formula term on one adds zero, and one authored file is correct everywhere. A boot line
  lists what was published.
- New: attributes each kill's rolled rarity to the MMO's kill-rarity seam
  (MMOSkillTreeAPI.registerKillRarityProvider, additive on the MMO's 1.6.0-cycle jar): a scaled
  kill carries its rarity id (Rare / Epic / Legendary / Boss) as the kill moment's qualifier, so
  quest and achievement criteria authoring a rarity word match scaled kills - the MMO's tier
  achievements (First_Legend_Kill, Legendary_Slayer, Rare_Hunter, Elite_Slayer) progress on them -
  and a mob-drop command's {tier} placeholder resolves to the same answer; a plain floor mob
  attributes nothing, and an ordinary kill criterion still counts every kill exactly once. On
  an older MMO jar the registration degrades with one warning (the same graceful-degradation
  story as the caster ABILITY entries) and everything else is unaffected.
- Fixed: the Freezing affix's slow actually slows the player it hits. The slow effect authored only
  the NPC movement leaf (`ApplicationEffects.HorizontalSpeedMultiplier`), which a player's client
  never applies, so a frozen player saw the frost tint and snow overlay at full running speed. The
  effect now also authors the player leaf (`MovementEffects.SpeedMultiplier`, Update 6) at the same
  0.7, and the Swift affix carries the matching 1.3 pair so its haste holds on any carrier.
- Changed (HARD BREAK, schema): a rarity or variant authors its death loot in ONE `Loot` block, the
  shared loot vocabulary the rest of the ecosystem already speaks, replacing the `BonusDropList`
  string and the `BonusRewards` compact-spec array. Rewrite `"BonusDropList": "Mmoscaling_Drops_Epic"`
  as `"Loot": { "Rolls": [ { "Grants": { "DropLists": ["Mmoscaling_Drops_Epic"] } } ] }`; a
  `"BonusRewards": ["xp MINING 500"]` entry becomes an ordinary `Grants.Commands` line or a
  `Grants.Rewards` entry naming a registered reward kind. The native `Server/Drops/*` tables are
  untouched and are simply referenced from `Grants.DropLists`, so no item content moved. What the
  block buys beyond the old pair: shared tables by id, exact item grants, per-roll `Conditions` and a
  factor-scaled `Chance`, ladder tiers, and any reward kind another mod registered - including gating
  on the readings above. The tier's `Multipliers.Loot` keeps its job as the number of times the whole
  block is rolled, which for a drop list is exactly what it always did; note that a tier rolling more
  than once now repeats its command and reward grants too, so write per-pass amounts. The five
  shipped rarity/variant files are re-authored onto it, and the content
  audit now names a dangling `Loot.Lootables` table id alongside a dangling drop list.
- Changed (HARD BREAK, schema): a world rule targets its worlds with the SHARED `Where`
  group, not a flat `Match` string. `WorldSettings.Where` decodes through `WorldSelector.CODEC`, so
  a rule authors `Match` (world-name patterns, the same wildcard
  grammar as the old flat field), `GameplayConfig` (exact config keys, the sturdy axis for an
  instance world whose NAME carries a fresh uuid) and `ExcludeMatch` - one vocabulary shared with
  NPC placements, dialogue world conditions and MMO world rules, scored on one specificity ladder.
  Rewrite `"Match": "*dungeon_*"` as `"Where": { "Match": ["*dungeon_*"] }`. Base-versus-rule
  semantics are preserved and made explicit: `"Where": {}` reads the same as omitting the group
  (both mean a pool-only base a `Parent` inherits from, never matched), via `WorldSelector.isBlank`.
  The 1.0.1 owner-array migration and the owner-directory README emit and teach the new shape, and
  the four shipped `Worlds/*.json` are re-authored onto it. The three Dungeon of Fear rules also
  move from a trailing-`*` prefix pattern to the CONTAINS form (`*dungeon_of_fear_i*`), which is
  what actually reaches a live instance world (its name is `instance-<Name>-<uuid>`, so the token
  sits mid-name); their relative precedence is unchanged, since the I/II/III literal cores still
  order longest-first.
- Changed: the copied matcher and the second evaluation engine are gone. `WorldSettingsConfig`
  selects by `MatchRank` through the one shared selector, `resolve(World)` scores all three axes
  while `resolve(String)` stays the pure name-only core (each with its own cache, so a world can
  never be served a view resolved from fewer axes than it has), and
  `ScalingContentValidator`'s private pattern parser is deleted in favour of the shared
  `WorldNameMatcher.Pattern` - so a validator can no longer reassure an author about an ordering
  the runtime does not use. `WorldRankParityTest` retires with the second ladder it guarded.
- Tuning: the shipped zone difficulty-floor gradient is flattened to a gentler early game -
  Zone1 8 -> 1 (Spawn 3 -> 1, Tier1/2/3 6/9/12 -> 1/2/3), Zone2 22 -> 5 (Tier1/2/3 18/22/26 ->
  5/8/10), Zone3 38 -> 12 (Tier1/2/3 34/38/42 -> 12/15/18), Zone4 55 -> 20 (Tier4/5 52/58 ->
  25/28), zone wildcard 10 -> 2. The world-baseline `Difficulty.Floor` (30.0) and the
  distance-escalation curve are unchanged.
- New: CasterRosterAsset + CasterRosterConfig (defaults<pack<owner fold) + Rosters.casterRosters()
  (id-sorted for a deterministic CasterRosterMatcher tie-break).
- New: CasterRosterMatcher, a pure precedence matcher (exact roleId > longest matching glob > first),
  the first implementation of the planned BossCurve keying pattern.
- New: per-entry Windup animations - an optional Windup{Animation, ItemAnimations, Slot} group played
  through the engine's own entity-generic AnimationUtils immediately before an ability cast, so a
  scaled mob visibly telegraphs the hit (zero MMO coupling; a NATIVE_CHAIN entry needs no Windup, its
  chain carries its own animation nodes).
- New: MobScalingCasterArmSystem (RefSystem, mirrors MobScalingEffectApplySystem) +
  MobScalingCasterTickSystem (EntityTickingSystem whose Archetype query itself excludes every
  non-caster mob, so steady-state cost is proportional to armed mobs only). NATIVE_CHAIN entries arm
  ONCE at spawn, never on cadence (a re-arm resets the engine's attack round-robin cursor and starves
  the mob's other chains).
- New: CasterFeatureState - a session-wide latch that disables ability-cast rosters with ONE warning
  on a LinkageError (running against a pre-1.6.0 MMO jar), so a mismatched jar pair degrades
  gracefully; NativeChain entries are unaffected.
- New: ScalingContentValidator.validateCasterRosters (Role.Id XOR Glob, AbilityId XOR NativeChain,
  unknown Scope, CadenceSeconds >= 2s floor, negative MinDifficulty/JitterSeconds, duplicate Role.Glob).

Community bug-fix wave (2026-08-04 Discord scan):

- Fix (CRITICAL, player scaling inert): the player/group power delta was gated behind a start ring that
  reused the distance-escalation start radius (shipped default 15000 blocks), so spawn difficulty sat
  at the floor everywhere regardless of player power, and toggling DistanceEscalation changed nothing.
  The ring is now its own orthogonal knob, `OpenWorld.PlayerScalingStartRingBlocks` (global default
  5000.0 = a newbie-protected ring near spawn; per-world overridable, and the three shipped Dungeon of
  Fear rules pin it to 0.0 so instance scaling applies from the first spawn). `/mobscaling inspect`
  gains a player-scaling line (applied / enabled / ring radius / in-ring / distance from spawn) and the
  admin UI exposes the knob on both the global and per-world forms.
- Rework: per-rarity role/group TARGETING lists replace the unfinished Java-side boss special case. A
  rarity's `Families` group gains `ForceGroups`/`ForceRoles` beside the existing Allow/Deny lists
  (force > deny > allow; the strongest matching forced tier wins, and force acts as a floor a stronger
  natural roll can still beat). The shipped `Rarities/Boss.json` now authors
  `Families.ForceGroups: ["Mmoscaling_Bosses"]`, so the boss NPCGroup finally does what its comment
  always promised; an owner overriding Boss.json without ForceGroups deliberately opts out.
- Fix (log spam + wasted tick time): re-adding an already-scaled mob (chunk reload, world transfer)
  threw `IllegalArgumentException: Entity contains component type: ScaledMobComponent` on every
  attempt (reported at 13k+ occurrences in one session, 46% of that log's warnings) and aborted the
  HP reconcile that re-add exists to run. The stamp is now an idempotent replace, so a re-add
  reconciles quietly (deterministic per-mob seed: same mob, same roll) and the reconcile-on-load
  design actually works for already-stamped mobs. The caster-kit arm stamp gets the same treatment.
- New: boot-time pack audit (PackDependencyAudit): a pack that authors `Server/MmoMobScaling/*` or
  `Server/NPC/Groups/Mmoscaling_*.json` without declaring the `Ziggfreed:MmoMobScaling` manifest
  dependency is named in the log with the exact missing dependency line, covering both failure shapes
  (a whole-asset-load abort with a misleading engine stack trace, or a silently shadowed override that
  loses the last-pack-wins race). Log-only, never a boot failure, and runs even when scaling is
  disabled.
- New: reference-existence validation in ScalingContentValidator: a dangling rarity/variant
  AuraEffectId, affix EffectId, BonusDropList, Families Allow/Deny/Force group id, or roster role id
  now WARNs by name at load (previously an unresolvable effect id was one runtime warn-once and a
  silent no-op, and a misleading engine boot crash got blamed on it). Degrades to permissive when an
  engine store cannot answer.
- Fix (config discoverability): the per-world `worlds/` owner folder is scaffolded up front with a
  README describing the one-file-per-world convention, and every boot logs the absolute owner-config
  paths (`mob-scaling config: ...`), closing the "the mod never creates its config" reports (the
  paths are relative to the server working directory, which is what made them hard to find).
- New: validator findings for shadowable per-world Match patterns: a rule whose match core is a strict
  prefix of another's (fragile if the longer rule is ever removed) and two rules with equal-length
  cores (a silent insertion-order tie-break) each WARN.
- Docs: CURSEFORGE/README gain "Where the files live", "Extension packs" (copy-pasteable manifest
  dependency, case sensitivity, zip-root rule), and a third-party nameplate compatibility note (a mod
  that flattens the localized display-name Message without substituting params prints the raw
  `{rarity} {base}` template; this mod deliberately never writes the overhead nameplate, and the
  crosshair inspector HUD renders rarity/variant correctly regardless).
- New: demo content - Demo_Boss_Caster.json arms the shipped Fire Dragon boss with the MMO's fireball
  (~14s cadence, a Hurt-flinch Windup on the Status slot since the dragon rig ships no cast animation),
  the NPC-only dragon_arcana ice bolt (~20s, no Windup - its native chain carries its own animation
  nodes), and a dodge NativeChain pointing at this mod's OWN Attack-tagged
  Mmoscaling_Demo_Dodge root (arms out of the box, and never risks the engine classifying a dodging
  PLAYER as attacking, which is why the MMO's player-facing MMO_Dodge stays untagged). Plus a fully
  native CAE_Mmoscaling_Caster_Demo.json/Mmoscaling_Caster_Demo.json pair (spawn via
  /npc spawn Mmoscaling_Caster_Demo) demonstrating the same periodic-cast idea authored entirely as
  native asset content, zero Java.
- Fix (compat): MobScalingDamageFilter's Order.BEFORE dependency retargeted from CombatXpEventSystem
  (moved to the MMO's Inspect damage group this cycle) to CombatDamageEventSystem (confirmed still
  Filter-group) - SystemDependency resolves by class across the whole graph regardless of group, so
  the old dependency was not actually broken by the move, just redundant and pointed at a system no
  longer in this phase; retargeting removes that latent fragility.
- Changed (HARD BREAK, upgraders): the lang file is renamed `scaling.lang` -> `mmomobscaling.lang` in
  all nine locales, so the namespace the engine derives from the filename becomes `mmomobscaling.`
  instead of the bare `scaling.` this mod shipped in 1.0.0 and 1.0.1. Every authored key stays the
  same (`rarity.rare.name`, `command.usage`, `ui.title`, and so on) - only the file's own basename
  and the fully-resolved prefix change, so `scaling.rarity.rare.name` reads as
  `mmomobscaling.rarity.rare.name` from this version on. This brings the mod in line with every
  other mod in the family, each of which namespaces its lang file by its own mod id rather than a
  bare English word a second mod could equally claim. A server owner with a translation overlay or
  a third-party pack authoring against the old `scaling.*` ids needs to re-point them at
  `mmomobscaling.*`.

## 1.0.2 (superseded by 1.1.0)

An in-game admin config UI (`/mobscaling ui`) with full persistence for every runtime tuning path,
plus per-world config reworked onto its own files with inheritance and more per-world knobs. Requires
MMO Skill Tree 1.5.0+ (the build that removes the old WorldRules mob-scaling baseline - update BOTH
together) and Ziggfreed's CommonLib 1.3.0+.

- Change (default tuning): softened the DEFAULT difficulty->stat curve and pushed distance escalation
  farther out, so a scaled mob is tankier than it is bursty and the deep-frontier ramp starts later.
  `Settings/Default.json` `StatCurve.OutDamagePerPoint` 0.04 -> 0.01 (the OUTGOING-damage bonus per
  difficulty point; the HP + incoming-reduction slopes and every cap are unchanged) and
  `DistanceEscalation.StartDistanceBlocks` 5000 -> 15000. The `Casual` (0.02 -> 0.0025) and `Hardcore`
  (0.08 -> 0.05) presets get the same out-damage softening; `Playtest` is deliberately left steep.
- Change (dungeon defaults rework): the shipped Dungeon of Fear world files are flat + self-contained now -
  the shared `DungeonOfFear_Base` Parent file is removed. `DungeonOfFear_I`/`II` turn scaling OFF outright
  (`Enabled:false`) in their instances; `DungeonOfFear_III` keeps player/group scaling ON with distance
  escalation OFF (inlined, no longer inherited from the base). III's effective behaviour is unchanged; I/II
  now disable scaling entirely instead of only pinning player-scaling off.
- Fix (Kweebec match): `KweebecNightmare.json`'s per-world Match is `*KweebecNightmare_*` (contains) so it
  catches the real instance worlds, whose live names carry BOTH a leading `instance-` prefix AND a random
  suffix (`instance-KweebecNightmare_Chase_Dread-<uuid>`). The old trailing-`*` prefix (`KweebecNightmare_*`)
  never matched those, so the scaling-off kill-switch silently did nothing there. Needs Ziggfreed's CommonLib
  1.3.0+, whose `WorldNameMatcher` carries the suffix/contains match forms (manifest requirement `>=1.3.0`).
- Change (schema rework): per-world settings move OUT of the inline `WorldOverrides` array into their own
  keyed asset files, `Server/MmoMobScaling/Worlds/*.json` (packs/jar) + a scanned owner dir
  `mods/MmoMobScaling/worlds/*.json` (one file per world rule; filename = id; a bare body is canonical,
  the pack-style `Payload` wrapper is accepted). A file carries the world `Match` selector (same exact >
  longest-`*`-prefix > `*` precedence) and may carry a top-level `"Parent": "<other-file-id>"`: unset
  leaves walk up the Parent chain (cross-layer, cycle-guarded, resolved by CommonLib's new
  `JsonParentResolver`), and whatever is still unset falls through to the GLOBAL effective settings - so
  a file is a partial overlay by default and a full custom definition when fully authored. A file with no
  `Match` is a pool-only BASE others inherit from. Layering across jar/pack/owner is replace-by-id
  (inheritance is Parent's job); everything decodes through ONE schema authority (`WorldSettings.CODEC`).
  A legacy owner `WorldOverrides` array (shipped in 1.0.1) MIGRATES automatically on first boot: each
  entry becomes its own `worlds/<match>.json` (the old top-level `PlayerScalingEnabled` moves under
  `OpenWorld`) and the array is stripped from the owner file. The old inline array on presets no longer
  decodes.
- New: per-world kill-switch + baseline floor, absorbed from the MMO jar. A world file's `Enabled: false`
  turns scaling off in matching worlds (residue is stripped on load); `Difficulty.Floor` is the
  world-baseline difficulty floor under the zone/biome `Difficulty/*.json` mappings (global default 30.0
  in `Settings/Default.json`). These replace the never-released `WorldRules.MobScaling` group on the MMO
  jar - mob difficulty now has exactly ONE per-world authoring surface (this mod's files). BREAKING pair:
  MMO Skill Tree 1.5.0 removes that group, so update both mods in the same deploy.
- New: the WHOLE `OpenWorld` group is per-world (AggregationMode, GroupDeltaBandWidth,
  OnlyRaiseDifficulty, AllowDifficultyIncreaseOnPartyJoin, LateArrivalBumpFactor, CompositionEnabled,
  PlayerScalingEnabled). `RegionSizeChunks` alone stays global (the region-power grid must stay
  consistent).
- New: a per-world `Pool` group gating what rolls in a world: `Rarities`/`Variants`/`Affixes` each take
  `Allow`/`Deny` id lists (deny wins; absent = allow-all), `Variants.ChanceMultiplier` scales every
  eligible variant's absolute chance (0 = no variants in that world), and `Affixes.ExtraSlots` rolls
  bonus affixes on top of the rarity/variant slots (a plain, variant-less mob has no host, so extras are
  a no-op there). So an endgame dungeon can spawn only Elite+, roll double variants, and stack an extra
  affix - per world, no Java.
- New: per-world HUD visibility. A world file's `ZoneHud.Enabled: false` / `InspectorHud.Enabled: false`
  hides that overlay in matching worlds as players cross world borders (a per-world `true` cannot
  re-enable a globally-off HUD; the global toggle stays the cheap fast path).
- New: `/mobscaling worlds` lists the folded per-world files (id, Match or base-only, Parent,
  owner-vs-shipped origin, kill-switch state).
- Change: the shipped dungeon defaults moved from `Settings/Default.json`'s inline array to jar world
  files that exercise the new inheritance: `Worlds/DungeonOfFear_Base.json` (a pool-only base:
  escalation off) inherited by `DungeonOfFear_I/II/III.json` (I + II also pin player scaling off), plus
  `Worlds/KweebecNightmare.json` (`Enabled: false` - absorbed from the MMO jar's old default).
- New: an in-game admin config page, `/mobscaling ui` (admin only). Four tabs - Global (Enabled, active
  preset, Intensity, RaritySpawnChance, player/group scaling, difficulty caps, distance escalation), Zone
  HUD and Mob Inspector HUD (enable, position preset + pixel offsets, sub-toggles, inspector range), and
  Worlds (a per-world FILE editor: add / edit / delete `worlds/*.json`, each row badged shipped vs
  owner override, with file name / Match / Parent / kill-switch / baseline floor / intensity / rarity
  chance / caps / player scaling / escalation). Global + HUD edits write the owner file
  `mods/MmoMobScaling/mob-scaling.json` and refold live; HUD + preset edits apply to all online players
  with no reconnect. `Enabled` shows a "takes effect on restart" note (the zero-cost registration gate
  registers systems at startup).
- Change (rework): the admin config page is now SPEC-DRIVEN over the new ziggfreed-common `ui/form`
  engine (`FieldSpec` + `SettingsForm`, five `Pages/ZigForm*Row.ui` templates) instead of ~24
  hand-written per-knob `.ui` rows + a matching per-field `EventData` codec key - adding a knob later is
  one `FieldSpec` line plus one lang key. `EventData` collapses to five keys (`Action`/`Tab`/`WorldId`/
  `Field`/`@Value`). FULL knob coverage across all four tabs: Global gains the whole
  `OpenWorld` group (aggregation, region size, band width, only-raise, party-join, late-arrival,
  composition), and the six `StatCurve` leaves; the per-world editor gains the same `OpenWorld` group,
  the six `StatCurve` leaves, the `Pool` group (rarity/variant/affix allow-deny + variant chance + extra
  affix slots), and per-world Zone/Inspector HUD visibility - every CONSUMED per-world knob is editable
  now (the rest of each HUD group - position, offsets, range, the zone/biome name-key prefixes - and
  `OpenWorld.RegionSizeChunks` decode on `WorldSettings` but apply GLOBALLY on purpose, so they stay off
  the per-world form: a HUD position is a per-viewport concern, not a per-world one, and the
  region-power grid size must stay identical across worlds). The Worlds
  tab is a TWO-PANEL layout (world list left, add/edit editor right, on a wider 960x680 frame) instead
  of one long single-column scroller. The page NEVER reopens itself now: every event answers with a
  partial `sendUpdate` (world-list changes clear + re-append + rebind in the same update, the official
  shared-source `ChangeModelPage.buildModelList` pattern), so editing no longer resets scroll position.
  Every hint/note WRAPS (the `ZigFormNoteRow` template) instead of truncating. Each tab (Global/Zone
  HUD/Inspector HUD) collects + saves as ONE unit via `SettingsForm.collectLeaves`; a toggle (`Enabled`,
  `PlayerScalingEnabled`, HUD enable/portrait/show-location, etc.) is instant-persist on click via a
  small `id -> ToggleDef` lookup table, not a chain of switch arms. A `collectLeaves` validation failure
  now NAMES the failing field (`scaling.ui.status.invalid_field`, a nested client-resolved `Message`
  param, validated against the official `PortalDeviceActivePage` precedent).
- Change: the per-world editor seeds from the file's AUTHORED body, not the `Parent`-merged effective
  view (`WorldSettingsConfig.authoredById`, a new accessor over a newly-tracked pre-merge raw-body pool;
  `foldedView()`/`effectiveById()` are both backed by the SAME post-merge map, so neither was safe to
  seed an editor from once the exposed knob count grew to ~40 - saving back would have materialized
  every inherited leaf into the child file and silently broken inheritance). A blank field / the Inherit
  tri-state now round-trips faithfully across a save.
- New: full persistence for the live commands. `/mobscaling intensity`, `/mobscaling hud`, and
  `/mobscaling preset` now SAVE to the owner file (they were runtime-only in 1.0.1, lost on restart). The
  UI and the commands share ONE write-back path (`config/MobScalingOwnerWriter` -> the owner file ->
  `MobScalingConfig.refreshFromDisk`), so a change made either way sticks and applies live.
- Fix: `/mobscaling` now takes the subcommand as a REQUIRED positional arg. It was optional, which (the
  Hytale parser binds optional args by NAME, not position) meant a bare token like `/mobscaling hud`
  never bound to the subcommand and silently fell through to `inspect`. The follow-on tuning values stay
  optional, so they are passed by name: `--hudTarget=<zone|inspector>`, `--hudValue=<on|off|POSITION>`,
  `--hudOffsetX`/`--hudOffsetY`, `--presetName`, `--intensity`.
- Change: requires Ziggfreed's CommonLib 1.3.0+ - the shared settings-UI toolkit the admin page consumes
  (`util/JsonOverrideWriter` owner-file write-back, `ui/hud/HudPosition` layout value, `ui/SettingsUiUtil`
  form binding, `Pages/ZigListRow.ui` row). The mod's private `hud/HudPosition` copy is retired in favour
  of the lifted common one (identical behavior).
- Change (round-2 admin-UX hardening, in-game feedback): removed the `PresetMode` dropdown from the
  Global tab. Verified nothing consumes `MobScalingConfig.getPresetMode()` outside the schema
  (`MobScalingSettingsAsset`) and the config fold; the codec field + fold stay for an owner who still
  sets it by hand, only the dead UI row + its lang key (`ui.global.preset_mode`) are gone.
- Change: the Global tab is reordered difficulty-first: `Enabled` note, Difficulty (floor + caps), the
  stat Curve (`Intensity` now leads it, as the curve's global slope multiplier), rarity + distance
  escalation (`RaritySpawnChance` leads it), Open World group last. `ui.global.esc_header` reworded to
  "Rarity & distance escalation" to match.
- New: a live skeleton-preview panel beside the Global settings. The Global tab is now two-panel
  (`LayoutMode: Left`): the existing form on the left, a new "Preview: Skeleton" column on the right
  showing a PLAIN mob (no rarity/variant) run through the CURRENT (unsaved) Global-form difficulty stat
  curve at five evenly-spaced sample difficulties between the live `MinCap`/`MaxCap`
  (`MobScalingAdminPage.refreshPreview`, mirroring `MobScalingConfig.buildCurve` - package-private there,
  so the `MobScaleFold.DifficultyStatCurve` is constructed directly in the page). Shows HP / outgoing-
  damage multipliers and incoming-damage-reduction percent per sample, formatted compactly
  (`x1.8`/`-22%`); refreshes on every Global-form `field`/`press`/`saveGlobal`/`selectPreset` event via a
  small preview-only partial update (never re-pushing the form's own values). Damage stays factor-only
  (base attack damage lives in weapon/attack assets, out of scope).
- New: the HP cell shows the skeleton's REAL health, not just the multiplier (`x2.6 (239)`), via a new
  `pages/RoleBaseHealthResolver`. The role registry read is public and entity-free
  (`NPCPlugin.getIndex` -> `getRoleBuilderInfo` -> `BuilderInfo.getBuilder()` returns the already-parsed
  `Builder<Role>` off the loaded-asset registry); the engine's OWN `BuilderManager.validateAllSpawnableNPCs`
  proves the EVALUATION is equally entity-free (`new ExecutionContext(builder.getBuilderParameters().createScope())`).
  The one non-public hop - `BuilderRole`'s `protected final IntHolder maxHealth` field, whose public
  accessor demands a `BuilderSupport` (and so a live entity) purely as an API-surface artifact - is
  bridged with a cached, `setAccessible`-once reflective field read, evaluated via the SAME entity-free
  pattern (`holder.rawGet(null)` for a static value, mirroring the engine's own `IntHolder.readJSON`;
  a real `ExecutionContext` for a `"Compute"`-driven one). Fully `try/catch(Throwable)`-guarded;
  memoizes both a success and a failure per role name for the process lifetime, so a broken read
  degrades silently to multipliers-only once, never retried per keystroke.
- New: inline help text on every setting. Every leaf-bearing field/toggle spec across all FOUR forms
  (Global, Zone HUD, Inspector HUD, Worlds; ~80 fields) now carries a `.withHint(...)` - one qualitative
  sentence, no digits - rendered under the row via the ziggfreed-common `ui/form` engine's `#Hint`
  sub-label (`FieldSpec.withHint`/`SettingsForm.applyHint`). A world-form field that mirrors an IDENTICAL
  global concept reuses the matching global hint key; a tri-state, a pool gate, or a world-identity field
  gets its own key (54 new `scaling.ui.hint.*` keys total).
- New: the per-world editor shows what a blank/Inherit field currently inherits. On edit (and after a
  save re-seeds), every blank/Inherit field's hint gains a computed "Inherits: {value}" line (a NEW
  `scaling.ui.world.inherits` key) on top of its static help text, resolved from
  `WorldSettingsConfig.effectiveById` (the `Parent`-merged view) falling back to the GLOBAL live
  `MobScalingConfig` value per leaf (a Pool gate's global reads as allow-all / an empty deny list /
  neutral scale / zero extra slots; a per-world HUD tri-state's global is the zone/inspector enabled
  flag). Composed via `Message.join(staticHint, Message.raw("\n"), inheritsMsg)` (no new wrapper lang key
  needed). An authored field shows the static hint alone; clearing the editor resets every hint to
  static-only.
- Change: world-list rows wrap instead of truncating. A new MOD-LOCAL `Pages/MmoscalingWorldRow.ui`
  (modeled on ziggfreed-common's `Pages/ZigListRow.ui`, same child ids) replaces the shared row for this
  page's 300px list panel: `#Title` wraps to two lines (no fixed title height) instead of cutting off a
  long world id / match pattern, `#Badge`/`#EditBtn`/`#RemoveBtn` narrow to leave room. `buildWorldList`
  is unchanged beyond the template-path constant.
- Fix (round-3 admin-UX hardening, in-game validation): the absolute-HP resolver silently fell back to
  multipliers-only for the LIVE skeleton, because the vanilla `Skeleton` role is a `Variant`
  (`"Type": "Variant", "Reference": "Template_Intelligent", "Modify": {"MaxHealth": 92, ...}`), not a
  plain `BuilderRole` - `RoleBaseHealthResolver`'s old `instanceof BuilderRole` gate rejected it outright.
  The resolver now mirrors the engine's own `BuilderManager.validateAllSpawnableNPCs` for a
  `BuilderRoleVariant`: seed an `ExecutionContext` from the variant's OWN builder parameters, fold the
  WHOLE `Modify` chain via the variant's public `createModifierScope(ExecutionContext)`, walk the SAME
  reference chain (`getReferenceIndex()`/`getBuilderManager()`/`tryGetCachedValidRole()` - all public, no
  second reflective field needed) to the TERMINAL template `BuilderRole`, then evaluate ITS `MaxHealth`
  holder against the folded scope (`Template_Intelligent.json`'s `"MaxHealth": {"Compute": "MaxHealth"}`
  is a `Compute` expression, so the old `isStatic()`/`rawGet(null)` shortcut would NPE on it - both role
  shapes now always evaluate via `rawGet(ctx)`). The preview now shows `x4.2 (386)` for the skeleton
  instead of `x4.2` alone.
- New: observed-spawn ground truth backs the resolver too. `RoleBaseHealthResolver.recordObserved` lets
  `event.MobScalingSpawnHook` feed it a role's ACTUAL pre-scale base max health, read off the balanced
  `EntityStatMap` right before this mod's own `mmoscaling_hp` modifier applies - live truth that already
  includes native balancing plus whatever any earlier-ordered mod stacked on top. `baseMaxHealth` checks
  this cache before the reflective template read, so once any mob of a role has spawned this session, the
  preview's absolute HP for that role reflects the table's actual live numbers, not just the authored
  template value.
- Change: the per-world editor's "Inherits: X" hint line now renders WHITE + BOLD end to end (the label
  and the substituted value alike), instead of the same muted grey as the static hint text above it.
- New: a manual difficulty probe in the Global-tab preview. A "Try a difficulty" field below the five
  fixed samples previews ONE more row at whatever difficulty (`>= 1`) you type, UNCLAMPED to the live
  Min/Max cap band - a way to sanity-check one specific number without retuning the caps first. Wired
  outside `globalForm` entirely (its own `"previewD"` event; nothing to persist), refreshed alongside the
  five fixed rows on every Global-tab change and on its own keystroke; hidden while blank or unparseable.

## 1.0.1

Per-world / per-instance tuning plus a live intensity dial. Requires MMO Skill Tree 1.5.0+ and
Ziggfreed's CommonLib 1.2.0+.

- New: PER-WORLD settings overlays. `Server/MmoMobScaling/Settings/*.json` gains a `WorldOverrides`
  array; each entry is a world-name `Match` (the SAME fuzzy matching as the MMO's WorldRules: exact >
  longest trailing-`*` prefix > bare `*`, case-insensitive) bound to a PARTIAL settings body that
  overlays the global fold for matching worlds at spawn time. A matched world may set its own
  `Intensity`, `RaritySpawnChance`, `PlayerScalingEnabled`, and the full `Difficulty` group (caps +
  `DistanceEscalation` + `StatCurve`); every unset leaf inherits the global settings. Layers
  CONCATENATE (deduped by `Match`, owner > preset > jar), so an owner file ADDS to / overrides shipped
  defaults without re-authoring the whole list. Resolved through a new `world/WorldOverrideMatcher` + a
  `config/SpawnScalingSettings` view the spawn hook, the HUD, and `/mobscaling inspect` all read, so a
  dungeon reports its ACTUAL numbers.
- New: `PlayerScalingEnabled` toggle (a global `OpenWorld` leaf + a per-world override). `false` skips
  the player/group power delta entirely, pinning a world to its escalated floor, the switch a
  fixed-difficulty authored dungeon uses.
- New: numeric `Intensity` dial (replaces the old inert string label). A multiplier (default `1.0`,
  clamped `>= 0`) on the difficulty-to-stat curve slopes (how tanky mobs are + how hard they hit),
  bounded by the existing per-factor caps; it does not touch rarity/affix magnitudes. Runtime-tunable
  with `/mobscaling intensity [multiplier]` (runtime only; the owner file's `Intensity` is the
  persistent authority). A world with an authored per-world `Intensity` override is unaffected.
- New: shipped defaults for three authored dungeons. `Default.json` ships `WorldOverrides` for
  `instance-dungeon_of_fear_i/ii/iii`: player/group scaling OFF for I and II (fixed difficulty), and
  distance-from-spawn escalation OFF for all three. The `_i*`/`_ii*`/`_iii*` prefixes self-disambiguate
  via longest-prefix and also catch suffixed instance worlds.
- Change: the four settings presets (Default/Casual/Hardcore/Playtest) no longer carry a string
  `Intensity` (their difficulty lives in their `StatCurve`); `Intensity` folds to a neutral `1.0`
  unless authored.

## 1.0.0

The first release of MMO Mob Scaling, a standalone open-world mob difficulty-scaling companion to the
MMO Skill Tree mod: open-world mobs scale to the players around them (a high-power group meets tougher,
rarer, affixed enemies; a lone newcomer is never overwhelmed). Everything is data-driven Hytale assets,
so any of it can be retuned per file or extended from a content pack. Requires MMO Skill Tree 1.5.0+ and
Ziggfreed's CommonLib 1.2.0+.

- New: LAYERED open-world difficulty. Every hostile mob is scaled to a difficulty resolved from three
  layers: Hytale's own worldgen ZONE and BIOME floors (`world/ZoneDifficultyResolver`, memoized
  `Zone.name()`/`Biome.getName()`, one query per chunk, over authored `Server/MmoMobScaling/Difficulty/*.json`
  Pattern-A mappings, precedence zone exact > zone `*` > biome exact > biome `*` > the `WorldRules` world
  baseline; the jar ships the Zone0..Zone4 gradient 3/8/22/38/55 + a zone wildcard + an Ocean1 biome example),
  a distance-from-spawn ESCALATION (past a configurable radius every `BlocksPerPoint` blocks adds +1 difficulty
  capped at `MaxBonus` AND raises rarity chance via `RarityChancePerPoint`, under `Difficulty.DistanceEscalation`),
  and the real POWER of the players standing in the region.
- New: ZONE + PROXIMITY hybrid region buckets. The group-power aggregate is keyed by the native zone name
  plus a chunk sub-grid cell (`RegionPowerTracker.RegionKey`), so a zone border always splits buckets while
  the delta stays local inside a huge zone; a world with no native worldgen falls back to the pure chunk grid.
  The cached per-region player-power scalar (maintained on player region-cross by `MobScalingPresenceSystem`,
  an O(1) spawn-path read, never a per-spawn scan) resolves through ziggfreed-common's `ScalingEngine` over the
  world floor, band-clamped by `OpenWorld.GroupDeltaBandWidth` + `Difficulty.MinCap`/`MaxCap`.
- New: player power is the MMO jar's real multi-pillar formula (combat + tree stat rewards + abilities +
  mastery + achievements per `PowerLevel.json` weights, read per region-cross from
  `MMOSkillTreeAPI.getPowerLevel`), so region difficulty tracks a player's BUILD, not just the max combat level.
- New: RARITY ladder + affixes. Rare / Epic / Legendary + a forced Boss tier, each a coloured nameplate, an
  aura tint, stat multipliers, affix slots, bonus XP, and a bonus loot table; five affixes ride native Hytale
  `EntityEffect` assets (Armored, Stalwart = knockback immunity + HP, Swift = native move-speed, Vampiric,
  Freezing = victim slow). Rolls are DETERMINISTIC per mob UUID, so a chunk reload reproduces the same mob. The
  rarity aura owns the single body-tint channel (blue/purple/gold); affix effects carry no competing tint.
- New: PER-FAMILY gating for rarities AND variants. A rarity tier (or a variant, below) can be whitelisted /
  blacklisted to mob FAMILIES via a nested `Families` block (`AllowGroups`/`DenyGroups` = native `NPCGroup`
  tagset ids, `AllowRoles`/`DenyRoles` = role-name globs like `Spider*`, case-insensitive; deny wins, an absent
  block = every mob eligible). The gate only NARROWS the roll and consumes no RNG (per-mob determinism
  unchanged), reusing the same native `hasTagInGroup` classification the boss/excluded tagsets use. New
  `family/` package (`FamilyFilter`/`FamilyGlob` pure + `MobFamilyMatcher` engine); a validator flags a
  self-contradictory filter (deny `*`, or an id in both allow + deny), and the matcher warns once on an unknown
  NPCGroup id.
- New: mob VARIANT overlays (`Server/MmoMobScaling/Variants/*.json`). A variant is a SECOND, independent roll
  axis that STACKS on top of the base rarity, so you get "Horrific Epic Spider" (epic base * horrific overlay).
  A variant carries its own absolute-`Chance` roll gate, `MinDifficulty` band, a `Families` filter, stat
  `Multipliers` that stack multiplicatively on the rarity, its own affix slots + allow-list, an optional
  `BonusDropList` (death loot stacks on the rarity's), an optional `AuraEffectId` fallback tint (applied only
  when the base rarity has no aura), and a `Roll.AllowedRarities` requires-rarity gate. Affixes gain an
  `AllowedVariants` gate (mirroring `AllowedRarities`) so an affix can be variant-exclusive. At most one variant
  lands per mob; a variant has no aura/tint (identity is the `{variant} {rarity} {base}` name frame + its
  affixes). New `variant/` package (`Variant`/`VariantRoster`) + `VariantConfig` fold + a `Variants` asset
  store. Ships a worked example: a spider-only `horrific` variant granting a unique `venomous` affix (gated to
  `horrific`, so it is transitively spider-only), with a `Mmoscaling_Drops_Horrific` bonus-loot table and a
  green `Mmoscaling_Aura_Horrific` fallback tint.
- New: risk pays. A scaled kill grants bonus MMO XP through the MMO's own kill path (a
  `MMOSkillTreeAPI.registerMobKillXpMultiplier` provider: kill XP only, an underdog bonus for fighting above
  your weight, an anti-runaway hard cap) and pulls extra loot from its tier's native `ItemDropList`
  (`Rarity.BonusDropList` -> `Server/Drops/MmoMobScaling/Mmoscaling_Drops_*`, owner/pack overridable), spawned
  as real ground items at the corpse mirroring vanilla `DropDeathItems` timing.
- New: NPCGroup BOSS classification. Authored native tagsets `Server/NPC/Groups/Mmoscaling_Bosses.json` (forces
  the weight-0 `boss` rarity tier + its aura) and `Mmoscaling_Excluded.json` (the owner opt-out list, wins over
  everything). The forced boss tier bypasses the rarity roll and the family gate.
- New: rarity-decorated display names. A scaled mob's `DisplayNameComponent` is re-stamped with the localized
  `name.decorated` frame (nested rarity + base-name messages, never joined English order), so death messages /
  kill feed read "Epic Zombie"; a player-named `PersistentDisplayName` is never touched.
- New: two player-facing HUD overlays, driven by one per-player ticking system (`MobScalingHudSystem`,
  lazy-install self-heal, skip-if-unchanged pushes): a ZONE DIFFICULTY card (`ZoneDifficultyHud`,
  `Hud/MmoscalingZoneHud.ui`: local effective difficulty, a coloured threat tier relative to the viewer, the
  viewer's own power + the tracked group power, the friendly in-game zone name) and a MOB INSPECTOR
  (`MobInspectorHud`, `Hud/MmoscalingMobInspector.ui`: the mob under the crosshair, its portrait, name, coloured
  rarity + variant tags, scaled difficulty, a live `current / max` HP bar, and its affixes as icon chips). Both
  restyled to MATCH the native Hytale objective HUD (the `ObjectivePanelContainer` frame + native palette +
  font); both toggle and reposition live via `/mobscaling hud`, and honor the MMO's per-player `/mmohud` toggles.
- New: `/mobscaling` admin command (`hytale:Admin`): `inspect` (report the difficulty inputs + breakdown at
  your position), `preset` (switch live between Default / Casual / Hardcore / Playtest), `hud` (live-tune the
  overlays across all online players), `purge` (strip ALL scaling residue - the HP modifier + `Mmoscaling_*`
  infinite effects - off loaded mobs, the full-uninstall hatch, registered OUTSIDE the zero-cost gate).
- New: RECONCILE on load. HP + auras converge to the current roll (`HealthUtil.reconcileMaxHealth` + an effect
  sweep) so a floor / rarity / affix retune never strands a stale inflated max or a doubled aura on a saved mob;
  an excluded / world-disabled mob is stripped. (A fully-disabled/uninstalled mod cannot self-heal saved
  residue; run `/mobscaling purge` per world first, see CLAUDE.md.)
- New: the settings fold cross-checks `Difficulty.MinCap`/`MaxCap` against the MMO jar's PowerLevel clamp
  (`MMOSkillTreeAPI.getPowerLevelMin()`/`getPowerLevelMax()`) and warns when the two scales drift; an unreadable
  clamp (older MMO jar) validates clean, advisory only. Content validation runs value-sanity findings over the
  folded rarities / affixes / variants at load (warn, never block).
- New: the zero-cost registration gate. The plugin loads its config in `setup()` and applies a registration
  gate (`MobScalingPlugin.shouldRegisterSystems`): when the config is disabled it registers NO systems and
  returns, so a disabled mod carries no per-tick cost at all.
- New: codec-driven config. The schema + defaults are Hytale asset codecs (Pattern A, PascalCase, NESTED
  sub-object groups, never flat prefixed keys, never Java-baked values): the settings asset
  (`MobScalingSettingsAsset` -> `Server/MmoMobScaling/Settings/Default.json`, groups
  `OpenWorld`/`Difficulty`+`DistanceEscalation`/`ZoneHud`/`InspectorHud`) plus the per-type keyed assets
  `Rarities/`/`Variants/`/`Affixes/`/`Difficulty/`. Owners override any key in
  `mods/MmoMobScaling/mob-scaling.json` (partial allowed, per-leaf overlay); a content pack can override the same
  paths. The settings fold is `owner > pack-store > jar`, so a partial pack override can never silently disable
  the mod, and `RaritySpawnChance` is clamped.
- New: full 9-locale `scaling.lang` (de/es/fr/hu/it/pt-BR/ru/tr alongside en-US), including the rarity / affix /
  variant name keys and the HUD strings.

### Technical

- Standalone Hytale sibling mod; package root `com.ziggfreed.mmomobscaling`, entry point `MobScalingPlugin`.
- Compiles `compileOnly` against the local `MMOSkillTree-1.5.0.jar` dev jar (the frozen 1.5.0 API) while the
  manifest pins the runtime requirement at MMOSkillTree `>=1.5.0` and ZiggfreedCommon `>=1.2.0`. Neither is
  bundled.
- Effect apply via a native `RefSystem.onEntityAdded` (synchronous add-pipeline CommandBuffer); the general
  damage multiply is a frozen `DamageModule` filter; the rarity HP multiplier + the Stalwart affix HpDelta stay
  on `HealthUtil` (the effect path lacks `maximizeStatValue`, and an effect-based +maxHP would spawn the mob
  damaged + double-apply); Vampiric per-hit lifesteal stays mod-side in `MobScalingOnHitSystem` (no native
  on-hit-dealt sensor).
- Consumes ziggfreed-common 1.2.0: the domain-free `scaling/` engine (`ScalingContext`/`ScalingEngine`),
  `HealthUtil.reconcileMaxHealth` + the ref-less `scaleMaxHealth(Holder,...)`, `EntityIdentifierUtil`
  `roleName`/`roleIndex`, and `EntityEffectService.apply` (asset-authoritative).

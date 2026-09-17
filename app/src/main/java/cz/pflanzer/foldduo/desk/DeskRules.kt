package cz.pflanzer.foldduo.desk

import cz.pflanzer.foldduo.pose.FoldPose

/**
 * B47 "Stůl": pure decisions behind desk mode (Fold 8 has no Flex mode, this replaces it in the
 * launcher). No Android types, so this runs as plain JUnit (DeskRulesTest), same convention as
 * standby/StandByRules.kt.
 */

/**
 * 17. 9. (Tom: "při otevření vidím nějaký budík s hodinami vlevo"): the Stand pose alone also
 * matches a phone merely held upright in the hand while opening. The desk is the laptop pose —
 * the hinge must sit in its middle step (90), bottom half down, top half up — so [shouldEnter]
 * and [shouldExit] take `hingeMid` (HingeStep.Mid) as a hard condition.
 */
/** The "Desk when standing" setting (StandBy page): Off never triggers, Desk shows the full
 * split face+deck below, StandByFace shows only the read-only face, full screen. */
enum class DeskMode { Off, Desk, StandByFace }

object DeskRules {
    /** Stand must hold this long before desk mode triggers (task spec, B47). */
    const val HOLD_MS = 1_200L

    /**
     * True once [pose] has been [FoldPose.Stand][cz.pflanzer.foldduo.pose.FoldPose.Stand] for at
     * least [holdMs] and the setting is not [DeskMode.Off]. Both [DeskMode.Desk] and
     * [DeskMode.StandByFace] enter through this same gate — only what is then shown differs.
     */
    fun shouldEnter(pose: FoldPose, mode: DeskMode, holdMs: Long, hingeMid: Boolean = true): Boolean =
        mode != DeskMode.Off && pose == FoldPose.Stand && hingeMid && holdMs >= HOLD_MS

    /**
     * Leaves the moment the pose is no longer Stand, the setting was turned off while showing, or
     * the user tapped the close ("×") affordance — whichever comes first.
     */
    fun shouldExit(pose: FoldPose, mode: DeskMode, closeTapped: Boolean, hingeMid: Boolean = true): Boolean =
        closeTapped || mode == DeskMode.Off || pose != FoldPose.Stand || !hingeMid
}

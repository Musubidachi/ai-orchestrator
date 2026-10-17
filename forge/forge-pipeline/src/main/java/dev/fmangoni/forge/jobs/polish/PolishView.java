package dev.fmangoni.forge.jobs.polish;

import dev.fmangoni.forge.design.DesignBoard;

/**
 * What the Polish page is shown: which project, whether designs are being drawn, why it cannot be
 * polished if it cannot, and the board.
 *
 * @param running whether a job is running on the project — the request for designs, or anything else
 * @param blocked why designs cannot be asked for or built now, or blank
 * @param buildAs what pressing Build does: build it now, build it as an enhancement, or keep the
 *                pick for when the project finishes
 * @param kept    whether the board's pick was kept for later rather than built
 * @param board   the designs on offer, or null when nobody has been asked
 */
public record PolishView(String id, String title, boolean running, String blocked, String buildAs,
                         boolean kept, DesignBoard board) {
}

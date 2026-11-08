/*
 * What a waiting project waits for, and the one time that counts.
 *
 * Shared by the extras that say when a project will start, the waiting card (status.js) and the next
 * 24 hours (nextday.js), so the two can never give different times. It shows nothing itself. Like
 * every extra (see ui/CLAUDE.md) it is attached by its tag in index.html; it reads no page and no
 * DOM, and gives the page one name, ForgeWait.
 */
(() => {

const TURN = /^waiting for (\S+) to be (?:accepted first|approved)/;
const NAMED = /^([A-Z]+) (?:reviews this|is out of quota|is unavailable)/;

class Wait {
  constructor(j, closed, now) {
    this.j = j;
    this.closed = closed;
    this.now = now;
    const turn = TURN.exec(j.blockedReason || "");
    this.earlier = turn ? turn[1] : "";
    this.kind = turn ? "turn"
      : (j.lastError || "").startsWith("waiting to be reviewed") ? "review"
      : j.phase === "SUSPENDED" || NAMED.test(j.blockedReason || "") ? "quota" : "other";
    this.vendor = this.kind === "review" ? this.reviewer()
      : this.kind === "quota" ? this.named(j.blockedReason) || j.assignedTo || "" : "";
    this.window = this.backAt(this.vendor);
    this.until = this.timeThatCounts();
  }

  /* The reviewer it waits for: the one the server names, else whichever is back soonest. */
  reviewer() {
    const soonest = this.reviewers()[0];
    return this.named(this.j.reviewBlockedReason) || (soonest ? soonest.vendor : "")
      || this.named(this.j.blockedReason);
  }

  named(reason) {
    const found = NAMED.exec(reason || "");
    return found ? found[1] : "";
  }

  /* The closed vendors that could review it, soonest back first. No builder reviews its own work. */
  reviewers() {
    return Object.values(this.closed)
      .filter(c => c.vendor !== this.j.assignedTo && c.until > this.now)
      .sort((a, b) => a.until - b.until);
  }

  /* When a closed vendor is back; null for one that is open. */
  backAt(vendor) {
    const c = vendor ? this.closed[vendor] : null;
    return c && c.until > this.now ? c.until : null;
  }

  /* A review may start as soon as any reviewer is free, whatever the project stored. Anything else
     needs both its stored retry time and its vendor's window behind it. */
  timeThatCounts() {
    if (this.kind === "review") return this.window;
    if (this.kind === "turn") return null;
    const times = [this.stored(), this.window].filter(Boolean);
    return times.length ? new Date(Math.max(...times)) : null;
  }

  /* The retry time the project stored when it stopped, while that is still ahead. */
  stored() {
    const at = this.j.retryAfter ? new Date(this.j.retryAfter) : null;
    return at && at > this.now ? at : null;
  }

  /* Whether the time that counts is the vendor's own, rather than one the project stored. */
  vendorsTime() {
    return !!this.window && !!this.until && this.window.getTime() === this.until.getTime();
  }

  /* The server's own sentence, said instead whenever the ledger gives no time. */
  serverSays() {
    return this.kind === "review" ? this.j.reviewBlockedReason || this.j.blockedReason : this.j.blockedReason;
  }
}

window.ForgeWait = Wait;

})();

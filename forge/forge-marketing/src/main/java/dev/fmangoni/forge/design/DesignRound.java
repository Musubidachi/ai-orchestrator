package dev.fmangoni.forge.design;

import dev.fmangoni.forge.agent.model.AgentKind;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One request for designs while the vendors answer it, each on a thread of its own.
 *
 * <p>Every answer is written to the board as it arrives, so the page shows each vendor's designs as
 * soon as that vendor is done rather than when the slowest one is. Options and notes are both kept
 * in vendor order however the answers interleave, so the same request always reads the same way
 * down the page.
 */
final class DesignRound {

    private final Instant asked;
    private final String note;
    private final Path boardFile;
    private final Map<AgentKind, List<DesignOption>> offered = new EnumMap<>(AgentKind.class);
    private final Map<AgentKind, List<String>> notes = new EnumMap<>(AgentKind.class);
    private final List<String> about = new ArrayList<>();

    /** The design this request started from, first on the board and still pickable, or null. */
    private final DesignOption liked;

    /** The kind of extra asked for, or blank for looks that replace the current one. */
    private final String extra;

    /** Set once the request is over. A vendor answering after that must not reopen the board. */
    private boolean closed;

    DesignRound(Instant asked, String note, Path boardFile, DesignOption liked, String extra) {
        this.asked = asked;
        this.extra = extra == null ? "" : extra;
        this.note = note;
        this.boardFile = boardFile;
        this.liked = liked;
        board(DesignBoard.ASKING).save(boardFile);
    }

    /** Why a vendor contributed nothing, or something less than it drew. */
    synchronized void refuse(AgentKind vendor, String why) {
        if (closed) {
            return;
        }
        notes.computeIfAbsent(vendor, kind -> new ArrayList<>()).add(why);
        board(DesignBoard.ASKING).save(boardFile);
    }

    synchronized void offer(AgentKind vendor, List<DesignOption> options, List<String> dropped) {
        if (closed) {
            return;
        }
        offered.put(vendor, List.copyOf(options));
        notes.computeIfAbsent(vendor, kind -> new ArrayList<>()).addAll(dropped);
        board(DesignBoard.ASKING).save(boardFile);
    }

    /** The finished board, saved: ready or none by what was offered. */
    synchronized DesignBoard finish() {
        closed = true;
        DesignBoard board = board(anyOffered() ? DesignBoard.READY : DesignBoard.NONE);
        board.save(boardFile);
        return board;
    }

    /** The board as far as it got, saved as cut short, with why. */
    synchronized DesignBoard interrupt(String why) {
        closed = true;
        about.add(why);
        DesignBoard board = board(DesignBoard.INTERRUPTED);
        board.save(boardFile);
        return board;
    }

    /** What the vendors offered, after the liked design when there is one. */
    private List<DesignOption> options() {
        return Stream.concat(Stream.ofNullable(liked), offered.values().stream().flatMap(List::stream)).toList();
    }

    /** Whether any vendor offered something: the liked design alone is not an answer to the request. */
    private boolean anyOffered() {
        return offered.values().stream().anyMatch(list -> !list.isEmpty());
    }

    private DesignBoard board(String state) {
        List<String> refused = Stream.concat(notes.values().stream().flatMap(List::stream), about.stream()).toList();
        return new DesignBoard(asked, note, state, options(), refused, "", null, "", extra);
    }
}

package dev.fmangoni.forge.cli.support;

import dev.fmangoni.forge.process.ProcessRegistry;
import dev.fmangoni.forge.project.ForgeHome;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * Makes sure no agent process outlives this one.
 *
 * <p>Windows does not reap the children of a dead process, and the job threads are daemons, so the
 * JVM exiting takes the threads instantly and leaves their agents running — still holding memory,
 * still able to write into a project, and on a subscription that is still being spent. Seen on this
 * machine: a {@code claude.cmd} from the morning still alive hours later under no parent at all.
 *
 * <p>The hook covers Ctrl+C and a terminate signal. The sweep covers what no hook can: a forcible
 * kill, End Task, a crash, a power cut. Anything the sweep kills is reported, because a process
 * being killed at startup is a fact about the previous run that is worth seeing.
 */
@UtilityClass
public class ChildGuard {

    public void install(ForgeHome home) {
        List<Long> swept = ProcessRegistry.adoptAndSweep(home.root().resolve("children.pids"));
        if (!swept.isEmpty()) {
            System.out.println("Cleaned up:      " + swept.size()
                    + " agent process(es) orphaned by a previous run " + swept);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(ProcessRegistry::killAll, "forge-cleanup"));
    }
}

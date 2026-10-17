package dev.fmangoni.forge.cli.support;

import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;

/** Every run under the root that has a readable state, in id order. */
@UtilityClass
public class RunStates {

    public List<RunState> all(ForgeHome home) {
        if (!Files.isDirectory(home.runs())) {
            return List.of();
        }
        try (Stream<Path> runs = Files.list(home.runs())) {
            return runs.filter(Files::isDirectory)
                    .map(dir -> RunStore.survey(new RunPaths(dir).stateFile()))
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(RunState::getId))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
